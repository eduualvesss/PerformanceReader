# Performance Recorder

Mod Fabric para **Minecraft 26.1.2** que registra o desempenho real da sua máquina durante uma sessão de jogo — desde o momento em que você entra em um mundo até o momento em que sai — e gera um relatório `.txt` detalhado, pensado para ser lido tanto por um humano (você) quanto por uma IA (usada para analisar os dados e sugerir mudanças de mods/configuração).

Começou como uma ferramenta prática para responder a uma pergunta: *"vale a pena trocar minha CPU?"* — e acabou se tornando também um pequeno projeto completo de Java/Gradle/Fabric.

## Funcionalidades

### Gravação de sessão
- Detecta automaticamente a entrada/saída de um mundo (singleplayer ou multiplayer), funcionando inteiramente no lado cliente.
- Tira uma amostra por segundo, durante toda a sessão, sem necessidade de comandos manuais.
- Grava o CSV bruto de forma incremental (linha por linha, com flush imediato) — se o jogo travar ou for fechado de forma abrupta, os dados já gravados não se perdem.

### Métricas coletadas a cada amostra
- **FPS** e tempo de quadro (frame time, em ms)
- **TPS** (ticks por segundo) e tempo médio de tick, calculados a partir dos ticks reais observados pelo cliente
- **Chunks carregados** e **contagem de entidades** no mundo atual
- **Uso de heap da JVM** (atual e máximo configurado via `-Xmx`)
- **Uso de CPU do processo Java** (via `com.sun.management.OperatingSystemMXBean`)
- **Uso de CPU do sistema inteiro**, agregado e por núcleo lógico (via OSHI)
- **RAM usada e total do sistema** (via OSHI)
- **Temperatura da CPU** (veja seção dedicada abaixo — esta métrica passou por uma reformulação importante na v2.0.0)

### Arquivos gerados por sessão
Cada sessão cria uma pasta `<timestamp>_<nome_do_mundo>` dentro de `perfrecorder_reports/`, contendo:
- **`dados_brutos.csv`** — dados brutos, segundo a segundo, de todas as métricas acima. Ideal para análise mais profunda, planilhas, ou para alimentar uma conversa com IA.
- **`relatorio.txt`** — relatório legível por humano: especificações da máquina, estatísticas agregadas (mín/méd/máx/p95) de cada métrica, uma linha do tempo condensada (amostras a cada 10s), e uma lista de pontos de atenção detectados automaticamente (ex.: "FPS abaixo de 30 em N amostras", "TPS abaixo de 18 — indica travamentos").

### Captura de temperatura da CPU (reformulada na v2.0.0)

Esta é a mudança mais significativa desta versão. Anteriormente, a temperatura era lida apenas via **OSHI** (que por sua vez usa WMI no Windows — `MSAcpi_ThermalZoneTemperature`). Essa abordagem **não funciona em diversas placas-mãe de terceiros**: foi confirmado em testes reais que uma HUANANZHI X99 com Xeon E5-2670 v3 nunca retorna temperatura por esse caminho, mesmo executando o jogo como Administrador.

A partir da v2.0.0, o mod usa uma estratégia em duas camadas:

1. **Sensor dedicado via LibreHardwareMonitorLib** (`sensors/CpuTempSensor/`) — um pequeno executável .NET, iniciado pelo mod como subprocesso, que lê a temperatura diretamente dos registradores MSR via driver de kernel (o mesmo mecanismo usado por ferramentas como HWMonitor e MSI Afterburner/RivaTuner). Essa é a fonte preferencial sempre que disponível.
2. **Fallback automático para OSHI** — se o sensor dedicado não puder ser iniciado (executável ausente, falha ao abrir o driver, etc.), o mod volta a tentar a leitura padrão via OSHI, exatamente como na v1.0.0. Se ambas as fontes falharem, a coluna `cpu_temp_celsius` simplesmente fica vazia — o mod nunca trava nem interrompe a sessão por causa disso.

