package dev.orderhub.economy.vault;

import dev.orderhub.economy.EconomyBridge;
import java.util.Optional;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;

/** Einziger Einstieg in Vault. Nur aufrufen, wenn das Plugin Vault installiert ist. */
public final class VaultHook {

  private VaultHook() {}

  /**
   * Liefert die Bridge. Der Anbieter wird bei jedem Aufruf neu gesucht, weil Wirtschaftsplugins
   * sich auch nach dem Start dieses Plugins registrieren koennen. Fehlt er, melden alle Aufrufe
   * UNAVAILABLE.
   */
  public static Optional<EconomyBridge> create() {
    return Optional.of(
        new VaultEconomyBridge(
            () -> {
              RegisteredServiceProvider<Economy> current =
                  Bukkit.getServicesManager().getRegistration(Economy.class);
              return current == null ? null : current.getProvider();
            },
            Bukkit::getOfflinePlayer));
  }

  /** Ob gerade ein Economy-Anbieter registriert ist. */
  public static boolean providerPresent() {
    return Bukkit.getServicesManager().getRegistration(Economy.class) != null;
  }
}
