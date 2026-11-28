package dev.orderhub.rest;

import dev.orderhub.domain.Order;
import dev.orderhub.domain.OrderQuery;
import dev.orderhub.domain.OrderStatus;
import dev.orderhub.flow.Services;
import dev.orderhub.storage.InsightsRepository.TopStat;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * Lesende JSON-Schnittstelle fuer Marktdaten (Webseiten, Bots). Keine Schreibzugriffe, keine
 * Spielerdaten ausser anonymen Ranglistenwerten. Jede Anfrage braucht den Token, schlechte Versuche
 * und Anfragen werden je Client begrenzt. Die Klasse kennt keine Sockets und ist deshalb direkt
 * testbar.
 *
 * @since 2.0.0
 */
public final class RestApi {

  /** Antwort der Schnittstelle. */
  public record Response(int status, String body) {}

  private static final long WINDOW_MILLIS = 60_000;
  private static final int MAX_FAILED_PER_WINDOW = 10;
  private static final int MAX_LIMIT = 100;

  private final Services s;
  private final byte[] tokenHash;
  private final int requestsPerMinute;
  private final LongSupplier clock;
  private final Supplier<String> serverId;
  private final Map<String, long[]> windows = new ConcurrentHashMap<>();
  private final Map<String, long[]> failures = new ConcurrentHashMap<>();

  public RestApi(Services services, String token, int requestsPerMinute, LongSupplier clock) {
    if (token == null || token.length() < 16) {
      throw new IllegalArgumentException("token must have at least 16 characters");
    }
    this.s = services;
    this.tokenHash = sha256(token);
    this.requestsPerMinute = Math.max(1, requestsPerMinute);
    this.clock = clock;
    this.serverId = () -> s.settings().get().serverId();
  }

  private static byte[] sha256(String text) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * Zaehler je Client im laufenden Fenster. Gibt {@code true}, solange das Limit nicht erreicht
   * ist.
   */
  private boolean allow(Map<String, long[]> map, String client, int limit) {
    long now = clock.getAsLong();
    boolean[] ok = {false};
    map.compute(
        client,
        (k, w) -> {
          long[] window = w;
          if (window == null || now - window[0] >= WINDOW_MILLIS) {
            window = new long[] {now, 0};
          }
          if (window[1] < limit) {
            window[1]++;
            ok[0] = true;
          }
          return window;
        });
    if (map.size() > 4096) {
      map.values().removeIf(w -> now - w[0] >= WINDOW_MILLIS);
    }
    return ok[0];
  }

  /**
   * @param authorization Wert des Headers {@code Authorization} oder {@code null}
   * @param client Kennung des Aufrufers fuer die Begrenzung, zum Beispiel die IP-Adresse
   */
  public Response handle(
      String method, String path, String rawQuery, String authorization, String client) {
    if (!allow(windows, client, requestsPerMinute)) {
      return error(429, "rate limit exceeded");
    }
    if (!authorized(authorization)) {
      if (!allow(failures, client, MAX_FAILED_PER_WINDOW)) {
        return error(429, "too many failed attempts");
      }
      return error(401, "unauthorized");
    }
    if (!"GET".equals(method)) {
      return error(405, "only GET is supported");
    }
    Map<String, String> query = parseQuery(rawQuery);
    try {
      return switch (path) {
        case "/v1/health" ->
            new Response(200, "{\"status\":\"ok\",\"server\":" + Json.str(serverId.get()) + "}");
        case "/v1/market/orders" -> orders(query);
        case "/v1/market/price" -> price(query);
        case "/v1/top" -> top(query);
        default -> error(404, "not found");
      };
    } catch (IllegalArgumentException e) {
      return error(400, e.getMessage());
    } catch (RuntimeException e) {
      s.logger().log(Level.WARNING, "rest request failed", e);
      return error(503, "temporarily unavailable");
    }
  }

