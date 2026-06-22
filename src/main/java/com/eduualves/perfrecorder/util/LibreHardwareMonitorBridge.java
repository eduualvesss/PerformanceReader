package com.eduualves.perfrecorder.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gerencia o subprocesso externo {@code CpuTempSensor.exe}, responsável por ler, via
 * LibreHardwareMonitorLib, métricas de hardware que a OSHI não consegue obter de forma
 * confiável (temperatura de CPU em diversas placas-mãe de terceiros) ou que a OSHI não
 * expõe de forma alguma (voltagem do core da CPU, e a maior parte das métricas de
 * GPU/RAM: clocks, voltagem, temperatura, uso de VRAM, etc).
 * <p>
 * Funcionamento:
 * <ul>
 *   <li>Na construção, tenta localizar e iniciar o executável dentro da pasta do mod;</li>
 *   <li>Uma thread daemon lê continuamente o stdout do processo e mantém apenas a
 *       última amostra completa em uma referência atômica (não há necessidade de
 *       histórico aqui, quem grava o histórico é o {@code SessionRecorder});</li>
 *   <li>Se o processo não puder ser iniciado (executável ausente, driver indisponível,
 *       falha de permissão, etc.), a ponte simplesmente fica "indisponível" - o chamador
 *       deve então recorrer ao fallback do OSHI (apenas para CPU/RAM básicos; GPU
 *       detalhada e voltagem de CPU não têm fallback, por não serem expostas pela OSHI).
 *       Nenhuma exceção é propagada.</li>
 * </ul>
 * <p>
 * Protocolo: cada linha de dados do subprocesso vem como {@code DATA:<json>}, um objeto
 * JSON plano (sem aninhamento) com chaves fixas - ver o lado C# ({@code Program.cs}) para
 * a lista completa. Chaves ausentes significam "sensor indisponível nesta amostra" e são
 * tratadas exatamente como um valor {@code null}.
 * <p>
 * Esta classe NUNCA deve travar o jogo nem a sessão de gravação: qualquer falha aqui é
 * tratada como "sensor indisponível", nunca como erro fatal.
 */
public class LibreHardwareMonitorBridge {

    private static final String EXECUTABLE_NAME = "CpuTempSensor.exe";
    private static final long READY_TIMEOUT_MS = 5_000;

    /** Casa pares "chave":valor_numerico dentro do JSON plano emitido pelo sensor. */
    private static final Pattern NUMBER_FIELD = Pattern.compile("\"(\\w+)\":(-?\\d+(?:\\.\\d+)?)");
    /** Casa pares "chave":"valor_string" (usado apenas para gpu_name). */
    private static final Pattern STRING_FIELD = Pattern.compile("\"(\\w+)\":\"((?:[^\"\\\\]|\\\\.)*)\"");

    private Process process;
    private OutputStream processStdin;
    private final AtomicReference<HardwareSnapshot> lastSnapshot = new AtomicReference<>(HardwareSnapshot.EMPTY);
    private volatile boolean available = false;

    /**
     * Snapshot imutável da última amostra completa recebida do sensor .NET. Qualquer
     * campo pode ser {@code null} caso o sensor correspondente não esteja disponível
     * naquele instante (ex.: GPU não detectada, voltagem não exposta pela placa-mãe).
     */
    public static final class HardwareSnapshot {
        static final HardwareSnapshot EMPTY = new HardwareSnapshot(java.util.Map.of());

        private final java.util.Map<String, Object> fields;

        private HardwareSnapshot(java.util.Map<String, Object> fields) {
            this.fields = fields;
        }

        public Double getDouble(String key) {
            Object v = fields.get(key);
            return v instanceof Double ? (Double) v : null;
        }

        public String getString(String key) {
            Object v = fields.get(key);
            return v instanceof String ? (String) v : null;
        }
    }

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
                        + "temperatura/voltagem de CPU e métricas de GPU via LibreHardwareMonitor "
                        + "desabilitadas (fallback para OSHI nas métricas que a OSHI suporta).");
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

            System.out.println("[PerfRecorder] Sensor de hardware via LibreHardwareMonitor ativo "
                    + "(CPU temp/voltagem, GPU, RAM).");
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
                if (line.startsWith("DATA:")) {
                    String json = line.substring("DATA:".length());
                    try {
                        lastSnapshot.set(parseSnapshot(json));
                    } catch (RuntimeException ignored) {
                        // linha malformada isolada; ignora e segue lendo, mantendo o
                        // último snapshot válido conhecido em vez de zerá-lo.
                    }
                }
                // outras linhas (ex.: ERROR: tardio) são ignoradas aqui silenciosamente -
                // já tratamos a inicialização acima; falhas após start() apenas resultam
                // em "lastSnapshot" parar de ser atualizado, e isAvailable() permanece
                // true mas getLastSnapshot() pode retornar um valor "congelado". Isso é
                // aceitável: para o caso de uso atual (1 amostra/segundo) é suficiente.
            }
        } catch (IOException e) {
            // Processo provavelmente morreu; marca como indisponível para que o
            // chamador volte a usar o fallback do OSHI nas próximas leituras.
            available = false;
        }
    }

    /**
     * Faz o parsing do objeto JSON plano emitido pelo sensor (sem aninhamento, apenas
     * pares chave/número ou chave/string). Implementado com expressões regulares simples
     * em vez de uma biblioteca JSON completa, já que o formato é deliberadamente raso e
     * controlado por nós dos dois lados (C# e Java) do mesmo mod.
     */
    private static HardwareSnapshot parseSnapshot(String json) {
        java.util.Map<String, Object> fields = new java.util.HashMap<>();

        Matcher numbers = NUMBER_FIELD.matcher(json);
        while (numbers.find()) {
            try {
                fields.put(numbers.group(1), Double.parseDouble(numbers.group(2)));
            } catch (NumberFormatException ignored) {
                // campo malformado isolado; ignora apenas este campo
            }
        }

        Matcher strings = STRING_FIELD.matcher(json);
        while (strings.find()) {
            String value = strings.group(2)
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\");
            fields.put(strings.group(1), value);
        }

        return new HardwareSnapshot(fields);
    }

    /** Última amostra completa conhecida, vinda do sensor .NET. Nunca retorna {@code null}. */
    public HardwareSnapshot getLastSnapshot() {
        return available ? lastSnapshot.get() : HardwareSnapshot.EMPTY;
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
