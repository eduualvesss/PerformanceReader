# Performance Recorder

Fabric mod for **Minecraft 26.1.2** that records your machine's real-world performance during a play session — from the moment you enter a world until the moment you leave — and generates a detailed `.txt` report, meant to be read by both a human (you) and an AI (used to analyze the data and suggest mod/configuration changes).

It started as a practical tool to answer one question: *"is it worth upgrading my CPU?"* — and ended up becoming a small but complete Java/Gradle/Fabric project along the way.

## Features

### Session recording
- Automatically detects entering/leaving a world (singleplayer or multiplayer), running entirely client-side.
- Takes one sample per second for the whole session, no manual commands needed.
- Writes the raw CSV incrementally (one line at a time, with an immediate flush) — if the game crashes or is closed abruptly, the data already written is not lost.

### Metrics collected per sample
- **FPS** and frame time (ms)
- **TPS** (ticks per second) and average tick time, computed from the real ticks observed by the client
- **Loaded chunks** and **entity count** in the current world
- **JVM heap usage** (current and the maximum configured via `-Xmx`)
- **Java process CPU usage** (via `com.sun.management.OperatingSystemMXBean`)
- **Whole-system CPU usage**, aggregated and per logical core (via OSHI)
- **System RAM used and total** (via OSHI)
- **CPU core temperature and voltage**, package power, and core clock (see dedicated section below)
- **Detailed GPU metrics**: temperature, core and memory/VRAM clock, core voltage, load (%), power draw, used/total VRAM, and fan RPM
- **RAM (DRAM) clock and voltage**, when exposed by the motherboard

### Files generated per session
Each session creates a `<timestamp>_<world_name>` folder inside `perfrecorder_reports/`, containing:
- **`dados_brutos.csv`** — raw, second-by-second data for every metric above. Ideal for deeper analysis, spreadsheets, or feeding into an AI conversation.
- **`relatorio.txt`** — human-readable report: machine specs, aggregated statistics (min/avg/max/p95) per metric, a condensed timeline (samples every 10s), and a list of automatically detected issues (e.g. "FPS below 30 in N samples", "TPS below 18 — indicates stutters").

### Temperature, voltage, GPU, and RAM capture

This is the most significant piece of engineering in the project. Originally, temperature was read only via **OSHI** (which in turn uses WMI on Windows — `MSAcpi_ThermalZoneTemperature`). That approach **doesn't work on several third-party motherboards**: confirmed in real testing that a HUANANZHI X99 with a Xeon E5-2670 v3 never returns a temperature through this path, even when running the game as Administrator. On top of that, OSHI **never exposed** CPU/GPU voltage, nor most of the detailed GPU and RAM metrics.

The mod uses a two-layer strategy:

1. **Dedicated sensor via LibreHardwareMonitorLib** (`sensors/CpuTempSensor/`) — a small .NET executable, started by the mod as a subprocess, that reads the MSR registers directly via a kernel-level driver (the same mechanism used by tools like HWMonitor and MSI Afterburner/RivaTuner). It's the preferred source for CPU temperature/voltage, and the **only available source** for CPU/GPU voltage and for all the detailed GPU and RAM metrics, since OSHI never exposed that data.
2. **Automatic fallback to OSHI**, only for what OSHI can actually do (CPU temperature, used/total RAM) — if the dedicated sensor can't be started (executable missing, driver failed to open, etc.), the mod falls back to the standard OSHI reading. Metrics that are exclusive to the dedicated sensor (CPU/GPU voltage, any GPU data, RAM clock/voltage) **have no possible fallback** — if the sensor isn't available, those columns are simply left empty. The mod never crashes or interrupts the session because of this.

