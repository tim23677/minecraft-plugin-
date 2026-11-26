# Java-Toolchain

Vorgefunden: Linux (Kernel 6.18), OpenJDK 21.0.13.1, Gradle 8.14.3 (systemweit), Node 22, Docker-CLI 29.9.3 ohne laufenden Daemon, Claude Code 2.1.332. Das Projektverzeichnis enthielt nur eine README mit einer Zeile und einen Git-Commit. Es gab keine vorhandenen Konfigurationen, Claude-Plugins oder Testframeworks, die geschützt werden mussten. Die globale Claude-Konfiguration (`~/.claude`) enthält Plugins der Umgebung und wurde nicht verändert.

## Abweichungen von der Vorgabe

Gradle 9.9.2 statt der systemweiten 8.14.3. Das Shadow-Plugin 9.6.1 bricht unter Gradle 8.14.3 beim Konfigurieren ab (fehlende Methode `AdhocComponentWithVariants.addVariantsFromConfiguration`). Der Wrapper ist auf 9.9.2 gesetzt, damit jeder Entwickler dieselbe Version nutzt.

JUnit 6.0.3 statt JUnit 5. MockBukkit `mockbukkit-v1.21` 4.116.3 bringt JUnit Jupiter 6.0.3 als Laufzeitabhängigkeit mit. Zwei Hauptversionen im selben Classpath wären ein Konfliktrisiko. Die Jupiter-API für normale Tests ist unverändert, 6.x benötigt Java 17 oder neuer. Wer bei JUnit 5 bleiben will, muss eine ältere MockBukkit-Version suchen und prüfen.

Paper API 1.21.11 statt der neuesten Linie. Paper 26.3 verlangt laut Gradle-Modulmetadaten Java 25 (`org.gradle.jvm.version: 25`). Paper API 1.21.11 verlangt Java 21. Bei Java 21 als Ziel ist 1.21.11 die passende Linie. Für 26.x wäre `mockbukkit-v26.3` und Java 25 nötig. Folia ist für 1.21.11 und 26.x verfügbar.

Die Paper-API-Version ist ein SNAPSHOT (`1.21.11-R0.1-SNAPSHOT`), so veröffentlicht Paper diese Linie. Builds sind dadurch nicht vollständig reproduzierbar. Für Releases empfiehlt sich, den aufgelösten Snapshot-Stand zu notieren.

## Toolchain-Einstellungen

`java.toolchain.languageVersion = 21`, `options.release = 21`, Compiler-Argumente `-Xlint:all -Werror`. Der Build schlägt bei jeder Compiler-Warnung fehl.
