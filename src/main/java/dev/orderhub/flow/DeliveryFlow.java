package dev.orderhub.flow;

import dev.orderhub.bootstrap.Messages;
import dev.orderhub.domain.ItemSnapshot;
import dev.orderhub.domain.Order;
import dev.orderhub.gui.DeliveryMenu;
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
    DeliveryMenu[] holder = new DeliveryMenu[1];
    DeliveryMenu menu =
        new DeliveryMenu(
            s.messages().get("menu.delivery-title", Messages.number("id", order.id())),
            inputSlots,
            stack -> s.matcher().matches(stack, order.item()),
            () -> info(order, wanted, holder[0]),
            named(Material.LIME_STAINED_GLASS_PANE, s.messages().get("menu.deliver")),
            named(Material.RED_STAINED_GLASS_PANE, s.messages().get("menu.cancel")),
            m -> confirm(player, m, order),
            p -> p.closeInventory());
    holder[0] = menu;
    s.menus().open(player, menu);
  }

  private ItemStack info(Order order, ItemStack wanted, DeliveryMenu menu) {
    int accepted =
        menu == null ? 0 : Math.min(count(menu.inputContents(), order.item()), order.remaining());
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

  private int count(List<ItemStack> stacks, ItemSnapshot required) {
    int total = 0;
    for (ItemStack stack : stacks) {
      if (s.matcher().matches(stack, required)) {
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
    menu.freeze();
    int available = Math.min(count(menu.inputContents(), order.item()), order.remaining());
    if (available <= 0) {
      player.sendMessage(s.messages().prefixed("delivery.nothing"));
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
                          order.id(), supplier, available, nonce, null, false));
          if (!prepared.ok()) {
            return new Step(prepared, null);
          }
          DeliveryEngine.ConfirmResult confirmed =
              s.delivery().confirmItemsRemoved(prepared.journalId());
          if (confirmed != DeliveryEngine.ConfirmResult.CONFIRMED
              && confirmed != DeliveryEngine.ConfirmResult.ALREADY_CONFIRMED) {
            s.delivery().abort(prepared.journalId());
            return new Step(null, null);
          }
          return new Step(prepared, confirmed);
        },
        step -> afterPrepare(player, menu, order, step),
        orphan ->
            s.logger().warning("player left during delivery prepare, journal left to recovery"));
  }

  private void afterPrepare(Player player, DeliveryMenu menu, Order order, Step step) {
    if (step.prepared() == null || !step.prepared().ok() || step.confirmed() == null) {
      String key =
          step.prepared() == null
              ? "general.busy"
              : switch (step.prepared().status()) {
                case BELOW_MINIMUM -> "delivery.below-minimum";
                case SELF_DELIVERY -> "delivery.own-order";
                case ORDER_NOT_ACTIVE, ORDER_NOT_FOUND -> "order.not-active";
                default -> "general.busy";
              };
      player.sendMessage(s.messages().prefixed(key, Messages.number("min", order.minDelivery())));
      player.closeInventory(); // das Framework gibt die Items zurueck
      return;
    }
    int quantity = step.prepared().quantity();
    UUID journal = step.prepared().journalId();
    List<ItemStack> drained = menu.takeInputs();
    int total = 0;
    for (ItemStack stack : drained) {
      if (s.matcher().matches(stack, order.item())) {
        total += stack.getAmount();
      }
    }
    if (total < quantity) {
      s.logger()
          .severe(
              "delivery " + journal + ": menu held fewer items than prepared; left to recovery");
      player.closeInventory();
      return;
    }
    int surplus = total - quantity;
    ItemStack template = drained.get(0).clone();
    template.setAmount(1);
    s.scheduler()
        .runAsync(() -> {
          DeliveryEngine.CommitResult result = commitSafely(journal);
          s.scheduler()
              .runOn(player, () -> afterCommit(player, order, template, quantity, surplus, result));
        });
  }

  private DeliveryEngine.CommitResult commitSafely(UUID journal) {
    try {
      return s.delivery().commit(journal);
    } catch (RuntimeException e) {
      s.logger().log(java.util.logging.Level.SEVERE, "commit outcome unknown for " + journal, e);
      return null; // Ausgang unklar: Recovery entscheidet
    }
  }

  private void afterCommit(
      Player player,
      Order order,
      ItemStack template,
      int quantity,
      int surplus,
      DeliveryEngine.CommitResult result) {
    if (surplus > 0) {
      giveOrQueue(player, template, surplus);
    }
    player.closeInventory();
    if (result == null) {
      player.sendMessage(s.messages().prefixed("delivery.processing"));
      return;
    }
    switch (result.status()) {
      case COMMITTED, ALREADY_COMMITTED -> {
        DeliveryCommitted event = result.event();
        long net = event == null ? 0 : event.netPayout();
        player.sendMessage(
            s.messages()
                .prefixed(
                    "delivery.success",
                    Messages.number("quantity", quantity),
                    Messages.component("item", template.effectiveName()),
                    Messages.number("id", order.id()),
                    Messages.text("amount", Money.format(net))));
        s.scheduler().runAsync(() -> s.payouts().runOnce(10));
        notifyOwner(order, template, quantity, event != null && event.orderCompleted());
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
                            Messages.component("item", template.effectiveName()))));
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
                  Messages.component("item", template.effectiveName())));
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
      DeliveryEngine.PrepareResult prepared, DeliveryEngine.ConfirmResult confirmed) {}
}
