package dev.orderhub.discord;

/**
 * Nachricht fuer Discord. Enthaelt nie UUIDs, IP-Adressen oder Befehle, nur Text. Der Titel wird
 * bereinigt, damit keine Mentions ({@code @everyone}) entstehen.
 */
public record DiscordMessage(String title, String body, int color) {

  public static final int COLOR_INFO = 0x4EA8DE;
  public static final int COLOR_WARN = 0xF2C94C;
  public static final int COLOR_ERROR = 0xEB5757;

  /** JSON fuer den Webhook mit abgeschalteten Mentions. */
  public String toJson(String username) {
    return "{\"username\":"
        + quote(username)
        + ",\"allowed_mentions\":{\"parse\":[]},\"embeds\":[{\"title\":"
        + quote(limit(title, 200))
        + ",\"description\":"
        + quote(limit(body, 1800))
        + ",\"color\":"
        + color
        + "}]}";
  }

  private static String limit(String s, int max) {
    String t = s == null ? "" : s.replace("@", "@​");
    return t.length() <= max ? t : t.substring(0, max - 1) + "…";
  }

  static String quote(String s) {
    StringBuilder sb = new StringBuilder("\"");
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        default -> {
          if (c < 0x20) {
            sb.append(String.format("\\u%04x", (int) c));
          } else {
            sb.append(c);
          }
        }
      }
    }
    return sb.append('"').toString();
  }
}
