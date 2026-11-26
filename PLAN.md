# OrderHub: Plan

Reihenfolge und Stand der Phasen. Der Stand wird in RELEASE_REPORT.md ehrlich fortgeschrieben.

| Phase | Inhalt | Verantwortlich | Stand |
|---|---|---|---|
| 1 | Repository-Analyse, SPEC, ARCHITECTURE, DATABASE, SECURITY | Lead | erledigt |
| 2 | Build, PlaceholderAPI-Abhängigkeit, Versionskatalog | Lead | erledigt |
| 3 | Scheduler-Abstraktion | vorhanden (ADR 0001) | vorhanden |
| 4 | Storage (Migrationen, JDBC, Pool, Retry) | Agent Storage | in Arbeit |
| 5 | Domain-Modell, Money | Lead | erledigt |
| 6-7 | Transaction Engine, Escrow, Economy | Agent Engine | in Arbeit |
| 8 | Item-Codec, Matcher, GUI-Framework | Agent Items/GUI | in Arbeit |
| 9 | Delivery/Claim im Bukkit-Kontext, Menüs, Befehle | Lead + Agenten | offen |
| 10-12 | Marktanalyse, Statistik, Favoriten, Benachrichtigungen | offen | offen |
| 13 | Discord-Outbox | offen | offen |
| 14 | Developer API und Events | offen | offen |
| 15 | Admin-System | offen | offen |
| 16 | Konfiguration, Sprachen, PlaceholderAPI | Lead | offen |
| 17 | Sicherheitsreview (plugin-security-reviewer), Folia-Review (folia-concurrency-reviewer) | Lead | offen |
| 18-19 | Integrations- und Kompatibilitätstests (Paper/Folia-Lauf über tools/mcstage) | Lead | offen |
| 20 | Release-JAR, Dokumentation, RELEASE_REPORT | Lead | offen |

Arbeitsregeln: nach jeder Phase `./gradlew spotlessApply build`, Tests tatsächlich ausführen, Befunde beheben, Ergebnisse nur berichten, wenn sie gemessen wurden.
