package dev.orderhub.flow;

import dev.orderhub.bootstrap.Messages;
import dev.orderhub.domain.ItemSnapshot;
import dev.orderhub.storage.ParcelRepository;
import dev.orderhub.storage.UnitOfWork;
import dev.orderhub.tx.ParcelTransferEngine;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Abholung persistenter Itempakete (Claims fuer Auftraggeber, Returns fuer Lieferanten). Ablauf je
 * Paket: Kapazitaet im Entity-Kontext, dann Reservierung und Uebergabe-Marker in der Datenbank,
 * dann Items ins Inventar (Entity-Kontext), dann Abschluss. Fehler lassen Pakete gespeichert.
 */
public final class ParcelFlow {

  /** Welche Paketart. */
  public enum Kind {
    CLAIM,
    RETURN
  }

  private final Services s;
  private final Kind kind;

  public ParcelFlow(Services services, Kind kind) {
    this.s = services;
    this.kind = kind;
  }

  private ParcelTransferEngine engine() {
    return kind == Kind.CLAIM ? s.claims() : s.returns();
  }

  private Function<UnitOfWork, ParcelRepository> repo() {
    return kind == Kind.CLAIM ? UnitOfWork::claims : UnitOfWork::returns;
  }

  /** Listet verfuegbare Pakete des Spielers (asynchron), Ergebnis im Entity-Kontext. */
  public void list(
      Player player,
      int offset,
      int limit,
      java.util.function.Consumer<List<ParcelRepository.Parcel>> done) {
    UUID id = player.getUniqueId();
    Async.supply(
        s.scheduler(),
        s.logger(),
        player,
        () ->
            s.storage().transact(uow -> repo().apply(uow).listAvailable(id, offset, limit)).join(),
        done,
        orphan -> {});
  }

  /** Holt alle verfuegbaren Pakete nacheinander ab (bis Inventar voll ist). */
  public void claimAll(Player player) {
    list(
        player,
        0,
        50,
        parcels -> {
          if (parcels.isEmpty()) {
            player.sendMessage(s.messages().prefixed("claim.nothing"));
            return;
          }
          claimSequential(player, parcels, 0);
        });
  }

  private void claimSequential(Player player, List<ParcelRepository.Parcel> parcels, int index) {
    if (index >= parcels.size() || !player.isOnline()) {
      return;
    }
    claim(
        player,
        parcels.get(index),
        moved -> claimSequential(player, parcels, index + (moved ? 1 : parcels.size())));
  }

  /** Holt ein Paket ab. {@code after} erhaelt true, wenn das Paket vollstaendig abgeholt wurde. */
  public void claim(
      Player player, ParcelRepository.Parcel parcel, java.util.function.Consumer<Boolean> after) {
    Optional<ItemStack> template = ItemService.decode(s.codec(), parcel.item());
    if (template.isEmpty()) {
      s.logger().severe("parcel " + parcel.id() + " has unreadable item data, left in storage");
      after.accept(false);
      return;
    }
    int capacity = ItemService.capacityFor(player, template.get());
    if (capacity <= 0) {
      player.sendMessage(s.messages().prefixed("claim.full"));
      after.accept(false);
      return;
    }
    String nonce = UUID.randomUUID().toString();
    UUID owner = player.getUniqueId();
    Async.supply(
        s.scheduler(),
        s.logger(),
        player,
        () -> {
          ParcelTransferEngine.Reservation r =
              engine().reserve(parcel.id(), owner, capacity, nonce);
          if (!r.reserved()) {
            return new Prepared(r, null);
          }
          return new Prepared(r, engine().beginHandover(r.journalId()));
        },
        prepared -> handover(player, template.get(), prepared, after),
        orphan -> {});
  }

  private void handover(
      Player player,
      ItemStack template,
      Prepared prepared,
      java.util.function.Consumer<Boolean> after) {
    ParcelTransferEngine.Reservation r = prepared.reservation();
    if (!r.reserved()
        || (prepared.handover() != ParcelTransferEngine.HandoverStatus.STARTED
            && prepared.handover() != ParcelTransferEngine.HandoverStatus.ALREADY_STARTED)) {
      after.accept(false);
      return;
    }
    int given = player.isOnline() ? ItemService.give(player, template, r.quantity()) : 0;
    ItemSnapshot item = r.item();
    UUID journal = r.journalId();
    int remainingAfter = r.quantity() - given;
    s.scheduler()
        .runAsync(() -> {
          ParcelTransferEngine.SettleStatus st = engine().settle(journal, given);
          if (st != ParcelTransferEngine.SettleStatus.SETTLED
              && st != ParcelTransferEngine.SettleStatus.RELEASED) {
            s.logger().severe("settle " + journal + " returned " + st + ", needs admin review");
          }
        });
    String key = remainingAfter > 0 ? "claim.partial" : "claim.success";
    if (given > 0) {
      player.sendMessage(
          s.messages()
              .prefixed(
                  key,
                  Messages.number("quantity", given),
                  Messages.component("item", template.effectiveName())));
    }
    after.accept(remainingAfter == 0 && given == r.quantity());
    if (item == null) {
      s.logger().fine("reservation without item snapshot");
    }
  }

  private record Prepared(
      ParcelTransferEngine.Reservation reservation, ParcelTransferEngine.HandoverStatus handover) {}
}