**Importante: o sensor dedicado exige que o jogo seja executado como Administrador.** Isso foi confirmado em teste real — o driver abre sem erro mesmo sem elevação, mas não consegue popular nenhum sensor sem privilégio de administrador. Sem essa elevação, o sensor fica disponível mas retorna sempre "indisponível" para aquela amostra, e o mod silenciosamente recorre ao OSHI (que, na mesma máquina, também não vai funcionar nessas placas). Veja `sensors/CpuTempSensor/README.md` para detalhes de build e diagnóstico.

Validação: em sessões de teste reais, os valores capturados por essa nova fonte (ex.: média de ~76°C em uma sessão de Minecraft) corresponderam de forma consistente com leituras simultâneas do MSI Afterburner na mesma máquina.

### Detecção de problemas (automática)
Ao final da sessão, o relatório lista automaticamente:
- Percentual de amostras com FPS abaixo de 30
- Percentual de amostras com TPS abaixo de 18 (indicando travamentos no mundo)
- Percentual de amostras em que o heap da JVM passou de 90% do limite configurado (`-Xmx`)

### Comparação com hardware hipotético
O mod em si **só registra o hardware que você realmente possui** — ele nunca simula um processador, placa-mãe ou GPU diferente. Para responder "como seria isso em outra CPU", o fluxo recomendado é:
1. Gravar uma sessão real no seu hardware atual.
2. Compartilhar `relatorio.txt` (e `dados_brutos.csv` para mais detalhe) com uma IA, ou comparar manualmente contra benchmarks publicados (PassMark, Cinebench, Geekbench, etc.) do hardware considerado.
3. Tratar o resultado como uma **estimativa**, não uma medição — diferenças de single-thread vs. multi-thread, contagem de núcleos/threads, e comportamento de threading específico de cada mod podem mudar o resultado de formas que um simples escalonamento percentual não captura.

## Requisitos

| Componente | Versão |
|---|---|
| Minecraft | 26.1.2 |
| Fabric Loader | 0.18.4+ (testado com 0.19.3) |
| Fabric API | 0.151.0+26.1.2 ou 0.152.1+26.1.2 |
| Java (build e execução) | JDK 25 |
| Gradle | 9.4+ (o wrapper gera isso automaticamente) |
| .NET SDK (apenas para compilar o sensor de temperatura) | 8.0+ |

> O Minecraft 26.1 exige especificamente o **Java 25** — garanta que você tem o JDK completo instalado, não apenas o runtime embutido no launcher.

## Compilando a partir do código-fonte

### 1. Sensor de temperatura (.NET) — opcional, mas recomendado
```powershell
cd sensors/CpuTempSensor
dotnet publish -c Release
mkdir publish\sensors -Force
copy bin\Release\net8.0\win-x64\publish\CpuTempSensor.exe publish\sensors\ -Force
```
Se você pular esta etapa, o mod compila e funciona normalmente — apenas a temperatura ficará disponível somente via OSHI (que pode ou não funcionar na sua placa-mãe).

