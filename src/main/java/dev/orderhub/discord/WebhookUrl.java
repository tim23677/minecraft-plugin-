package dev.orderhub.discord;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** Prueft Webhook-URLs: nur https, nur Discord-Hosts, keine Zugangsdaten in der URL, kein Port. */
public final class WebhookUrl {

  private static final Set<String> HOSTS =
      Set.of("discord.com", "discordapp.com", "canary.discord.com", "ptb.discord.com");

  private WebhookUrl() {}

  public static Optional<URI> validate(String raw) {
    if (raw == null || raw.isBlank() || raw.length() > 300) {
      return Optional.empty();
    }
    try {
      URI uri = new URI(raw.trim());
      boolean ok =
          "https".equals(uri.getScheme())
              && uri.getHost() != null
              && HOSTS.contains(uri.getHost().toLowerCase(Locale.ROOT))
              && uri.getUserInfo() == null
              && uri.getPort() == -1
              && uri.getRawPath() != null
              && uri.getRawPath().startsWith("/api/webhooks/")
              && uri.getRawFragment() == null;
      return ok ? Optional.of(uri) : Optional.empty();
    } catch (URISyntaxException e) {
      return Optional.empty();
    }
  }

  /** Kurzform fuer Logs ohne Token. */
  public static String redact(String raw) {
    return validate(raw).map(u -> u.getHost() + "/api/webhooks/***").orElse("<invalid webhook>");
  }
}
