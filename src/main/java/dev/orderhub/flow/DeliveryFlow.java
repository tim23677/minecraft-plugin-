package dev.orderhub.flow;

import dev.orderhub.bootstrap.Messages;
import dev.orderhub.domain.Order;
import dev.orderhub.gui.DeliveryMenu;
import dev.orderhub.item.PreparedTemplate;
import dev.orderhub.item.Tolerance;
import dev.orderhub.money.Money;
import dev.orderhub.tx.DeliveryCommitted;
import dev.orderhub.tx.DeliveryEngine;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Lieferung an einen Auftrag. Reihenfolge (siehe ARCHITECTURE.md): Menue einfrieren, Journal
 * vorbereiten, ITEMS_REMOVED bestaetigen, Items aus dem Menue nehmen, Commit. Ist der Ausgang nach
 * dem Entnehmen unklar (Datenbankfehler beim Commit), werden Items NICHT direkt zurueckgegeben: Die
 * Recovery entscheidet anhand des Journals, damit nichts doppelt entsteht.
 */
public final class DeliveryFlow {

  private final Services s;
  private final List<Integer> inputSlots;

  public DeliveryFlow(Services services, List<Integer> inputSlots) {
    this.s = services;
    this.inputSlots = List.copyOf(inputSlots);
  }

  /** Laedt den Auftrag asynchron und oeffnet das Liefermenue. */
  public void open(Player player, long orderId) {
    Async.supply(
        s.scheduler(),
        s.logger(),
        player,
        () -> s.storage().transact(uow -> uow.orders().find(orderId)).join(),
        found -> {
          if (found.isEmpty()) {
            player.sendMessage(
                s.messages().prefixed("order.not-found", Messages.number("id", orderId)));
            return;
          }
          openMenu(player, found.get());
        },
        orphan -> {});
  }

  private void openMenu(Player player, Order order) {
    if (!order.status().acceptsDeliveries() || order.remaining() <= 0) {
      player.sendMessage(s.messages().prefixed("order.not-active"));
      return;
    }
    if (order.owner().equals(player.getUniqueId())) {
      player.sendMessage(s.messages().prefixed("delivery.own-order"));
      return;
    }
    Optional<ItemStack> template = ItemService.decode(s.codec(), order.item());
    if (template.isEmpty()) {
      s.logger().severe("order " + order.id() + " has unreadable item data");
      player.sendMessage(s.messages().prefixed("general.database-error"));
      return;
    }
    ItemStack wanted = template.get();
    // Die Vorlage wird einmal je Menue dekodiert, nicht bei jedem Slot-Vergleich.
    Optional<PreparedTemplate> prepared = s.matcher().prepare(order.item());
    if (prepared.isEmpty()) {
      s.logger().severe("order " + order.id() + " has unreadable item data");
      player.sendMessage(s.messages().prefixed("general.database-error"));
      return;
    }
    PreparedTemplate tpl = prepared.get();
    DeliveryMenu[] holder = new DeliveryMenu[1];
    DeliveryMenu menu =
        new DeliveryMenu(
            s.messages().get("menu.delivery-title", Messages.number("id", order.id())),
            inputSlots,
            stack -> s.matcher().matches(stack, tpl, Tolerance.EXACT),
            () -> info(order, wanted, holder[0], tpl),
            named(Material.LIME_STAINED_GLASS_PANE, s.messages().get("menu.deliver")),
            named(Material.RED_STAINED_GLASS_PANE, s.messages().get("menu.cancel")),
            m -> confirm(player, m, order),
            p -> p.closeInventory());
    holder[0] = menu;
    menu.template(tpl);
    s.menus().open(player, menu);
  }

  private ItemStack info(Order order, ItemStack wanted, DeliveryMenu menu, PreparedTemplate tpl) {
    int accepted = menu == null ? 0 : Math.min(count(menu.inputContents(), tpl), order.remaining());
    long payout = Money.multiply(order.unitPrice(), accepted);
    ItemStack icon = wanted.clone();
    icon.setAmount(1);
    icon.editMeta(meta -> {
      meta.displayName(s.messages().get("menu.delivery-info"));
      meta.lore(
          List.of(
              s.messages()
                  .get("lore.order-unit", Messages.text("unit", Money.format(order.unitPrice()))),
              s.messages()
                  .get(
                      "lore.delivery-accepted",
                      Messages.number("quantity", accepted),
                      Messages.number("remaining", order.remaining())),
              s.messages()
                  .get("lore.delivery-gross", Messages.text("amount", Money.format(payout)))));
    });
    return icon;
  }

  private int count(List<ItemStack> stacks, PreparedTemplate required) {
    int total = 0;
    for (ItemStack stack : stacks) {
      if (s.matcher().matches(stack, required, Tolerance.EXACT)) {
        total += stack.getAmount();
      }
    }
    return total;
  }

