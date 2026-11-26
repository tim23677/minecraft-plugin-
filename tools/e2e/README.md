# Spieler-Szenario auf einem echten Server

`e2e.js` spielt zwei Bots (mineflayer) auf einem echten Paper-Server mit VaultUnlocked und EssentialsX:
Auftrag erstellen, Lieferung mit Ueberschuss, Auszahlung ueber Vault, Abholung, Storno mit
Erstattung, harter Absturz (SIGKILL) direkt nach der Bestaetigung einer Lieferung, Neustart und
Recovery. Danach wird die Datenbank gegen die Invariante "alle Items im Menue = Claim + Rueckgabe"
geprueft.

Vorbereitung (einmalig), alles in einem leeren Ordner `srv`:

1. `paper.jar` (Paper 1.21.11), `eula.txt` mit `eula=true` (Zustimmung der verantwortlichen Person)
2. `plugins/OrderHub-<version>.jar`, `plugins/EssentialsX-2.22.0.jar`, `plugins/VaultUnlocked-2.20.3.jar`
   (beide von Modrinth)
3. `server.properties` mit `online-mode=false` und `server-port=25599`
4. in einem Ordner `bots`: `npm install mineflayer@4`, `e2e.js` dorthin kopieren

Start: `node e2e.js ../srv ../result.json`. Der Server wird vom Skript gestartet und beendet.
Ergebnis: eine Zeile je Pruefung (`PASS` oder `FAIL`) und `result.json`.
