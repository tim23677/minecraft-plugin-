package dev.orderhub.integration;

import dev.orderhub.tx.AuditSink;
import dev.orderhub.tx.RecoveryReport;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Schreibt Recovery-Berichte und Admin-Warnungen ins Log und gibt sie an einen Zusatzkanal weiter.
 */
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Logger ist gemeinsam genutzte Infrastruktur")
public final class ConsoleAudit implements AuditSink {

  private final Logger logger;
  private final Consumer<String> adminChannel;

  public ConsoleAudit(Logger logger, Consumer<String> adminChannel) {
    this.logger = logger;
    this.adminChannel = adminChannel;
  }

  @Override
  public void recoveryReport(RecoveryReport report, long timestamp) {
    Level level = report.hasEscalations() ? Level.WARNING : Level.INFO;
    logger.log(level, "Recovery: " + report.summary());
    for (RecoveryReport.Escalation e : report.escalations()) {
      logger.warning(
          "MANUAL_REVIEW journal="
              + e.journalId()
              + " type="
              + e.type()
              + " amount="
              + e.amount()
              + " reason="
              + e.reason());
    }
    if (report.hasEscalations()) {
      adminChannel.accept("Recovery: " + report.summary());
    }
  }

  @Override
  public void adminWarning(String message) {
    logger.warning("ADMIN: " + message);
    adminChannel.accept(message);
  }
}
