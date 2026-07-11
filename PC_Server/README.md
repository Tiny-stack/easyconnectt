# PC Server

A **modular** (JPMS) Java 21 application built with Gradle, tuned so `jlink` and
`jpackage` produce a minimal self-contained runtime.

## Layout

```
PC_Server/
├── build.gradle              # app plugin + jlinkImage / jpackageImage tasks
├── settings.gradle
├── gradle.properties         # app name/version/module/mainClass knobs
└── src/main/java/
    ├── module-info.java      # the JPMS module descriptor
    └── com/easyconnect/pcserver/App.java
```

The project is a true Java module (`module-info.java`), so Gradle puts it on the
**module path**. That's what lets `jlink` compute the minimal set of JDK modules
your app actually needs.

## Common commands

| Command | What it does |
|---|---|
| `./gradlew run` | Run the app from source |
| `./gradlew build` | Compile + jar + tests |
| `./gradlew jlinkImage` | Minimal custom runtime → `build/image/` |
| `./gradlew jpackageImage` | Self-contained native app-image → `build/jpackage/PCServer/` |

Run the produced artifacts directly:

```bash
build/image/bin/pcserver            # jlink launcher
build/jpackage/PCServer/bin/PCServer # jpackage app-image
```

## Why it's small

Current image is ~37 MB (vs ~300 MB for a full JDK) because:

- The app requires only `java.base`, so jlink bundles nothing else.
- Both tasks pass: `--strip-debug --no-header-files --no-man-pages --compress=zip-9`.

**To keep it small:** add as few `requires` as possible in `module-info.java` —
every module you require gets pulled into the image.

## Adding dependencies

Prefer **modular** JARs (ones with their own `module-info`). Add them in
`build.gradle` under `dependencies { implementation '...' }` and add a matching
`requires <module.name>;` in `module-info.java`. Non-modular ("automatic module")
JARs work for `run`/`build` but cannot be `jlink`-ed directly — if you need those,
switch the jlink/jpackage tasks to the `org.beryx.jlink` plugin, which repackages
them automatically.

## Native installers (optional)

`jpackageImage` builds a portable `app-image` (no OS tooling required). For real
installers, change `--type` in the `jpackageImage` task to:

- Linux: `deb` (needs `dpkg`/`fakeroot`) or `rpm` (needs `rpmbuild`)
- Windows: `msi` or `exe`  •  macOS: `dmg` or `pkg`
