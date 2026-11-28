package dev.orderhub.config;

import dev.orderhub.money.Money;

/**
 * Gebuehren in Basispunkten (100 = 1 %).
 *
 * @param createFeeBp Erstellgebuehr auf den Escrow
 * @param salesTaxBp Verkaufssteuer auf die Bruttoauszahlung
 * @param exemptionEnabled ob eine Gebuehrenbefreiung ueberhaupt beachtet wird
 */
public record Fees(int createFeeBp, int salesTaxBp, boolean exemptionEnabled) {

  public Fees {
    if (createFeeBp < 0 || createFeeBp > 10_000 || salesTaxBp < 0 || salesTaxBp > 10_000) {
      throw new IllegalArgumentException("basis points out of range");
    }
  }

  public static Fees none() {
    return new Fees(0, 0, true);
  }

  /** Erstellgebuehr auf {@code escrow}. */
  public long createFee(long escrow, boolean exempt) {
    return exempt && exemptionEnabled ? 0L : Money.percentOf(escrow, createFeeBp);
  }

  /** Verkaufssteuer auf {@code gross}. */
  public long salesTax(long gross, boolean exempt) {
    return exempt && exemptionEnabled ? 0L : Money.percentOf(gross, salesTaxBp);
  }
}
