package com.eduualves.perfrecorder;

import com.eduualves.perfrecorder.data.PerformanceSample;
import com.eduualves.perfrecorder.report.ReportWriter;
import com.eduualves.perfrecorder.util.SystemInfoCollector;
import net.minecraft.client.Minecraft;

import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Gerencia o ciclo de vida completo de uma sessão de gravação de desempenho:
 * desde a entrada no mundo até a saída dele.
 * <p>
 * Responsabilidades:
 * <ul>
 *   <li>Tirar amostras periódicas (a cada segundo) de FPS/TPS/CPU/RAM/chunks/entidades;</li>
 *   <li>Gravar cada amostra imediatamente em um arquivo CSV bruto (para não perder dados
 *       caso o jogo trave ou seja fechado de forma abrupta);</li>
 *   <li>Ao final da sessão, gerar o relatório .txt detalhado.</li>
 * </ul>
 */
public class SessionRecorder {

    private static final DateTimeFormatter FILE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private final SystemInfoCollector systemInfo;
    private final com.sun.management.OperatingSystemMXBean jvmOsBean;

    private final List<PerformanceSample> samples = new ArrayList<>();
    private final AtomicInteger tickCounter = new AtomicInteger(0);

    private long sessionStartMillis;
    private String worldName;
    private Path sessionFolder;
    private BufferedWriter rawCsvWriter;
    private boolean active = false;

    // Acumuladores usados para calcular o TPS dentro da janela de 1 segundo
    private long lastTickTimestampNanos = 0L;
    private final List<Double> tickDurationsMsInWindow = new ArrayList<>();

    public SessionRecorder() {
        this.systemInfo = new SystemInfoCollector();
        this.jvmOsBean = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    }

    /** Chamado quando o jogador entra em um mundo (singleplayer ou multiplayer). */
    public void startSession(String worldName) {
        if (active) {
            return; // já existe uma sessão em andamento; evita duplicar
        }
        this.worldName = sanitizeFileName(worldName);
        this.sessionStartMillis = System.currentTimeMillis();
        this.samples.clear();
        this.tickCounter.set(0);
        this.tickDurationsMsInWindow.clear();
        this.lastTickTimestampNanos = System.nanoTime();
        this.active = true;

        try {
            Path baseDir = getReportsBaseDir();
            Files.createDirectories(baseDir);
            String timestamp = LocalDateTime.now().format(FILE_TIMESTAMP);
            this.sessionFolder = baseDir.resolve(timestamp + "_" + this.worldName);
            Files.createDirectories(sessionFolder);

            Path rawCsvPath = sessionFolder.resolve("dados_brutos.csv");
            this.rawCsvWriter = Files.newBufferedWriter(rawCsvPath, StandardCharsets.UTF_8);
            this.rawCsvWriter.write(PerformanceSample.csvHeader());
            this.rawCsvWriter.newLine();
            this.rawCsvWriter.flush();
        } catch (IOException e) {
            System.err.println("[PerfRecorder] Falha ao iniciar arquivo de sessão: " + e.getMessage());
            this.active = false;
        }
    }

    /** Chamado a cada tick de mundo do cliente (~20x por segundo); usado para medir TPS local. */
    public void onClientTick() {
        if (!active) return;
        long now = System.nanoTime();
        double deltaMs = (now - lastTickTimestampNanos) / 1_000_000.0;
        lastTickTimestampNanos = now;
        tickDurationsMsInWindow.add(deltaMs);
        tickCounter.incrementAndGet();
    }

