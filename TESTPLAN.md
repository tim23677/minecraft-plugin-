# Manueller Testplan

Dieser Plan beschreibt Szenarien für einen lokalen Paper-Server und einen Folia-Server. Die Spalte "Tatsächliches Ergebnis" ist bis zur Durchführung "nicht ausgeführt". Automatisierte Entsprechungen und ihre Ergebnisse stehen in RELEASE_REPORT.md.

Durchführung: `ACCEPT_MINECRAFT_EULA=true tools/mcstage/run.sh` startet Paper und Folia mit dem Jar (die EULA-Zustimmung liegt bei der ausführenden Person), siehe tools/README.md.

## 1. Auftrag erstellen

Voraussetzungen: Vault und Economy aktiv, Spieler mit 1000 Guthaben, 64 Diamanten Item in der Hand.

Durchführung: /order create 64 8 ausführen und bestätigen.

Erwartetes Ergebnis: Auftrag erscheint in /order mine, Guthaben sinkt um 512 plus Gebühr, Escrow 512 im Auftrag.

Tatsächliches Ergebnis Paper: nicht ausgeführt. Status: offen.

Tatsächliches Ergebnis Folia: nicht ausgeführt. Status: offen.

## 2. Auftrag mit zu wenig Guthaben erstellen

Voraussetzungen: Spieler mit 10 Guthaben.

Durchführung: /order create 64 8.

Erwartetes Ergebnis: Meldung zu wenig Guthaben, kein Auftrag, kein Guthabenverlust.

Tatsächliches Ergebnis Paper: nicht ausgeführt. Status: offen.

Tatsächliches Ergebnis Folia: nicht ausgeführt. Status: offen.

## 3. Auftrag teilweise beliefern

Voraussetzungen: Offener Auftrag über 64, zweiter Spieler mit 20 Stück.

Durchführung: /order deliver <id>, 20 Stück einlegen, liefern.

Erwartetes Ergebnis: Fortschritt 20/64, Lieferant erhält 20 mal Preis abzüglich Steuer, Auftraggeber findet 20 in /order claims.

Tatsächliches Ergebnis Paper: nicht ausgeführt. Status: offen.

Tatsächliches Ergebnis Folia: nicht ausgeführt. Status: offen.

## 4. Auftrag vollständig beliefern

Voraussetzungen: Wie oben, Rest 44.

Durchführung: Rest liefern.

Erwartetes Ergebnis: Auftrag COMPLETED, Escrow 0, Auftraggeber erhält Hinweis.

Tatsächliches Ergebnis Paper: nicht ausgeführt. Status: offen.

Tatsächliches Ergebnis Folia: nicht ausgeführt. Status: offen.

## 5. Auftrag abbrechen

Voraussetzungen: Offener Auftrag mit Teillieferung.

Durchführung: /order cancel <id> bestätigen.

Erwartetes Ergebnis: Nur Restbetrag wird erstattet, Auftrag CANCELLED.

Tatsächliches Ergebnis Paper: nicht ausgeführt. Status: offen.

Tatsächliches Ergebnis Folia: nicht ausgeführt. Status: offen.

## 6. Auftrag läuft ab

Voraussetzungen: orders.expiry-hours auf 1 setzen, Zeit abwarten oder Ablauf in DB vorziehen.

Durchführung: Ablaufprüfung (Minute) oder /orderadmin purge-expired confirm test.

Erwartetes Ergebnis: Auftrag EXPIRED, Restbetrag erstattet.

Tatsächliches Ergebnis Paper: nicht ausgeführt. Status: offen.

Tatsächliches Ergebnis Folia: nicht ausgeführt. Status: offen.

## 7. Inventar voll

Voraussetzungen: Auftraggeber mit vollem Inventar, Claim vorhanden.

Durchführung: /order claims.

Erwartetes Ergebnis: Meldung Inventar voll, Items bleiben gespeichert, nichts auf dem Boden.

Tatsächliches Ergebnis Paper: nicht ausgeführt. Status: offen.

Tatsächliches Ergebnis Folia: nicht ausgeführt. Status: offen.

## 8. Liefermenü schließen

Voraussetzungen: Items im Liefermenü.

Durchführung: Menü per Esc schließen.

Erwartetes Ergebnis: Items kommen zurück ins Inventar oder in /order returns.

Tatsächliches Ergebnis Paper: nicht ausgeführt. Status: offen.

Tatsächliches Ergebnis Folia: nicht ausgeführt. Status: offen.

## 9. Spieler verlässt den Server

Voraussetzungen: Items im Liefermenü.

Durchführung: Verbindung trennen.

Erwartetes Ergebnis: Beim nächsten Login werden Items automatisch zurückgegeben.

Tatsächliches Ergebnis Paper: nicht ausgeführt. Status: offen.

Tatsächliches Ergebnis Folia: nicht ausgeführt. Status: offen.

## 10. Spieler wird gekickt

Voraussetzungen: Items im Liefermenü.

Durchführung: /kick.

Erwartetes Ergebnis: Wie Disconnect.

Tatsächliches Ergebnis Paper: nicht ausgeführt. Status: offen.

Tatsächliches Ergebnis Folia: nicht ausgeführt. Status: offen.

## 11. Spieler stirbt

Voraussetzungen: Items im Liefermenü.

Durchführung: Spieler töten.

Erwartetes Ergebnis: Items nicht verloren und nicht dupliziert.

Tatsächliches Ergebnis Paper: nicht ausgeführt. Status: offen.

