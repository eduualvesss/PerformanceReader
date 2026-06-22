# CpuTempSensor

Processo auxiliar (.NET 8, C#) que lê métricas detalhadas de hardware via
[LibreHardwareMonitorLib](https://github.com/LibreHardwareMonitor/LibreHardwareMonitor)
e as imprime continuamente em stdout. Veja os comentários em `Program.cs` para o
protocolo de comunicação usado entre este processo e o mod Java
(`LibreHardwareMonitorBridge`).

## Por que isso existe

A biblioteca OSHI, usada pelo mod para o restante das métricas de sistema, lê
temperatura de CPU via WMI (`MSAcpi_ThermalZoneTemperature`). Esse caminho não
funciona em diversas placas-mãe de terceiros — confirmado em testes com uma
HUANANZHI X99 + Xeon E5-2670 v3, mesmo executando o launcher como Administrador.
A OSHI também não expõe **nenhuma** métrica de voltagem (CPU ou GPU), nem a
maior parte das métricas detalhadas de GPU (clock de núcleo/memória, uso,
temperatura, VRAM, RPM do cooler) ou de RAM (clock, voltagem).

O LibreHardwareMonitorLib lê os registradores MSR diretamente via driver de
kernel (WinRing0), o mesmo mecanismo usado por ferramentas como HWMonitor e
MSI Afterburner/RivaTuner — por isso funciona onde o WMI padrão falha, e é a
única fonte disponível para as métricas exclusivas mencionadas acima.

## Métricas coletadas

| Categoria | Métricas |
|---|---|
| CPU | Temperatura (°C), **voltagem do core (V)**, potência do package (W), clock médio dos núcleos (MHz) |
| GPU | Nome do modelo, temperatura (°C), clock do núcleo (MHz), clock da memória/VRAM (MHz), voltagem do core (V), uso/load (%), potência (W), VRAM usada/total (MB), RPM do(s) cooler(s) |
| RAM | Clock (MHz), voltagem DRAM (V) — quando expostos pela placa-mãe |

Cada métrica é independente: a ausência de uma (ex.: GPU não suportada, placa-mãe
que não expõe voltagem de RAM) nunca impede a leitura das demais.

## Protocolo de comunicação (stdout, uma linha por amostra)

```
READY                 -> emitido uma vez, após inicialização do driver
ERROR:<mensagem>      -> falha ao abrir o driver/hardware; processo termina
DATA:<json>           -> uma amostra completa, a cada ~1 segundo
```

O `<json>` é um objeto plano (sem aninhamento), por exemplo:

```json
{"cpu_temp_c":62.30,"cpu_core_voltage_v":1.235,"cpu_package_power_w":95.40,
 "cpu_clock_mhz":3401.00,"gpu_name":"NVIDIA GeForce GTX 1060 6GB",
 "gpu_temp_c":58.00,"gpu_core_clock_mhz":1708.00,"gpu_memory_clock_mhz":4004.00,
 "gpu_load_percent":45.00,"gpu_power_w":85.30,"gpu_memory_used_mb":2048.00,
 "gpu_memory_total_mb":6144.00,"ram_clock_mhz":2133.00,"ram_voltage_v":1.200}
```

Chaves de sensores indisponíveis simplesmente **não aparecem** no objeto (em vez
de aparecerem com valor `null`) — o lado Java trata "chave ausente" exatamente
como trataria um valor nulo.

> **Nota de compatibilidade:** versões anteriores deste sensor usavam o protocolo
> `TEMP:<valor>` / `TEMP:NULL`, reportando apenas temperatura de CPU. Esse
> protocolo foi substituído por `DATA:<json>` para acomodar as novas métricas de
> GPU/RAM/voltagem. `LibreHardwareMonitorBridge.java` já foi atualizado para o
> novo formato.

## Como compilar e publicar

Pré-requisito: .NET SDK 8.0 instalado (`dotnet --version` deve reportar 8.x).

```bash
cd sensors/CpuTempSensor
dotnet publish -c Release
```

Isso gera o executável self-contained (não exige .NET instalado na máquina do
jogador) em:

```
sensors/CpuTempSensor/bin/Release/net8.0/win-x64/publish/CpuTempSensor.exe
```

**Antes de rodar o build do mod**, copie (ou crie um symlink) esse executável
para a pasta que o Gradle embute como recurso:

```bash
mkdir -p sensors/CpuTempSensor/publish/sensors
cp bin/Release/net8.0/win-x64/publish/CpuTempSensor.exe publish/sensors/
```

A partir daí, `./gradlew build` (na raiz do projeto) já inclui o `.exe`
dentro do jar final, em `/sensors/CpuTempSensor.exe`. Em tempo de execução,
`SensorResourceExtractor` copia esse recurso para
`config/perfrecorder/sensors/CpuTempSensor.exe` na primeira inicialização do
mod, e `LibreHardwareMonitorBridge` o executa a partir daí.

## Exigência de privilégio de administrador (confirmado em teste real)

Em teste real (HUANANZHI X99 + Xeon E5-2670 v3), o sensor retornou nenhuma
leitura de temperatura/voltagem de CPU (chaves ausentes em todas as amostras)
quando executado **sem** privilégios de administrador, mesmo com o driver
abrindo sem erro (`READY` apareceu normalmente). Executando o mesmo binário em
um terminal **como Administrador**, os valores passaram a ser lidos
corretamente.

Isso significa que, na prática, **o launcher do Minecraft precisa ser
executado como Administrador** para que esta funcionalidade produza dados.
Isso é uma restrição do driver MSR/WinRing0 usado pelo LibreHardwareMonitorLib
para acessar os registradores de temperatura/voltagem - não há forma de
contornar isso apenas via código, é uma exigência do próprio mecanismo de
leitura em nível de kernel do Windows. As métricas de GPU, por outro lado,
costumam funcionar mesmo sem elevação na maioria dos drivers de vídeo
modernos (NVIDIA/AMD), mas a recomendação de executar como Administrador
continua sendo a mais segura para garantir a cobertura completa.

Se o launcher não estiver elevado, as métricas de CPU (temperatura/voltagem)
continuarão ausentes (não trava nem dá erro), e o mod cai no fallback do OSHI
para temperatura de CPU como já documentado abaixo - ou seja, o comportamento
seguro permanece o mesmo, só não há nenhuma fonte adicional disponível nesse
caso.

## Comportamento de fallback

Se o `.exe` não tiver sido publicado/embutido (por exemplo, durante
desenvolvimento local sem rodar `dotnet publish`), ou se ele falhar ao iniciar
(driver bloqueado, hardware incompatível, etc.), o mod simplesmente cai de
volta para a leitura via OSHI apenas para as métricas que a OSHI suporta
(temperatura de CPU, RAM usada/total). As métricas exclusivas deste sensor
(voltagem de CPU, qualquer dado de GPU, clock/voltagem de RAM) **não têm
fallback possível** e ficam com suas colunas vazias no CSV - a OSHI nunca as
expôs. Nenhuma falha aqui impede o mod de funcionar.

## Testando isoladamente

Você pode rodar o sensor sozinho, fora do mod, para depurar:

```bash
./bin/Release/net8.0/win-x64/publish/CpuTempSensor.exe
```

Saída esperada (uma leitura por segundo, até fechar o terminal):

```
READY
DATA:{"cpu_temp_c":54.00,"cpu_core_voltage_v":1.21,...}
DATA:{"cpu_temp_c":55.00,"cpu_core_voltage_v":1.22,...}
...
```

Se aparecer `ERROR:<mensagem>` em vez de `READY`, o driver do
LibreHardwareMonitorLib não conseguiu abrir — geralmente por falta de
privilégio de administrador ou por política de segurança (antivírus/driver
signing) bloqueando o WinRing0. Tente executar o terminal como Administrador
para isolar a causa.

