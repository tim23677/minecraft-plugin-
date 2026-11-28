package dev.orderhub.market;

/**
 * Kleines Textdiagramm aus Balkenzeichen. Werte von 0 oder weniger gelten als "keine Daten" und
 * erscheinen als Punkt.
 *
 * @since 2.0.0
 */
public final class Sparkline {

  private static final char[] BARS = {'▁', '▂', '▃', '▄', '▅', '▆', '▇', '█'};

  private Sparkline() {}

  public static String of(long[] values) {
    long min = Long.MAX_VALUE;
    long max = Long.MIN_VALUE;
    for (long v : values) {
      if (v > 0) {
        min = Math.min(min, v);
        max = Math.max(max, v);
      }
    }
    StringBuilder out = new StringBuilder(values.length);
    for (long v : values) {
      if (v <= 0) {
        out.append('·');
      } else if (max == min) {
        out.append(BARS[BARS.length / 2]);
      } else {
        int level = (int) ((v - min) * (BARS.length - 1) / (max - min));
        out.append(BARS[level]);
      }
    }
    return out.toString();
  }
}
