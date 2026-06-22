package com.eduualves.perfrecorder.data;

/**
 * Representa uma única amostra (snapshot) de métricas de desempenho
 * coletada em um instante específico durante a sessão de jogo.
 * <p>
 * Cada amostra contém dados do jogo (FPS, TPS, chunks, entidades), dados
 * do sistema operacional (CPU e RAM da máquina toda, obtidos via OSHI) e
 * dados detalhados de hardware (temperatura/voltagem de CPU, e métricas de
 * GPU e RAM) obtidos via a ponte LibreHardwareMonitor. Os campos vindos
 * exclusivamente da ponte (voltagem de CPU, tudo de GPU, clock/voltagem de
 * RAM) podem ser {@code null} quando o sensor dedicado não está disponível
 * - isso nunca impede a gravação do restante da amostra.
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
    public final Double cpuTemperatureCelsius; // pode ser null se nenhum sensor estiver disponível

    // --- Métricas detalhadas de CPU (via LibreHardwareMonitor; null se sensor indisponível) ---
    public final Double cpuCoreVoltageV;
    public final Double cpuPackagePowerW;
    public final Double cpuClockMhz;

    // --- Métricas de GPU (via LibreHardwareMonitor; null se sensor/GPU indisponível) ---
    public final Double gpuTemperatureCelsius;
    public final Double gpuCoreClockMhz;
    public final Double gpuMemoryClockMhz;
    public final Double gpuCoreVoltageV;
    public final Double gpuLoadPercent;
    public final Double gpuPowerW;
    public final Double gpuMemoryUsedMb;
    public final Double gpuMemoryTotalMb;
    public final Double gpuFanRpm;

    // --- Métricas detalhadas de RAM (via LibreHardwareMonitor; null se sensor indisponível) ---
    public final Double ramClockMhz;
    public final Double ramVoltageV;

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
            Double cpuTemperatureCelsius,
            Double cpuCoreVoltageV,
            Double cpuPackagePowerW,
            Double cpuClockMhz,
            Double gpuTemperatureCelsius,
            Double gpuCoreClockMhz,
            Double gpuMemoryClockMhz,
            Double gpuCoreVoltageV,
            Double gpuLoadPercent,
            Double gpuPowerW,
            Double gpuMemoryUsedMb,
            Double gpuMemoryTotalMb,
            Double gpuFanRpm,
            Double ramClockMhz,
            Double ramVoltageV
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
        this.cpuCoreVoltageV = cpuCoreVoltageV;
        this.cpuPackagePowerW = cpuPackagePowerW;
        this.cpuClockMhz = cpuClockMhz;
        this.gpuTemperatureCelsius = gpuTemperatureCelsius;
        this.gpuCoreClockMhz = gpuCoreClockMhz;
        this.gpuMemoryClockMhz = gpuMemoryClockMhz;
        this.gpuCoreVoltageV = gpuCoreVoltageV;
        this.gpuLoadPercent = gpuLoadPercent;
        this.gpuPowerW = gpuPowerW;
        this.gpuMemoryUsedMb = gpuMemoryUsedMb;
        this.gpuMemoryTotalMb = gpuMemoryTotalMb;
        this.gpuFanRpm = gpuFanRpm;
        this.ramClockMhz = ramClockMhz;
        this.ramVoltageV = ramVoltageV;
    }

    /** Cabeçalho CSV correspondente à ordem dos campos em {@link #toCsvLine()}. */
    public static String csvHeader() {
        return "segundos,timestamp_epoch,fps,frame_time_ms,tps,tick_time_ms,chunks_carregados,"
                + "entidades,jvm_heap_usado_mb,jvm_heap_max_mb,jvm_cpu_percent,"
                + "sistema_cpu_percent,sistema_ram_usada_mb,sistema_ram_total_mb,cpu_temp_celsius,"
                + "cpu_core_voltage_v,cpu_package_power_w,cpu_clock_mhz,"
                + "gpu_temp_celsius,gpu_core_clock_mhz,gpu_memory_clock_mhz,gpu_core_voltage_v,"
                + "gpu_load_percent,gpu_power_w,gpu_memory_used_mb,gpu_memory_total_mb,gpu_fan_rpm,"
                + "ram_clock_mhz,ram_voltage_v";
    }

    /** Serializa a amostra como uma linha CSV (usada no log bruto da sessão). */
    public String toCsvLine() {
        return String.format(java.util.Locale.US,
                "%d,%d,%d,%.2f,%.2f,%.2f,%d,%d,%d,%d,%.2f,%.2f,%d,%d,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s",
                secondsSinceStart, epochMillis, fps, frameTimeMs, tps, tickTimeMs,
                loadedChunks, entityCount, jvmHeapUsedMb, jvmHeapMaxMb, jvmCpuLoadPercent,
                systemCpuLoadPercent, systemRamUsedMb, systemRamTotalMb,
                fmt(cpuTemperatureCelsius, 1),
                fmt(cpuCoreVoltageV, 3),
                fmt(cpuPackagePowerW, 1),
                fmt(cpuClockMhz, 0),
                fmt(gpuTemperatureCelsius, 1),
                fmt(gpuCoreClockMhz, 0),
                fmt(gpuMemoryClockMhz, 0),
                fmt(gpuCoreVoltageV, 3),
                fmt(gpuLoadPercent, 1),
                fmt(gpuPowerW, 1),
                fmt(gpuMemoryUsedMb, 0),
                fmt(gpuMemoryTotalMb, 0),
                fmt(gpuFanRpm, 0),
                fmt(ramClockMhz, 0),
                fmt(ramVoltageV, 3)
        );
    }

    /** Formata um valor opcional com N casas decimais, ou string vazia se {@code null} (célula vazia no CSV). */
    private static String fmt(Double value, int decimalPlaces) {
        if (value == null) {
            return "";
        }
        return String.format(java.util.Locale.US, "%." + decimalPlaces + "f", value);
    }
}
