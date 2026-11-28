package dev.orderhub.service;

import dev.orderhub.discord.DiscordService;
import dev.orderhub.market.MarketMath;
import dev.orderhub.market.OpenOrderView;
import dev.orderhub.market.Trade;
import dev.orderhub.money.Money;
import dev.orderhub.storage.InsightsRepository;
import dev.orderhub.storage.Storage;
import dev.orderhub.tx.DeliveryCommitted;
import dev.orderhub.tx.JournalEntry;
import dev.orderhub.tx.TxState;
import dev.orderhub.tx.TxType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.IntSupplier;

/**
 * Statistik, Marktanalyse, Favoriten und Benachrichtigungen. Alles hier beruht auf echten
 * OrderHub-Daten (abgeschlossene Lieferungen), nichts wird geschaetzt oder erfunden. Blockiert,
 * deshalb nie auf einem Minecraft-Thread aufrufen.
 */
public final class InsightsService {

  /** Marktbild eines Items fuer einen Zeitraum. */
  public record MarketView(
      String itemKey,
      int days,
      Optional<MarketMath.TradeSummary> trades,
      Optional<MarketMath.DemandSummary> demand,
      Optional<MarketMath.Recommendation> recommendation) {}

  private final Storage storage;
  private final DiscordService discord;
  private final IntSupplier alertCooldownSeconds;
  private final IntSupplier minTradesForRecommendation;
  private volatile java.util.function.Predicate<UUID> online = id -> false;

  /**
   * Legt fest, ob ein Spieler gerade online ist. Nur fuer Offline-Spieler werden Hinweise "waehrend
   * du weg warst" gespeichert. Ohne Angabe gelten alle als offline.
   */
  public void onlineCheck(java.util.function.Predicate<UUID> check) {
    this.online = check;
  }

  public InsightsService(
      Storage storage,
      DiscordService discord,
      IntSupplier alertCooldownSeconds,
      IntSupplier minTradesForRecommendation) {
    this.storage = storage;
    this.discord = discord;
    this.alertCooldownSeconds = alertCooldownSeconds;
    this.minTradesForRecommendation = minTradesForRecommendation;
  }

  /**
   * Neuer Auftrag: Statistik, Preisalarme. Liefert die Spieler, fuer die ein Alarm als
   * Benachrichtigung gespeichert wurde (Mehrfachalarme fuer denselben Auftrag werden verhindert).
   */
  public List<UUID> orderCreated(
      long orderId, UUID owner, String itemKey, long unitPrice, int quantity) {
    List<UUID> notified =
        storage
            .transact(uow -> {
              long now = uow.now();
              uow.insights().recordOrderCreated(owner, now);
              long cooldownUntil = now - alertCooldownSeconds.getAsInt() * 1000L;
              List<UUID> hits = new ArrayList<>();
              for (InsightsRepository.Favorite f :
                  uow.insights().alertsFor(itemKey, unitPrice, cooldownUntil)) {
                if (f.player().equals(owner)) {
                  continue;
                }
                String payload =
                    itemKey + "|" + quantity + "|" + Money.format(unitPrice) + "|" + orderId;
                if (uow.insights()
                    .addNote(f.player(), "price-alert", payload, "alert:" + orderId, now)) {
                  uow.insights().touchAlert(f.player(), itemKey, now);
                  hits.add(f.player());
                }
              }
              return hits;
            })
            .join();
    long total = Money.multiply(unitPrice, quantity);
    if (total >= discord.largeOrderThreshold()) {
      discord.publish(
          DiscordService.Event.LARGE_ORDER,
          "Large order #" + orderId,
          quantity
              + "x "
              + itemKey
              + " at "
              + Money.format(unitPrice)
              + " each, total "
              + Money.format(total));
    }
    return notified;
  }

  /** Abgeschlossene Lieferung: Handelsverlauf und Statistik, idempotent ueber die Journal-Id. */
  public void deliveryCommitted(DeliveryCommitted e) {
    storage
        .transact(uow -> {
          long now = uow.now();
          boolean fresh =
              uow.insights()
                  .recordTrade(
                      e.journalId(), e.orderId(), e.itemKey(), e.unitPrice(), e.quantity(), now);
          if (fresh) {
            uow.insights()
                .recordDelivery(
                    e.supplier(), e.owner(), e.quantity(), e.gross(), e.netPayout(), now);
            if (e.orderCompleted()) {
              uow.insights().recordOrderCompleted(e.owner(), now);
            }
            if (!online.test(e.owner())) {
              uow.insights()
                  .addNote(
                      e.owner(),
                      "delivered",
                      e.itemKey() + "|" + e.quantity() + "|" + e.orderId(),
                      "deliv:" + e.journalId(),
                      now);
            }
          }
          return null;
        })
        .join();
    if (e.orderCompleted()) {
      discord.publish(
          DiscordService.Event.ORDER_COMPLETED,
          "Order #" + e.orderId() + " completed",
          e.itemKey() + " fully delivered");
    }
  }

  /**
   * Ein Auftrag ist abgelaufen: Hinweis fuer den Auftraggeber, wenn er offline ist. Idempotent
   * ueber die Auftrags-Id.
   */
  public void noteExpired(long orderId) {
    storage
        .transact(uow -> {
          var order = uow.orders().find(orderId);
          if (order.isPresent() && !online.test(order.get().owner())) {
            uow.insights()
                .addNote(
                    order.get().owner(),
                    "expired",
                    order.get().item().itemKey() + "|" + order.get().remaining() + "|" + orderId,
                    "exp:" + orderId,
                    uow.now());
          }
          return null;
        })
        .join();
  }

