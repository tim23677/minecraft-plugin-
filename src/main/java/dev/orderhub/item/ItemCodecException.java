package dev.orderhub.item;

import java.util.Objects;

/** Ausdruecklicher Fehler beim Kodieren oder Dekodieren von Items. Es entsteht nie Teilzustand. */
public final class ItemCodecException extends Exception {

  private static final long serialVersionUID = 1L;

  /** Maschinenlesbarer Grund. */
  public enum Reason {
    EMPTY_ITEM,
    TOO_LARGE,
    SERIALIZATION_FAILED,
    TRUNCATED,
    BAD_MAGIC,
    UNSUPPORTED_FORMAT_VERSION,
    UNSUPPORTED_DATA_VERSION,
    CORRUPT_PAYLOAD,
    INVALID_AMOUNT,
    KEY_MISMATCH
  }

  private final Reason reason;

  public ItemCodecException(Reason reason, String message) {
    super(reason + ": " + message);
    this.reason = Objects.requireNonNull(reason, "reason");
  }

  public ItemCodecException(Reason reason, String message, Throwable cause) {
    super(reason + ": " + message, cause);
    this.reason = Objects.requireNonNull(reason, "reason");
  }

  public Reason reason() {
    return reason;
  }
}
