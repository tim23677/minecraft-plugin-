package dev.orderhub.economy.vault;

import dev.orderhub.economy.EconomyBridge;
import dev.orderhub.economy.EconomyOutcomeUnknownException;
import dev.orderhub.economy.EconomyResult;
import dev.orderhub.money.Money;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;

/**
 * Einziger Ort, an dem Vault-Typen vorkommen. Der Provider wird bei jedem Aufruf neu erfragt
 * (Provider kann wechseln oder ausfallen). Erfolg gilt nur bei bestaetigtem {@code SUCCESS}.
 */
public final class VaultEconomyBridge implements EconomyBridge {

  private final Supplier<Economy> provider;
  private final Function<UUID, OfflinePlayer> players;

  /**
   * @param provider liefert den aktuellen Provider oder {@code null}
   * @param players loest eine UUID in einen (ggf. Offline-)Spieler auf
   */
  public VaultEconomyBridge(Supplier<Economy> provider, Function<UUID, OfflinePlayer> players) {
    this.provider = provider;
    this.players = players;
  }

  private Economy economy() {
    try {
      return provider.get();
    } catch (RuntimeException | LinkageError e) {
      return null;
    }
  }

  @Override
  public EconomyResult has(UUID player, long minor) {
    Economy eco = economy();
    if (eco == null) {
      return EconomyResult.UNAVAILABLE;
    }
    double amount;
    try {
      amount = Money.toDouble(minor);
    } catch (ArithmeticException e) {
      return EconomyResult.FAILED;
    }
    try {
      return eco.has(players.apply(player), amount) ? EconomyResult.OK : EconomyResult.INSUFFICIENT;
    } catch (RuntimeException e) {
      return EconomyResult.UNAVAILABLE;
    }
  }

  @Override
  public EconomyResult withdraw(UUID player, long minor, String reference) {
    Economy eco = economy();
    if (eco == null) {
      return EconomyResult.UNAVAILABLE;
    }
    double amount;
    try {
      amount = Money.toDouble(minor);
    } catch (ArithmeticException e) {
      return EconomyResult.FAILED;
    }
    if (amount <= 0) {
      return EconomyResult.FAILED;
    }
    EconomyResult enough = has(player, minor);
    if (enough != EconomyResult.OK) {
      return enough;
    }
    EconomyResponse response;
    try {
      response = eco.withdrawPlayer(players.apply(player), amount);
    } catch (RuntimeException e) {
      throw new EconomyOutcomeUnknownException("withdraw " + reference, e);
    }
    return map(response, "withdraw " + reference);
  }

  @Override
  public EconomyResult deposit(UUID player, long minor, String reference) {
    Economy eco = economy();
    if (eco == null) {
      return EconomyResult.UNAVAILABLE;
    }
    double amount;
    try {
      amount = Money.toDouble(minor);
    } catch (ArithmeticException e) {
      return EconomyResult.FAILED;
    }
    if (amount <= 0) {
      return EconomyResult.FAILED;
    }
    EconomyResponse response;
    try {
      response = eco.depositPlayer(players.apply(player), amount);
    } catch (RuntimeException e) {
      throw new EconomyOutcomeUnknownException("deposit " + reference, e);
    }
    return map(response, "deposit " + reference);
  }

  private static EconomyResult map(EconomyResponse response, String what) {
    if (response == null || response.type == null) {
      throw new EconomyOutcomeUnknownException(what + ": no response", null);
    }
    return switch (response.type) {
      case SUCCESS -> EconomyResult.OK;
      case FAILURE -> EconomyResult.FAILED;
      case NOT_IMPLEMENTED -> EconomyResult.UNAVAILABLE;
    };
  }
}
