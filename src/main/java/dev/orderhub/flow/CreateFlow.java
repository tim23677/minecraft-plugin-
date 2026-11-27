package dev.orderhub.flow;

import dev.orderhub.bootstrap.Messages;
import dev.orderhub.bootstrap.Settings;
import dev.orderhub.domain.ItemSnapshot;
import dev.orderhub.gui.ConfirmMenu;
import dev.orderhub.item.ItemCodecException;
import dev.orderhub.item.ValidationResult;
import dev.orderhub.money.Money;
import dev.orderhub.service.OrderService;
import java.time.Duration;
import java.util.List;
import java.util.OptionalInt;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Erstellen eines Auftrags: Item aus der Hand, Menge und Preis per Argument oder Chat,
 * Bestaetigung.
 */
public final class CreateFlow {

  private final Services s;
  private final ChatInput chat;

  public CreateFlow(Services services, ChatInput chat) {
    this(services, chat, System::currentTimeMillis);
  }

  CreateFlow(Services services, ChatInput chat, java.util.function.LongSupplier clock) {
    this.s = services;
    this.chat = chat;
    this.cooldown = new CreateCooldown(clock);
  }

  /**
   * Startet die Erstellung. {@code quantity} und {@code price} duerfen {@code null} sein (Chat).
   */
  public void start(Player player, String quantity, String price) {
    ItemStack hand = player.getInventory().getItemInMainHand();
    ValidationResult check = s.matcher().validateForOrder(hand);
    if (!check.valid()) {
      player.sendMessage(
          s.messages()
              .prefixed(
                  "create.item-rejected",
                  Messages.text(
                      "reason",
                      s.messages()
                          .raw(
                              "reject."
                                  + check
                                      .reason()
                                      .name()
                                      .toLowerCase(java.util.Locale.ROOT)
                                      .replace('_', '-')))));
      return;
    }
    final ItemSnapshot snapshot;
    try {
      snapshot = s.codec().encode(hand);
    } catch (ItemCodecException e) {
      player.sendMessage(
          s.messages().prefixed("create.item-rejected", Messages.text("reason", "invalid")));
      return;
    }
    ItemStack template = hand.clone();
    template.setAmount(1);
    Settings settings = s.settings().get();
    if (quantity != null && price != null) {
      withQuantity(player, snapshot, template, quantity, price);
      return;
    }
    player.sendMessage(
        s.messages()
            .prefixed("create.prompt-quantity", Messages.text("cancel", settings.cancelWord())));
    chat.await(
        player,
        settings.inputTimeoutSeconds(),
        q -> {
          player.sendMessage(
              s.messages()
                  .prefixed("create.prompt-price", Messages.text("cancel", settings.cancelWord())));
          chat.await(
              player,
              settings.inputTimeoutSeconds(),
              p -> withQuantity(player, snapshot, template, q, p),
              () -> player.sendMessage(s.messages().prefixed("create.cancelled")));
        },
        () -> player.sendMessage(s.messages().prefixed("create.cancelled")));
  }

  private void withQuantity(
      Player player, ItemSnapshot snapshot, ItemStack template, String qRaw, String pRaw) {
    OptionalInt q = parseQuantity(qRaw);
    if (q.isEmpty()) {
      player.sendMessage(
          s.messages().prefixed("general.invalid-number", Messages.text("input", qRaw)));
      return;
    }
    final long unit;
    try {
      unit = Money.parse(pRaw);
    } catch (NumberFormatException e) {
      player.sendMessage(
          s.messages().prefixed("general.invalid-amount", Messages.text("input", pRaw)));
      return;
    }
    if (unit < s.limits().minPrice() || unit > s.limits().maxPrice()) {
      player.sendMessage(
          s.messages()
              .prefixed(
                  "create.price-out-of-range",
                  Messages.text("min", Money.format(s.limits().minPrice())),
                  Messages.text("max", Money.format(s.limits().maxPrice()))));
      return;
    }
    if (q.getAsInt() < 1 || q.getAsInt() > s.limits().maxQuantity()) {
      player.sendMessage(
          s.messages()
              .prefixed(
                  "create.quantity-out-of-range",
                  Messages.number("max", s.limits().maxQuantity())));
      return;
    }
    long total = Money.multiply(unit, q.getAsInt());
    boolean exempt = player.hasPermission("orderhub.bypass.fee");
    long fee = s.orders().quoteCreateFee(total, exempt);
    player.sendMessage(
        s.messages()
            .prefixed(
                "create.summary",
                Messages.number("quantity", q.getAsInt()),
                Messages.component("item", ItemService.nameOf(template)),
                Messages.text("unit", Money.format(unit)),
                Messages.text("total", Money.format(total)),
                Messages.text("fee", Money.format(fee))));
    int quantity = q.getAsInt();
    ConfirmMenu confirm =
        new ConfirmMenu(
            s.messages().get("menu.confirm-title"),
            icon(Material.LIME_STAINED_GLASS_PANE, s.messages().get("menu.confirm")),
            icon(Material.RED_STAINED_GLASS_PANE, s.messages().get("menu.cancel")),
            p -> submit(p, snapshot, template, quantity, unit, exempt, fee),
            id -> {});
    s.menus().open(player, confirm);
  }

  private ItemStack icon(Material material, net.kyori.adventure.text.Component name) {
    ItemStack stack = new ItemStack(material);
    stack.editMeta(meta -> meta.displayName(name));
    return stack;
  }

  private static OptionalInt parseQuantity(String raw) {
    if (raw == null || !raw.matches("[0-9]{1,7}")) {
      return OptionalInt.empty();
    }
    return OptionalInt.of(Integer.parseInt(raw));
  }

