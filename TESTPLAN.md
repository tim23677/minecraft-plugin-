# Testplan

Stand: 2026-11-28. Dieser Plan ist die Grundlage der Simulation auf einem echten Paper-Server (`sim/run.sh`). Ergebnisse stehen in `SIMULATION_REPORT.md`.

## Projekt

Gradle (Kotlin DSL), Java 21, Paper API 1.21.11, `api-version: 1.21`, Folia-fähig. Abhängigkeiten laut `plugin.yml`: `softdepend: [Vault, PlaceholderAPI]`, Bibliotheken HikariCP, SQLite JDBC, MariaDB Connector/J. Datenbank: SQLite oder MariaDB. Kein Redis, keine externe HTTP-API außer dem optionalen Discord-Webhook.

## Befehle und Rechte

Spieler (`/order`, Rechte `orderhub.use` plus je Unterbefehl):

| Unterbefehl | Recht | Prüfungen |
|---|---|---|
| (leer), `help` | use | Hauptmenü, Hilfe in zwei Zeilen |
| `create [menge] [preis] [optionen]` | create | ohne Argumente (Chat-Eingabe), gültig, ungültig, Menge/Preis außerhalb der Grenzen, Optionen `durability=`, `enchants`, `autoclaim`, falsche Option, Bestätigung, Abbruch |
| `mine` | create | Menü, leer und gefüllt |
| `market [sortierung\|text]` | market | Sortierungen, Suchtext, leer |
| `sell [menge\|all]` | market | ohne Item, ohne passenden Auftrag, ungültige Menge |
| `deliver <id>` | market | ungültige Id, unbekannte Id, eigener Auftrag |
| `cancel <id>` | create | ungültige Id, fremder Auftrag, eigener Auftrag |
| `info <id>` | market | gültig, unbekannt, ohne Argument |
| `renew <id> on\|off` | create | gültig, ungültig, fremder Auftrag |
| `template [save\|use\|delete\|list]` | create | alle Zweige, Namen, Limit, Optionen |
| `claims`, `returns` | use | leer, mit Paketen |
| `stats`, `history` | stats | Menüs |
| `favorites [add\|remove\|alert]` | favorites | alle Zweige |
| `analytics [item]` | market | mit und ohne Item |

Verwaltung (`/orderadmin`, Rechte `orderhub.admin.*`): `reload`, `list`, `cancel <id> confirm <grund>`, `purge-expired confirm <grund>`, `transactions`, `transaction <uuid>`, `inspect <id>`, `review list|show|resolve`, `health`, `dump`, `backup confirm <grund>`, `export orders|ledger confirm <grund>`, `recovery confirm <grund>`, `statistics`, `debug`. Jeweils mit Recht, ohne Recht, ohne `confirm`, ohne Grund.

## Listener und Ereignisse

Join (Rückgaben, Login-Zusammenfassung, Pakete), Quit (Cooldown aufräumen, offene Eingaben), Inventarklicks in den Plugin-Menüs (links, rechts, Shift, Zahlentasten, Drag, Doppelklick), Menü schließen, Tod und Teleport bei offenem Menü, Chat-Eingabe für Menge, Preis und Suche, Serverstopp bei offenen Menüs.

## Konfiguration

`config.yml` (Grenzen, Gebühren, Cooldown, Ablauf, Sprache, Backup, Logging, Vault-Timeout), `menus.yml`, `messages_<sprache>.yml`, `discord.yml`. Grenzfälle: Menge 1, Gebühr 0 und 10000 bp, Cooldown 0, ungültige Sprache, ungültige Werte (Warnung, Standardwert).

## Zusätzliche Prüfungen

- `reload confirm` und Neustart: Daten und Konfiguration bleiben erhalten.
- Befehle in schneller Folge, Thread-Fehler im Log (`Asynchronous`, `Not on server thread`, Folia-Meldungen), Speicherverlauf.
- Rechte entziehen über LuckPerms (`orderhub.use`, `orderhub.create`, `orderhub.market`).
- Wirtschaft über EssentialsX und VaultUnlocked.
- Harter Absturz (SIGKILL) während einer Lieferung und Recovery (siehe `tools/e2e`).

## Ergebnisse

Siehe `SIMULATION_REPORT.md`.
