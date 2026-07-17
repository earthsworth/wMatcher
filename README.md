# wMatcher — Working Matcher

wMatcher is a Java 21 desktop application for comparing two versions of a Jar. It combines compact ASM models, conservative obfuscation-aware matching, raw bytecode inspection, and on-demand Vineflower source views.

## Features

- Compares classes, fields, methods, bytecode instructions, and Jar resources.
- Matches renamed or obfuscated entities using deterministic structural and code fingerprints.
- Keeps uncertain matches as explainable candidates; confirmed mappings are one-to-one and undoable.
- Shows synchronized structure, ASM Textifier bytecode, and Vineflower source differences.
- Supports Multi-Release Jars, Tiny v2 import/export, paired ProGuard/R8 imports, and versioned `.wmatch` projects.
- Uses a bilingual English/简体中文 Swing interface with FlatLaf light and dark themes.
- Never loads or executes classes from the compared Jars and never extracts them to disk.

## Run

Install a Java 21 or newer runtime, then run:

```text
java -jar wMatcher-1.0.0-all.jar
```

Choose an old Jar and a new Jar, optionally attach dependency Jars, select the effective Java release for Multi-Release entries, and start the comparison. Source code is decompiled only when its tab is opened.

## Build and test

Use the checked-in Gradle Wrapper:

```text
./gradlew clean check shadowJar
```

On Windows use `gradlew.bat`. The runnable artifact is written to `wmatcher-app/build/libs/`. The build requires a JDK 21 toolchain, runs all unit and headless UI smoke tests, produces reproducible archives, and copies the dependency license inventory to `build/reports/licenses/`.

## Safety limits

Each side is limited to a 1 GiB Jar, 100,000 entries, and 8 GiB of expanded data. Resources are hashed as streams; previews are capped at 5 MiB. Duplicate paths, malformed archives, invalid classes, and exceeded limits stop analysis with an actionable error.

Vineflower source cache data is stored below the current user's application cache and is capped at 2 GiB. Logs rotate at 10 MiB with a total cap of 50 MiB. wMatcher performs no telemetry and no network access at runtime.