  private boolean authorized(String header) {
    if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
      return false;
    }
    return MessageDigest.isEqual(sha256(header.substring(7).trim()), tokenHash);
  }

  static Map<String, String> parseQuery(String raw) {
    Map<String, String> out = new HashMap<>();
    if (raw == null || raw.isEmpty() || raw.length() > 512) {
      return out;
    }
    for (String pair : raw.split("&")) {
      int eq = pair.indexOf('=');
      String key = eq < 0 ? pair : pair.substring(0, eq);
      String value = eq < 0 ? "" : pair.substring(eq + 1);
      try {
        out.put(
            URLDecoder.decode(key, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT),
            URLDecoder.decode(value, StandardCharsets.UTF_8));
      } catch (IllegalArgumentException e) {
        // Fehlerhaft kodierter Parameter: wird ignoriert.
      }
    }
    return out;
  }

  private static String itemKey(Map<String, String> q) {
    String item = q.get("item");
    if (item == null || item.isBlank()) {
      return null;
    }
    String key = item.trim().toLowerCase(Locale.ROOT);
    if (!key.matches("[a-z0-9_.-]+(:[a-z0-9_./-]+)?")) {
      throw new IllegalArgumentException("invalid item");
    }
    return key.contains(":") ? key : "minecraft:" + key;
  }

  private static int intParam(Map<String, String> q, String name, int def, int min, int max) {
    String v = q.get(name);
    if (v == null) {
      return def;
    }
    if (!v.matches("[0-9]{1,9}")) {
      throw new IllegalArgumentException("invalid " + name);
    }
    return Math.max(min, Math.min(max, Integer.parseInt(v)));
  }

  private Response orders(Map<String, String> q) {
    String item = itemKey(q);
    int limit = intParam(q, "limit", 50, 1, MAX_LIMIT);
    OrderQuery.Sort sort = OrderQuery.Sort.NEWEST;
    if (q.containsKey("sort")) {
      try {
        sort = OrderQuery.Sort.valueOf(q.get("sort").toUpperCase(Locale.ROOT).replace('-', '_'));
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException("invalid sort");
      }
    }
    Long min = q.containsKey("min") ? price(q.get("min")) : null;
    Long max = q.containsKey("max") ? price(q.get("max")) : null;
    if (min != null && max != null && min > max) {
      throw new IllegalArgumentException("min must not exceed max");
    }
    OrderQuery query =
        new OrderQuery(null, item, null, OrderStatus.ACTIVE, sort, 0, limit, null, null, min, max);
    List<Order> rows = s.storage().transact(uow -> uow.orders().search(query)).join();
    StringBuilder out = new StringBuilder("{\"orders\":[");
    boolean first = true;
    for (Order o : rows) {
      if (!first) {
        out.append(',');
      }
      first = false;
      out.append("{\"id\":")
          .append(o.id())
          .append(",\"item\":")
          .append(Json.str(o.item().itemKey()))
          .append(",\"unit_price\":")
          .append(o.unitPrice())
          .append(",\"remaining\":")
          .append(o.remaining())
          .append(",\"total\":")
          .append(o.quantityTotal())
          .append(",\"expires_at\":")
          .append(o.expiresAt())
          .append(",\"category\":")
          .append(Json.str(o.category()))
          .append('}');
    }
    return new Response(200, out.append("]}").toString());
  }

  private static long price(String raw) {
    try {
      long v = dev.orderhub.money.Money.parse(raw);
      if (v < 0) {
        throw new IllegalArgumentException("invalid price");
      }
      return v;
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("invalid price");
    }
  }

  private Response price(Map<String, String> q) {
    String item = itemKey(q);
    if (item == null) {
      throw new IllegalArgumentException("item is required");
    }
    int days = intParam(q, "days", 7, 1, 90);
    var view = s.insights().market(item, days, clock.getAsLong());
    StringBuilder out =
        new StringBuilder("{\"item\":").append(Json.str(item)).append(",\"days\":").append(days);
    out.append(",\"demand\":");
    view.demand()
        .ifPresentOrElse(
            d ->
                out.append("{\"orders\":")
                    .append(d.orders())
                    .append(",\"open_units\":")
                    .append(d.openUnits())
                    .append(",\"lowest\":")
                    .append(d.lowestUnitPrice())
                    .append(",\"highest\":")
                    .append(d.highestUnitPrice())
                    .append(",\"median\":")
                    .append(d.medianUnitPrice())
                    .append(",\"average\":")
                    .append(d.averageUnitPrice())
                    .append('}'),
            () -> out.append("null"));
    out.append(",\"trades\":");
    view.trades()
        .ifPresentOrElse(
            t ->
                out.append("{\"count\":")
                    .append(t.trades())
                    .append(",\"units\":")
                    .append(t.volumeUnits())
                    .append(",\"volume\":")
                    .append(t.volumeMoney())
                    .append(",\"median\":")
                    .append(t.medianUnitPrice())
                    .append(",\"average\":")
                    .append(t.averageUnitPrice())
                    .append('}'),
            () -> out.append("null"));
    return new Response(200, out.append('}').toString());
  }

  private Response top(Map<String, String> q) {
    TopStat stat;
    try {
      stat = TopStat.valueOf(q.getOrDefault("stat", "earned").toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("invalid stat");
    }
    int n = intParam(q, "n", 10, 1, 25);
    var rows = s.insights().top(stat, n);
    StringBuilder out =
        new StringBuilder("{\"stat\":")
            .append(Json.str(stat.name().toLowerCase(Locale.ROOT)))
            .append(",\"entries\":[");
    int rank = 1;
    for (var row : rows) {
      if (rank > 1) {
        out.append(',');
      }
      out.append("{\"rank\":").append(rank++).append(",\"value\":").append(row.value()).append('}');
    }
    return new Response(200, out.append("]}").toString());
  }

  private static Response error(int status, String message) {
    return new Response(status, "{\"error\":" + Json.str(message) + "}");
  }
}