  private ItemStack named(Material material, Component name) {
    ItemStack stack = new ItemStack(material);
    stack.editMeta(meta -> meta.displayName(name));
    return stack;
  }

  private void confirm(Player player, DeliveryMenu menu, Order order) {
    menu.freeze(); // ab jetzt gehoeren die Items im Menue diesem Ablauf, nicht dem Schliessen-Hook
    int inMenu = count(menu.inputContents(), menu.template());
    int available = Math.min(inMenu, order.remaining());
    if (available <= 0) {
      player.sendMessage(s.messages().prefixed("delivery.nothing"));
      giveAll(player, menu.takeInputs());
      player.closeInventory();
      return;
    }
    UUID supplier = player.getUniqueId();
    String nonce = UUID.randomUUID().toString();
    Async.supply(
        s.scheduler(),
        s.logger(),
        player,
        () -> {
          DeliveryEngine.PrepareResult prepared =
              s.delivery()
                  .prepare(
                      new DeliveryEngine.PrepareRequest(
                          order.id(), supplier, available, nonce, null, false, inMenu));
          if (!prepared.ok()) {
            return new Step(prepared, null);
          }
          DeliveryEngine.ConfirmResult confirmed =
              s.delivery().confirmItemsRemoved(prepared.journalId());
          if (confirmed != DeliveryEngine.ConfirmResult.CONFIRMED
              && confirmed != DeliveryEngine.ConfirmResult.ALREADY_CONFIRMED) {
            var aborted = s.delivery().abort(prepared.journalId());
            // Nur bei ABORTED wurde die Reservierung verbraucht: dann geben wir selbst zurueck.
            // Sonst (Recovery war schneller) liegen die Items schon als Rueckgabe in der Datenbank.
            return new Step(null, null, aborted != DeliveryEngine.AbortResult.ABORTED);
          }
          return new Step(prepared, confirmed);
        },
        step -> afterPrepare(player, menu, order, step),
        // Spieler weg: der Ablauf laeuft ohne Spieler zu Ende, die Items bleiben im eingefrorenen
        // Menue
        // bis hierher und werden nie doppelt zurueckgegeben.
        step -> finishWithoutPlayer(supplier, menu, order, step),
        () -> prepareOutcomeUnknown(player, menu, order, supplier, nonce));
  }

  /**
   * prepare ist mit einer Ausnahme beendet: Die Reservierung kann trotzdem committed sein. Nur wenn
   * das Journal den Versuch nicht kennt, gehen die Items direkt zurueck. Existiert er, gibt die
   * Recovery die Reservierung frei (sonst gaebe es die Items doppelt).
   */
  private void prepareOutcomeUnknown(
      Player player, DeliveryMenu menu, Order order, UUID supplier, String nonce) {
    player.sendMessage(s.messages().prefixed("general.database-error"));
    closeAttempt(player, menu, order, supplier, nonce, 1);
  }

  private static final int CLOSE_ATTEMPTS = 6;

  /**
   * Schliesst den Versuch atomar ab (Tombstone). Nur wenn er nie entstanden ist, gehen die Items
   * direkt zurueck. Schlaegt die Datenbank fehl, bleiben die Items im eingefrorenen Menue und der
   * Abschluss wird wiederholt (fail closed, nie doppelt zurueckgeben).
   */
  private void closeAttempt(
      Player player, DeliveryMenu menu, Order order, UUID supplier, String nonce, int attempt) {
    s.scheduler()
        .runAsync(() -> {
          DeliveryEngine.AttemptStatus status;
          try {
            status = s.delivery().closeAttempt(order.id(), supplier, nonce);
          } catch (RuntimeException e) {
            if (attempt >= CLOSE_ATTEMPTS) {
              s.logger()
                  .log(
                      java.util.logging.Level.SEVERE,
                      "delivery attempt "
                          + nonce
                          + " of "
                          + supplier
                          + " could not be closed; items stay unreturned (manual compensation)",
                      e);
              return;
            }
            s.scheduler()
                .runLater(
                    () -> closeAttempt(player, menu, order, supplier, nonce, attempt + 1), 100);
            return;
          }
          boolean directReturn = status == DeliveryEngine.AttemptStatus.NEVER_PREPARED;
          boolean handled =
              s.scheduler()
                  .tryRunOn(
                      player,
                      () -> {
                        if (directReturn) {
                          giveAll(player, menu.takeInputs());
                        }
                        player.closeInventory();
                      });
          if (!handled && directReturn) {
            for (ItemStack stack : menu.takeInputs()) {
              ItemStack template = stack.clone();
              template.setAmount(1);
              queueReturn(supplier, template, stack.getAmount());
            }
          }
        });
  }

