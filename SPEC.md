# OrderHub: Spezifikation

Stand: 2026-11-26. Diese Datei beschreibt, was OrderHub leisten soll, und trennt ausdrücklich Umgesetztes von Geplantem. Den tatsächlichen Umsetzungsstand dokumentiert RELEASE_REPORT.md.

## Produkt

OrderHub ist ein Spieler-zu-Spieler-Marktplatz für Kaufaufträge auf Paper und Folia. Ein Spieler erstellt einen Auftrag (Item, Menge, Stückpreis), der Gesamtbetrag wird bei der Erstellung reserviert (Escrow). Andere Spieler liefern Items, erhalten sofort die Auszahlung, der Auftraggeber holt die gelieferten Items über ein Claim-Menü ab.

## Plattform und Abhängigkeiten

- Java 21, Gradle Kotlin DSL, Shadow.
- Paper API 1.21.11-R0.1-SNAPSHOT (siehe tools/JAVA_TOOLCHAIN.md zur Begründung). Folia über `folia-supported: true`. Kein NMS, kein Spigot-Support.
- Adventure und MiniMessage kommen aus der Paper-API (nicht gebündelt).
- Vault 1.7.1 (compileOnly, Soft-Dependency), PlaceholderAPI (compileOnly, Soft-Dependency).
- HikariCP, SQLite-JDBC, MariaDB-JDBC (gebündelt, relokiert bei Hikari und SLF4J).
- Tests: JUnit Jupiter, MockBukkit, AssertJ, Testcontainers (nur mit Docker-Daemon).

## Geldmodell

Beträge sind intern `long` in Minor Units (zwei Nachkommastellen, 1,00 = 100). Währungspräzision 2, Rundung `HALF_EVEN` bei Konvertierung aus `double`/`BigDecimal`, Überlauf über `Math.addExact`/`multiplyExact`. Obergrenze `Money.MAX = 9_000_000_000_000_00` Minor Units. `double` erscheint nur an der Vault-Grenze, NaN und Infinity werden abgelehnt.

## Kernfunktionen (Soll)

1. Aufträge erstellen, ansehen, pausieren, fortsetzen, stornieren, archivieren, Teillieferung mit Mindestliefermenge, Ablauf, Vorlagen, Bulk-Erstellung, Auto-Verlängerung.
2. Escrow mit Erstellgebühr, Gebührenbefreiung (`orderhub.bypass.fee`), Rückerstattung bei Storno und Ablauf (nur Restbetrag), Verkaufssteuer auf Auszahlungen.
3. Transaction Engine mit dauerhaftem Journal, Statusmaschine, Idempotency Keys, Recovery beim Start, MANUAL_REVIEW für unklare Zustände.
4. Item-Matcher mit Standard-Ablehnung (benannt, Lore, verzaubert, beschädigt, Container-Inhalt, unerwartete Datenkomponenten) und konfigurierbarer Sperrliste.
5. Delivery-Menü mit eigenem InventoryHolder, serverseitiger Prüfung, persistenter Rückgabewarteschlange.
6. Claim-System mit persistenten Restmengen, ohne Drops.
7. Mehrserverbetrieb über gemeinsame MariaDB/MySQL, Versionsspalten, `SELECT ... FOR UPDATE`, Outbox mit Polling für Cache-Invalidierung. SQLite im Einzelserverbetrieb.
8. Marktanalyse aus echten Abschlüssen (Preisverlauf 24h/7d/30d/90d, Median, Volumen), Preisempfehlung nur bei ausreichender Datenlage.
9. Favoriten, Preisalarme, Benachrichtigungen mit Spam-Schutz.
10. Spielerstatistiken aus nachvollziehbaren Journaldaten.
11. Discord-Webhook über Outbox, asynchron, mit Backoff, vollständig deaktivierbar.
12. Öffentliche API (Services und Events), Admin-Befehle mit Begründung und Audit-Log, PlaceholderAPI-Expansion.
13. Konfiguration (`config.yml`, `menus.yml`, `discord.yml`, `messages_en.yml`, `messages_de.yml`) mit Versionierung und nicht überschreibender Ergänzung.

## Grenzen, die ehrlich benannt werden

- Inventar, SQL und Vault bilden keine gemeinsame atomare Transaktion. OrderHub liefert ein absturzsicheres Protokoll mit konservativer Recovery, keine Exactly-once-Garantie über Vault.
- Absturz zwischen erfolgreicher Provider-Auszahlung und gespeichertem Erfolgsstatus erzeugt `PAYOUT_IN_PROGRESS`. Die Recovery leitet diesen Fall nach MANUAL_REVIEW, statt blind erneut auszuzahlen.
- Absolute Dupe-Freiheit wird nicht behauptet.

## Abschlusskriterien

Siehe RELEASE_REPORT.md. Ein Kriterium gilt nur als erfüllt, wenn der zugehörige Test oder Lauf tatsächlich ausgeführt wurde.
