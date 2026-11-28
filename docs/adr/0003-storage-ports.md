# 0003: Storage als Ports mit Unit of Work

Datum: 2026-11-26. Status: umgesetzt.

Domänen- und Engine-Code arbeitet gegen Interfaces (`Storage.transact`, `UnitOfWork`, Repositories). Die JDBC-Implementierung ist austauschbar, Tests der Engine laufen mit einer In-Memory-Implementierung mit denselben Constraints, Tests der JDBC-Schicht laufen als gemeinsamer Vertragstest gegen SQLite und MariaDB.

Folge: Jede Datenbankarbeit läuft über einen begrenzten DB-Executor und nie auf Server-, Region- oder Entity-Threads. Deadlocks und Lock-Konflikte werden mit begrenztem Backoff wiederholt, deshalb müssen Transaktionsfunktionen frei von Nebenwirkungen außerhalb der Datenbank sein.
