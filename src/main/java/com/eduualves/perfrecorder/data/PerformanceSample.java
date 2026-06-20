package com.eduualves.perfrecorder.data;

/**
 * Representa uma única amostra (snapshot) de métricas de desempenho
 * coletada em um instante específico durante a sessão de jogo.
 * <p>
 * Cada amostra contém tanto dados do jogo (FPS, TPS, chunks, entidades)
 * quanto dados do sistema operacional (CPU e RAM da máquina toda),
 * obtidos via a biblioteca OSHI.
 */
public class PerformanceSample {

    /** Segundos desde o início da sessão (criação/entrada no mundo). */
    public final long secondsSinceStart;

    /** Timestamp absoluto (epoch millis) em que a amostra foi tirada. */
    public final long epochMillis;

    // --- Métricas do jogo (cliente) ---
    public final int fps;
    public final double frameTimeMs;
    public final double tps;
    public final double tickTimeMs;
    public final int loadedChunks;
    public final int entityCount;

    // --- Métricas da JVM (processo Java) ---
    public final long jvmHeapUsedMb;
    public final long jvmHeapMaxMb;
    public final double jvmCpuLoadPercent;

    // --- Métricas do sistema operacional (máquina toda, via OSHI) ---
    public final double systemCpuLoadPercent;
    public final double[] perCoreCpuLoadPercent;
    public final long systemRamUsedMb;
    public final long systemRamTotalMb;
    public final Double cpuTemperatureCelsius; // pode ser null se o sensor não estiver disponível

    public PerformanceSample(
            long secondsSinceStart,
            long epochMillis,
            int fps,
            double frameTimeMs,
            double tps,
            double tickTimeMs,
            int loadedChunks,
            int entityCount,
            long jvmHeapUsedMb,
            long jvmHeapMaxMb,
            double jvmCpuLoadPercent,
            double systemCpuLoadPercent,
            double[] perCoreCpuLoadPercent,
            long systemRamUsedMb,
            long systemRamTotalMb,
            Double cpuTemperatureCelsius
    ) {
        this.secondsSinceStart = secondsSinceStart;
        this.epochMillis = epochMillis;
        this.fps = fps;
        this.frameTimeMs = frameTimeMs;
        this.tps = tps;
        this.tickTimeMs = tickTimeMs;
        this.loadedChunks = loadedChunks;
        this.entityCount = entityCount;
        this.jvmHeapUsedMb = jvmHeapUsedMb;
        this.jvmHeapMaxMb = jvmHeapMaxMb;
        this.jvmCpuLoadPercent = jvmCpuLoadPercent;
        this.systemCpuLoadPercent = systemCpuLoadPercent;
        this.perCoreCpuLoadPercent = perCoreCpuLoadPercent;
        this.systemRamUsedMb = systemRamUsedMb;
        this.systemRamTotalMb = systemRamTotalMb;
        this.cpuTemperatureCelsius = cpuTemperatureCelsius;
    }

    /** Cabeçalho CSV correspondente à ordem dos campos em {@link #toCsvLine()}. */
    public static String csvHeader() {
        return "segundos,timestamp_epoch,fps,frame_time_ms,tps,tick_time_ms,chunks_carregados,"
                + "entidades,jvm_heap_usado_mb,jvm_heap_max_mb,jvm_cpu_percent,"
                + "sistema_cpu_percent,sistema_ram_usada_mb,sistema_ram_total_mb,cpu_temp_celsius";
    }

    /** Serializa a amostra como uma linha CSV (usada no log bruto da sessão). */
    public String toCsvLine() {
        return String.format(java.util.Locale.US,
                "%d,%d,%d,%.2f,%.2f,%.2f,%d,%d,%d,%d,%.2f,%.2f,%d,%d,%s",
                secondsSinceStart, epochMillis, fps, frameTimeMs, tps, tickTimeMs,
                loadedChunks, entityCount, jvmHeapUsedMb, jvmHeapMaxMb, jvmCpuLoadPercent,
                systemCpuLoadPercent, systemRamUsedMb, systemRamTotalMb,
                cpuTemperatureCelsius == null ? "" : String.format(java.util.Locale.US, "%.1f", cpuTemperatureCelsius)
        );
    }
}