  private final CreateCooldown cooldown;

  /** Gibt den Cooldown-Eintrag frei, wenn der Spieler den Server verlaesst. */
  public void forget(UUID player) {
    cooldown.forget(player);
  }

  /** Zeitgesteuerte Bereinigung abgelaufener Cooldown-Eintraege. Thread-sicher. */
  public int purgeExpiredCooldowns() {
    return cooldown.purgeExpired(s.settings().get().createCooldownSeconds());
  }

  int cooldownSize() {
    return cooldown.size();
  }

  private void submit(
      Player player,
      ItemSnapshot snapshot,
      ItemStack template,
      int quantity,
      long unit,
      boolean feeExempt,
      long shownFee) {
    Settings settings = s.settings().get();
    long waitMillis = cooldown.tryAcquire(player.getUniqueId(), settings.createCooldownSeconds());
    if (waitMillis > 0) {
      player.sendMessage(
          s.messages()
              .prefixed("create.cooldown", Messages.number("seconds", (waitMillis + 999) / 1000)));
      return;
    }
    int maxOpen = s.limits().maxOpenOrders(player::hasPermission);
    UUID requestId = UUID.randomUUID();
    OrderService.CreateOrderRequest request =
        new OrderService.CreateOrderRequest(
            requestId,
            player.getUniqueId(),
            snapshot,
            unit,
            quantity,
            1,
            Duration.ofHours(settings.expiryHours()),
            false,
            categoryOf(template),
            maxOpen,
            feeExempt,
            shownFee);
    Async.supply(
        s.scheduler(),
        s.logger(),
        player,
        () -> s.orders().createOrder(request),
        result -> report(player, template, snapshot, quantity, unit, result),
        orphan -> {});
  }

  private void report(
      Player player,
      ItemStack template,
      ItemSnapshot snapshot,
      int quantity,
      long unit,
      OrderService.CreateResult result) {
    var item = Messages.component("item", ItemService.nameOf(template));
    switch (result.status()) {
      case CREATED, ALREADY_CREATED -> {
        if (result.status() == OrderService.CreateStatus.CREATED && result.orderId() != null) {
          afterCreated(player, template, snapshot, quantity, unit, result);
        }
        player.sendMessage(
            s.messages()
                .prefixed(
                    "create.success",
                    Messages.number("id", result.orderId() == null ? 0 : result.orderId()),
                    Messages.number("quantity", quantity),
                    item,
                    Messages.text("total", Money.format(result.escrow()))));
      }
      case INSUFFICIENT_FUNDS ->
          player.sendMessage(
              s.messages()
                  .prefixed(
                      "create.insufficient-funds",
                      Messages.text("total", Money.format(result.escrow() + result.fee())),
                      Messages.text("balance", "-")));
      case LIMIT_REACHED ->
          player.sendMessage(
              s.messages()
                  .prefixed(
                      "create.limit-reached",
                      Messages.number("count", 0),
                      Messages.number("limit", s.limits().maxOpenOrders(player::hasPermission))));
      case FEE_CHANGED ->
          player.sendMessage(
              s.messages()
                  .prefixed(
                      "create.fee-changed", Messages.text("fee", Money.format(result.fee()))));
      case INVALID_PRICE, INVALID_QUANTITY, INVALID_AMOUNT ->
          player.sendMessage(
              s.messages().prefixed("general.invalid-amount", Messages.text("input", "")));
      case DB_UNAVAILABLE, FAILED_NEEDS_REVIEW, ECONOMY_UNCERTAIN ->
          player.sendMessage(s.messages().prefixed("general.database-error"));
      default -> player.sendMessage(s.messages().prefixed("general.busy"));
    }
  }

  /** Statistik, Preisalarme und Discord nach erfolgreicher Erstellung (asynchron). */
  private void afterCreated(
      Player player,
      ItemStack template,
      ItemSnapshot snapshot,
      int quantity,
      long unit,
      OrderService.CreateResult result) {
    long orderId = result.orderId();
    UUID owner = player.getUniqueId();
    s.scheduler()
        .runAsync(() -> {
          try {
            dev.orderhub.integration.Events.fire(
                new dev.orderhub.api.event.OrderCreateEvent(
                    orderId, owner, snapshot.itemKey(), quantity, unit));
            if (result.journalId() != null) {
              dev.orderhub.integration.Events.fire(
                  new dev.orderhub.api.event.TransactionStateChangeEvent(
                      result.journalId(), "ESCROW_CREATE", "COMMITTED", result.escrow()));
            }
            List<UUID> notified =
                s.insights().orderCreated(orderId, owner, snapshot.itemKey(), unit, quantity);
            for (UUID target : notified) {
              Player online = org.bukkit.Bukkit.getPlayer(target);
              if (online != null) {
                s.scheduler()
                    .tryRunOn(
                        online,
                        () ->
                            online.sendMessage(
                                s.messages()
                                    .prefixed(
                                        "notify.price-alert",
                                        Messages.number("quantity", quantity),
                                        Messages.component("item", ItemService.nameOf(template)),
                                        Messages.text("unit", Money.format(unit)),
                                        Messages.number("id", orderId))));
              }
            }
          } catch (RuntimeException e) {
            s.logger().log(java.util.logging.Level.WARNING, "insights after create failed", e);
          }
        });
  }

  /** Grobe Kategorie fuer Filter, abgeleitet vom Material-Namen. */
  static String categoryOf(ItemStack stack) {
    return dev.orderhub.item.Categorizer.of(stack.getType().getKey().toString());
  }

  /** Fuer Tests: Liste der erlaubten Kategorien. */
  static List<String> categories() {
    return dev.orderhub.item.Categorizer.ALL;
  }
}
