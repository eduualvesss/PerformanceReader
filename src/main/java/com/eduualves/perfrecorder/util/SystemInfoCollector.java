package com.eduualves.perfrecorder.util;

import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.GlobalMemory;
import oshi.hardware.HardwareAbstractionLayer;
import oshi.hardware.Sensors;
import oshi.software.os.OperatingSystem;

/**
 * Encapsula o uso da biblioteca OSHI para coletar informações de hardware
 * da máquina como um todo (não apenas do processo Java/JVM), complementada
 * pela ponte {@link LibreHardwareMonitorBridge} para métricas que a OSHI não
 * expõe de forma alguma (voltagem de CPU/GPU, clocks de GPU/RAM, temperatura
 * de GPU, uso de VRAM) ou que expõe de forma pouco confiável em certas placas
 * (temperatura de CPU).
 * <p>
 * Uma única instância deve ser reaproveitada durante toda a sessão, pois
 * o cálculo de uso de CPU depende de "ticks" anteriores para gerar uma
 * porcentagem (a primeira leitura é sempre descartável).
 */
public class SystemInfoCollector {

    private final SystemInfo systemInfo;
    private final HardwareAbstractionLayer hardware;
    private final CentralProcessor processor;
    private final GlobalMemory memory;
    private final Sensors sensors;
    private final OperatingSystem os;
    private final LibreHardwareMonitorBridge hardwareBridge;

    private long[] previousTicks;
    private long[][] previousProcTicks;

    public SystemInfoCollector() {
        this.systemInfo = new SystemInfo();
        this.hardware = systemInfo.getHardware();
        this.processor = hardware.getProcessor();
        this.memory = hardware.getMemory();
        this.sensors = hardware.getSensors();
        this.os = systemInfo.getOperatingSystem();
        this.previousTicks = processor.getSystemCpuLoadTicks();
        this.previousProcTicks = processor.getProcessorCpuLoadTicks();

        // OSHI lê temperatura de CPU via WMI (MSAcpi_ThermalZoneTemperature), que não
        // funciona em diversas placas-mãe de terceiros (confirmado em testes com
        // HUANANZHI X99, mesmo com privilégios de administrador), e não expõe voltagem
        // de CPU/GPU nem a maioria das métricas detalhadas de GPU/RAM. Como alternativa,
        // usamos um sensor dedicado baseado em LibreHardwareMonitorLib, que lê os
        // registradores MSR diretamente via driver de kernel - o mesmo mecanismo usado
        // por ferramentas como HWMonitor e MSI Afterburner/RivaTuner. Se essa ponte não
        // puder ser iniciada por qualquer motivo, as métricas básicas de CPU/RAM caem de
        // volta para a OSHI automaticamente; métricas exclusivas da ponte (voltagem,
        // detalhes de GPU) simplesmente ficam indisponíveis (não há fallback possível
        // para elas, já que a OSHI não as expõe).
        SensorResourceExtractor.ensureExtracted();
        this.hardwareBridge = new LibreHardwareMonitorBridge().start();

        // Salvaguarda: se o jogo for fechado abruptamente (crash, "Force Quit", etc.)
        // sem que SessionRecorder.endSession() seja chamado, este hook garante que o
        // subprocesso CpuTempSensor.exe não fique órfão consumindo o driver/recursos.
        Runtime.getRuntime().addShutdownHook(new Thread(hardwareBridge::stop));
    }

    /** Retorna o uso de CPU total do sistema (0-100%) desde a última chamada. */
    public double getSystemCpuLoadPercent() {
        double load = processor.getSystemCpuLoadBetweenTicks(previousTicks) * 100.0;
        previousTicks = processor.getSystemCpuLoadTicks();
        return load;
    }

    /** Retorna o uso de CPU por núcleo lógico (0-100% cada), desde a última chamada. */
    public double[] getPerCoreCpuLoadPercent() {
        double[] loads = processor.getProcessorCpuLoadBetweenTicks(previousProcTicks);
        previousProcTicks = processor.getProcessorCpuLoadTicks();
        double[] percentages = new double[loads.length];
        for (int i = 0; i < loads.length; i++) {
            percentages[i] = loads[i] * 100.0;
        }
        return percentages;
    }

