package dev.orderhub.discord;

/** Exponentieller Backoff mit Obergrenze, ohne Zufall (deterministisch testbar). */
public final class Backoff {

  private Backoff() {}

  /** Wartezeit in Sekunden vor Versuch {@code attempt} (1 = erster Wiederholungsversuch). */
  public static long seconds(int attempt, long initialSeconds, long maxSeconds) {
    if (attempt < 1) {
      return 0;
    }
    long delay = initialSeconds;
    for (int i = 1; i < attempt && delay < maxSeconds; i++) {
      delay = Math.min(maxSeconds, delay * 2);
    }
    return Math.min(delay, maxSeconds);
  }
}