**Important: the dedicated sensor requires the game to run as Administrator** for the CPU metrics (temperature/voltage/clock/power). This was confirmed in real testing — the driver opens without error even without elevation, but it can't populate any CPU MSR sensor without administrator privileges. GPU metrics, on the other hand, usually work fine without elevation on most modern video drivers (NVIDIA/AMD) — but running as Administrator is still the safest recommendation for full coverage. Without elevation, the sensor's CPU metrics come back "unavailable" for that sample, and the mod silently falls back to OSHI for temperature only (which, on the same problematic motherboards, won't work either). See `sensors/CpuTempSensor/README.md` for build, protocol, and diagnostic details.

Validation: in real test sessions, the values captured by this source (e.g. an average of ~76°C in a Minecraft session) consistently matched simultaneous readings from MSI Afterburner on the same machine.

### Automatic issue detection
At the end of the session, the report automatically lists:
- Percentage of samples with FPS below 30
- Percentage of samples with TPS below 18 (indicating world stutters)
- Percentage of samples where JVM heap usage went above 90% of the configured limit (`-Xmx`)
- Percentage of samples with GPU temperature above 85°C (indicates possible throttling or insufficient cooling)

### Comparing against hypothetical hardware
The mod itself **only records the hardware you actually have** — it never simulates a different CPU, motherboard, or GPU. To answer "what would this look like on different hardware," the recommended flow is:
1. Record a real session on your current hardware.
2. Share `relatorio.txt` (and `dados_brutos.csv` for more detail) with an AI, or manually compare against published benchmarks (PassMark, Cinebench, Geekbench, etc.) for the hardware you're considering.
3. Treat the result as an **estimate**, not a measurement — differences in single-thread vs. multi-thread performance, core/thread count, and mod-specific threading behavior can all change the outcome in ways a simple percentage scaling can't capture.

## Requirements

| Component | Version |
|---|---|
| Minecraft | 26.1.2 |
| Fabric Loader | 0.18.4+ (tested with 0.19.3) |
| Fabric API | 0.151.0+26.1.2 or 0.152.1+26.1.2 |
| Java (build and run) | JDK 25 |
| Gradle | 9.4+ (the wrapper handles this automatically) |
| .NET SDK (only to build the temperature sensor) | 8.0+ |
| IntelliJ IDEA (recommended IDE) | Community or Ultimate, any recent version |

> Minecraft 26.1 specifically requires **Java 25** — make sure you have the full JDK installed, not just the runtime bundled with your launcher.

## Building from source in IntelliJ IDEA

This is the exact workflow that was tested end-to-end (including the mistakes that are easy to make the first time).

### 0. Install prerequisites first

- **JDK 25** — [Adoptium Temurin 25](https://adoptium.net/temurin/releases/?version=25) is a solid free option. Verify with:
  ```powershell
  java -version
  ```
- **(Optional) .NET SDK 8** — only needed if you want the GPU/voltage/RAM sensor actually compiled and working. Verify with:
  ```powershell
  dotnet --version
  ```

### 1. (Optional) Build the temperature/GPU sensor first

If you skip this step, the mod still compiles and runs fine — you'll just be missing GPU, CPU voltage, and detailed RAM data (those columns stay empty, with CPU temperature still available through the OSHI fallback on motherboards where it works).

```powershell
cd PerformanceReader-master\sensors\CpuTempSensor
dotnet publish -c Release
mkdir publish\sensors -Force
copy bin\Release\net8.0\win-x64\publish\CpuTempSensor.exe publish\sensors\ -Force
```

Run these commands **from inside `sensors\CpuTempSensor`** — this is a separate .NET project from the Java mod, and its own `dotnet publish` output only matters locally to this folder.

### 2. Open the project in IntelliJ

1. **File → Open** and select the `PerformanceReader-master` folder (the root, where `build.gradle` lives — not any subfolder like `sensors/CpuTempSensor`).
2. IntelliJ should detect it's a Gradle project and offer to import it. Accept ("Trust Project" if prompted).
3. Wait for the Gradle sync to finish (progress bar at the bottom).

### 3. Make sure the Gradle wrapper is present

The project ships with `gradlew.bat` and `gradle/wrapper/gradle-wrapper.jar` already included, so in most cases this step is unnecessary. If IntelliJ complains about a missing or broken wrapper during sync, regenerate it — **from the project root**, not from any subfolder:

```powershell
cd E:\PerformanceReader\PerformanceReader-master
.\gradlew.bat wrapper --gradle-version 9.4.0
```

> **Common mistake:** running `gradlew.bat` from inside `sensors\CpuTempSensor` (or any other subfolder) fails with `is not recognized as the name of a cmdlet, function, script file, or operable program`, because `gradlew.bat` only exists at the project root. Run `dir` first if you're not sure where you are — you should see `build.gradle`, `gradlew.bat`, `settings.gradle`, and the `src` folder listed.

### 4. Set the project JDK

If IntelliJ doesn't pick up JDK 25 automatically:
- **File → Project Structure → Project** → under "SDK", select JDK 25 (or "Add SDK → Download JDK" → version 25, vendor Eclipse Temurin).
- Also check **Settings → Build Tools → Gradle** and make sure "Gradle JVM" points to JDK 25.

### 5. Build the `.jar`

From the project root (same place as `build.gradle`):

```powershell
cd E:\PerformanceReader\PerformanceReader-master
.\gradlew.bat build
```

This compiles the mod, runs any tests, and packages everything. The first run can take a while, since Gradle/Loom needs to download Minecraft 26.1.2, mappings, and the Fabric API.

The final jar appears at:

```
PerformanceReader-master\build\libs\perfrecorder-2.1.0.jar
```

(A `perfrecorder-2.1.0-sources.jar` is generated alongside it — that's the source jar, you don't need it to actually play, only the first one.)

### 6. (Alternative) Run directly from IntelliJ instead of building a jar

If you just want to test the mod without producing a distributable jar, use the Fabric Loom run task instead:
- In the Gradle panel (right-hand side of IntelliJ): `perfrecorder → Tasks → fabric → runClient`. Double-click it.
- This downloads Minecraft, compiles the mod, and launches the game with the mod already loaded — useful for quick iteration, but it doesn't produce the `.jar` file by itself (use `gradlew.bat build` for that).

### Known build pitfalls (and why they happen)

- **`oshi-core` version conflict with other mods.** Several popular mods (e.g. Bobby) also bundle OSHI. If two different OSHI versions end up on the classpath at the same time, you'll see a `Configuration conflict: there is more than one oshi.properties file` warning, usually followed by a crash. Fix: align this mod's `oshi_version` in `gradle.properties` with whatever version the rest of your modpack already uses.
- **`NoSuchMethodError: IsProcessorFeaturePresent` on Windows.** This method was only added to JNA in version **5.14.0**. If you bump `oshi-core` without also bumping the `jna`/`jna-platform` versions in the `include(...)` lines of `build.gradle`, the older JNA version silently wins on the classpath and OSHI breaks when calling a method that doesn't exist in it. Fix: keep `jna`/`jna-platform` on a recent version (5.17.0+) alongside whatever `oshi-core` version you're using.
- **`getCpuLoad()` doesn't exist on `OperatingSystemMXBean`.** JVM process CPU usage is exposed via `com.sun.management.OperatingSystemMXBean#getProcessCpuLoad()`, an OpenJDK-specific extended interface — not the plain `java.lang.management.OperatingSystemMXBean`. If both get imported, Java throws an *ambiguous reference* error because the simple name collides; using the fully-qualified type `com.sun.management.OperatingSystemMXBean` on the field (with an explicit cast at construction) avoids the conflict.
- **`getProcessorCpuLoadBetweenTicks` expects `long[][]`, not `long[]`.** OSHI distinguishes *system-aggregated* CPU ticks (`long[]`, from `getSystemCpuLoadTicks()`) from *per-core* ticks (`long[][]`, from `getProcessorCpuLoadTicks()`). They're not interchangeable — keep two separate "previous ticks" fields if you need both.
- **CPU temperature/voltage sensor returns no value at all.** Before digging into the code, confirm administrator privileges first: confirmed in real testing that, without elevation, the MSR driver opens without error but doesn't populate any CPU sensor (the `cpu_temp_c`/`cpu_core_voltage_v` keys simply don't appear in the emitted JSON). Run the `.exe` standalone from a terminal opened as Administrator to isolate the cause (see `sensors/CpuTempSensor/README.md`). GPU metrics usually work fine without elevation.
- **`gradlew.bat` "is not recognized"** when run from PowerShell. This almost always means you're in the wrong directory — `gradlew.bat` only lives at the project root, not inside `sensors/CpuTempSensor` or any other subfolder. `cd` back to the root (where `build.gradle` is) and try again.

## Installation (to play, not to develop)

1. Install the [Fabric Loader](https://fabricmc.net/use/installer/) for Minecraft 26.1.2.
2. Drop `perfrecorder-2.1.0.jar` and the matching **Fabric API** jar into your instance's `mods` folder.
3. Launch the game using the Fabric 26.1.2 profile.
4. **For CPU temperature/voltage capture to work**, launch the Minecraft launcher as Administrator (right-click → "Run as administrator"). Without this, the mod still works normally, but the CPU temperature/voltage/clock/power columns stay empty (GPU metrics usually work fine without elevation).

Reports are written to `<game_directory>/perfrecorder_reports/`. The game directory depends on your launcher — for the vanilla launcher it's `.minecraft`, but alternative launchers (TLauncher, MultiMC, Prism, CurseForge, etc.) usually keep their own folder structure inside `%appdata%`.

## Reading a report

Each session generates a `<timestamp>_<world_name>` folder containing:

- **`relatorio.txt`** — start here. Machine specs, aggregated statistics (min/avg/max/p95) for every tracked metric, a timeline every 10 seconds, and a short list of automatically detected issues.
- **`dados_brutos.csv`** — the full second-by-second data, useful for deeper analysis, spreadsheets, or feeding back into an AI conversation.

### Known quirks in the data

- **The first ~3-5 seconds of every session show `FPS=0`.** This is world load time (chunk generation, render setup), not a real performance drop — worth mentally discounting this when reading the summary, since it can drag the minimum FPS down to 0 without reflecting an actual issue.
- **`chunks_carregados` currently reports a constant value that never changes during the whole session.** Known bug: the value is defensively extracted from the debug string of `Level#gatherChunkSourceStats()` via a regex that captures the first integer found, which doesn't necessarily correspond to the real loaded chunk count. Treat this column as unreliable until it's fixed.
- **There can be a duplicate sample at the start of a session** (same `segundos` value, two consecutive rows). Likely a small initialization race between the client's first tick and the first sampling call. It doesn't affect the integrity of the rest of the data, but worth keeping in mind when averaging over the first few seconds.
- **The CPU columns (`cpu_temp_celsius`, `cpu_core_voltage_v`, `cpu_package_power_w`, `cpu_clock_mhz`) can be empty** if: (a) the dedicated sensor wasn't compiled/embedded into the jar, (b) the game wasn't run as Administrator, or (c) none of the available sources could read the hardware. This isn't a bug — it's the safe fallback behavior described above. Keep in mind `cpu_temp_celsius` can still come from the OSHI fallback even without administrator (on motherboards where WMI works), while the other CPU columns have no fallback and depend exclusively on the dedicated sensor.
- **The GPU columns (`gpu_*`) and the detailed RAM columns (`ram_clock_mhz`, `ram_voltage_v`) are empty whenever the dedicated sensor isn't available**, since OSHI never exposed those metrics — there's no possible fallback for them. Even with the sensor active, `ram_clock_mhz`/`ram_voltage_v` can still be empty on motherboards that don't expose those specific sensors to LibreHardwareMonitor.

## Project structure

```
perfrecorder/
├── build.gradle
├── gradle.properties
├── sensors/
│   └── CpuTempSensor/                    # Dedicated .NET sensor (LibreHardwareMonitorLib)
│       ├── CpuTempSensor.csproj
│       ├── Program.cs
│       └── README.md                      # Sensor build and diagnostic instructions
├── src/main/java/com/eduualves/perfrecorder/
│   ├── PerfRecorderClient.java            # Entry point; registers Fabric lifecycle events
│   ├── SessionRecorder.java                # Session lifecycle, sampling, raw CSV writing
│   ├── data/PerformanceSample.java         # Snapshot of a single point in time (CPU/GPU/RAM)
│   ├── report/ReportWriter.java            # Generates relatorio.txt from the collected samples
│   └── util/
│       ├── SystemInfoCollector.java        # OSHI wrapper + orchestrates CPU/GPU/RAM reading
│       ├── LibreHardwareMonitorBridge.java # Manages the .NET sensor subprocess (CPU/GPU/RAM)
│       └── SensorResourceExtractor.java    # Extracts the embedded .exe from the jar on first run
└── src/main/resources/
    └── fabric.mod.json
```

## Design notes

- **Client-side only.** This mod measures the local player's experience (FPS, your machine's CPU/RAM), so it doesn't need a server-side component and works immediately in singleplayer.
- **Defensive by design.** Every sampling pass and every event handler is wrapped in try/catch — a one-off failure in any collector (an unstable sensor, an API missing on some platform) should never crash the game nor silently interrupt the rest of the recording session. The same principle applies to the hardware sensor: any failure in the LibreHardwareMonitor bridge just results in a fallback, never a fatal error.
- **No simulation inside the mod.** As described above, "what if my hardware were different" is explicitly out of scope for the mod itself; that's external analysis work using the data the mod *actually* collects.

## Changelog

### v2.1.0
- **Added detailed GPU capture**: temperature, core and memory/VRAM clock, core voltage, load (%), power draw, used/total VRAM, and fan RPM. Supports NVIDIA, AMD, and Intel via LibreHardwareMonitorLib.
- **Added CPU core voltage capture**, previously missing — along with package power and average core clock.
- **Added RAM (DRAM) clock and voltage capture**, when exposed by the motherboard.
- The dedicated sensor's (`CpuTempSensor.exe`) communication protocol was migrated from `TEMP:<value>` to `DATA:<json>`, a flat object that accommodates all the new metrics without nesting. Keys for unavailable sensors simply don't appear in the object.
- `dados_brutos.csv` gained 15 new columns (CPU: voltage/power/clock; GPU: 9 metrics; RAM: clock/voltage). Columns for metrics unavailable in a given session are left empty, without affecting the reading of the others.
- `relatorio.txt` gained dedicated statistics blocks for GPU and detailed RAM, plus a new automatic issue-detection source (GPU temperature above 85°C).
- All new metrics follow the same design principle already used for CPU temperature: none of them is mandatory, and the absence of any one of them never interrupts the session nor affects the others.

### v2.0.0
- **Reworked CPU temperature capture.** Added a dedicated sensor via LibreHardwareMonitorLib (.NET subprocess), with automatic fallback to OSHI. Solves the case of motherboards where WMI-based reading never works (confirmed in testing with a HUANANZHI X99).
- Added a diagnostic warning (stderr) when the sensor finds no temperature value across consecutive readings, suggesting a missing administrator privilege.
- Added clean shutdown of the sensor subprocess at the end of the session, plus a safety shutdown hook to avoid orphaned processes in case of an abrupt game close.

### v1.0.0
- Initial release: session recording, FPS/TPS/CPU/RAM metrics via OSHI, `.txt` report and raw CSV generation.

## License

MIT — use it however you like.
