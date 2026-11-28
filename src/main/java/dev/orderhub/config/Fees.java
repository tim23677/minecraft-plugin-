package dev.orderhub.config;

import dev.orderhub.money.Money;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Gebuehren in Basispunkten (100 = 1 %).
 *
 * <p>Neben dem Grundsatz gibt es Stufen je Permission (die niedrigste zutreffende Gebuehr gewinnt)
 * und Zeitfenster, die die Grundgebuehr oder die Steuer voruebergehend ersetzen.
 *
 * @param createFeeBp Erstellgebuehr auf den Escrow
 * @param salesTaxBp Verkaufssteuer auf die Bruttoauszahlung
 * @param exemptionEnabled ob eine Gebuehrenbefreiung ueberhaupt beachtet wird
 * @param tiers Gebuehrenstufen je Permission
 * @param windows Zeitfenster mit abweichenden Saetzen
 * @param zone Zeitzone der Fenster
 */
public record Fees(
    int createFeeBp,
    int salesTaxBp,
    boolean exemptionEnabled,
    List<Tier> tiers,
    List<Window> windows,
    ZoneId zone) {

  /** Gebuehrenstufe: wer die Permission hat, zahlt hoechstens diese Erstellgebuehr. */
  public record Tier(String permission, int createFeeBp) {
    public Tier {
      if (permission == null || permission.isBlank() || createFeeBp < 0 || createFeeBp > 10_000) {
        throw new IllegalArgumentException("tier");
      }
    }
  }

  /**
   * Zeitfenster, in dem andere Saetze gelten. {@code null} laesst den jeweiligen Satz unveraendert.
   * Ist {@code from} nach {@code to}, reicht das Fenster ueber Mitternacht.
   */
  public record Window(
      String name,
      Set<DayOfWeek> days,
      LocalTime from,
      LocalTime to,
      Integer createFeeBp,
      Integer salesTaxBp) {
    public Window {
      days = Set.copyOf(days);
      if ((createFeeBp != null && (createFeeBp < 0 || createFeeBp > 10_000))
          || (salesTaxBp != null && (salesTaxBp < 0 || salesTaxBp > 10_000))) {
        throw new IllegalArgumentException("window rate");
      }
    }

    boolean active(java.time.ZonedDateTime at) {
      if (!days.isEmpty() && !days.contains(at.getDayOfWeek())) {
        return false;
      }
      LocalTime now = at.toLocalTime();
      return from.isAfter(to)
          ? !now.isBefore(from) || !now.isAfter(to)
          : !now.isBefore(from) && !now.isAfter(to);
    }
  }

  public Fees {
    if (createFeeBp < 0 || createFeeBp > 10_000 || salesTaxBp < 0 || salesTaxBp > 10_000) {
      throw new IllegalArgumentException("basis points out of range");
    }
    tiers = List.copyOf(tiers);
    windows = List.copyOf(windows);
    zone = zone == null ? ZoneId.systemDefault() : zone;
  }

  /** Ohne Stufen und Zeitfenster. */
  public Fees(int createFeeBp, int salesTaxBp, boolean exemptionEnabled) {
    this(createFeeBp, salesTaxBp, exemptionEnabled, List.of(), List.of(), ZoneId.systemDefault());
  }

  public static Fees none() {
    return new Fees(0, 0, true);
  }

  private Window activeWindow(Instant at, boolean forCreate) {
    var zoned = at.atZone(zone);
    for (Window w : windows) {
      if (w.active(zoned) && (forCreate ? w.createFeeBp() : w.salesTaxBp()) != null) {
        return w;
      }
    }
    return null;
  }

  /** Erstellgebuehr in bp ohne Stufen: Grundsatz oder ein aktives Zeitfenster. */
  public int baseCreateFeeBpAt(Instant at) {
    Window w = activeWindow(at, true);
    return w == null ? createFeeBp : w.createFeeBp();
  }

  /** Erstellgebuehr in bp fuer jemanden mit diesen Permissions: niedrigste zutreffende Stufe. */
  public int createFeeBpAt(Predicate<String> hasPermission, Instant at) {
    int best = baseCreateFeeBpAt(at);
    for (Tier tier : tiers) {
      if (tier.createFeeBp() < best && hasPermission.test(tier.permission())) {
        best = tier.createFeeBp();
      }
    }
    return best;
  }

  /** Ob {@code bp} ein Satz ist, den diese Konfiguration zu {@code at} vergibt. */
  public boolean isOfferedCreateFeeBp(int bp, Instant at) {
    if (bp == baseCreateFeeBpAt(at)) {
      return true;
    }
    for (Tier tier : tiers) {
      if (tier.createFeeBp() == bp && tier.createFeeBp() < baseCreateFeeBpAt(at)) {
        return true;
      }
    }
    return false;
  }

  /** Verkaufssteuer in bp zu {@code at}. */
  public int salesTaxBpAt(Instant at) {
    Window w = activeWindow(at, false);
    return w == null ? salesTaxBp : w.salesTaxBp();
  }

  /** Erstellgebuehr auf {@code escrow} jetzt, ohne Stufen. */
  public long createFee(long escrow, boolean exempt) {
    return createFee(escrow, exempt, baseCreateFeeBpAt(Instant.now()));
  }

  /** Erstellgebuehr auf {@code escrow} mit dem Satz {@code bp}. */
  public long createFee(long escrow, boolean exempt, int bp) {
    return exempt && exemptionEnabled ? 0L : Money.percentOf(escrow, bp);
  }

  /** Verkaufssteuer auf {@code gross} jetzt. */
  public long salesTax(long gross, boolean exempt) {
    return exempt && exemptionEnabled ? 0L : Money.percentOf(gross, salesTaxBpAt(Instant.now()));
  }
}
