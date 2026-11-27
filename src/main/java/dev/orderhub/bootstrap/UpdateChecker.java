package dev.orderhub.bootstrap;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Optionale Update-Pruefung gegen die GitHub-Releases eines Repositories. Standardmaessig aus
 * ({@code updates.check: false}). Sendet keine Daten ausser der Anfrage selbst. Blockiert: nur auf
 * dem Async-Scheduler aufrufen.
 */
public final class UpdateChecker {

  private static final Pattern REPOSITORY =
      Pattern.compile("[A-Za-z0-9][A-Za-z0-9-]{0,38}/(?!\\.{1,2}$)[A-Za-z0-9_.-]{1,100}");

  private final HttpClient client;

  public UpdateChecker() {
    this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
  }

  UpdateChecker(HttpClient client) {
    this.client = client;
  }

  /** Ob {@code owner/name} ein zulaessiger Repository-Name ist. */
  public static boolean validRepository(String repository) {
    return repository != null && REPOSITORY.matcher(repository).matches();
  }

  /** URL der neuesten Version eines Repositories. */
  public static URI latestReleaseUrl(String repository) {
    if (!validRepository(repository)) {
      throw new IllegalArgumentException("repository must look like owner/name");
    }
    return URI.create("https://api.github.com/repos/" + repository + "/releases/latest");
  }

  /**
   * Liefert den Tag der neuesten Version, wenn er neuer ist als {@code current}. Fehler (Netz,
   * Format) ergeben leer und stoeren den Betrieb nie.
   */
  private static final java.util.regex.Pattern SAFE_TAG =
      java.util.regex.Pattern.compile("[vV]?\\d{1,6}(\\.\\d{1,6}){0,3}([-+][A-Za-z0-9.]{1,32})?");

  public Optional<String> newerVersion(URI releaseUrl, String current) {
    try {
      HttpRequest request =
          HttpRequest.newBuilder(releaseUrl)
              .timeout(Duration.ofSeconds(10))
              .header("Accept", "application/vnd.github+json")
              .header("User-Agent", "OrderHub-UpdateCheck")
              .GET()
              .build();
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        return Optional.empty();
      }
      JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
      String tag = json.get("tag_name").getAsString();
      if (!SAFE_TAG.matcher(tag).matches()) {
        return Optional.empty();
      }
      return isNewer(current, tag) ? Optional.of(tag) : Optional.empty();
    } catch (IOException | RuntimeException e) {
      return Optional.empty();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Optional.empty();
    }
  }

  /** Vergleicht numerische Versionsteile ({@code v1.2.3} gegen {@code 1.10.0}). */
  public static boolean isNewer(String current, String candidate) {
    int[] a = parts(current);
    int[] b = parts(candidate);
    for (int i = 0; i < Math.max(a.length, b.length); i++) {
      int x = i < a.length ? a[i] : 0;
      int y = i < b.length ? b[i] : 0;
      if (y != x) {
        return y > x;
      }
    }
    return false;
  }

  private static int[] parts(String version) {
    String core = version.replaceFirst("^[vV]", "").split("[-+]", 2)[0];
    String[] pieces = core.split("\\.");
    int[] out = new int[pieces.length];
    for (int i = 0; i < pieces.length; i++) {
      try {
        out[i] = Integer.parseInt(pieces[i]);
      } catch (NumberFormatException e) {
        out[i] = 0;
      }
    }
    return out;
  }
}
