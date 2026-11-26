# Lizenzprüfung

Quelle der Angaben: LICENSE-Datei im geklonten Repository oder POM-Eintrag auf Maven Central. Wo beides fehlt, steht "nicht verifiziert". Das ist keine Rechtsberatung.

## Skills und Repositories

| Quelle | Lizenz | Verwendung in OrderHub |
| --- | --- | --- |
| decebals/claude-code-java | MIT | sechs Skills kopiert, Lizenztext in tools/licenses |
| Zavelinski/testsmith | MIT | kopiert, Lizenztext in tools/licenses |
| Zavelinski/secret-scan | MIT | kopiert, Lizenztext in tools/licenses |
| ducpm2303/claude-java-plugins | keine LICENSE-Datei | nicht kopiert, nicht installiert |
| CJCrafter/FoliaScheduler | keine LICENSE-Datei im Repository gefunden | nicht verwendet |

Die MIT-Lizenz verlangt, den Copyright-Hinweis und den Lizenztext bei Kopien mitzuliefern. Das ist durch tools/licenses erfüllt. Die Skills sind Werkzeuge der Entwicklung und gehören nicht in das ausgelieferte Plugin-Jar.

## Werkzeuge der Entwicklung (nicht im ausgelieferten Jar)

| Werkzeug | Lizenz | Prüfquelle |
| --- | --- | --- |
| MockBukkit | MIT | LICENSE im Repository, POM |
| SpotBugs | LGPL 2.1 | LICENSE im Repository |
| Dependency-Check | Apache 2.0 | LICENSE.txt im Repository |
| Spotless | Apache 2.0 | LICENSE.txt im Repository |
| Testcontainers | MIT | POM |
| PIT | Apache 2.0 | POM |
| Renovate | AGPL 3.0 | LICENSE im Repository, wird nicht eingebunden |
| JUnit Jupiter | EPL 2.0 | POM |
| AssertJ | Apache 2.0 | POM |
| Gradle, Shadow-Plugin | nicht verifiziert | |
| pitest-junit5-plugin | nicht verifiziert (POM ohne Lizenzfeld) | |

## Bibliotheken im ausgelieferten Jar

| Bibliothek | Lizenz | Anmerkung |
| --- | --- | --- |
| HikariCP 7.1.0 | Apache 2.0 | POM, relokiert |
| sqlite-jdbc 3.54.1.0 | nicht verifiziert (POM ohne Lizenzfeld) | vor Auslieferung prüfen |
| slf4j-api 2.0.17 | nicht verifiziert (POM ohne Lizenzfeld) | vor Auslieferung prüfen |
| MariaDB Connector/J 4.0.0 | LGPL 2.1 oder später | POM, siehe Hinweis |

Hinweis zu LGPL und Shadow-Jar: Der MariaDB-Treiber steckt derzeit unverändert im Fat-Jar. Die LGPL verlangt, dass Nutzer die Bibliothek austauschen können. Ein Treiber in einem verkauften Jar ist dafür ein Risiko, das juristisch geklärt werden sollte. Eine mögliche Alternative ist das Laden zur Laufzeit über den Eintrag `libraries:` in plugin.yml, bei dem Paper die Bibliothek von Maven Central lädt. Ob Paper selbst einen Treiber mitbringt, wurde nicht geprüft.

## Nur zur Compile-Zeit

Paper API (Lizenz nicht verifiziert, nicht im Jar) und Vault API (LGPL 3.0 laut license.txt im Repository, nicht im Jar). SpotBugs-Annotations (LGPL 2.1 laut SpotBugs-Repository, nur Compile-Zeit, nicht im Jar).
