# 0002: Befehle über Paper-Brigadier mit durchgereichten Unterbefehlen

Datum: 2026-11-26. Status: entschieden.

## Ausgangslage

`/order` (Aliase `orderhub`, `orders`) und `/orderadmin` waren in `plugin.yml` deklariert und als `TabExecutor` registriert. Die Unterbefehle (`create`, `market`, `cancel ... confirm` und weitere) prüfen ihre Berechtigungen einzeln und erzeugen ihre Vorschläge selbst.

## Geprüft

MockBukkit 4.116.3 enthält `PaperCommandsMock` und lässt das Lifecycle-Event `COMMANDS` feuern. Ein Probeplugin registrierte einen Brigadier-Knoten mit `requires`-Prüfung, `player.performCommand` führte ihn aus. Die Tab-Vervollständigung über `server.getCommandTabComplete` liefert für Plugin-Knoten dagegen eine leere Liste, der Dispatcher von `PaperCommandsMock` enthält sie nicht. Die Vorschläge werden deshalb im Test über einen eigenen Dispatcher mit den echten Knoten geprüft.

## Entscheidung

Die Wurzelknoten `order` (mit Aliasen) und `orderadmin` werden über `BrigadierCommands` im Lifecycle-Event `COMMANDS` registriert, `commands:` fällt aus `plugin.yml`. Jeder Knoten hat `requires` (`orderhub.use` bzw. mindestens ein Admin-Recht) und ein einziges gieriges Textargument. Ausführung und Vorschläge gehen an einen austauschbaren `CommandHandler`. Bis die Datenbank bereit ist, steht dort ein Platzhalter, der `general.starting` antwortet.

Die Unterbefehle werden nicht als eigene Brigadier-Knoten mit typisierten Argumenten modelliert. Das würde die Berechtigungs-, Bestätigungs- und Eingabelogik von rund 20 Unterbefehlen neu schreiben, ohne dass sich das Verhalten für Spieler ändert, und die vorhandenen Tests müssten neu aufgebaut werden. Der Gewinn von Brigadier (Client-seitige Sicht auf Berechtigungen, Vorschläge ohne Serverrundlauf für die Wurzel) ist mit dem Wurzelknoten schon da.

Die Knoten werden einmal je Plugin-Instanz registriert. Ein erneutes Aktivieren setzt nur die Platzhalter-Handler zurück, weil Paper das Registrieren von Lifecycle-Handlern nach dem ersten Aktivieren ablehnt.

## Folgen

Typisierte Argumente (Menge, Preis, Spieler) gibt es nicht, Fehleingaben meldet weiter der jeweilige Handler als Chatnachricht. Die Aufrufe von `getCommand(...)` entfallen. Der Brigadier-Pfad ist nur mit MockBukkit geprüft, nicht auf einem echten Paper- oder Folia-Server (mcstage).
