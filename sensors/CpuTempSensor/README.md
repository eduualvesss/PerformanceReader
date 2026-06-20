# CpuTempSensor

Processo auxiliar (.NET 8, C#) que lê a temperatura da CPU via
[LibreHardwareMonitorLib](https://github.com/LibreHardwareMonitor/LibreHardwareMonitor)
e a imprime continuamente em stdout. Veja os comentários em `Program.cs` para o
protocolo de comunicação usado entre este processo e o mod Java
(`LibreHardwareMonitorBridge`).

## Por que isso existe

A biblioteca OSHI, usada pelo mod para o restante das métricas de sistema, lê
temperatura de CPU via WMI (`MSAcpi_ThermalZoneTemperature`). Esse caminho não
funciona em diversas placas-mãe de terceiros — confirmado em testes com uma
HUANANZHI X99 + Xeon E5-2670 v3, mesmo executando o launcher como Administrador.

O LibreHardwareMonitorLib lê os registradores MSR diretamente via driver de
kernel (WinRing0), o mesmo mecanismo usado por ferramentas como HWMonitor e
MSI Afterburner/RivaTuner — por isso funciona onde o WMI padrão falha.

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

## Comportamento de fallback

Se o `.exe` não tiver sido publicado/embutido (por exemplo, durante
desenvolvimento local sem rodar `dotnet publish`), ou se ele falhar ao iniciar
(driver bloqueado, hardware incompatível, etc.), o mod simplesmente cai de
volta para a leitura via OSHI — exatamente o comportamento anterior a esta
mudança. Nenhuma falha aqui impede o mod de funcionar; apenas a coluna
`cpu_temp_celsius` permanece vazia, como já acontecia antes.

## Testando isoladamente

Você pode rodar o sensor sozinho, fora do mod, para depurar:

```bash
./bin/Release/net8.0/win-x64/publish/CpuTempSensor.exe
```

Saída esperada (uma leitura por segundo, até fechar o terminal):

```
READY
TEMP:54.0
TEMP:55.0
TEMP:54.5
...
```

Se aparecer `ERROR:<mensagem>` em vez de `READY`, o driver do
LibreHardwareMonitorLib não conseguiu abrir — geralmente por falta de
privilégio de administrador ou por política de segurança (antivírus/driver
signing) bloqueando o WinRing0. Tente executar o terminal como Administrador
para isolar a causa.
