package dev.orderhub.placeholder;

import dev.orderhub.flow.Services;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

/**
 * PlaceholderAPI-Erweiterung {@code %orderhub_...%}. Antwortet nur aus dem Cache, nie mit einer
 * synchronen Datenbankabfrage. Fehlende Werte liefern {@code 0} bzw. {@code -}.
 */
@SuppressFBWarnings(
    value = "HE_INHERITS_EQUALS_USE_HASHCODE",
    justification = "equals ist in PlaceholderExpansion final, Identitaetsvergleich ist gewollt")
public final class OrderHubExpansion extends PlaceholderExpansion {

  private final String version;
  private final PlaceholderResolver resolver;

  public OrderHubExpansion(Services services, String version, PlaceholderCache cache) {
    this.version = version;
    this.resolver = new PlaceholderResolver(services, cache);
  }

  @Override
  public @NotNull String getIdentifier() {
    return "orderhub";
  }

  @Override
  public @NotNull String getAuthor() {
    return "OrderHub";
  }

  @Override
  public @NotNull String getVersion() {
    return version;
  }

  @Override
  public boolean persist() {
    return true;
  }

  @Override
  public String onRequest(OfflinePlayer player, @NotNull String params) {
    return resolver.resolve(player == null ? null : player.getUniqueId(), params);
  }
}
