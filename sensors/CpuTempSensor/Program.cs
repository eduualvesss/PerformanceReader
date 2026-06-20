using LibreHardwareMonitor.Hardware;

namespace CpuTempSensor;

/// <summary>
/// Processo auxiliar mínimo, executado pelo mod Performance Recorder como subprocesso.
///
/// Motivação: a biblioteca OSHI (usada pelo mod em Java) lê temperatura de CPU via WMI
/// (MSAcpi_ThermalZoneTemperature), que não é exposto corretamente em diversas placas-mãe
/// de terceiros (confirmado em testes: HUANANZHI X99 com Xeon E5-2670 v3 retorna sempre
/// indisponível, mesmo executando como Administrador).
///
/// O LibreHardwareMonitorLib lê os registradores MSR diretamente via driver de kernel
/// (WinRing0), o mesmo mecanismo usado por ferramentas como HWMonitor e MSI Afterburner/
/// RivaTuner - por isso funciona onde o WMI padrão falha.
///
/// Protocolo de comunicação (stdout, uma linha por amostra):
///   "TEMP:<valor_em_celsius>"   -> leitura válida
///   "TEMP:NULL"                  -> sensor indisponível nesta leitura
///   "READY"                      -> emitido uma vez, após inicialização do driver,
///                                    sinalizando ao processo Java que pode começar a ler
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
                IsCpuEnabled = true
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

        // Diagnóstico: se as primeiras N leituras consecutivas vierem vazias, é muito
        // provável que o processo não esteja rodando com privilégio de administrador
        // (confirmado em teste real: o driver abre sem erro, mas nenhum sensor MSR é
        // populado sem elevação). Avisamos isso uma única vez em stderr - não afeta o
        // protocolo em stdout, que o lado Java continua lendo normalmente.
        const int consecutiveNullsBeforeWarning = 3;
        var consecutiveNulls = 0;
        var adminWarningPrinted = false;

        try
        {
            while (!stopRequested)
            {
                double? temp = ReadCpuPackageTemperature(computer);

                if (temp.HasValue)
                {
                    consecutiveNulls = 0;
                }
                else
                {
                    consecutiveNulls++;
                    if (consecutiveNulls >= consecutiveNullsBeforeWarning && !adminWarningPrinted)
                    {
                        Console.Error.WriteLine(
                            "AVISO: nenhum sensor de temperatura encontrado nas últimas "
                            + consecutiveNullsBeforeWarning + " leituras. Isto geralmente "
                            + "significa que o processo não está rodando como Administrador "
                            + "(o driver MSR abre sem erro, mas não expõe sensores sem "
                            + "elevação). Execute o launcher do Minecraft como Administrador "
                            + "para habilitar esta leitura.");
                        Console.Error.Flush();
                        adminWarningPrinted = true;
                    }
                }

                Console.WriteLine(temp.HasValue
                    ? $"TEMP:{temp.Value:F1}"
                    : "TEMP:NULL");
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
    /// Procura, entre todos os sensores do(s) hardware(s) do tipo CPU, a temperatura
    /// do "Package" (temperatura agregada do chip). Caso a placa não exponha um
    /// sensor de Package (algumas configurações expõem apenas por núcleo), faz
    /// fallback para a média dos sensores de núcleo individuais encontrados.
    /// </summary>
    private static double? ReadCpuPackageTemperature(Computer computer)
    {
        double? packageTemp = null;
        var coreTemps = new System.Collections.Generic.List<double>();

        foreach (IHardware hardware in computer.Hardware)
        {
            if (hardware.HardwareType != HardwareType.Cpu)
            {
                continue;
            }

            hardware.Update();

            foreach (ISensor sensor in hardware.Sensors)
            {
                if (sensor.SensorType != SensorType.Temperature || sensor.Value is null)
                {
                    continue;
                }

                string name = sensor.Name;
                if (name.Contains("Package", StringComparison.OrdinalIgnoreCase))
                {
                    packageTemp = sensor.Value;
                }
                else if (name.Contains("Core", StringComparison.OrdinalIgnoreCase))
                {
                    coreTemps.Add(sensor.Value!.Value);
                }
            }
        }

        if (packageTemp.HasValue)
        {
            return packageTemp;
        }

        if (coreTemps.Count > 0)
        {
            double sum = 0;
            foreach (double t in coreTemps) sum += t;
            return sum / coreTemps.Count;
        }

        return null;
    }
}
