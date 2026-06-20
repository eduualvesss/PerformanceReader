package com.eduualves.perfrecorder.util;

import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.GlobalMemory;
import oshi.hardware.HardwareAbstractionLayer;
import oshi.hardware.Sensors;
import oshi.software.os.OperatingSystem;

/**
 * Encapsula o uso da biblioteca OSHI para coletar informações de hardware
 * da máquina como um todo (não apenas do processo Java/JVM).
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
    private final LibreHardwareMonitorBridge temperatureBridge;

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

        // OSHI lê temperatura via WMI (MSAcpi_ThermalZoneTemperature), que não funciona
        // em diversas placas-mãe de terceiros (confirmado em testes com HUANANZHI X99,
        // mesmo com privilégios de administrador). Como alternativa, tentamos um sensor
        // dedicado baseado em LibreHardwareMonitorLib, que lê os registradores MSR
        // diretamente via driver de kernel - o mesmo mecanismo usado por HWMonitor e
        // MSI Afterburner/RivaTuner. Se essa ponte não puder ser iniciada por qualquer
        // motivo, getCpuTemperatureCelsius() cai de volta para o OSHI automaticamente.
        SensorResourceExtractor.ensureExtracted();
        this.temperatureBridge = new LibreHardwareMonitorBridge().start();

        // Salvaguarda: se o jogo for fechado abruptamente (crash, "Force Quit", etc.)
        // sem que SessionRecorder.endSession() seja chamado, este hook garante que o
        // subprocesso CpuTempSensor.exe não fique órfão consumindo o driver/recursos.
        Runtime.getRuntime().addShutdownHook(new Thread(temperatureBridge::stop));
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
        if (temperatureBridge.isAvailable()) {
            Double bridgeTemp = temperatureBridge.getLastTemperature();
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

    /** Libera o subprocesso do sensor de temperatura, se estiver em execução. Chamar ao encerrar a sessão/mod. */
    public void shutdown() {
        temperatureBridge.stop();
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
