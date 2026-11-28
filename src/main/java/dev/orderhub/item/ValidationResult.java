package dev.orderhub.item;

import java.util.Objects;

/**
 * Ergebnis der Pruefung eines Items.
 *
 * @param valid {@code true}, wenn zugelassen
 * @param reason Ablehnungsgrund, {@code null} wenn {@code valid}
 * @param detail technischer Zusatz (z. B. Komponentenschluessel) fuer Logs, kein Spielertext
 */
public record ValidationResult(boolean valid, RejectionReason reason, String detail) {

  private static final ValidationResult OK = new ValidationResult(true, null, "");

  public ValidationResult {
    Objects.requireNonNull(detail, "detail");
    if (valid == (reason != null)) {
      throw new IllegalArgumentException("reason must be set exactly when invalid");
    }
  }

  public static ValidationResult ok() {
    return OK;
  }

  public static ValidationResult rejected(RejectionReason reason) {
    return new ValidationResult(false, Objects.requireNonNull(reason), "");
  }

  public static ValidationResult rejected(RejectionReason reason, String detail) {
    return new ValidationResult(false, Objects.requireNonNull(reason), detail);
  }
}
