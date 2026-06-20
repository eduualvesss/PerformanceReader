# Performance Recorder

Mod Fabric para Minecraft **26.1.2** que registra o desempenho da sua máquina
durante uma sessão de jogo (do momento em que você entra no mundo até o
momento em que sai dele) e gera um relatório `.txt` detalhado, pensado para
ser lido tanto por você quanto por uma IA (como o Claude), para ajudar a
decidir quais mods ajustar ou quais configurações mudar.

## O que o mod faz

- Detecta automaticamente quando você entra em um mundo (singleplayer ou
  multiplayer) e começa a gravar.
- A cada segundo, registra:
  - FPS e tempo de quadro (frame time)
  - TPS (ticks por segundo) e tempo médio de tick, calculado localmente
  - Chunks carregados e número de entidades no mundo
  - Uso de heap da JVM (RAM que o próprio Minecraft está usando)
  - Uso de CPU e RAM de **toda a máquina** (via a biblioteca OSHI), não
    apenas do processo do jogo
  - Temperatura da CPU, quando o sensor está disponível
- Quando você sai do mundo, gera automaticamente:
  - `dados_brutos.csv`: todas as amostras, segundo a segundo
  - `relatorio.txt`: um resumo legível, com estatísticas, uma linha do
    tempo condensada e uma seção de "pontos de atenção" detectados
    automaticamente (quedas de FPS, TPS baixo, heap perto do limite, etc.)

## O que o mod **não** faz