  public Optional<InsightsRepository.PlayerStats> stats(UUID player) {
    return storage.transact(uow -> uow.insights().stats(player)).join();
  }

  /** Letzte Lieferungen, an denen der Spieler beteiligt war (als Lieferant). */
  public List<JournalEntry> deliveryHistory(UUID player, int limit) {
    return storage
        .transact(uow ->
            uow.journal().findByPlayer(player, 0, Math.max(1, limit) * 4).stream()
                .filter(j -> j.type() == TxType.DELIVERY)
                .filter(j -> j.state() != TxState.FAILED)
                .limit(limit)
                .toList())
        .join();
  }

  public MarketView market(String itemKey, int days, long nowMillis) {
    long since = nowMillis - Duration.ofDays(days).toMillis();
    return storage
        .transact(uow -> {
          List<Trade> trades =
              uow.insights().tradesSince(itemKey, since, 5000).stream()
                  .map(t -> new Trade(t.unitPrice(), t.quantity(), t.at()))
                  .toList();
          List<OpenOrderView> open = uow.insights().openDemand(itemKey, 5000);
          return new MarketView(
              itemKey,
              days,
              MarketMath.summarizeTrades(trades, since),
              MarketMath.summarizeDemand(open),
              MarketMath.recommend(trades, since, minTradesForRecommendation.getAsInt()));
        })
        .join();
  }

  /** Rangliste der Spieler nach einer Kennzahl. */
  public List<InsightsRepository.TopRow> top(InsightsRepository.TopStat stat, int n) {
    return storage.transact(uow -> uow.insights().top(stat, n)).join();
  }

  /** Durchschnittspreis je Tag der letzten {@code days} Tage, Tage ohne Handel haben 0. */
  public long[] priceTrend(String itemKey, int days, long nowMillis) {
    long since = nowMillis - Duration.ofDays(days).toMillis();
    return storage
        .transact(uow -> {
          List<Trade> trades =
              uow.insights().tradesSince(itemKey, since, 5000).stream()
                  .map(t -> new Trade(t.unitPrice(), t.quantity(), t.at()))
                  .toList();
          return MarketMath.dailyAverages(trades, nowMillis, days);
        })
        .join();
  }

  public List<String> topItems(int n) {
    return storage.transact(uow -> uow.insights().activeItemKeys(n)).join();
  }

  public boolean addFavorite(UUID player, String itemKey) {
    return storage.transact(uow -> uow.insights().addFavorite(player, itemKey, uow.now())).join();
  }

  public boolean removeFavorite(UUID player, String itemKey) {
    return storage.transact(uow -> uow.insights().removeFavorite(player, itemKey)).join();
  }

  public List<InsightsRepository.Favorite> favorites(UUID player) {
    return storage.transact(uow -> uow.insights().favorites(player)).join();
  }

  public boolean setAlert(UUID player, String itemKey, Long minPrice) {
    return storage.transact(uow -> uow.insights().setAlert(player, itemKey, minPrice)).join();
  }

  /**
   * Holt offene Benachrichtigungen und markiert sie als zugestellt (Zusammenfassung beim Login).
   */
  public List<InsightsRepository.Note> takeNotes(UUID player, int limit) {
    return storage.transact(uow -> claim(uow, uow.insights().pendingNotes(player, limit))).join();
  }

  /**
   * Gibt beanspruchte Hinweise wieder frei, wenn der Spieler sie nicht mehr erreicht hat (zum
   * Beispiel Abgang zwischen Beanspruchen und Anzeigen). Sie erscheinen beim naechsten Login.
   */
  public void releaseNotes(List<InsightsRepository.Note> notes) {
    if (notes.isEmpty()) {
      return;
    }
    List<Long> ids = notes.stream().map(InsightsRepository.Note::id).toList();
    storage
        .transact(uow -> {
          uow.insights().releaseNotes(ids);
          return null;
        })
        .join();
  }

  /**
   * Holt offene Hinweise fuer die Spieler, die gerade auf diesem Server online sind (wird
   * zeitgesteuert aufgerufen). Hinweise, die ein anderer Server in derselben Zeit beansprucht hat,
   * fehlen hier.
   */
  public List<InsightsRepository.Note> takeNotesForOnline(
      java.util.Collection<UUID> players, int limit) {
    return storage
        .transact(uow -> claim(uow, uow.insights().pendingNotesForPlayers(players, limit)))
        .join();
  }

  private static List<InsightsRepository.Note> claim(
      dev.orderhub.storage.UnitOfWork uow, List<InsightsRepository.Note> pending) {
    if (pending.isEmpty()) {
      return pending;
    }
    List<Long> claimed =
        uow.insights()
            .claimNotes(pending.stream().map(InsightsRepository.Note::id).toList(), uow.now());
    return pending.stream().filter(n -> claimed.contains(n.id())).toList();
  }

  /** Bereinigt alten Handelsverlauf. */
  public int purge(int retentionDays, long nowMillis) {
    long cutoff = nowMillis - Duration.ofDays(retentionDays).toMillis();
    return storage.transact(uow -> uow.insights().purgeTradesBefore(cutoff)).join();
  }
}
