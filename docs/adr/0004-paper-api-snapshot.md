# ADR 0004: Paper-API als Snapshot

Status: angenommen, 2026-11-27

## Kontext

`gradle/libs.versions.toml` bindet `io.papermc.paper:paper-api` in der Version `1.21.11-R0.1-SNAPSHOT`. Die Master-Vorgabe verlangt eine feste Release-Version, sobald verfügbar.

## Entscheidung

Der Snapshot bleibt. PaperMC veröffentlicht die API zu einer Minecraft-Version ausschließlich als `-SNAPSHOT` im Repository `https://repo.papermc.io/repository/maven-public/`. Ich habe keine Release-Koordinate gefunden, auf die man umstellen könnte. Dieser Satz beschreibt meinen Stand beim Schreiben, er ist keine Zusage von PaperMC.

## Folgen und Gegenmaßnahmen

- Zwei Builds zu verschiedenen Zeiten können gegen eine andere Snapshot-Datei auflösen. Das Plugin-Jar ist trotzdem bitgleich reproduzierbar, weil die Paper-API `compileOnly` ist und nicht im Jar liegt. Verifiziert: zwei Läufe von `./gradlew clean shadowJar` ergaben dieselbe SHA-256.
- Die aufgelöste Snapshot-Datei steht im SBOM (`build/reports/cyclonedx/bom.json`) und im RELEASE_REPORT je Release.
- Eine Änderung der API zwischen Snapshots fällt beim Kompilieren mit `-Werror` und in den MockBukkit-Tests auf. Vor jedem Release läuft der Build frisch.
- Sobald PaperMC eine Release-Version der API anbietet, wird `paper` im Versionskatalog darauf umgestellt.