### 2. Mod (Java/Fabric)
1. Instale o **JDK 25** ([Adoptium Temurin](https://adoptium.net/temurin/releases/?version=25) é uma opção gratuita e estável).
2. Abra a pasta do projeto no **IntelliJ IDEA** (a edição Community já serve) e deixe importar como projeto Gradle.
3. Se `gradlew`/`gradlew.bat` ainda não existir, gere uma vez com um Gradle instalado no sistema:
   ```
   gradle wrapper --gradle-version 9.4.0
   ```
4. Compile:
   ```
   ./gradlew build        # Linux/macOS
   .\gradlew.bat build    # Windows
   ```
5. O jar final aparece em `build/libs/perfrecorder-2.0.0.jar`.

### Armadilhas conhecidas de build (e por que acontecem)

- **Conflito de versão do `oshi-core` com outros mods.** Vários mods populares (ex.: Bobby) também empacotam OSHI. Se duas versões diferentes de OSHI acabam no classpath ao mesmo tempo, aparece um aviso `Configuration conflict: there is more than one oshi.properties file`, geralmente seguido de crash. Correção: alinhe o `oshi_version` deste mod no `gradle.properties` com a versão que o resto do seu modpack já usa.
- **`NoSuchMethodError: IsProcessorFeaturePresent` no Windows.** Esse método só foi adicionado ao JNA na versão **5.14.0**. Se você atualizar o `oshi-core` sem também atualizar as versões de `jna`/`jna-platform` nas linhas `include(...)` do `build.gradle`, a versão antiga do JNA acaba vencendo silenciosamente no classpath e o OSHI quebra ao chamar um método que não existe nela. Correção: mantenha `jna`/`jna-platform` em uma versão recente (5.17.0+) junto com a versão do `oshi-core` usada.
- **`getCpuLoad()` não existe em `OperatingSystemMXBean`.** O uso de CPU do processo da JVM é exposto via `com.sun.management.OperatingSystemMXBean#getProcessCpuLoad()`, uma interface estendida específica do OpenJDK — não a interface simples `java.lang.management.OperatingSystemMXBean`. Se ambas forem importadas, o Java lança um erro de *referência ambígua* porque o nome simples colide; usar o tipo totalmente qualificado `com.sun.management.OperatingSystemMXBean` no campo (com um cast explícito na construção) evita o conflito.
- **`getProcessorCpuLoadBetweenTicks` espera `long[][]`, não `long[]`.** O OSHI distingue ticks de CPU *agregados do sistema* (`long[]`, de `getSystemCpuLoadTicks()`) de ticks *por núcleo* (`long[][]`, de `getProcessorCpuLoadTicks()`). Não são intercambiáveis — mantenha dois campos separados de "ticks anteriores" se precisar de ambos.
- **Sensor de temperatura retorna sempre `TEMP:NULL`.** Antes de investigar o código, confirme privilégio de administrador: confirmado em teste real que, sem elevação, o driver MSR abre sem erro mas não popula nenhum sensor. Execute o `.exe` isoladamente em um terminal como Administrador para isolar a causa (veja `sensors/CpuTempSensor/README.md`).

## Instalação (para jogar, não para desenvolver)

1. Instale o [Fabric Loader](https://fabricmc.net/use/installer/) para Minecraft 26.1.2.
2. Coloque `perfrecorder-2.0.0.jar` e o jar correspondente da **Fabric API** na pasta `mods` da sua instância.
3. Inicie o jogo usando o perfil Fabric 26.1.2.
4. **Para captura de temperatura funcionar**, inicie o launcher do Minecraft como Administrador (clique direito → "Executar como administrador"). Sem isso, o mod continua funcionando normalmente, mas a coluna de temperatura fica vazia.

Os relatórios são gravados em `<diretório_do_jogo>/perfrecorder_reports/`. O diretório do jogo depende do seu launcher — no launcher vanilla é `.minecraft`, mas launchers alternativos (TLauncher, MultiMC, Prism, CurseForge, etc.) costumam usar sua própria estrutura de pastas dentro de `%appdata%`.

## Lendo um relatório

Cada sessão gera uma pasta `<timestamp>_<nome_do_mundo>` contendo:

- **`relatorio.txt`** — comece por aqui. Especificações da máquina, estatísticas agregadas (mín/méd/máx/p95) de cada métrica monitorada, uma linha do tempo a cada 10 segundos, e uma lista curta de problemas detectados automaticamente.
- **`dados_brutos.csv`** — os dados completos, segundo a segundo, úteis para análise mais profunda, planilhas, ou para retroalimentar uma conversa com IA.

### Particularidades conhecidas dos dados

- **Os primeiros ~3-5 segundos de toda sessão mostram `FPS=0`.** Isso é o tempo de carregamento do mundo (geração de chunks, preparação de renderização), não uma queda real de desempenho — vale descontar mentalmente isso ao ler o resumo, já que pode arrastar o mínimo de FPS para 0 sem refletir um problema real.
- **`chunks_carregados` atualmente reporta um valor constante, sem mudar durante toda a sessão.** Bug conhecido: o valor é extraído de forma defensiva da string de debug de `Level#gatherChunkSourceStats()` via uma regex que captura o primeiro número inteiro encontrado, o que não necessariamente corresponde à contagem real de chunks carregados. Trate esta coluna como não confiável até que seja corrigida.
- **Pode haver uma amostra duplicada no início da sessão** (mesmo valor de `segundos`, duas linhas consecutivas). Provavelmente uma pequena corrida de inicialização entre o primeiro tick do cliente e a primeira chamada de amostragem. Não afeta a integridade do restante dos dados, mas vale ter em mente ao calcular médias sobre os primeiros segundos.
- **`cpu_temp_celsius` pode ficar vazia mesmo na v2.0.0** se: (a) o sensor dedicado não foi compilado/embutido no jar, (b) o jogo não foi executado como Administrador, ou (c) nenhuma das duas fontes (sensor dedicado ou OSHI) conseguiu ler o hardware. Isso não é um erro — é o comportamento seguro de fallback documentado acima.

## Estrutura do projeto

```
perfrecorder/
├── build.gradle
├── gradle.properties
├── sensors/
│   └── CpuTempSensor/                    # Sensor .NET dedicado (LibreHardwareMonitorLib)
│       ├── CpuTempSensor.csproj
│       ├── Program.cs
│       └── README.md                      # Instruções de build e diagnóstico do sensor
├── src/main/java/com/eduualves/perfrecorder/
│   ├── PerfRecorderClient.java            # Ponto de entrada; registra eventos de ciclo de vida do Fabric
│   ├── SessionRecorder.java                # Ciclo de vida da sessão, amostragem, escrita do CSV bruto
│   ├── data/PerformanceSample.java         # Snapshot de um único instante no tempo
│   ├── report/ReportWriter.java            # Gera o relatorio.txt a partir das amostras coletadas
│   └── util/
│       ├── SystemInfoCollector.java        # Wrapper OSHI + orquestração da leitura de temperatura
│       ├── LibreHardwareMonitorBridge.java # Gerencia o subprocesso do sensor .NET (novo na v2.0.0)
│       └── SensorResourceExtractor.java    # Extrai o .exe embutido no jar na primeira execução (novo na v2.0.0)
└── src/main/resources/
    └── fabric.mod.json
```

## Notas de design

- **Apenas client-side.** Este mod mede a experiência do jogador local (FPS, CPU/RAM da sua máquina), então não precisa de componente server-side e funciona imediatamente em singleplayer.
- **Defensivo por design.** Toda passagem de amostragem e todo handler de evento é protegido por try/catch — uma falha pontual em qualquer coletor (um sensor instável, uma API ausente em alguma plataforma) nunca deve travar o jogo nem interromper silenciosamente o resto da sessão de gravação. O mesmo princípio se aplica ao sensor de temperatura: qualquer falha na ponte com o LibreHardwareMonitor apenas resulta em fallback, nunca em erro fatal.
- **Sem simulação dentro do mod.** Como descrito acima, "e se meu hardware fosse diferente" é explicitamente fora do escopo do mod em si; isso é trabalho de análise externa usando os dados que o mod *de fato* coleta.

## Changelog

### v2.0.0
- **Reformulada a captura de temperatura da CPU.** Adicionado sensor dedicado via LibreHardwareMonitorLib (subprocesso .NET), com fallback automático para OSHI. Resolve o caso de placas-mãe onde a leitura via WMI nunca funciona (confirmado em testes com HUANANZHI X99).
- Adicionado aviso de diagnóstico (stderr) quando o sensor não encontra nenhum valor de temperatura em leituras consecutivas, sugerindo falta de privilégio de administrador.
- Adicionado encerramento limpo do subprocesso do sensor ao final da sessão, e um shutdown hook de segurança para evitar processos órfãos em caso de fechamento abrupto do jogo.

### v1.0.0
- Versão inicial: gravação de sessão, métricas de FPS/TPS/CPU/RAM via OSHI, geração de relatório `.txt` e CSV bruto.

## Licença

MIT — use como quiser.