    /**
     * Chamado uma vez por segundo (via agendamento simples baseado em contagem de ticks)
     * para tirar uma amostra completa de desempenho.
     * <p>
     * Todo o corpo do método é protegido por try/catch: uma falha pontual em qualquer
     * coletor (OSHI, JVM, API do jogo) não deve travar o jogo nem interromper o restante
     * da sessão de gravação - na pior das hipóteses, perdemos uma amostra isolada.
     */
    public void sampleOnce(Minecraft client) {
        if (!active) return;

        try {
            long secondsSinceStart = (System.currentTimeMillis() - sessionStartMillis) / 1000;

            int fps = client.getFps();
            double frameTimeMs = fps > 0 ? 1000.0 / fps : 0.0;

            double tps;
            double avgTickTimeMs;
            if (!tickDurationsMsInWindow.isEmpty()) {
                double sum = 0;
                for (double d : tickDurationsMsInWindow) sum += d;
                avgTickTimeMs = sum / tickDurationsMsInWindow.size();
                // TPS ideal é 20; calculamos o TPS real baseado no tempo médio de tick observado.
                tps = avgTickTimeMs > 0 ? Math.min(20.0, 1000.0 / avgTickTimeMs) : 20.0;
            } else {
                avgTickTimeMs = 0.0;
                tps = 20.0;
            }
            tickDurationsMsInWindow.clear();

            int loadedChunks = 0;
            int entityCount = 0;
            if (client.level != null) {
                loadedChunks = parseLoadedChunks(client.level.gatherChunkSourceStats());
                entityCount = client.level.getEntityCount();
            }

            Runtime runtime = Runtime.getRuntime();
            long heapUsedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
            long heapMaxMb = runtime.maxMemory() / (1024 * 1024);
            double jvmCpuLoad = jvmOsBean.getProcessCpuLoad() * 100.0; // pode retornar -1 se indisponível

            double systemCpuLoad = systemInfo.getSystemCpuLoadPercent();
            double[] perCore = systemInfo.getPerCoreCpuLoadPercent();
            long ramUsed = systemInfo.getUsedRamMb();
            long ramTotal = systemInfo.getTotalRamMb();
            Double cpuTemp = systemInfo.getCpuTemperatureCelsius();

            // Métricas adicionais via LibreHardwareMonitor (podem ser null se o sensor
            // dedicado não estiver disponível - ver SystemInfoCollector para detalhes).
            Double cpuCoreVoltage = systemInfo.getCpuCoreVoltage();
            Double cpuPackagePower = systemInfo.getCpuPackagePowerWatts();
            Double cpuClock = systemInfo.getCpuClockMhz();

            Double gpuTemp = systemInfo.getGpuTemperatureCelsius();
            Double gpuCoreClock = systemInfo.getGpuCoreClockMhz();
            Double gpuMemClock = systemInfo.getGpuMemoryClockMhz();
            Double gpuCoreVoltage = systemInfo.getGpuCoreVoltage();
            Double gpuLoad = systemInfo.getGpuLoadPercent();
            Double gpuPower = systemInfo.getGpuPowerWatts();
            Double gpuMemUsed = systemInfo.getGpuMemoryUsedMb();
            Double gpuMemTotal = systemInfo.getGpuMemoryTotalMb();
            Double gpuFanRpm = systemInfo.getGpuFanRpm();

            Double ramClock = systemInfo.getRamClockMhz();
            Double ramVoltage = systemInfo.getRamVoltage();

            PerformanceSample sample = new PerformanceSample(
                    secondsSinceStart,
                    System.currentTimeMillis(),
                    fps,
                    frameTimeMs,
                    tps,
                    avgTickTimeMs,
                    loadedChunks,
                    entityCount,
                    heapUsedMb,
                    heapMaxMb,
                    Math.max(0, jvmCpuLoad),
                    Math.max(0, systemCpuLoad),
                    perCore,
                    ramUsed,
                    ramTotal,
                    cpuTemp,
                    cpuCoreVoltage,
                    cpuPackagePower,
                    cpuClock,
                    gpuTemp,
                    gpuCoreClock,
                    gpuMemClock,
                    gpuCoreVoltage,
                    gpuLoad,
                    gpuPower,
                    gpuMemUsed,
                    gpuMemTotal,
                    gpuFanRpm,
                    ramClock,
                    ramVoltage
            );

            samples.add(sample);
            writeRawLine(sample);
        } catch (Exception e) {
            System.err.println("[PerfRecorder] Falha ao coletar amostra (sessão continua): " + e.getMessage());
        }
    }

    /**
     * Extrai a contagem de chunks carregados a partir da string retornada por
     * {@code Level#gatherChunkSourceStats()} (a mesma usada na tela de debug F3).
     * <p>
     * O formato típico é algo como {@code "Chunks[C] W: 441 E: 23..."}. Como o
     * formato exato pode variar entre versões, o parsing é feito de forma
     * defensiva: procuramos o primeiro número inteiro na string. Se nada for
     * encontrado, retornamos 0 em vez de lançar uma exceção.
     */
    private static int parseLoadedChunks(String chunkSourceStats) {
        if (chunkSourceStats == null || chunkSourceStats.isBlank()) return 0;
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\d+)").matcher(chunkSourceStats);
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private void writeRawLine(PerformanceSample sample) {
        if (rawCsvWriter == null) return;
        try {
            rawCsvWriter.write(sample.toCsvLine());
            rawCsvWriter.newLine();
            rawCsvWriter.flush();
        } catch (IOException e) {
            System.err.println("[PerfRecorder] Falha ao escrever amostra: " + e.getMessage());
        }
    }

    /** Chamado quando o jogador sai do mundo. Finaliza a sessão e gera o relatório .txt. */
    public Path endSession() {
        if (!active) return null;
        active = false;

        try {
            if (rawCsvWriter != null) {
                rawCsvWriter.close();
            }
        } catch (IOException e) {
            System.err.println("[PerfRecorder] Falha ao fechar arquivo CSV: " + e.getMessage());
        }

        Path reportPath = null;
        try {
            ReportWriter writer = new ReportWriter(systemInfo, worldName, sessionStartMillis, samples);
            reportPath = sessionFolder.resolve("relatorio.txt");
            writer.write(reportPath);
        } catch (IOException e) {
            System.err.println("[PerfRecorder] Falha ao gerar relatório: " + e.getMessage());
        }

        // Encerra o subprocesso do sensor de temperatura (se estiver ativo) para não
        // deixar um processo CpuTempSensor.exe órfão rodando após a sessão terminar.
        systemInfo.shutdown();

        return reportPath;
    }

    public boolean isActive() {
        return active;
    }

    private static String sanitizeFileName(String name) {
        if (name == null || name.isBlank()) return "mundo_desconhecido";
        return name.replaceAll("[^a-zA-Z0-9_\\-]", "_");
    }

    private static Path getReportsBaseDir() {
        return Path.of("perfrecorder_reports");
    }
}
