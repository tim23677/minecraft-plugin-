package dev.orderhub.command;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Findet zu einer Fehleingabe den aehnlichsten Unterbefehl (Levenshtein-Abstand). */
final class Suggest {

  /** Groesster Abstand, bei dem noch ein Vorschlag erscheint. */
  static final int MAX_DISTANCE = 2;

  private Suggest() {}

  static Optional<String> closest(String input, List<String> options) {
    String in = input.toLowerCase(Locale.ROOT);
    if (in.isEmpty() || in.length() > 32) {
      return Optional.empty();
    }
    String best = null;
    int bestDistance = Integer.MAX_VALUE;
    for (String option : options) {
      int d = distance(in, option);
      if (d < bestDistance) {
        bestDistance = d;
        best = option;
      }
    }
    return bestDistance <= MAX_DISTANCE && bestDistance < in.length()
        ? Optional.ofNullable(best)
        : Optional.empty();
  }

  static int distance(String a, String b) {
    int[] previous = new int[b.length() + 1];
    int[] current = new int[b.length() + 1];
    for (int j = 0; j <= b.length(); j++) {
      previous[j] = j;
    }
    for (int i = 1; i <= a.length(); i++) {
      current[0] = i;
      for (int j = 1; j <= b.length(); j++) {
        int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
        current[j] =
            Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
      }
      int[] swap = previous;
      previous = current;
      current = swap;
    }
    return previous[b.length()];
  }
}