    /**
     * Temperatura da CPU em Celsius, ou {@code null} se nenhuma fonte de leitura
     * estiver disponível.
     * <p>
     * Ordem de tentativa:
     * <ol>
     *   <li>{@link LibreHardwareMonitorBridge} (lê MSR via driver de kernel) - mais
     *       confiável, mas depende do sensor externo ter sido iniciado com sucesso;</li>
     *   <li>OSHI via WMI - mantido como fallback para máquinas onde o WMI funciona
     *       corretamente (ex.: muitos notebooks e placas OEM padrão).</li>
     * </ol>
     * Se ambas as fontes falharem, retorna {@code null} (mesmo comportamento de antes,
     * para não quebrar o formato do CSV nem o restante do mod).
     */
    public Double getCpuTemperatureCelsius() {
        if (hardwareBridge.isAvailable()) {
            Double bridgeTemp = hardwareBridge.getLastSnapshot().getDouble("cpu_temp_c");
            if (bridgeTemp != null) {
                return bridgeTemp;
            }
            // Ponte ativa mas ainda sem leitura válida (ex.: primeiríssima amostra,
            // antes do loop do sensor produzir a primeira linha) - tenta OSHI nesta
            // amostra específica, sem desistir da ponte para as próximas.
        }

        try {
            double temp = sensors.getCpuTemperature();
            if (temp <= 0) {
                return null;
            }
            return temp;
        } catch (Exception e) {
            // Alguns sistemas (especialmente VMs ou hardware sem sensores expostos) podem
            // lançar exceções em vez de simplesmente retornar 0. Tratamos como "indisponível".
            return null;
        }
    }

    /**
     * Voltagem do core da CPU, em Volts, ou {@code null} se indisponível.
     * <p>
     * Esta métrica só é exposta pela ponte {@link LibreHardwareMonitorBridge} (via
     * registradores MSR/VRM) - a OSHI não tem nenhum equivalente, então não há
     * fallback possível aqui. Se a ponte não estiver disponível (sensor não
     * compilado/embutido, driver bloqueado, ou jogo sem privilégio de administrador),
     * o valor retornado é sempre {@code null}.
     */
    public Double getCpuCoreVoltage() {
        if (!hardwareBridge.isAvailable()) {
            return null;
        }
        return hardwareBridge.getLastSnapshot().getDouble("cpu_core_voltage_v");
    }

    /** Potência (Package Power) consumida pela CPU em Watts, ou {@code null} se indisponível. */
    public Double getCpuPackagePowerWatts() {
        if (!hardwareBridge.isAvailable()) {
            return null;
        }
        return hardwareBridge.getLastSnapshot().getDouble("cpu_package_power_w");
    }

    /** Clock médio dos núcleos da CPU em MHz, ou {@code null} se indisponível. */
    public Double getCpuClockMhz() {
        if (!hardwareBridge.isAvailable()) {
            return null;
        }
        return hardwareBridge.getLastSnapshot().getDouble("cpu_clock_mhz");
    }

    // --- Métricas de GPU (exclusivas da ponte LibreHardwareMonitor; sem fallback OSHI) ---

    /** Temperatura da GPU em Celsius, ou {@code null} se indisponível. */
    public Double getGpuTemperatureCelsius() {
        if (!hardwareBridge.isAvailable()) {
            return null;
        }
        return hardwareBridge.getLastSnapshot().getDouble("gpu_temp_c");
    }

    /** Clock do núcleo da GPU em MHz, ou {@code null} se indisponível. */
    public Double getGpuCoreClockMhz() {
        if (!hardwareBridge.isAvailable()) {
            return null;
        }
        return hardwareBridge.getLastSnapshot().getDouble("gpu_core_clock_mhz");
    }

    /** Clock da memória (VRAM) da GPU em MHz, ou {@code null} se indisponível. */
    public Double getGpuMemoryClockMhz() {
        if (!hardwareBridge.isAvailable()) {
            return null;
        }
        return hardwareBridge.getLastSnapshot().getDouble("gpu_memory_clock_mhz");
    }

    /** Voltagem do core da GPU em Volts, ou {@code null} se indisponível. */
    public Double getGpuCoreVoltage() {
        if (!hardwareBridge.isAvailable()) {
            return null;
        }
        return hardwareBridge.getLastSnapshot().getDouble("gpu_core_voltage_v");
    }

    /** Uso (load) da GPU em porcentagem (0-100), ou {@code null} se indisponível. */
    public Double getGpuLoadPercent() {
        if (!hardwareBridge.isAvailable()) {
            return null;
        }
        return hardwareBridge.getLastSnapshot().getDouble("gpu_load_percent");
    }

