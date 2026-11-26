package dev.orderhub.economy;

import java.util.UUID;

/** Platzhalter ohne Wirtschaftsanbieter: Alles meldet UNAVAILABLE, es wird nie Geld bewegt. */
public final class UnavailableEconomy implements EconomyBridge {

  @Override
  public EconomyResult has(UUID player, long minor) {
    return EconomyResult.UNAVAILABLE;
  }

  @Override
  public EconomyResult withdraw(UUID player, long minor, String reference) {
    return EconomyResult.UNAVAILABLE;
  }

  @Override
  public EconomyResult deposit(UUID player, long minor, String reference) {
    return EconomyResult.UNAVAILABLE;
  }
}