  private static boolean usable(Step step) {
    return step.prepared() != null && step.prepared().ok() && step.confirmed() != null;
  }

  private String failureKey(Step step) {
    if (step.prepared() == null) {
      return "general.busy";
    }
    return switch (step.prepared().status()) {
      case BELOW_MINIMUM -> "delivery.below-minimum";
      case SELF_DELIVERY -> "delivery.own-order";
      case ORDER_NOT_ACTIVE, ORDER_NOT_FOUND -> "order.not-active";
      default -> "general.busy";
    };
  }

  /** Entity-Kontext, Spieler online. */
  private void afterPrepare(Player player, DeliveryMenu menu, Order order, Step step) {
    if (!usable(step)) {
      player.sendMessage(
          s.messages().prefixed(failureKey(step), Messages.number("min", order.minDelivery())));
      if (step.storedByEngine()) {
        // Die Items liegen bereits als Rueckgabe in der Datenbank: Menue leeren, nicht doppeln.
        menu.takeInputs();
        player.closeInventory();
        new ParcelFlow(s, ParcelFlow.Kind.RETURN).claimAll(player);
        return;
      }
      giveAll(player, menu.takeInputs());
      player.closeInventory();
      return;
    }
    Taken taken = take(menu, order, step.prepared().quantity());
    if (taken == null) {
      s.logger()
          .severe(
              "delivery "
                  + step.prepared().journalId()
                  + ": menu held fewer items than prepared; left to recovery");
      player.closeInventory();
      return;
    }
    s.scheduler()
        .runAsync(() -> {
          DeliveryEngine.CommitResult result = commitSafely(step.prepared().journalId());
          // Alles Spielerunabhaengige zuerst, damit es auch bei einem Abgang nie ausfaellt.
          postCommit(order, taken.template(), taken.quantity(), result);
          // Ein Ueberschuss ist bereits in der Datenbank als Rueckgabe gespeichert (Commit).
          s.scheduler()
              .tryRunOn(
                  player,
                  () ->
                      afterCommitForPlayer(
                          player,
                          order,
                          taken.template(),
                          taken.quantity(),
                          taken.surplus(),
                          result));
        });
  }

  /** Spieler nicht mehr erreichbar (laeuft auf einem Async-Thread). */
  private void finishWithoutPlayer(UUID supplier, DeliveryMenu menu, Order order, Step step) {
    if (!usable(step)) {
      if (step.storedByEngine()) {
        menu.takeInputs();
        return;
      }
      for (ItemStack stack : menu.takeInputs()) {
        ItemStack template = stack.clone();
        template.setAmount(1);
        queueReturn(supplier, template, stack.getAmount());
      }
      return;
    }
    Taken taken = take(menu, order, step.prepared().quantity());
    if (taken == null) {
      s.logger()
          .severe(
              "delivery "
                  + step.prepared().journalId()
                  + ": menu held fewer items than prepared; left to recovery");
      return;
    }
    DeliveryEngine.CommitResult result = commitSafely(step.prepared().journalId());
    postCommit(order, taken.template(), taken.quantity(), result);
  }

  private record Taken(ItemStack template, int quantity, int surplus) {}

  /** Nimmt die Items aus dem Menue. {@code null}, wenn weniger als vorbereitet drin sind. */
  private Taken take(DeliveryMenu menu, Order order, int quantity) {
    List<ItemStack> drained = menu.takeInputs();
    int total = 0;
    ItemStack template = null;
    for (ItemStack stack : drained) {
      if (s.matcher().matches(stack, menu.template(), Tolerance.EXACT)) {
        total += stack.getAmount();
        if (template == null) {
          template = stack.clone();
          template.setAmount(1);
        }
      }
    }
    if (template == null || total < quantity) {
      return null;
    }
    return new Taken(template, quantity, total - quantity);
  }

  private DeliveryEngine.CommitResult commitSafely(UUID journal) {
    try {
      return s.delivery().commit(journal);
    } catch (RuntimeException e) {
      s.logger().log(java.util.logging.Level.SEVERE, "commit outcome unknown for " + journal, e);
      return null; // Ausgang unklar: Recovery entscheidet
    }
  }