    /** Potência consumida pela GPU em Watts, ou {@code null} se indisponível. */
    public Double getGpuPowerWatts() {
        if (!hardwareBridge.isAvailable()) {
            return null;
        }
        return hardwareBridge.getLastSnapshot().getDouble("gpu_power_w");
    }

    /** VRAM usada em MB, ou {@code null} se indisponível. */
    public Double getGpuMemoryUsedMb() {
        if (!hardwareBridge.isAvailable()) {
            return null;
        }
        return hardwareBridge.getLastSnapshot().getDouble("gpu_memory_used_mb");
    }

    /** VRAM total em MB, ou {@code null} se indisponível. */
    public Double getGpuMemoryTotalMb() {
        if (!hardwareBridge.isAvailable()) {
            return null;
        }
        return hardwareBridge.getLastSnapshot().getDouble("gpu_memory_total_mb");
    }

    /** Rotação do(s) cooler(s) da GPU em RPM, ou {@code null} se indisponível. */
    public Double getGpuFanRpm() {
        if (!hardwareBridge.isAvailable()) {
            return null;
        }
        return hardwareBridge.getLastSnapshot().getDouble("gpu_fan_rpm");
    }

    /**
     * Nome do modelo da GPU conforme reportado pela ponte LibreHardwareMonitor, ou
     * {@code null} se indisponível. Difere de {@link #getGpuInfo()} (que usa OSHI e é
     * usado na ficha técnica estática do relatório); este método reflete especificamente
     * qual GPU é a fonte das métricas dinâmicas acima.
     */
    public String getGpuNameFromBridge() {
        if (!hardwareBridge.isAvailable()) {
            return null;
        }
        return hardwareBridge.getLastSnapshot().getString("gpu_name");
    }

    // --- Métricas detalhadas de RAM (exclusivas da ponte; sem fallback OSHI) ---

    /** Clock da RAM em MHz, ou {@code null} se indisponível (depende da placa-mãe expor o sensor). */
    public Double getRamClockMhz() {
        if (!hardwareBridge.isAvailable()) {
            return null;
        }
        return hardwareBridge.getLastSnapshot().getDouble("ram_clock_mhz");
    }

    /** Voltagem da RAM (DRAM) em Volts, ou {@code null} se indisponível. */
    public Double getRamVoltage() {
        if (!hardwareBridge.isAvailable()) {
            return null;
        }
        return hardwareBridge.getLastSnapshot().getDouble("ram_voltage_v");
    }

    /** Libera o subprocesso do sensor de hardware, se estiver em execução. Chamar ao encerrar a sessão/mod. */
    public void shutdown() {
        hardwareBridge.stop();
    }

    public long getTotalRamMb() {
        return memory.getTotal() / (1024 * 1024);
    }

    public long getUsedRamMb() {
        long total = memory.getTotal();
        long available = memory.getAvailable();
        return (total - available) / (1024 * 1024);
    }

    // --- Informações estáticas da máquina (coletadas uma vez, no início da sessão) ---

    public String getCpuModelName() {
        return processor.getProcessorIdentifier().getName().trim();
    }

    public int getPhysicalCoreCount() {
        return processor.getPhysicalProcessorCount();
    }

    public int getLogicalCoreCount() {
        return processor.getLogicalProcessorCount();
    }

    public double getMaxCpuFreqGhz() {
        long[] freqs = processor.getCurrentFreq();
        long max = 0;
        for (long f : freqs) {
            if (f > max) max = f;
        }
        // fallback para a frequência máxima nominal caso a leitura dinâmica falhe
        if (max <= 0) {
            max = processor.getMaxFreq();
        }
        return max / 1_000_000_000.0;
    }

    public String getOsName() {
        return os.toString();
    }

    public String getManufacturerAndModel() {
        var computerSystem = hardware.getComputerSystem();
        return computerSystem.getManufacturer() + " " + computerSystem.getModel();
    }

    public String getGpuInfo() {
        try {
            var gpus = hardware.getGraphicsCards();
            if (gpus.isEmpty()) {
                return "Não detectada pela OSHI";
            }
            StringBuilder sb = new StringBuilder();
            for (var gpu : gpus) {
                if (sb.length() > 0) sb.append(" | ");
                sb.append(gpu.getName());
                long vram = gpu.getVRam();
                if (vram > 0) {
                    sb.append(" (").append(vram / (1024 * 1024)).append(" MB VRAM)");
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return "Não foi possível detectar a GPU";
        }
    }
}
