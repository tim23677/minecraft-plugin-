# 0002: Journal mit Zustandsmaschine statt verteilter Transaktion

Datum: 2026-11-26. Status: umgesetzt.

Inventar, SQL-Datenbank und Vault bilden keine gemeinsame atomare Transaktion. OrderHub führt deshalb ein dauerhaftes Journal (`transaction_journal`) mit strikter Zustandsmaschine (`TxState`), Idempotency Key (UNIQUE) und Compare-and-Set auf `(id, state, version)`.

Regeln: Geld wird nie innerhalb einer Datenbanktransaktion bewegt. Der Vault-Aufruf passiert nach dem Persistieren des Absichtszustands (`PAYOUT_IN_PROGRESS`). Bleibt ein Eintrag dort hängen, ist der Ausgang unbekannt und die Recovery setzt ihn auf `MANUAL_REVIEW`, statt erneut zu zahlen. Items verlassen das Menü erst nach `ITEMS_REMOVED` im Journal, Recovery gibt sie sonst über `item_returns` zurück.

Verworfen: Exactly-once-Zusage über Vault (technisch nicht möglich, Vault hat keine Transaktions-Id), Zwei-Phasen-Commit (Vault unterstützt es nicht).

Folgen: Der Fall "Vault hat gezahlt, Absturz vor dem Speichern" bleibt ein manuell zu klärender Fall. Die Dokumentation sagt das offen.