Tatsächliches Ergebnis Folia: nicht ausgeführt. Status: offen.

## 12. Spieler wechselt die Welt

Voraussetzungen: Items im Liefermenü.

Durchführung: Teleport in andere Welt.

Erwartetes Ergebnis: Menü schließt, Items zurück.

Tatsächliches Ergebnis Paper: nicht ausgeführt. Status: offen.

Tatsächliches Ergebnis Folia: nicht ausgeführt. Status: offen.

## 13. Serverneustart während einer Lieferung

Voraussetzungen: Lieferung bestätigt, Server hart beenden (kill -9).

Durchführung: Neustart.

Erwartetes Ergebnis: Recovery-Bericht im Log, kein doppelter Payout, Items oder Claim konsistent.

Tatsächliches Ergebnis Paper: nicht ausgeführt. Status: offen.

Tatsächliches Ergebnis Folia: nicht ausgeführt. Status: offen.

## 14. Datenbankausfall

Voraussetzungen: MariaDB-Server anhalten.

Durchführung: /order market, Lieferung versuchen.

Erwartetes Ergebnis: Fehlermeldung, kein Verlust, nach Wiederanlauf funktioniert alles.

Tatsächliches Ergebnis Paper: nicht ausgeführt. Status: offen.

Tatsächliches Ergebnis Folia: nicht ausgeführt. Status: offen.

## 15. Vault-Auszahlungsfehler

Voraussetzungen: Economy-Plugin deaktivieren oder Fehler erzwingen.

Durchführung: Lieferung ausführen.

Erwartetes Ergebnis: Payout bleibt PENDING mit Wiederholung, nach Economy-Rückkehr wird gezahlt.

Tatsächliches Ergebnis Paper: nicht ausgeführt. Status: offen.

Tatsächliches Ergebnis Folia: nicht ausgeführt. Status: offen.

## 16. Zwei Spieler liefern gleichzeitig

Voraussetzungen: Auftrag mit Restmenge 10.

Durchführung: Beide liefern 10.

Erwartetes Ergebnis: Gesamtlieferung höchstens 10, der andere bekommt Items zurück.

Tatsächliches Ergebnis Paper: nicht ausgeführt. Status: offen.

Tatsächliches Ergebnis Folia: nicht ausgeführt. Status: offen.

## 17. 20 Spieler liefern gleichzeitig

Voraussetzungen: Auftrag mit Restmenge 64, 20 Bots.

Durchführung: Alle liefern.

Erwartetes Ergebnis: Summe der Lieferungen gleich Auftragsmenge, genau eine Auszahlung je Lieferung.

Tatsächliches Ergebnis Paper: nicht ausgeführt. Status: offen.

Tatsächliches Ergebnis Folia: nicht ausgeführt. Status: offen.

## 18. Zwei Server bearbeiten denselben Auftrag

Voraussetzungen: MariaDB, zwei Server mit verschiedener server-id.

Durchführung: Gleichzeitig liefern und stornieren.

Erwartetes Ergebnis: Nur eine Aktion gewinnt, kein doppeltes Geld.

Tatsächliches Ergebnis Paper: nicht ausgeführt. Status: offen.

Tatsächliches Ergebnis Folia: nicht ausgeführt. Status: offen.

## 19. Admin storniert während einer Lieferung

Voraussetzungen: Lieferung läuft.

Durchführung: /orderadmin cancel <id> confirm test.

Erwartetes Ergebnis: Lieferung oder Storno gewinnt, nie beides mit doppeltem Geld.

Tatsächliches Ergebnis Paper: nicht ausgeführt. Status: offen.

Tatsächliches Ergebnis Folia: nicht ausgeführt. Status: offen.

## 20. Auftrag läuft während einer Lieferung ab

Voraussetzungen: Ablaufzeit knapp.

Durchführung: Liefern.

Erwartetes Ergebnis: Eine der beiden Aktionen gewinnt konsistent.

Tatsächliches Ergebnis Paper: nicht ausgeführt. Status: offen.

Tatsächliches Ergebnis Folia: nicht ausgeführt. Status: offen.

## 21. Claim während eines Neustarts

Voraussetzungen: Claim läuft.

Durchführung: Server beenden.

Erwartetes Ergebnis: Paket bleibt RESERVED, Recovery eskaliert nach MANUAL_REVIEW oder gibt belegt sicher frei.

Tatsächliches Ergebnis Paper: nicht ausgeführt. Status: offen.

Tatsächliches Ergebnis Folia: nicht ausgeführt. Status: offen.

## 22. Doppelte Transaktionsanfrage

Voraussetzungen: Zweimal schnell auf Bestätigen klicken.

Durchführung: Klick wiederholen.

Erwartetes Ergebnis: Genau ein Auftrag, genau eine Lieferung.

Tatsächliches Ergebnis Paper: nicht ausgeführt. Status: offen.

Tatsächliches Ergebnis Folia: nicht ausgeführt. Status: offen.

## 23. Recovery nach unklarem Auszahlungsstatus

Voraussetzungen: Absturz während PAYOUT_IN_PROGRESS.

Durchführung: Neustart, /orderadmin statistics.

Erwartetes Ergebnis: Eintrag in MANUAL_REVIEW, keine automatische Zahlung.

Tatsächliches Ergebnis Paper: nicht ausgeführt. Status: offen.

Tatsächliches Ergebnis Folia: nicht ausgeführt. Status: offen.
