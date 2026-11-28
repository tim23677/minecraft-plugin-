package dev.orderhub.rest;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Bindet {@link RestApi} an den im JDK enthaltenen HTTP-Server. Zwei Arbeitsthreads, nur GET, keine
 * Weiterleitungen, keine Cookies, alle Antworten ohne Zwischenspeicher.
 *
 * @since 2.0.0
 */
public final class RestServer implements AutoCloseable {

  private final HttpServer server;
  private final ExecutorService executor;

  private RestServer(HttpServer server, ExecutorService executor) {
    this.server = server;
    this.executor = executor;
  }

  /**
   * Startet den Server.
   *
   * @throws IOException wenn die Adresse nicht belegt werden kann
   */
  public static RestServer start(String bind, int port, RestApi api, Logger logger)
      throws IOException {
    HttpServer http = HttpServer.create(new InetSocketAddress(bind, port), 16);
    ExecutorService pool =
        Executors.newFixedThreadPool(
            2,
            r -> {
              Thread t = new Thread(r, "orderhub-rest");
              t.setDaemon(true);
              return t;
            });
    http.setExecutor(pool);
    http.createContext(
        "/",
        exchange -> {
          try {
            handle(exchange, api);
          } catch (RuntimeException e) {
            logger.log(Level.WARNING, "rest handler failed", e);
            send(exchange, 500, "{\"error\":\"internal error\"}");
          } finally {
            exchange.close();
          }
        });
    http.start();
    return new RestServer(http, pool);
  }

  private static void handle(HttpExchange exchange, RestApi api) throws IOException {
    String client = exchange.getRemoteAddress().getAddress().getHostAddress();
    var response =
        api.handle(
            exchange.getRequestMethod(),
            exchange.getRequestURI().getPath(),
            exchange.getRequestURI().getRawQuery(),
            exchange.getRequestHeaders().getFirst("Authorization"),
            client);
    send(exchange, response.status(), response.body());
  }

  private static void send(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    var headers = exchange.getResponseHeaders();
    headers.set("Content-Type", "application/json; charset=utf-8");
    headers.set("Cache-Control", "no-store");
    headers.set("X-Content-Type-Options", "nosniff");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  /** Port, auf dem der Server tatsaechlich lauscht (bei Port 0 der gewaehlte). */
  public int port() {
    return server.getAddress().getPort();
  }

  @Override
  public void close() {
    server.stop(0);
    executor.shutdownNow();
  }
}
