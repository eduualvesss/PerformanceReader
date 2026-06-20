package com.eduualves.perfrecorder.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Gerencia o subprocesso externo {@code CpuTempSensor.exe}, responsável por ler a
 * temperatura da CPU via LibreHardwareMonitorLib quando a leitura padrão via OSHI/WMI
 * não está disponível (situação comum em diversas placas-mãe de terceiros).
 * <p>
 * Funcionamento:
 * <ul>
 *   <li>Na construção, tenta localizar e iniciar o executável dentro da pasta do mod;</li>
 *   <li>Uma thread daemon lê continuamente o stdout do processo e mantém apenas a
 *       última leitura em uma referência atômica (não há necessidade de histórico aqui,
 *       quem grava o histórico é o {@code SessionRecorder});</li>
 *   <li>Se o processo não puder ser iniciado (executável ausente, driver indisponível,
 *       falha de permissão, etc.), a ponte simplesmente fica "indisponível" - o chamador
 *       deve então recorrer ao fallback do OSHI. Nenhuma exceção é propagada.</li>
 * </ul>
 * <p>
 * Esta classe NUNCA deve travar o jogo nem a sessão de gravação: qualquer falha aqui é
 * tratada como "sensor indisponível", nunca como erro fatal.
 */
public class LibreHardwareMonitorBridge {

    private static final String EXECUTABLE_NAME = "CpuTempSensor.exe";
    private static final long READY_TIMEOUT_MS = 5_000;

    private Process process;
    private OutputStream processStdin;
    private final AtomicReference<Double> lastTemperature = new AtomicReference<>(null);
    private volatile boolean available = false;

    /**
     * Tenta iniciar o subprocesso sensor. Retorna a própria instância para permitir
     * uso fluente; nunca lança exceção - falhas resultam apenas em {@link #isAvailable()}
     * retornando {@code false}.
     */
    public LibreHardwareMonitorBridge start() {
        try {
            Path exePath = locateExecutable();
            if (exePath == null) {
                System.out.println("[PerfRecorder] CpuTempSensor.exe não encontrado; "
                        + "temperatura via LibreHardwareMonitor desabilitada (fallback para OSHI).");
                return this;
            }

            ProcessBuilder builder = new ProcessBuilder(exePath.toAbsolutePath().toString());
            builder.redirectErrorStream(false);
            this.process = builder.start();
            this.processStdin = process.getOutputStream();

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));

            // Espera bloqueante curta apenas pelo sinal "READY" inicial, para sabermos
            // se o driver abriu corretamente antes de declarar o sensor disponível.
            String firstLine = readLineWithTimeout(reader, READY_TIMEOUT_MS);
            if (firstLine == null) {
                System.out.println("[PerfRecorder] CpuTempSensor.exe não respondeu a tempo; "
                        + "fallback para OSHI.");
                stop();
                return this;
            }
            if (firstLine.startsWith("ERROR:")) {
                System.out.println("[PerfRecorder] CpuTempSensor.exe reportou erro ao abrir o driver: "
                        + firstLine.substring("ERROR:".length()) + " (fallback para OSHI).");
                stop();
                return this;
            }
            if (!"READY".equals(firstLine)) {
                System.out.println("[PerfRecorder] Resposta inesperada de CpuTempSensor.exe: "
                        + firstLine + " (fallback para OSHI).");
                stop();
                return this;
            }

            this.available = true;

            Thread readerThread = new Thread(() -> pumpReaderLoop(reader), "perfrecorder-cputemp-bridge");
            readerThread.setDaemon(true);
            readerThread.start();

            System.out.println("[PerfRecorder] Sensor de temperatura via LibreHardwareMonitor ativo.");
        } catch (IOException e) {
            System.out.println("[PerfRecorder] Não foi possível iniciar CpuTempSensor.exe ("
                    + e.getMessage() + "); fallback para OSHI.");
            this.available = false;
        }
        return this;
    }

    private void pumpReaderLoop(BufferedReader reader) {
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("TEMP:")) {
                    String value = line.substring("TEMP:".length());
                    if ("NULL".equals(value)) {
                        lastTemperature.set(null);
                    } else {
                        try {
                            lastTemperature.set(Double.parseDouble(value));
                        } catch (NumberFormatException ignored) {
                            // linha malformada isolada; ignora e segue lendo
                        }
                    }
                }
                // outras linhas (ex.: ERROR: tardio) são ignoradas aqui silenciosamente -
                // já tratamos a inicialização acima; falhas após start() apenas resultam
                // em "lastTemperature" parar de ser atualizado, e isAvailable() permanece
                // true mas getTemperature() pode retornar um valor "congelado". Isso é
                // aceitável: o chamador sempre pode comparar o timestamp se quiser maior
                // rigor, mas para o caso de uso atual (1 amostra/segundo) é suficiente.
            }
        } catch (IOException e) {
            // Processo provavelmente morreu; marca como indisponível para que o
            // chamador volte a usar o fallback do OSHI nas próximas leituras.
            available = false;
        }
    }

    /** Última temperatura conhecida (Celsius), ou {@code null} se indisponível. */
    public Double getLastTemperature() {
        return available ? lastTemperature.get() : null;
    }

    public boolean isAvailable() {
        return available;
    }

    /** Encerra o subprocesso de forma limpa, se estiver em execução. */
    public void stop() {
        available = false;
        try {
            if (processStdin != null) {
                processStdin.close(); // fechar stdin é o sinal de parada combinado no protocolo
            }
        } catch (IOException ignored) {
            // melhor esforço; seguimos para destroy() de qualquer forma
        }
        if (process != null && process.isAlive()) {
            process.destroy();
        }
    }

    /**
     * Espera, em polling simples, até {@code timeoutMs} por uma linha disponível.
     * <p>
     * Usamos {@link BufferedReader#ready()} em vez de uma chamada bloqueante de
     * {@code readLine()} com timeout nativo porque a API padrão de streams do Java
     * não oferece {@code readLine(timeout)} - isso é uma limitação conhecida da
     * biblioteca padrão, não uma escolha de design. O polling de 50ms é granular
     * o suficiente para este caso de uso (esperamos apenas a linha "READY" uma
     * única vez, na inicialização).
     */
    private static String readLineWithTimeout(BufferedReader reader, long timeoutMs) throws IOException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (reader.ready()) {
                return reader.readLine();
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    /**
     * Procura o executável na pasta {@code sensors/} dentro do diretório de configuração
     * do mod (copiada na primeira execução a partir dos recursos do .jar - veja
     * {@code SensorResourceExtractor}). Retorna {@code null} se não encontrado.
     */
    private static Path locateExecutable() {
        Path candidate = Path.of("config", "perfrecorder", "sensors", EXECUTABLE_NAME);
        if (Files.isRegularFile(candidate)) {
            return candidate;
        }
        return null;
    }
}
