package dev.orderhub.bootstrap;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Baut den Text der Support-Datei fuer {@code /orderadmin dump}: Version, Server, Plugins,
 * Konfiguration ohne Geheimnisse und die letzten Fehler. Rein textbasiert und ohne Bukkit, damit
 * die Bereinigung ohne Server testbar ist. Geheimnisse, Zugangsdaten in URLs, Webhook-Adressen,
 * UUIDs und IP-Adressen werden ersetzt, bevor etwas geschrieben wird.
 *
 * @since 2.0.0
 */
public final class SupportDump {

  private static final Pattern SECRET_KEY =
      Pattern.compile(
          "(?i).*(password|passwd|pwd|secret|token|webhook|api-?key|license|credential).*");
  private static final Pattern URL_USERINFO = Pattern.compile("(?i)(//)[^/@\\s]+:[^/@\\s]*@");
  private static final Pattern URL_SECRET_PARAM =
      Pattern.compile("(?i)([?&;](?:password|passwd|pwd|user|username|token|secret)=)[^&;\\s]*");
  private static final Pattern IPV6 =
      Pattern.compile(
          "(?i)(?<!\\w)(?:[0-9a-f]{1,4}:){7}[0-9a-f]{1,4}(?!\\w)"
              + "|(?<!\\w)(?:[0-9a-f]{1,4}(?::[0-9a-f]{1,4}){0,6})?::"
              + "(?:[0-9a-f]{1,4}(?::[0-9a-f]{1,4}){0,6})?(?!\\w)"
              + "|\\[[0-9a-f:]{3,}\\]");
  private static final Pattern UUID_PATTERN =
      Pattern.compile(
          "\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b");
  private static final Pattern IPV4 =
      Pattern.compile(
          "\\b(?:(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)\\b");
  private static final Pattern WEBHOOK =
      Pattern.compile("(?i)https?://(?:[a-z0-9-]+\\.)?discord(?:app)?\\.com/api/webhooks/\\S+");

  private static final Pattern EXCEPTION_HEADER =
      Pattern.compile("^[\\w.$]+(Exception|Error)\\b.*");

  private SupportDump() {}

  /** Ersetzt UUIDs, IPv4- und IPv6-Adressen und Discord-Webhook-Adressen. */
  public static String scrub(String text) {
    String out = WEBHOOK.matcher(text).replaceAll("<webhook>");
    out = URL_USERINFO.matcher(out).replaceAll("$1<credentials>@");
    out = URL_SECRET_PARAM.matcher(out).replaceAll("$1<hidden>");
    out = UUID_PATTERN.matcher(out).replaceAll("<uuid>");
    out = IPV4.matcher(out).replaceAll("<ip>");
    return IPV6.matcher(out).replaceAll("<ip>");
  }

  /**
   * Konfiguration als sortierte {@code schluessel: wert}-Zeilen. Werte zu Schluesseln mit Passwort,
   * Token, Webhook, Lizenz und aehnlichem werden durch {@code ***} ersetzt, alle anderen Werte
   * laufen durch {@link #scrub}.
   *
   * @param flat die Konfiguration als flache Zuordnung Schluessel zu Wert
   */
  public static List<String> configLines(Map<String, ?> flat) {
    Map<String, String> sorted = new TreeMap<>();
    flat.forEach((key, value) -> {
      String text = value == null ? "null" : String.valueOf(value);
      sorted.put(key, SECRET_KEY.matcher(key).matches() ? "***" : scrub(text));
    });
    List<String> lines = new ArrayList<>();
    sorted.forEach((key, value) -> lines.add(key + ": " + value));
    return lines;
  }

  /** Obergrenze fuer den gelesenen Teil des Serverlogs. */
  public static final int LOG_TAIL_BYTES = 2 * 1024 * 1024;

  /**
   * Liest hoechstens die letzten {@code maxBytes} Bytes einer Datei als Zeilen. Bei einer
   * groesseren Datei faellt die angeschnittene erste Zeile weg. Ein Serverlog kann sehr gross
   * werden, deshalb wird es nie ganz in den Speicher gelesen.
   */
  public static List<String> tailLines(java.nio.file.Path file, int maxBytes)
      throws java.io.IOException {
    try (java.io.RandomAccessFile in = new java.io.RandomAccessFile(file.toFile(), "r")) {
      long length = in.length();
      long start = Math.max(0, length - maxBytes);
      in.seek(start);
      byte[] bytes = new byte[(int) (length - start)];
      in.readFully(bytes);
      String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
      List<String> lines = new ArrayList<>(List.of(text.split("\\R", -1)));
      if (start > 0 && !lines.isEmpty()) {
        lines.remove(0);
      }
      if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
        lines.remove(lines.size() - 1);
      }
      return lines;
    }
  }

  /**
   * Die letzten {@code max} Zeilen des Serverlogs, die OrderHub betreffen: Zeilen mit WARN, ERROR,
   * SEVERE oder Exception, die OrderHub nennen, samt der folgenden Stacktrace-Zeilen.
   */
  public static List<String> relevantLogLines(List<String> log, int max) {
    List<String> picked = new ArrayList<>();
    boolean inTrace = false;
    for (String line : log) {
      String upper = line.toUpperCase(Locale.ROOT);
      boolean mentions = line.contains("OrderHub") || line.contains("dev.orderhub");
      boolean problem =
          upper.contains("WARN")
              || upper.contains("ERROR")
              || upper.contains("SEVERE")
              || line.contains("Exception");
      if (mentions && problem) {
        picked.add(scrub(line));
        inTrace = true;
      } else if (inTrace
          && (line.startsWith("\tat ")
              || line.startsWith("Caused by")
              || line.startsWith("        at ")
              || line.startsWith("\t... ")
              || EXCEPTION_HEADER.matcher(line).matches())) {
        picked.add(scrub(line));
      } else {
        inTrace = false;
      }
    }
    int from = Math.max(0, picked.size() - Math.max(1, max));
    return List.copyOf(picked.subList(from, picked.size()));
  }

  /** Setzt die Datei aus den Abschnitten zusammen. */
  public static String compose(
      Map<String, String> facts, List<String> plugins, List<String> config, List<String> errors) {
    StringBuilder out = new StringBuilder();
    out.append("OrderHub support file\n");
    out.append(
        "Check this file before you share it. Secrets, UUIDs and IP addresses are removed,\n");
    out.append("but server and plugin names stay.\n\n");
    out.append("== Facts ==\n");
    facts.forEach((k, v) -> out.append(k).append(": ").append(scrub(v)).append('\n'));
    out.append("\n== Plugins ==\n");
    plugins.forEach(p -> out.append(scrub(p)).append('\n'));
    out.append("\n== config.yml (secrets removed) ==\n");
    config.forEach(l -> out.append(l).append('\n'));
    out.append("\n== Recent OrderHub problems in the server log ==\n");
    if (errors.isEmpty()) {
      out.append("none found\n");
    }
    errors.forEach(l -> out.append(l).append('\n'));
    return out.toString();
  }
}