Importante deixar claro: este mod só registra dados **reais** da sua
máquina. Ele não tem como simular hardware que você não possui (por
exemplo, "como seria o desempenho com uma RTX 4060" ou "com um Ryzen 5
3600"). Simulações desse tipo são uma análise feita *depois*, por fora do
mod — por exemplo, me mostrando o relatório gerado e pedindo para eu
comparar com benchmarks conhecidos de outras peças de hardware.

## Onde os relatórios são salvos

Os relatórios são salvos em uma pasta `perfrecorder_reports/` dentro do
diretório de onde o Minecraft é executado (normalmente a pasta `.minecraft`
da sua instância, ou a pasta da instância do seu launcher, ex: pasta da
instância no MultiMC/Prism Launcher).

Cada sessão gera uma subpasta com data, hora e nome do mundo, por exemplo:

```
perfrecorder_reports/
└── 2026-06-20_14-30-00_MeuMundo/
    ├── relatorio.txt
    └── dados_brutos.csv
```

## Requisitos

- **Minecraft**: 26.1.2
- **Fabric Loader**: 0.18.4 ou superior
- **Fabric API**: 0.152.1+26.1.2 (ou a versão correspondente a 26.1.2)
- **Java**: 25 ou superior (tanto para jogar quanto para compilar)

⚠️ **Atenção ao Java 25**: a versão 26.1 do Minecraft exige Java 25 como
mínimo. Isso é diferente do Java 21 normalmente usado em versões mais
antigas. Verifique no seu launcher (ou nas configurações da instância) se
ele está configurado para rodar com Java 25. Para compilar o mod você
também precisa do **JDK** 25 completo instalado (não apenas o runtime que
vem empacotado com o launcher).

## Como compilar

**Opção A — Abrindo no IntelliJ IDEA (mais simples):**

1. Instale o **JDK 25** e o **IntelliJ IDEA 2025.3+**.
2. Abra a pasta do projeto no IntelliJ via `File > Open`.
3. O IntelliJ deve detectar o `gradle-wrapper.properties` já incluído e
   oferecer para baixar o Gradle 9.4.0 automaticamente e importar o
   projeto. Aceite o download.
4. Use o painel Gradle (lado direito) para rodar a tarefa `build`, ou rode
   `./gradlew build` no terminal integrado depois da importação.

**Opção B — Linha de comando:**

1. Instale o **JDK 25** (ex: [Adoptium Temurin
   25](https://adoptium.net/temurin/releases/?version=25)).
2. Instale o Gradle 9.4+ globalmente uma única vez (ex: via
   [SDKMAN](https://sdkman.io/): `sdk install gradle 9.4.0`).
3. Na pasta do projeto, rode `gradle wrapper --gradle-version 9.4.0` para
   gerar o `gradlew`/`gradlew.bat`/`gradle-wrapper.jar` (esses arquivos
   binários não vêm inclusos neste pacote).
4. A partir daí, use `./gradlew build` (Linux/Mac) ou `gradlew.bat build`
   (Windows) normalmente.

Em ambos os casos, o arquivo `.jar` final estará em
`build/libs/perfrecorder-1.0.0.jar`.

### Possíveis problemas na primeira compilação

- **Erro de versão de Java**: confirme com `java -version` que está usando
  o JDK 25. Se tiver várias versões instaladas, configure a variável
  `JAVA_HOME` para apontar para o JDK 25 antes de rodar o Gradle.
- **Erro ao baixar dependências**: o Gradle precisa de acesso à internet na
  primeira execução para baixar o Minecraft, o Fabric Loader, a Fabric API
  e a OSHI. Verifique sua conexão/firewall caso o build falhe nessa etapa.
- **IntelliJ IDEA**: se for abrir o projeto no IntelliJ, é necessária a
  versão 2025.3 ou superior para que os mixins/Java 25 funcionem
  corretamente.

## Como instalar (depois de compilado)

1. Copie `perfrecorder-1.0.0.jar` para a pasta `mods/` da sua instância do
   Minecraft com Fabric.
2. Certifique-se de que a **Fabric API** (versão `0.152.1+26.1.2` ou
   equivalente) também está na pasta `mods/` — o Performance Recorder
   depende dela.
3. Inicie o Minecraft normalmente, usando o perfil Fabric para a versão
   26.1.2.

## Como usar os relatórios para pedir ajustes

Depois de jogar uma sessão (entrar em um mundo e depois saí-lo, seja
voltando ao menu ou fechando o jogo normalmente), abra a pasta
`perfrecorder_reports/` mais recente e:

1. Abra o arquivo `relatorio.txt` para uma visão geral rápida.
2. Se quiser uma análise detalhada comigo (Claude), pode colar o conteúdo
   do `relatorio.txt` na conversa, ou enviar o arquivo, junto com:
   - Quais mods você está usando atualmente
   - O que você quer melhorar (FPS, uso de memória, etc.)
   - Se quiser, sua configuração de hardware atual e/ou hipotética que
     queira comparar

Com isso, posso sugerir ajustes de configuração, mods de otimização
(como o Distant Horizons que você mencionou antes, Sodium, Lithium, etc.)
ou mudanças de hardware com base em dados reais da sua sessão, em vez de
suposições genéricas.

## Estrutura do projeto

```
src/main/java/com/eduualves/perfrecorder/
├── PerfRecorderClient.java     # Entrypoint do mod, registra eventos da Fabric API
├── SessionRecorder.java        # Gerencia o ciclo de vida de uma sessão de gravação
├── data/
│   └── PerformanceSample.java  # Representa uma amostra/snapshot de métricas
├── report/
│   └── ReportWriter.java       # Gera o relatorio.txt a partir das amostras
└── util/
    └── SystemInfoCollector.java # Encapsula o uso da OSHI (hardware real da máquina)
```

## Notas técnicas

- O mod é **client-side only**: ele mede a experiência local do jogador, e
  por isso funciona tanto em singleplayer quanto em qualquer servidor
  multiplayer, sem precisar de nada instalado no lado do servidor.
- A contagem de CPU/RAM da máquina usa a biblioteca
  [OSHI](https://github.com/oshi/oshi), padrão da indústria para esse tipo
  de coleta em Java, e funciona em Windows, Linux e macOS.
- Todos os textos do relatório estão em português, já que o objetivo é
  facilitar tanto a sua leitura quanto a análise posterior.
