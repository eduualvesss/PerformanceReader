# Performance Recorder

A Fabric mod for **Minecraft 26.1.2** that records your machine's real performance during a play session — from the moment you enter a world to the moment you leave it — and generates a detailed `.txt` report designed to be read by a human (you) and an AI (used to analyze the data and suggest mod/config changes).

This started as a practical tool to answer one question: *"Is it worth upgrading my CPU?"* — and ended up doubling as a small but complete real-world Java/Gradle/Fabric project.

## What it does

- Detects entering/leaving a world automatically (singleplayer or multiplayer), client-side only.
- Samples once per second: FPS, frame time, TPS, tick time, loaded chunks, entity count, JVM heap usage, and full-system CPU/RAM/temperature (via [OSHI](https://github.com/oshi/oshi)).
- Writes two files per session into a timestamped folder:
  - `dados_brutos.csv` — raw, second-by-second data (written incrementally, so a crash doesn't lose the session).
  - `relatorio.txt` — a human-readable report: machine specs, summary statistics, a condensed timeline, and automatically flagged issues (FPS/TPS drops).
- Is intentionally **read-only with respect to your hardware** — it never simulates hardware you don't own. "What if I had CPU X" comparisons are done afterward, externally, by comparing the recorded data against known benchmarks (see [Comparing Hypothetical Hardware](#comparing-hypothetical-hardware) below).

## Requirements

| Component | Version |
|---|---|
| Minecraft | 26.1.2 |
| Fabric Loader | 0.18.4+ (tested with 0.19.3) |
| Fabric API | 0.151.0+26.1.2 or 0.152.1+26.1.2 |
| Java (build & run) | JDK 25 |
| Gradle | 9.4+ (wrapper generates this automatically) |

> Minecraft 26.1 requires **Java 25** specifically — make sure you have the full JDK installed, not just the launcher's bundled runtime.

## Building from source

1. Install **JDK 25** ([Adoptium Temurin](https://adoptium.net/temurin/releases/?version=25) is a free, solid option).
2. Open the project folder in **IntelliJ IDEA** (Community Edition is fine) and let it import as a Gradle project.
3. If `gradlew`/`gradlew.bat` isn't present yet, generate it once with a system-installed Gradle:
   ```
   gradle wrapper --gradle-version 9.4.0
   ```
4. Build:
   ```
   ./gradlew build        # Linux/macOS
   .\gradlew.bat build    # Windows
   ```
5. The finished mod jar appears at `build/libs/perfrecorder-1.0.0.jar`.

### Known build pitfalls (and why they happen)

These came up while building this exact project — leaving them here so they don't surprise you twice.

- **`oshi-core` version conflicts with other mods.** Several popular mods (e.g. Bobby) also bundle OSHI. If two different OSHI versions end up on the classpath at once, you'll see a `Configuration conflict: there is more than one oshi.properties file` warning, often followed by a crash. Fix: align this mod's `oshi_version` in `gradle.properties` with whatever version the rest of your modpack already uses.
- **`NoSuchMethodError: IsProcessorFeaturePresent` on Windows.** This method was only added to JNA in version **5.14.0**. If you bump `oshi-core` to a newer release without also bumping the bundled `jna`/`jna-platform` versions in the `include(...)` lines of `build.gradle`, the older JNA you're shipping silently wins on the classpath and OSHI crashes trying to call a method that doesn't exist in it. Fix: keep `jna`/`jna-platform` on a recent release (5.17.0+) alongside whatever `oshi-core` version you're using.
- **`getCpuLoad()` doesn't exist on `OperatingSystemMXBean`.** The JVM's process CPU usage is exposed via `com.sun.management.OperatingSystemMXBean#getProcessCpuLoad()`, an OpenJDK-specific extended interface — not the plain `java.lang.management.OperatingSystemMXBean`. If both happen to be imported, Java throws an *ambiguous reference* error because the simple name collides; using the fully-qualified `com.sun.management.OperatingSystemMXBean` type for the field (with an explicit cast at construction) avoids the clash entirely.
- **`getProcessorCpuLoadBetweenTicks` expects `long[][]`, not `long[]`.** OSHI distinguishes *aggregate system* CPU ticks (`long[]`, from `getSystemCpuLoadTicks()`) from *per-core* ticks (`long[][]`, from `getProcessorCpuLoadTicks()`). They are not interchangeable — keep two separate "previous ticks" fields if you need both system-wide and per-core load.

## Installation (playing, not developing)

1. Install [Fabric Loader](https://fabricmc.net/use/installer/) for Minecraft 26.1.2.
2. Drop both `perfrecorder-1.0.0.jar` and the matching **Fabric API** jar into your instance's `mods` folder.
3. Launch the game using the Fabric 26.1.2 profile.

Reports are written to `<game_directory>/perfrecorder_reports/`. Note that the game directory depends on your launcher — for the vanilla launcher it's `.minecraft`, but alternative launchers (TLauncher, MultiMC, Prism, etc.) typically use their own folder structure under `%appdata%`.

## Reading a report

Each session produces a folder named `<timestamp>_<world_name>` containing:

- **`relatorio.txt`** — start here. Machine specs, aggregate stats (min/avg/max/p95) for every tracked metric, a 10-second-interval timeline, and a short list of automatically detected issues (e.g. "FPS below 30 in N samples").
- **`dados_brutos.csv`** — the full second-by-second data, useful for deeper analysis, spreadsheets, or feeding back into an AI conversation.

### Known data quirks (as of v1.0.0)

- **The first ~3–5 seconds of every session show `FPS=0`.** This is world-loading time (chunk generation, render setup), not a real performance drop — worth mentally discounting when reading the summary, since it can pull the FPS minimum down to 0 without reflecting an actual issue.
- **`chunks_carregados` (loaded chunks) currently reports a constant, unchanging value across an entire session.** This is a known bug: the value is parsed defensively out of `Level#gatherChunkSourceStats()`'s debug string via a regex that grabs the first integer found, which doesn't reliably land on the actual loaded-chunk count. Treat this column as unreliable until fixed.

## Comparing hypothetical hardware

The mod itself only ever records the hardware you actually have. To answer "what would this look like on a different CPU/GPU," the workflow is:

1. Record a real session on your current hardware.
2. Share `relatorio.txt` (and `dados_brutos.csv` for finer detail) with an AI assistant, or compare it manually against published benchmarks (PassMark, Cinebench, Geekbench, etc.) for the hardware you're considering.
3. Treat the result as an **estimate**, not a measurement — single-thread vs. multi-thread differences, core/thread count changes, and per-mod threading behavior (chunk generation, networking, world-tick logic) can all shift the outcome in ways a simple percentage scaling won't capture.

This project's own motivating use case was exactly this: deciding whether to move from a Xeon E5-2670 v3 to an AM4 Ryzen 5 2600, using a real recorded session as the baseline instead of guessing.

## Project structure

```
perfrecorder/
├── build.gradle
├── gradle.properties
├── src/main/java/com/eduualves/perfrecorder/
│   ├── PerfRecorderClient.java      # Entrypoint; registers Fabric lifecycle events
│   ├── SessionRecorder.java          # Session lifecycle, sampling, raw CSV writing
│   ├── data/PerformanceSample.java   # Single point-in-time snapshot
│   ├── report/ReportWriter.java      # Generates relatorio.txt from collected samples
│   └── util/SystemInfoCollector.java # OSHI wrapper for whole-machine hardware stats
└── src/main/resources/
    └── fabric.mod.json
```

## Design notes

- **Client-side only.** This mod measures the local player's experience (FPS, your machine's CPU/RAM), so it doesn't need a server-side component and works out of the box in singleplayer.
- **Defensive by design.** Every sampling pass and every event handler is wrapped in try/catch — a single failed reading (a flaky sensor, a missing API on some platform) should never crash the game or silently kill the rest of the recording session.
- **No simulation inside the mod.** As covered above, "what if my hardware were different" is explicitly out of scope for the mod itself; it's a job for external analysis using the data the mod *does* collect.

## License

MIT — do whatever you'd like with it.
