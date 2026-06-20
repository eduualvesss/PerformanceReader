package com.eduualves.perfrecorder.report;

import com.eduualves.perfrecorder.data.PerformanceSample;
import com.eduualves.perfrecorder.util.SystemInfoCollector;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Gera o relatório final em texto puro (.txt), pensado para ser lido tanto
 * por um humano quanto por uma IA (como o Claude) para diagnóstico de
 * desempenho e sugestão de ajustes de mods/configurações.
 * <p>
 * O relatório é dividido em seções claramente delimitadas para facilitar
 * a leitura e o parsing.
 */
public class ReportWriter {

    private static final DateTimeFormatter DISPLAY_TIMESTAMP =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss").withZone(ZoneId.systemDefault());

    private final SystemInfoCollector systemInfo;
    private final String worldName;
    private final long sessionStartMillis;
    private final List<PerformanceSample> samples;

    public ReportWriter(SystemInfoCollector systemInfo, String worldName, long sessionStartMillis,
                         List<PerformanceSample> samples) {
        this.systemInfo = systemInfo;
        this.worldName = worldName;
        this.sessionStartMillis = sessionStartMillis;
        this.samples = samples;
    }

    public void write(Path outputPath) throws IOException {
        try (BufferedWriter w = Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8)) {
            writeHeader(w);
            writeMachineSpecs(w);
            writeSummaryStatistics(w);
            writeTimeline(w);
            writeProblemDetection(w);
            writeFooter(w);
        }
    }

    // ---------------------------------------------------------------------
    // Cabeçalho
    // ---------------------------------------------------------------------

    private void writeHeader(BufferedWriter w) throws IOException {
        long endMillis = System.currentTimeMillis();
        Duration duration = Duration.ofMillis(endMillis - sessionStartMillis);

        line(w, "=".repeat(78));
        line(w, "RELATÓRIO DE DESEMPENHO - PERFORMANCE RECORDER");
        line(w, "=".repeat(78));
        line(w, "Mundo                : " + worldName);
        line(w, "Início da sessão     : " + DISPLAY_TIMESTAMP.format(Instant.ofEpochMilli(sessionStartMillis)));
        line(w, "Fim da sessão        : " + DISPLAY_TIMESTAMP.format(Instant.ofEpochMilli(endMillis)));
        line(w, "Duração total        : " + formatDuration(duration));
        line(w, "Total de amostras    : " + samples.size() + " (1 amostra/segundo)");
        line(w, "");
    }

    // ---------------------------------------------------------------------
    // Ficha técnica da máquina
    // ---------------------------------------------------------------------

    private void writeMachineSpecs(BufferedWriter w) throws IOException {
        line(w, "-".repeat(78));
        line(w, "FICHA TÉCNICA DA MÁQUINA");
        line(w, "-".repeat(78));
        line(w, "CPU                  : " + systemInfo.getCpuModelName());
        line(w, "Núcleos físicos      : " + systemInfo.getPhysicalCoreCount());
        line(w, "Núcleos lógicos      : " + systemInfo.getLogicalCoreCount());
        line(w, String.format(Locale.US, "Frequência máxima    : %.2f GHz", systemInfo.getMaxCpuFreqGhz()));
        line(w, "RAM total            : " + systemInfo.getTotalRamMb() + " MB");
        line(w, "GPU(s)               : " + systemInfo.getGpuInfo());
        line(w, "Placa-mãe/Fabricante : " + systemInfo.getManufacturerAndModel());
        line(w, "Sistema operacional  : " + systemInfo.getOsName());
        line(w, "");
        line(w, "NOTA: estes dados descrevem o hardware REAL desta máquina no momento");
        line(w, "da gravação. Para avaliar uma configuração de hardware FUTURA/HIPOTÉTICA");
        line(w, "(ex: troca de CPU ou placa-mãe), use estes números como ponto de partida");
        line(w, "para uma análise comparativa externa - este mod não simula hardware que");
        line(w, "a máquina não possui.");
        line(w, "");
    }

    // ---------------------------------------------------------------------
    // Estatísticas resumidas
    // ---------------------------------------------------------------------

    private void writeSummaryStatistics(BufferedWriter w) throws IOException {
        line(w, "-".repeat(78));
        line(w, "RESUMO ESTATÍSTICO");
        line(w, "-".repeat(78));

        if (samples.isEmpty()) {
            line(w, "Nenhuma amostra foi coletada durante esta sessão.");
            line(w, "");
            return;
        }

        Stats fps = Stats.of(samples.stream().mapToDouble(s -> s.fps));
        Stats frameTime = Stats.of(samples.stream().mapToDouble(s -> s.frameTimeMs));
        Stats tps = Stats.of(samples.stream().mapToDouble(s -> s.tps));
        Stats tickTime = Stats.of(samples.stream().mapToDouble(s -> s.tickTimeMs));
        Stats chunks = Stats.of(samples.stream().mapToDouble(s -> s.loadedChunks));
        Stats entities = Stats.of(samples.stream().mapToDouble(s -> s.entityCount));
        Stats heapUsed = Stats.of(samples.stream().mapToDouble(s -> s.jvmHeapUsedMb));
        Stats sysCpu = Stats.of(samples.stream().mapToDouble(s -> s.systemCpuLoadPercent));
        Stats ramUsed = Stats.of(samples.stream().mapToDouble(s -> s.systemRamUsedMb));

        writeStatBlock(w, "FPS", fps, "");
        writeStatBlock(w, "Tempo de quadro (frame time)", frameTime, "ms");
        writeStatBlock(w, "TPS (ticks por segundo)", tps, "");
        writeStatBlock(w, "Tempo médio de tick", tickTime, "ms");
        writeStatBlock(w, "Chunks carregados", chunks, "");
        writeStatBlock(w, "Entidades renderizadas", entities, "");
        writeStatBlock(w, "RAM usada pela JVM (heap)", heapUsed, "MB");
        writeStatBlock(w, "Uso de CPU do sistema", sysCpu, "%");
        writeStatBlock(w, "RAM usada no sistema", ramUsed, "MB");

        long heapMax = samples.get(0).jvmHeapMaxMb;
        line(w, String.format(Locale.US, "Heap máximo configurado (-Xmx) : %d MB", heapMax));

        long ramTotal = samples.get(0).systemRamTotalMb;
        line(w, String.format(Locale.US, "RAM total do sistema           : %d MB", ramTotal));
        line(w, "");
    }

    private void writeStatBlock(BufferedWriter w, String label, Stats s, String unit) throws IOException {
        line(w, String.format(Locale.US,
                "%-32s mín=%.1f%s  méd=%.1f%s  máx=%.1f%s  p95=%.1f%s",
                label + ":", s.min, unit, s.avg, unit, s.max, unit, s.p95, unit));
    }

    // ---------------------------------------------------------------------
    // Linha do tempo (amostras agregadas por intervalo, para não inflar o arquivo)
    // ---------------------------------------------------------------------

    private void writeTimeline(BufferedWriter w) throws IOException {
        line(w, "-".repeat(78));
        line(w, "LINHA DO TEMPO (amostras a cada 10 segundos)");
        line(w, "-".repeat(78));
        line(w, String.format("%-8s %6s %8s %6s %8s %8s %10s %8s",
                "Tempo", "FPS", "FrameMs", "TPS", "Chunks", "Entid.", "RAM(MB)", "CPU(%)"));

        for (int i = 0; i < samples.size(); i += 10) {
            PerformanceSample s = samples.get(i);
            line(w, String.format(Locale.US, "%6ds %6d %8.1f %6.1f %8d %8d %10d %8.1f",
                    s.secondsSinceStart, s.fps, s.frameTimeMs, s.tps, s.loadedChunks,
                    s.entityCount, s.systemRamUsedMb, s.systemCpuLoadPercent));
        }
        line(w, "");
        line(w, "(Os dados completos, segundo a segundo, estão em 'dados_brutos.csv'");
        line(w, "na mesma pasta deste relatório.)");
        line(w, "");
    }

    // ---------------------------------------------------------------------
    // Detecção automática de problemas
    // ---------------------------------------------------------------------

    private void writeProblemDetection(BufferedWriter w) throws IOException {
        line(w, "-".repeat(78));
        line(w, "PONTOS DE ATENÇÃO DETECTADOS AUTOMATICAMENTE");
        line(w, "-".repeat(78));

        if (samples.isEmpty()) {
            line(w, "Sem dados suficientes para análise.");
            line(w, "");
            return;
        }

        boolean foundAny = false;

        // Quedas de FPS abaixo de 30
        long fpsDrops = samples.stream().filter(s -> s.fps < 30).count();
        if (fpsDrops > 0) {
            double pct = 100.0 * fpsDrops / samples.size();
            line(w, String.format(Locale.US,
                    "- FPS abaixo de 30 em %d amostras (%.1f%% da sessão).", fpsDrops, pct));
            foundAny = true;
        }

        // Quedas de TPS abaixo de 18 (servidor/mundo travando)
        long tpsDrops = samples.stream().filter(s -> s.tps < 18.0).count();
        if (tpsDrops > 0) {
            double pct = 100.0 * tpsDrops / samples.size();
            line(w, String.format(Locale.US,
                    "- TPS abaixo de 18 em %d amostras (%.1f%% da sessão) - indica travamentos no mundo.",
                    tpsDrops, pct));
            foundAny = true;
        }

        // Uso de heap próximo do limite configurado (> 90%)
        long heapMax = samples.get(0).jvmHeapMaxMb;
        long heapNearLimit = samples.stream().filter(s -> s.jvmHeapUsedMb > heapMax * 0.9).count();
        if (heapNearLimit > 0) {
            line(w, String.format(Locale.US,
                    "- Uso de memória heap da JVM passou de 90%% do limite configurado em %d amostras. " +
                            "Considere aumentar a RAM alocada ao Minecraft (-Xmx).", heapNearLimit));
            foundAny = true;
        }

        // CPU do sistema constantemente alta
        long cpuHigh = samples.stream().filter(s -> s.systemCpuLoadPercent > 90.0).count();
        if (cpuHigh > 0) {
            double pct = 100.0 * cpuHigh / samples.size();
            line(w, String.format(Locale.US,
                    "- Uso de CPU do sistema acima de 90%% em %.1f%% da sessão - possível gargalo de CPU.", pct));
            foundAny = true;
        }

        // Crescimento incomum de entidades (pode indicar mob farms/lag de entidades)
        int maxEntities = samples.stream().mapToInt(s -> s.entityCount).max().orElse(0);
        if (maxEntities > 500) {
            line(w, String.format(Locale.US,
                    "- Pico de %d entidades renderizadas simultaneamente - verifique acúmulo de mobs/itens.",
                    maxEntities));
            foundAny = true;
        }

        if (!foundAny) {
            line(w, "Nenhum problema evidente detectado automaticamente. Desempenho estável.");
        }
        line(w, "");
    }

    private void writeFooter(BufferedWriter w) throws IOException {
        line(w, "=".repeat(78));
        line(w, "Gerado automaticamente pelo mod Performance Recorder.");
        line(w, "Os dados brutos completos (linha a linha) estão em 'dados_brutos.csv'.");
        line(w, "=".repeat(78));
    }

    // ---------------------------------------------------------------------
    // Utilitários
    // ---------------------------------------------------------------------

    private static void line(BufferedWriter w, String text) throws IOException {
        w.write(text);
        w.newLine();
    }

    private static String formatDuration(Duration d) {
        long h = d.toHours();
        long m = d.toMinutesPart();
        long s = d.toSecondsPart();
        return String.format("%02dh %02dmin %02dseg", h, m, s);
    }

    /** Pequena classe auxiliar para estatísticas descritivas (min/média/máx/p95). */
    private static class Stats {
        final double min, max, avg, p95;

        private Stats(double min, double max, double avg, double p95) {
            this.min = min;
            this.max = max;
            this.avg = avg;
            this.p95 = p95;
        }

        static Stats of(java.util.stream.DoubleStream streamSource) {
            double[] values = streamSource.toArray();
            if (values.length == 0) return new Stats(0, 0, 0, 0);
            double[] sorted = values.clone();
            java.util.Arrays.sort(sorted);
            double min = sorted[0];
            double max = sorted[sorted.length - 1];
            double sum = 0;
            for (double v : values) sum += v;
            double avg = sum / values.length;
            int p95Index = (int) Math.ceil(0.95 * sorted.length) - 1;
            p95Index = Math.max(0, Math.min(sorted.length - 1, p95Index));
            double p95 = sorted[p95Index];
            return new Stats(min, max, avg, p95);
        }
    }
}
