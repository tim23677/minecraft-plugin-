# Regeln der Verkaufsplattformen

Stand der Abfrage: 2026-11-27. Alle Aussagen stammen aus den unten genannten Seiten, die in dieser Sitzung abgerufen wurden. Die Plattformen ändern ihre Regeln ohne Ankündigung, vor jeder Veröffentlichung sind die Quellen erneut zu lesen.

## SpigotMC

Quelle: https://www.spigotmc.org/threads/premium-resource-guidelines.31667/ und https://www.spigotmc.org/wiki/spigot-rules/

Die Premium-Richtlinien enthalten den Satz, dass derzeit kein neuer Zugang zum Einstellen von Premium-Ressourcen vergeben wird. Für einen neuen Autor ist SpigotMC als Verkaufsweg deshalb nicht verfügbar, solange das so bleibt. Falls der Zugang vorhanden ist, gelten diese Regeln:

- Höchstpreis 19,99 USD.
- Die Ressource muss die neueste stabile Spigot-Version unterstützen und ein Plugin sein. Sie darf nicht von anderen Premium-Ressourcen abhängen.
- Obfuskation ist erlaubt, der dekompilierte Code muss für das Team lesbar bleiben. Bezahlte Obfuskatoren müssen genannt und freigegeben werden. Package-Root und Hauptklasse müssen unverschleiert bleiben.
- Lizenzsysteme jeder Art sind verboten. Die Ressource muss direkt aus der heruntergeladenen Datei laufen, ohne manuelle Installationsschritte und ohne Zugriff auf einen bestimmten Server. Code muss bei Webserver-Fehlern ausfallsicher sein.
- Werbung ist nur auf der Ressourcenseite erlaubt, nicht in Konsole oder Spielmeldungen des Plugins.
- Externe Download-Links müssen Direktlinks sein.
- Ressourcen ohne Update seit sechs Monaten können als inaktiv gelten.

Folgen für OrderHub: Es gibt keine Online-Lizenzprüfung und keine Obfuskation (SECURITY.md). Der Wunsch aus Phase 7, Bibliotheken über `libraries` in plugin.yml von Maven Central nachzuladen, kollidiert mit der Anforderung, dass die Datei ohne Zugriff auf einen bestimmten Server läuft. Deshalb wird eine Variante mit eingebetteten Treibern ausgeliefert (siehe ADR 0004).

## BuiltByBit

Quellen: https://builtbybit.com/wiki/resource-eulas/ und https://builtbybit.com/wiki/anti-piracy-placeholders

- Weiterverkauf eines Produkts oder des Zugangs dazu ist für alle Lizenztypen ausgeschlossen. Wer einen Server für Dritte betreibt, die das Produkt nutzen sollen, braucht je Kunde eine eigene Lizenz.
- Käufer dürfen ein DRM-System oder die Tracking-Daten (Anti-Piracy-Platzhalter) nicht entfernen oder abschalten.
- Bei Obfuskation oder Verschlüsselung müssen die Platzhalter-Kennungen ausgenommen werden, damit BuiltByBit sie beim Download ersetzen kann. OrderHub verwendet keine Obfuskation, die Platzhalter sind daher unproblematisch, aber noch nicht eingebaut.
- Ein Thread im Forum (https://builtbybit.com/threads/selling-your-first-premium-resource-on-builtbybit-a-beginners-checklist.744651/) nennt, dass DRM-Lizenzsysteme zulässig sind. Den genauen Regeltext habe ich nicht gelesen.

BuiltByBit ist damit der Verkaufsweg, der zum Produkt passt.

## Hangar

Quelle: https://hangar.papermc.io/guidelines (Seitenstand 30. Dezember 2025)

- Funktionen dürfen nicht hinter einer Bezahlschranke oder einem vom Autor vergebenen Lizenzschlüssel liegen. Verlinkungen auf kostenpflichtige Add-ons oder Vollversionen sind ebenfalls ausgeschlossen. Links zu kostenpflichtigem Support sind erlaubt.
- Werbung ist nicht erlaubt, Obfuskation in jeder Form ist verboten.
- Externe Download-Links müssen direkt auf die Datei zeigen, ohne Werbelink, Passwort oder Konto.
- Der Projektname darf keine Plattform, Version oder Beschreibung enthalten.

Folge: Ein kostenpflichtiges OrderHub kann auf Hangar nicht angeboten werden. Eine kostenlose Variante mit vollem Funktionsumfang wäre möglich, ist aber nicht geplant.

## Modrinth

Quelle: https://modrinth.com/legal/rules (zuletzt geändert 13. August 2026)

- Inhalte, die Daten an einen Server hochladen, den die Nutzer nicht selbst ausdrücklich wählen, brauchen eine klare Offenlegung. Das gilt für bStats und für die Update-Prüfung. Beide sind abschaltbar und werden in der README beschrieben.
- Beschreibung muss klar sein und eine englische Fassung enthalten, irreführende Behauptungen sind verboten.
- Der Einsatz generativer KI muss angegeben werden. Bilder aus generativer KI sind in Galerie, Icon und Beschreibung verboten.
- Zu bezahlten Plugins oder Lizenzprüfung enthalten die Regeln keine Aussage, die ich gefunden habe. Ob ein Bezahlmodell dort zulässig ist, ist nicht geklärt.

## Folgerungen für das Listing

- Hauptplattform: BuiltByBit. SpigotMC nur, wenn der Premium-Zugang wieder offen ist. Hangar scheidet für ein kostenpflichtiges Produkt aus. Modrinth ist ungeklärt.
- Der Update-Checker bleibt standardmäßig aus (`updates.check: false`) und wird in der Beschreibung genannt.
- bStats ist abschaltbar und in der README offengelegt.
- Keine Werbung im Plugin selbst.
