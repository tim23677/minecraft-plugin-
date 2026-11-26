# Paper- und Folia-Tests

## Was MockBukkit prüfen kann

Geprüft und bestanden (9 Tests): Plugin laden, aktivieren und deaktivieren, Command registriert, `/orderhub` als Spieler öffnet das Menü mit dem richtigen `InventoryHolder`, Konsole bekommt keinen Menüzugriff, Klicks im eigenen Menü werden abgebrochen, Klicks in fremden Inventaren bleiben unberührt, Auswahl der Bukkit-Scheduler-Implementierung, Ausführung eines globalen Tasks nach einem Tick.

Für diese Tests liegt der Quellcode in `src/test/java/dev/orderhub/`. Sie laufen mit `./gradlew test`.

## Was MockBukkit nicht ersetzt

MockBukkit simuliert die Bukkit-API, es ist kein Server. Es kann nicht belegen: regionalisierte Threads und Thread-Besitz auf Folia, das Verhalten von `RegionScheduler`, `EntityScheduler` und `AsyncScheduler` unter echter Last, Paper-spezifische Netzwerk- und Chunk-Logik, Zusammenspiel mit echten Vault-Wirtschaftsplugins, Verhalten bei Serverstart und -stopp mit anderen Plugins, Leistung. Die Folia-Zweige in `FoliaPluginScheduler` sind deshalb durch keinen automatischen Test abgedeckt. Sie werden erst durch den Serverlauf unten geprüft.

Praktische Einschränkung: Die Hauptklasse darf nicht `final` sein, weil MockBukkit sie per ByteBuddy unterklassifiziert (Fehler "Cannot subclass primitive, array or final types" beim ersten Versuch).

Nicht mit MockBukkit testbar und nur mit echter Serverinstanz: Folia-Threading, Welt- und Chunk-Operationen unter Last, Vault mit echtem Wirtschaftsplugin, Start mit Produktions-Plugins, Reload-Verhalten, Verhalten mit echten Clients.

## Datenbanktests

`SqliteMigrationTest` läuft ohne Docker und prüft die Migration gegen eine echte SQLite-Datei. `MariaDbMigrationIT` startet über Testcontainers ein `mariadb:11.4`-Image. In dieser Umgebung gibt es keinen Docker-Daemon; der Test wurde übersprungen, nicht bestanden. Ob die Migration auf MariaDB funktioniert, ist unbewiesen. Auf einem Rechner mit Docker: `./gradlew integrationTest`.

## Echter Serverlauf mit mcstage (ausgeführt)

Werkzeug: mcstage 0.56.0 (Apache 2.0), gebaut aus einem festen Commit mit `tools/mcstage/setup.sh`. Szenario: `tools/mcstage/mcstage.yml`. Start: `ACCEPT_MINECRAFT_EULA=true tools/mcstage/run.sh`. Die EULA-Zustimmung hat der Nutzer ausdrücklich erteilt, sie ist im Skript weiterhin eine bewusst gesetzte Variable.

Ergebnis am 2026-11-26: Paper 1.21.11 (Build 132) und Folia 1.21.11 (Build 14) starten mit dem Shadow-Jar, OrderHub wird aktiviert, `plugins` listet OrderHub, der Konsolenbefehl `orderhub` antwortet mit der Ablehnung, kein Fehler aus der Verbotsliste, Lauf 35 Sekunden, 2 von 2 Fällen bestanden. Die Serverjars wurden per SHA-256 geprüft.

Was das beweist und was nicht: OrderHub lädt auf beiden echten Servern, `folia-supported: true` wird akzeptiert, `PluginScheduler.create` wählt auf Folia den Folia-Zweig ohne Fehler. Es beweist nicht, dass `runAt`, `runOn`, `runGlobal` und `runAsync` auf Folia korrekt arbeiten, denn das Gerüst startet beim Aktivieren keine Tasks. Menü und Klicks wurden mit keinem echten Spieler getestet.

Erster Lauf: 2 von 2 Fällen fehlgeschlagen. Ursachen: Der Schritt `enabled:` kann mit mcstage 0.56.0 bei Plugins, die vor der Meldung "Done" aktiviert werden, nie greifen, weil der Boot-Schritt diese Zeilen bereits verbraucht (nachgelesen in `ServerProcess.await` und `ScenarioRunner`). Das ist eine Eigenheit von mcstage, nicht von OrderHub; das Szenario prüft stattdessen die Pluginliste. Außerdem hatte die Vorlage `level-type: minecraft:flat` ohne Generatoreinstellungen und erzeugte `No key layers`-Fehler; die Einstellung ist entfernt.

Das ältere Skript `tools/folia-test/run-folia-smoke.sh` bleibt bestehen, ist aber durch mcstage ersetzt und wurde nie ausgeführt.

## Echte Folia-Integrationstests (frühere Vorbereitung)

Vorbereitet: `tools/folia-test/run-folia-smoke.sh`. Das Skript lädt Folia 1.21.11 (aktueller Stable-Build 14 laut PaperMC-Download-API), prüft die SHA-256-Summe, kopiert das Shadow-Jar in `plugins/`, startet den Server, sendet den Command `orderhub` an die Konsole (der als Konsole abgelehnt werden muss), stoppt den Server und sucht im Log nach der Aktivierung und nach Fehlern.

Getestet wurde Download und Prüfsumme (erfolgreich) und der Abbruch ohne EULA (erwartet). Der eigentliche Lauf wurde nicht ausgeführt, weil er die Minecraft-EULA (https://aka.ms/MinecraftEULA) akzeptiert und diese Zustimmung bei dir liegt. Start mit `ACCEPT_MINECRAFT_EULA=true tools/folia-test/run-folia-smoke.sh` nach `./gradlew build`.

Der Rauchtest prüft nur Aktivierung und Fehlerfreiheit im Log. Er prüft nicht, ob das Menü auf Folia mit einem echten Spieler geöffnet werden kann. Dafür wäre ein Bot-Client oder ein Plugin-interner Selbsttest nötig, das ist noch nicht vorbereitet.

Entscheidung zur Scheduler-Bibliothek: decisions/0001-scheduler.md.

Ungeprüft am Skript: Die Suchmuster im Serverlog (`Enabling OrderHub`, `ERROR`, `Done (`) stammen aus dem üblichen Paper-Logformat und wurden nicht gegen einen echten Lauf abgeglichen. Beim ersten Lauf kann eine Anpassung nötig sein.
