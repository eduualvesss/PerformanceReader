using System.Globalization;
using System.Text;
using LibreHardwareMonitor.Hardware;

namespace CpuTempSensor;

/// <summary>
/// Processo auxiliar mínimo, executado pelo mod Performance Recorder como subprocesso.
///
/// Motivação: a biblioteca OSHI (usada pelo mod em Java) lê temperatura de CPU via WMI
/// (MSAcpi_ThermalZoneTemperature), que não é exposto corretamente em diversas placas-mãe
/// de terceiros (confirmado em testes: HUANANZHI X99 com Xeon E5-2670 v3 retorna sempre
/// indisponível, mesmo executando como Administrador). OSHI também não expõe nenhuma
/// métrica de voltagem de CPU, nem clocks/temperaturas/voltagens de GPU.
///
/// O LibreHardwareMonitorLib lê os registradores MSR diretamente via driver de kernel
/// (WinRing0), o mesmo mecanismo usado por ferramentas como HWMonitor e MSI Afterburner/
/// RivaTuner - por isso funciona onde o WMI padrão falha, e também é a única fonte
/// disponível aqui para voltagem de CPU e para a maior parte das métricas de GPU/RAM.
///
/// Protocolo de comunicação (stdout, uma linha por amostra):
///   "DATA:<json>"  -> leitura desta amostra, serializada como um objeto JSON plano
///                     (apenas pares chave/valor numéricos ou null, sem aninhamento -
///                     ver SerializeSample()). Chaves ausentes do hardware (ex.: GPU
///                     não detectada) simplesmente não aparecem no objeto.
///   "READY"        -> emitido uma vez, após inicialização do driver, sinalizando ao
///                      processo Java que pode começar a ler.
///   "ERROR:<msg>"  -> falha ao abrir o driver/hardware; processo termina com código 1.
///
/// Compatibilidade: o protocolo antigo ("TEMP:<valor>" / "TEMP:NULL") foi substituído
/// por "DATA:<json>", que já inclui a temperatura de CPU sob a chave "cpu_temp_c".
/// O lado Java (LibreHardwareMonitorBridge) foi atualizado para ler o novo formato.
///
/// O processo roda em loop até receber EOF em stdin (o mod Java fecha o stream de
/// entrada para sinalizar o encerramento) ou até o processo pai morrer.
/// </summary>
internal static class Program
{
    private const int SampleIntervalMs = 1000;

    private static int Main()
    {
        Computer? computer = null;

        try
        {
            computer = new Computer
            {
                IsCpuEnabled = true,
                IsGpuEnabled = true,
                IsMemoryEnabled = true,
                IsMotherboardEnabled = true
            };
            computer.Open();
        }
        catch (Exception ex)
        {
            // Falha ao abrir o driver (ex: sem privilégio de administrador, driver
            // bloqueado por política de segurança, ou hardware incompatível).
            // Sinalizamos isso de forma explícita e terminamos - o lado Java trata
            // a ausência de "READY" como "sensor indisponível" e cai no fallback.
            Console.WriteLine("ERROR:" + ex.Message.Replace('\n', ' ').Replace('\r', ' '));
            return 1;
        }

        Console.WriteLine("READY");
        Console.Out.Flush();

        // Thread separada apenas para detectar o fechamento do stdin (sinal de parada
        // enviado pelo processo Java). Quando o pai fecha o pipe, ReadLine() retorna null.
        var stopRequested = false;
        var watcherThread = new System.Threading.Thread(() =>
        {
            Console.In.ReadLine();
            stopRequested = true;
        })
        {
            IsBackground = true
        };
        watcherThread.Start();

        // Diagnóstico: se as primeiras N leituras consecutivas vierem totalmente vazias
        // (nem temperatura, nem voltagem de CPU), é muito provável que o processo não
        // esteja rodando com privilégio de administrador (confirmado em teste real: o
        // driver abre sem erro, mas nenhum sensor MSR é populado sem elevação). Avisamos
        // isso uma única vez em stderr - não afeta o protocolo em stdout.
        const int consecutiveNullsBeforeWarning = 3;
        var consecutiveNulls = 0;
        var adminWarningPrinted = false;

        try
        {
            while (!stopRequested)
            {
                HardwareSample sample = ReadHardwareSample(computer);

                bool cpuMsrEmpty = sample.CpuTempC is null && sample.CpuCoreVoltageV is null;
                if (!cpuMsrEmpty)
                {
                    consecutiveNulls = 0;
                }
                else
                {
                    consecutiveNulls++;
                    if (consecutiveNulls >= consecutiveNullsBeforeWarning && !adminWarningPrinted)
                    {
                        Console.Error.WriteLine(
                            "AVISO: nenhum sensor MSR de CPU (temperatura/voltagem) encontrado nas "
                            + "últimas " + consecutiveNullsBeforeWarning + " leituras. Isto geralmente "
                            + "significa que o processo não está rodando como Administrador "
                            + "(o driver MSR abre sem erro, mas não expõe sensores sem "
                            + "elevação). Execute o launcher do Minecraft como Administrador "
                            + "para habilitar esta leitura.");
                        Console.Error.Flush();
                        adminWarningPrinted = true;
                    }
                }

                Console.WriteLine("DATA:" + SerializeSample(sample));
                Console.Out.Flush();

                System.Threading.Thread.Sleep(SampleIntervalMs);
            }
        }
        finally
        {
            computer.Close();
        }

        return 0;
    }

