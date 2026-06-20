# Performance Recorder

A Fabric mod for Minecraft **26.1.2** that records your machine's
performance during a play session (from the moment you enter a world until
the moment you leave it) and generates a detailed `.txt` report, designed
to be read by both you and an AI (like Claude), to help decide which mods
to tweak or which settings to change.

## What the mod does

- Automatically detects when you enter a world (singleplayer or
  multiplayer) and starts recording.
- Every second, it logs:
  - FPS and frame time
  - TPS (ticks per second) and average tick time, calculated locally
  - Loaded chunks and entity count in the world
  - JVM heap usage (RAM the Minecraft process itself is using)
  - CPU and RAM usage for **the entire machine** (via the OSHI library),
    not just the game process
  - CPU temperature, when the sensor is available
- When you leave the world, it automatically generates:
  - `dados_brutos.csv`: every sample, second by second
  - `relatorio.txt`: a readable summary, with statistics, a condensed
    timeline, and an "attention points" section with automatically
    detected issues (FPS drops, low TPS, heap nearing its limit, etc.)

## What the mod does **not** do

Important to make clear: this mod only records **real** data from your
machine. It has no way to simulate hardware you don't own (for example,
"what would performance look like with an RTX 4060" or "with a Ryzen 5
3600"). Simulations like that are an analysis done *afterwards*, outside
the mod — for example, by showing me the generated report and asking me to
compare it against known benchmarks for other hardware.

## Where reports are saved

Reports are saved in a `perfrecorder_reports/` folder inside the directory
Minecraft is run from (usually your instance's `.minecraft` folder, or
your launcher's instance folder, e.g. the instance folder in
MultiMC/Prism Launcher).

Each session generates a subfolder with the date, time, and world name,
for example:

```
perfrecorder_reports/
└── 2026-06-20_14-30-00_MyWorld/
    ├── relatorio.txt
    └── dados_brutos.csv
```

## Requirements

- **Minecraft**: 26.1.2
- **Fabric Loader**: 0.18.4 or higher
- **Fabric API**: 0.152.1+26.1.2 (or the version matching 26.1.2)
- **Java**: 25 or higher (both to play and to compile)

⚠️ **Java 25 note**: Minecraft version 26.1 requires Java 25 as a minimum.
This is different from the Java 21 typically used in older versions.
Check your launcher (or your instance settings) to confirm it's configured
to run with Java 25. To compile the mod you'll also need the full **JDK**
25 installed (not just the runtime bundled with the launcher).

## How to build

**Option A — Opening in IntelliJ IDEA (simplest):**

1. Install **JDK 25** and **IntelliJ IDEA 2025.3+**.
2. Open the project folder in IntelliJ via `File > Open`.
3. IntelliJ should detect the included `gradle-wrapper.properties` and
   offer to automatically download Gradle 9.4.0 and import the project.
   Accept the download.
4. Use the Gradle panel (right side) to run the `build` task, or run
   `./gradlew build` in the integrated terminal after import finishes.

**Option B — Command line:**

1. Install **JDK 25** (e.g. [Adoptium Temurin
   25](https://adoptium.net/temurin/releases/?version=25)).
2. Install Gradle 9.4+ globally once (e.g. via
   [SDKMAN](https://sdkman.io/): `sdk install gradle 9.4.0`).
3. In the project folder, run `gradle wrapper --gradle-version 9.4.0` to
   generate `gradlew`/`gradlew.bat`/`gradle-wrapper.jar` (these binary
   files aren't included in this package).
4. From there on, use `./gradlew build` (Linux/Mac) or `gradlew.bat build`
   (Windows) as usual.

In both cases, the final `.jar` file will be at
`build/libs/perfrecorder-1.0.0.jar`.

### Common first-build issues

- **Java version error**: confirm with `java -version` that you're using
  JDK 25. If you have multiple versions installed, set the `JAVA_HOME`
  environment variable to point to JDK 25 before running Gradle.
- **Dependency download errors**: Gradle needs internet access on first
  run to download Minecraft, Fabric Loader, the Fabric API, and OSHI.
  Check your connection/firewall if the build fails at this stage.
- **IntelliJ IDEA**: if opening the project in IntelliJ, version 2025.3 or
  higher is required for mixins/Java 25 to work correctly.

## How to install (once built)

1. Copy `perfrecorder-1.0.0.jar` to the `mods/` folder of your Fabric
   Minecraft instance.
2. Make sure the **Fabric API** (version `0.152.1+26.1.2` or equivalent)
   is also in the `mods/` folder — Performance Recorder depends on it.
3. Start Minecraft normally, using the Fabric profile for version 26.1.2.

## How to use the reports to request tweaks

After playing a session (entering a world and then leaving it, either by
returning to the menu or closing the game normally), open the most recent
`perfrecorder_reports/` folder and:

1. Open the `relatorio.txt` file for a quick overview.
2. If you'd like a detailed analysis with me (Claude), you can paste the
   contents of `relatorio.txt` into the conversation, or send the file,
   along with:
   - Which mods you're currently using
   - What you want to improve (FPS, memory usage, etc.)
   - If you'd like, your current and/or hypothetical hardware
     configuration you want to compare against

With that, I can suggest configuration tweaks, optimization mods (like the
Distant Horizons mod you mentioned earlier, Sodium, Lithium, etc.) or
hardware changes based on real data from your session, rather than generic
guesses.

## Project structure

```
src/main/java/com/eduualves/perfrecorder/
├── PerfRecorderClient.java     # Mod entrypoint, registers Fabric API events
├── SessionRecorder.java        # Manages the lifecycle of a recording session
├── data/
│   └── PerformanceSample.java  # Represents a single metrics snapshot
├── report/
│   └── ReportWriter.java       # Generates relatorio.txt from the samples
└── util/
    └── SystemInfoCollector.java # Wraps OSHI usage (the machine's real hardware)
```

## Technical notes

- The mod is **client-side only**: it measures the local player's
  experience, so it works in both singleplayer and any multiplayer server
  without needing anything installed server-side.
- Machine-wide CPU/RAM tracking uses the
  [OSHI](https://github.com/oshi/oshi) library, the industry standard for
  this kind of collection in Java, and works on Windows, Linux, and macOS.
- The generated report files (`relatorio.txt`, `dados_brutos.csv`) keep
  their Portuguese names and content, since that's the primary language
  for reading and follow-up analysis; this README is the English-language
  reference for the project itself.
