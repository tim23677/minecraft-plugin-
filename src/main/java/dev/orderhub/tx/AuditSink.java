package dev.orderhub.tx;

/** Port fuer Audit-Eintraege, bis das audit_log-Repository existiert. */
public interface AuditSink {

  /** Schreibt einen Recovery-Bericht. */
  void recoveryReport(RecoveryReport report, long timestamp);

  /** Warnung an Administratoren, z. B. bei MANUAL_REVIEW. */
  void adminWarning(String message);

  /** Sink ohne Wirkung. */
  AuditSink NOOP =
      new AuditSink() {
        @Override
        public void recoveryReport(RecoveryReport report, long timestamp) {
          // bewusst leer
        }

        @Override
        public void adminWarning(String message) {
          // bewusst leer
        }
      };
}