    /// <summary>
    /// Snapshot plano de todas as métricas lidas nesta amostra. Cada campo é independente
    /// e pode ser null caso o hardware/sensor correspondente não esteja disponível -
    /// nenhum campo individual ausente impede os demais de serem reportados.
    /// </summary>
    private readonly struct HardwareSample
    {
        public double? CpuTempC { get; init; }
        public double? CpuCoreVoltageV { get; init; }
        public double? CpuPackagePowerW { get; init; }
        public double? CpuClockMhz { get; init; }

        public string? GpuName { get; init; }
        public double? GpuTempC { get; init; }
        public double? GpuCoreClockMhz { get; init; }
        public double? GpuMemoryClockMhz { get; init; }
        public double? GpuCoreVoltageV { get; init; }
        public double? GpuLoadPercent { get; init; }
        public double? GpuPowerW { get; init; }
        public double? GpuMemoryUsedMb { get; init; }
        public double? GpuMemoryTotalMb { get; init; }
        public double? GpuFanRpm { get; init; }

        public double? RamClockMhz { get; init; }
        public double? RamVoltageV { get; init; }
    }

    /// <summary>
    /// Percorre todo o hardware exposto pelo Computer e monta um único HardwareSample
    /// com a melhor leitura disponível de cada métrica. Cada bloco (CPU/GPU/RAM) é
    /// independente: a falha ou ausência de um tipo de hardware nunca impede a leitura
    /// dos demais.
    /// </summary>
    private static HardwareSample ReadHardwareSample(Computer computer)
    {
        double? cpuTemp = null;
        double? cpuCoreVoltage = null;
        double? cpuPackagePower = null;
        double? cpuClock = null;
        var coreTemps = new System.Collections.Generic.List<double>();
        var coreVoltages = new System.Collections.Generic.List<double>();
        var coreClocks = new System.Collections.Generic.List<double>();

        string? gpuName = null;
        double? gpuTemp = null;
        double? gpuCoreClock = null;
        double? gpuMemClock = null;
        double? gpuCoreVoltage = null;
        double? gpuLoad = null;
        double? gpuPower = null;
        double? gpuMemUsed = null;
        double? gpuMemTotal = null;
        double? gpuFanRpm = null;

        double? ramClock = null;
        double? ramVoltage = null;

        foreach (IHardware hardware in computer.Hardware)
        {
            hardware.Update();

            switch (hardware.HardwareType)
            {
                case HardwareType.Cpu:
                    foreach (ISensor sensor in hardware.Sensors)
                    {
                        if (sensor.Value is null) continue;
                        string name = sensor.Name;
                        float value = sensor.Value.Value;

                        switch (sensor.SensorType)
                        {
                            case SensorType.Temperature:
                                if (name.Contains("Package", StringComparison.OrdinalIgnoreCase))
                                {
                                    cpuTemp = value;
                                }
                                else if (name.Contains("Core", StringComparison.OrdinalIgnoreCase))
                                {
                                    coreTemps.Add(value);
                                }
                                break;

                            case SensorType.Voltage:
                                // Em praticamente todas as plataformas Intel/AMD suportadas pelo
                                // LibreHardwareMonitorLib, a voltagem de núcleo é exposta como
                                // "CPU Core" (agregada) e/ou "CPU Core #N" (por núcleo individual).
                                // Algumas placas só expõem por núcleo, daí o fallback para a média.
                                if (name.Equals("CPU Core", StringComparison.OrdinalIgnoreCase)
                                    || name.Equals("Core", StringComparison.OrdinalIgnoreCase))
                                {
                                    cpuCoreVoltage = value;
                                }
                                else if (name.Contains("Core", StringComparison.OrdinalIgnoreCase)
                                         && !name.Contains("VID", StringComparison.OrdinalIgnoreCase))
                                {
                                    coreVoltages.Add(value);
                                }
                                break;

                            case SensorType.Power:
                                if (name.Contains("Package", StringComparison.OrdinalIgnoreCase))
                                {
                                    cpuPackagePower = value;
                                }
                                break;

                            case SensorType.Clock:
                                // Ignora o sensor "Bus Speed"; queremos o clock dos núcleos.
                                if (name.Contains("Core", StringComparison.OrdinalIgnoreCase))
                                {
                                    coreClocks.Add(value);
                                }
                                break;
                        }
                    }
                    break;

                case HardwareType.GpuNvidia:
                case HardwareType.GpuAmd:
                case HardwareType.GpuIntel:
                    // Se houver mais de uma GPU, mantemos a primeira encontrada com leitura
                    // válida (o caso comum de um único usuário com uma GPU dedicada).
                    gpuName ??= hardware.Name;

                    foreach (ISensor sensor in hardware.Sensors)
                    {
                        if (sensor.Value is null) continue;
                        string name = sensor.Name;
                        float value = sensor.Value.Value;

                        switch (sensor.SensorType)
                        {
                            case SensorType.Temperature:
                                if (gpuTemp is null && (name.Contains("Core", StringComparison.OrdinalIgnoreCase)
                                                         || name.Contains("GPU", StringComparison.OrdinalIgnoreCase)))
                                {
                                    gpuTemp = value;
                                }
                                break;

                            case SensorType.Clock:
                                if (name.Contains("Core", StringComparison.OrdinalIgnoreCase))
                                {
                                    gpuCoreClock = value;
                                }
                                else if (name.Contains("Memory", StringComparison.OrdinalIgnoreCase))
                                {
                                    gpuMemClock = value;
                                }
                                break;

                            case SensorType.Voltage:
                                if (gpuCoreVoltage is null)
                                {
                                    gpuCoreVoltage = value;
                                }
                                break;

                            case SensorType.Load:
                                if (name.Contains("Core", StringComparison.OrdinalIgnoreCase)
                                    || name.Equals("GPU Core", StringComparison.OrdinalIgnoreCase))
                                {
                                    gpuLoad = value;
                                }
                                break;

                            case SensorType.Power:
                                if (gpuPower is null)
                                {
                                    gpuPower = value;
                                }
                                break;

                            case SensorType.SmallData:
                                if (name.Contains("Memory Used", StringComparison.OrdinalIgnoreCase))
                                {
                                    gpuMemUsed = value;
                                }
                                else if (name.Contains("Memory Total", StringComparison.OrdinalIgnoreCase))
                                {
                                    gpuMemTotal = value;
                                }
                                break;

                            case SensorType.Fan:
                                if (gpuFanRpm is null)
                                {
                                    gpuFanRpm = value;
                                }
                                break;
                        }
                    }
                    break;

                case HardwareType.Memory:
                    // Não há nada a ler aqui no momento: uso de RAM (usada/disponível/total)
                    // já é coberto de forma confiável pela OSHI em SystemInfoCollector
                    // (getUsedRamMb/getTotalRamMb), então evitamos duplicar essa leitura.
                    // O hardware "Memory" do LibreHardwareMonitorLib não expõe clock nem
                    // voltagem - esses sensores aparecem sob o Motherboard/SuperIO (ver
                    // case HardwareType.Motherboard abaixo).
                    break;

                case HardwareType.Motherboard:
                    // Em algumas placas, o clock/voltagem do módulo de RAM aparece sob o
                    // subhardware "SuperIO" do motherboard em vez do hardware "Memory" -
                    // por isso percorremos os subhardwares aqui também.
                    foreach (IHardware sub in hardware.SubHardware)
                    {
                        sub.Update();
                        foreach (ISensor sensor in sub.Sensors)
                        {
                            if (sensor.Value is null) continue;
                            string name = sensor.Name;
                            float value = sensor.Value.Value;

                            if (sensor.SensorType == SensorType.Clock
                                && name.Contains("Memory", StringComparison.OrdinalIgnoreCase))
                            {
                                ramClock ??= value;
                            }
                            else if (sensor.SensorType == SensorType.Voltage
                                     && (name.Contains("DRAM", StringComparison.OrdinalIgnoreCase)
                                         || name.Contains("Memory", StringComparison.OrdinalIgnoreCase)))
                            {
                                ramVoltage ??= value;
                            }
                        }
                    }
                    break;
            }
        }

        // Fallback de temperatura de CPU: média dos núcleos se não houver sensor "Package".
        if (cpuTemp is null && coreTemps.Count > 0)
        {
            cpuTemp = Average(coreTemps);
        }

        // Fallback de voltagem de CPU: média dos núcleos individuais se não houver
        // sensor agregado "CPU Core".
        if (cpuCoreVoltage is null && coreVoltages.Count > 0)
        {
            cpuCoreVoltage = Average(coreVoltages);
        }

        if (cpuClock is null && coreClocks.Count > 0)
        {
            cpuClock = Average(coreClocks);
        }

        return new HardwareSample
        {
            CpuTempC = cpuTemp,
            CpuCoreVoltageV = cpuCoreVoltage,
            CpuPackagePowerW = cpuPackagePower,
            CpuClockMhz = cpuClock,

            GpuName = gpuName,
            GpuTempC = gpuTemp,
            GpuCoreClockMhz = gpuCoreClock,
            GpuMemoryClockMhz = gpuMemClock,
            GpuCoreVoltageV = gpuCoreVoltage,
            GpuLoadPercent = gpuLoad,
            GpuPowerW = gpuPower,
            GpuMemoryUsedMb = gpuMemUsed,
            GpuMemoryTotalMb = gpuMemTotal,
            GpuFanRpm = gpuFanRpm,

            RamClockMhz = ramClock,
            RamVoltageV = ramVoltage
        };
    }

