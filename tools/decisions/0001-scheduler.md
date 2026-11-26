# 0001: Eigene Scheduler-Abstraktion statt FoliaScheduler

Datum: 2026-11-26. Status: entschieden, offen für Revision nach dem ersten Folia-Serverlauf.

## Ausgangslage

OrderHub muss auf Paper und Folia laufen. Auf Folia gibt es keinen globalen Hauptthread, Aufgaben müssen an Region, Entity oder globalen Kontext gebunden werden. Die Paper-API ab 1.21 enthält `RegionScheduler`, `EntityScheduler`, `GlobalRegionScheduler` und `AsyncScheduler` auf `Server` und `Entity`. Auf Paper läuft derselbe Code mit dem Bukkit-Scheduler.

## Verglichen

CJCrafter/FoliaScheduler (Tag v0.8.0, letzter Commit 2026-05-27): fertige Aufgabenverkettung, Rückgabewerte als `CompletableFuture`, Unterstützung für sehr alte Spigot-Versionen (ab 1.12.2) und Reflexion mit Remapping. Das Repository enthält keine LICENSE-Datei. Es muss geshadet und relokiert werden und hängt damit am Update-Rhythmus einer Ein-Personen-Bibliothek.

Eigene Abstraktion `PluginScheduler`: vier Methoden (`runAt`, `runOn`, `runGlobal`, `runAsync`) plus `cancelAll`, zwei Implementierungen, Auswahl über Klassenerkennung. Etwa 120 Zeilen, vollständig unter eigener Kontrolle, nutzt nur öffentliche Paper-API. Zielversion ist 1.21+, ältere Versionen sind nicht Ziel.

## Entscheidung

Eigene Abstraktion. Gründe: Es existiert keine erkennbare Lizenz der Bibliothek für ein kommerziell verkauftes Plugin. Die Unterstützung für Versionen vor 1.20 wird nicht gebraucht. Reflexion und Remapping sind für eine Paper-1.21-Basis nicht nötig. Jede zusätzliche Abhängigkeit in einem verkauften Jar vergrößert die Lizenz- und Update-Pflichten.

## Folgen

Es fehlen derzeit wiederkehrende Tasks mit Verzögerung und Rückgabewerte. Wenn sie gebraucht werden, kommen sie in das Interface, nicht über eine Bibliothek. Die Folia-Zweige sind bis zum ersten echten Serverlauf ungetestet. Revision, wenn: die Lizenz von FoliaScheduler geklärt ist und das Projekt Versionen vor 1.20 unterstützen soll.
