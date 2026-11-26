# Gradle-Konfiguration

Dateien: `settings.gradle.kts` (Repositories mit Inhaltsfiltern, `FAIL_ON_PROJECT_REPOS`), `gradle/libs.versions.toml` (alle Versionen an einer Stelle), `build.gradle.kts`, `gradle.properties` (zwei Worker, kein paralleler Lauf, Build-Cache), `renovate.json`.

Repositories: Maven Central für alles. Das Paper-Repository nur für `io.papermc.paper`, `com.mojang`, `net.md-5`. JitPack nur für `com.github.MilkBowl`. Die Filter verhindern, dass fremde Repositories Artefakte anderer Gruppen liefern.

Abhängigkeiten: `compileOnly` für Paper API, Vault API und SpotBugs-Annotations. `implementation` für HikariCP und SLF4J. `runtimeOnly` für die JDBC-Treiber. Im Test zusätzlich MockBukkit, Jupiter über die JUnit-BOM, AssertJ und Testcontainers über deren BOM.

Aufgaben

- `test`: schließt Tests mit Tag `integration` aus.
- `integrationTest`: nur Tests mit Tag `integration`.
- `shadowJar`: relokiert HikariCP und SLF4J nach `dev.orderhub.libs`, hängt an `build`. Das normale Jar erhält den Klassifikator `plain`.
- `spotlessCheck` und `spotbugsMain`/`spotbugsTest` hängen über `check` an `build`.
- `pitest`: Mutationstest, nicht Teil von `build`, weil er langsam ist.
- `dependencyCheckAnalyze`: nicht Teil von `build`. Bricht bei CVSS ab 7.0 ab. Der Schlüssel kommt aus `NVD_API_KEY`.

SpotBugs: Aufwand `MAX`, Schwelle `MEDIUM`. Ein Befund (`EI_EXPOSE_REP` in `OrderHubMenu.getInventory`) ist per `@SuppressFBWarnings` mit Begründung an genau dieser Methode unterdrückt, weil `InventoryHolder` die Rückgabe des Inventars vorschreibt.

Bekannte Einschränkungen: Das Fat-Jar ist rund 13 MB groß, vermutlich wegen der nativen Bibliotheken in sqlite-jdbc (nicht aufgeschlüsselt). Maven Central antwortete beim ersten Lauf mit HTTP 429; `gradle.properties` begrenzt deshalb die Parallelität, was Wiederholungen meist erspart. Gradle meldet Deprecation-Hinweise, die von Plugins stammen (nicht untersucht).