    private static double Average(System.Collections.Generic.List<double> values)
    {
        double sum = 0;
        foreach (double v in values) sum += v;
        return sum / values.Count;
    }

    /// <summary>
    /// Serializa a amostra como um objeto JSON plano (sem aninhamento), escrito à mão
    /// para evitar adicionar uma dependência de serialização externa a este executável
    /// minimalista. Campos com valor null são omitidos do objeto inteiramente - o lado
    /// Java trata uma chave ausente exatamente como trataria um valor null.
    /// </summary>
    private static string SerializeSample(HardwareSample s)
    {
        var sb = new StringBuilder();
        sb.Append('{');
        bool first = true;

        void AppendNumber(string key, double? value)
        {
            if (value is null) return;
            if (!first) sb.Append(',');
            first = false;
            sb.Append('"').Append(key).Append("\":")
              .Append(value.Value.ToString("F2", CultureInfo.InvariantCulture));
        }

        void AppendString(string key, string? value)
        {
            if (value is null) return;
            if (!first) sb.Append(',');
            first = false;
            string escaped = value.Replace("\\", "\\\\").Replace("\"", "\\\"");
            sb.Append('"').Append(key).Append("\":\"").Append(escaped).Append('"');
        }

        AppendNumber("cpu_temp_c", s.CpuTempC);
        AppendNumber("cpu_core_voltage_v", s.CpuCoreVoltageV);
        AppendNumber("cpu_package_power_w", s.CpuPackagePowerW);
        AppendNumber("cpu_clock_mhz", s.CpuClockMhz);

        AppendString("gpu_name", s.GpuName);
        AppendNumber("gpu_temp_c", s.GpuTempC);
        AppendNumber("gpu_core_clock_mhz", s.GpuCoreClockMhz);
        AppendNumber("gpu_memory_clock_mhz", s.GpuMemoryClockMhz);
        AppendNumber("gpu_core_voltage_v", s.GpuCoreVoltageV);
        AppendNumber("gpu_load_percent", s.GpuLoadPercent);
        AppendNumber("gpu_power_w", s.GpuPowerW);
        AppendNumber("gpu_memory_used_mb", s.GpuMemoryUsedMb);
        AppendNumber("gpu_memory_total_mb", s.GpuMemoryTotalMb);
        AppendNumber("gpu_fan_rpm", s.GpuFanRpm);

        AppendNumber("ram_clock_mhz", s.RamClockMhz);
        AppendNumber("ram_voltage_v", s.RamVoltageV);

        sb.Append('}');
        return sb.ToString();
    }
}
