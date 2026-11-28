# 0003: JDBC-Treiber im Jar bündeln oder über `libraries` laden

Datum: 2026-11-26. Status: offen, wartet auf die Entscheidung des Auftraggebers. `build.gradle.kts` ist nicht geändert.

## Ausgangslage

Das Shadow-Jar (`build/libs/OrderHub-0.1.0-SNAPSHOT.jar`, 13,6 MB) enthält sqlite-jdbc 3.54.1.0 (21,9 MB entpackt, wegen der Native-Bibliotheken), mariadb-java-client 4.0.0 (1,7 MB entpackt), HikariCP (relokiert) und SLF4J (relokiert). Die Treiber sind nicht relokiert. Der Eigencode macht 0,9 MB aus.

LICENSE_AUDIT.md: sqlite-jdbc steht unter Apache 2.0. Der MariaDB-Treiber steht unter LGPL 2.1 oder später. Ob ein unverändert eingebetteter LGPL-Treiber in einem verkauften Jar die Austauschbarkeit erfüllt, ist juristisch ungeklärt und wurde nicht geprüft.

## Befund beim Prüfen

Im Shadow-Jar enthielt `META-INF/services/java.sql.Driver` nur `org.sqlite.JDBC`. Beide Treiber bringen die Datei mit, Shadow behält ohne `mergeServiceFiles()` nur eine. Ein Test mit `DriverManager.getDriver("jdbc:mariadb://...")` gegen das Jar lieferte "No suitable driver". Die MariaDB-Tests liefen bisher gegen den Test-Classpath und haben das nicht gesehen. Behoben ist es ohne Eingriff in die Build-Datei: `DataSourceFactory` setzt `driverClassName` ausdrücklich (Commit "JDBC-Treiberklassen ausdrücklich setzen", Test `driversAreNamedExplicitlyAndLoadable`). Dieser Weg funktioniert bei jeder der Optionen unten. Ein Lauf des fertigen Jars gegen eine echte MariaDB steht aus.

## Optionen

A. Beide Treiber im Jar lassen (Ist-Zustand). Läuft offline und ohne Zusatzschritt. Das Jar ist groß, und der LGPL-Treiber steckt unverändert im verkauften Artefakt.

B. Beide über `libraries:` in `plugin.yml` laden. Paper lädt sie beim Start von Maven Central in einen eigenen Classloader des Plugins. Das Jar schrumpft auf etwa 0,5 MB, kein Treiber steckt im verkauften Artefakt, und die LGPL-Frage entfällt weitgehend, weil der Server des Käufers die unveränderte Bibliothek selbst holt. Dafür braucht der erste Start Netzzugang zu Maven Central, ein Server ohne Internet startet nicht. Ob Paper die heruntergeladenen Dateien gegen Prüfsummen prüft, ist nicht verifiziert. Ob Folia `libraries:` gleich behandelt, ist nicht verifiziert.

C. SQLite im Jar lassen (Apache 2.0, Standardbetrieb funktioniert offline), nur den MariaDB-Treiber über `libraries:` laden. Löst die LGPL-Frage, das Jar bleibt bei etwa 12 MB, und nur Käufer mit MariaDB brauchen beim Start Netzzugang zu Maven Central.

## Offene Punkte, die nur ein Serverlauf klärt

Ob Paper selbst einen SQLite- oder MySQL-Treiber auf dem Server-Classpath mitbringt und ob der gebündelte SQLite-Treiber damit kollidiert (Native-Bibliothek in zwei Classloadern), wurde nicht geprüft. `tools/mcstage/run.sh` würde das auf echtem Paper und Folia zeigen.

## Empfehlung

Option C. Sie ändert den Standardbetrieb nicht und räumt die LGPL-Frage aus dem Artefakt. Die Entscheidung hängt an einer juristischen Einschätzung und an der Zumutbarkeit von Netzzugang beim ersten Start mit MariaDB. Umgesetzt wird erst nach Antwort: Eintrag in `plugin.yml`, `compileOnly` statt `runtimeOnly` für den MariaDB-Treiber in der Build-Datei, Anpassung der Tests und der Doku.