  /**
   * Spielerunabhaengig, laeuft auf dem Async-Thread: Ereignisse, Statistik, Auszahlung, Besitzer.
   */
  private void postCommit(
      Order order, ItemStack template, int quantity, DeliveryEngine.CommitResult result) {
    if (result == null) {
      return;
    }
    switch (result.status()) {
      case COMMITTED, ALREADY_COMMITTED -> {
        DeliveryCommitted event = result.event();
        try {
          s.payouts().runOnce(10);
        } catch (RuntimeException e) {
          s.logger().log(java.util.logging.Level.WARNING, "payout kick failed", e);
        }
        if (event != null) {
          dev.orderhub.integration.Events.fire(
              new dev.orderhub.api.event.OrderDeliveryEvent(
                  event.orderId(),
                  event.supplier(),
                  event.owner(),
                  event.itemKey(),
                  event.quantity(),
                  event.gross(),
                  event.netPayout()));
          dev.orderhub.integration.Events.fire(
              new dev.orderhub.api.event.TransactionStateChangeEvent(
                  event.journalId(), "DELIVERY", "COMMITTED", event.gross()));
          if (event.orderCompleted()) {
            dev.orderhub.integration.Events.fire(
                new dev.orderhub.api.event.OrderCompleteEvent(
                    event.orderId(), event.owner(), event.itemKey()));
          }
          try {
            s.insights().deliveryCommitted(event);
          } catch (RuntimeException e) {
            s.logger().log(java.util.logging.Level.WARNING, "insights after delivery failed", e);
          }
        }
        notifyOwner(order, template, quantity, event != null && event.orderCompleted());
      }
      case REJECTED_ITEMS_RETURNED -> s.logger().info("delivery rejected, items stored as return");
      default -> s.logger().warning("delivery commit ended with " + result.status());
    }
  }

  /** Entity-Kontext: Ueberschuss zurueckgeben, Menue schliessen, Meldung. */
  private void afterCommitForPlayer(
      Player player,
      Order order,
      ItemStack template,
      int quantity,
      int surplus,
      DeliveryEngine.CommitResult result) {
    player.closeInventory();
    if (result == null) {
      player.sendMessage(s.messages().prefixed("delivery.processing"));
      return;
    }
    switch (result.status()) {
      case COMMITTED, ALREADY_COMMITTED -> {
        long net = result.event() == null ? 0 : result.event().netPayout();
        player.sendMessage(
            s.messages()
                .prefixed(
                    "delivery.success",
                    Messages.number("quantity", quantity),
                    Messages.component("item", ItemService.nameOf(template)),
                    Messages.number("id", order.id()),
                    Messages.text("amount", Money.format(net))));
        if (surplus > 0) {
          // Der Ueberschuss liegt als Rueckgabe bereit und wird ins Inventar uebernommen.
          new ParcelFlow(s, ParcelFlow.Kind.RETURN).claimAll(player);
        }
      }
      case REJECTED_ITEMS_RETURNED -> {
        player.sendMessage(s.messages().prefixed("delivery.returned"));
        new ParcelFlow(s, ParcelFlow.Kind.RETURN).claimAll(player);
      }
      default -> player.sendMessage(s.messages().prefixed("delivery.processing"));
    }
  }

  private void notifyOwner(Order order, ItemStack template, int quantity, boolean completed) {
    Player owner = Bukkit.getPlayer(order.owner());
    if (owner == null) {
      return; // Offline: Benachrichtigung ueber das Claim-Menue beim naechsten Login
    }
    s.scheduler()
        .tryRunOn(
            owner,
            () ->
                owner.sendMessage(
                    s.messages()
                        .prefixed(
                            completed ? "order.completed" : "notify.delivered",
                            Messages.number("id", order.id()),
                            Messages.number("quantity", quantity),
                            Messages.component("item", ItemService.nameOf(template)))));
  }

  /** Gibt Items zurueck, was nicht ins Inventar passt, wird persistent als Return gespeichert. */
  void giveOrQueue(Player player, ItemStack template, int amount) {
    int given = ItemService.give(player, template, amount);
    int rest = amount - given;
    if (rest > 0) {
      queueReturn(player.getUniqueId(), template, rest);
      player.sendMessage(
          s.messages()
              .prefixed(
                  "delivery.stored-for-later",
                  Messages.number("quantity", rest),
                  Messages.component("item", ItemService.nameOf(template))));
    }
  }

  /** Speichert Items dauerhaft in {@code item_returns}. */
  void queueReturn(UUID owner, ItemStack template, int amount) {
    new ReturnQueue(s.storage(), s.scheduler(), s.codec(), s.logger())
        .persist(owner, template, amount, false);
  }

  /** Alle Items einer Liste zurueckgeben (z. B. nach Abbruch). */
  void giveAll(Player player, List<ItemStack> stacks) {
    List<ItemStack> copy = new ArrayList<>(stacks);
    for (ItemStack stack : copy) {
      ItemStack template = stack.clone();
      template.setAmount(1);
      giveOrQueue(player, template, stack.getAmount());
    }
  }

  private record Step(
      DeliveryEngine.PrepareResult prepared,
      DeliveryEngine.ConfirmResult confirmed,
      boolean storedByEngine) {
    Step(DeliveryEngine.PrepareResult prepared, DeliveryEngine.ConfirmResult confirmed) {
      this(prepared, confirmed, false);
    }
  }
}
