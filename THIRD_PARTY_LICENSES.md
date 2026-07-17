# Third-party dependency inventory

This inventory covers the direct runtime and build/test dependencies pinned by wMatcher 1.0.0. The authoritative license and notice files embedded in each dependency remain controlling.

| Component | Version | License |
|---|---:|---|
| ASM (`org.ow2.asm`) | 9.10.1 | BSD 3-Clause |
| Vineflower (`org.vineflower`) | 1.12.0 | Apache License 2.0 |
| FlatLaf (`com.formdev`) | 3.7.2 | Apache License 2.0 |
| RSyntaxTextArea (`com.fifesoft`) | 3.6.3 | BSD 3-Clause |
| java-diff-utils (`io.github.java-diff-utils`) | 4.17 | Apache License 2.0 |
| mapping-io (`net.fabricmc`) | 0.8.0 | Apache License 2.0 |
| Jackson Databind (`com.fasterxml.jackson.core`) | 2.22.1 | Apache License 2.0 |
| SLF4J (`org.slf4j`) | 2.0.18 | MIT License |
| Logback (`ch.qos.logback`) | 1.5.38 | EPL 1.0 / LGPL 2.1 dual license |
| Shadow Gradle Plugin (`com.gradleup.shadow`) | 9.6.0 | Apache License 2.0 |
| JUnit | 6.1.2 | Eclipse Public License 2.0 |
| AssertJ Core | 3.27.7 | Apache License 2.0 |

The fat Jar also retains dependency-provided `META-INF/LICENSE*` and `META-INF/NOTICE*` resources where their packaging permits. Transitive components are resolved from the locked Gradle dependency graph and retain their own embedded notices.
