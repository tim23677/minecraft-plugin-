package dev.orderhub.flow;

import dev.orderhub.bootstrap.Messages;
import dev.orderhub.domain.ItemSnapshot;
import dev.orderhub.domain.OrderTemplate;
import dev.orderhub.item.ItemCodecException;
import dev.orderhub.money.Money;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * {@code /order template save|use|delete|list}: Auftragsvorlagen je Spieler. Beim Benutzen gelten
 * die aktuellen Grenzen, Gebuehren und Sperrlisten, das Ergebnis geht durch dieselbe
 * Zusammenfassung und Bestaetigung wie {@code /order create}.
 *
 * @since 2.0.0
 */
public final class TemplateFlow {

  /** Hoechstzahl gespeicherter Vorlagen je Spieler. */
  public static final int MAX_PER_PLAYER = 10;

  private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,32}");

  private final Services s;
  private final CreateFlow create;

  public TemplateFlow(Services services, CreateFlow create) {
    this.s = services;
    this.create = create;
  }

  /**
   * @param args die Argumente nach {@code template}
   */
  public void handle(Player player, String[] args) {
    if (args.length == 0) {
      player.sendMessage(s.messages().prefixed("template.usage"));
      return;
    }
    switch (args[0].toLowerCase(Locale.ROOT)) {
      case "save" -> save(player, args);
      case "use" -> use(player, args);
      case "delete" -> delete(player, args);
      case "list" -> list(player);
      default -> player.sendMessage(s.messages().prefixed("template.usage"));
    }
  }

  private boolean validName(Player player, String[] args) {
    if (args.length < 2 || !NAME.matcher(args[1]).matches()) {
      player.sendMessage(s.messages().prefixed("template.usage"));
      return false;
    }
    return true;
  }

  private void save(Player player, String[] args) {
    if (!validName(player, args)) {
      return;
    }
    if (args.length < 4 || !args[2].matches("[0-9]{1,7}")) {
      player.sendMessage(s.messages().prefixed("template.usage"));
      return;
    }
    OptionFlags.Result flags =
        OptionFlags.parse(java.util.Arrays.asList(args).subList(4, args.length));
    if (!flags.ok()) {
      player.sendMessage(
          s.messages().prefixed("create.options-invalid", Messages.text("input", flags.invalid())));
      return;
    }
    int quantity = Integer.parseInt(args[2]);
    long unit;
    try {
      unit = Money.parse(args[3]);
    } catch (NumberFormatException e) {
      player.sendMessage(
          s.messages().prefixed("general.invalid-amount", Messages.text("input", args[3])));
      return;
    }
    if (quantity < 1
        || quantity > s.limits().maxQuantity()
        || unit < s.limits().minPrice()
        || unit > s.limits().maxPrice()) {
      player.sendMessage(
          s.messages().prefixed("general.invalid-amount", Messages.text("input", args[3])));
      return;
    }
    ItemStack hand = player.getInventory().getItemInMainHand();
    var check = s.matcher().validateForOrder(hand);
    if (!check.valid()) {
      player.sendMessage(
          s.messages().prefixed("create.item-rejected", Messages.text("reason", "invalid")));
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
    String name = args[1];
    OrderTemplate template =
        new OrderTemplate(
            0,
            player.getUniqueId(),
            name,
            snapshot,
            unit,
            quantity,
            1,
            s.settings().get().expiryHours(),
            flags.options(),
            false,
            System.currentTimeMillis());
    Async.supply(
        s.scheduler(),
        s.logger(),
        player,
        () -> s.storage().transact(uow -> uow.templates().save(template, MAX_PER_PLAYER)).join(),
        saved ->
            player.sendMessage(
                saved
                    ? s.messages().prefixed("template.saved", Messages.text("name", name))
                    : s.messages()
                        .prefixed("template.limit", Messages.number("max", MAX_PER_PLAYER))),
        orphan -> {},
        () -> player.sendMessage(s.messages().prefixed("general.database-error")));
  }

  private void use(Player player, String[] args) {
    if (!validName(player, args)) {
      return;
    }
    String name = args[1];
    Async.supply(
        s.scheduler(),
        s.logger(),
        player,
        () -> s.storage().transact(uow -> uow.templates().find(player.getUniqueId(), name)).join(),
        found -> {
          if (found.isEmpty()) {
            player.sendMessage(
                s.messages().prefixed("template.not-found", Messages.text("name", name)));
            return;
          }
          OrderTemplate t = found.get();
          Optional<ItemStack> stack = ItemService.decode(s.codec(), t.item());
          if (stack.isEmpty()) {
            player.sendMessage(
                s.messages().prefixed("create.item-rejected", Messages.text("reason", "invalid")));
            return;
          }
          ItemStack sample = stack.get();
          sample.setAmount(1);
          create.startFromTemplate(
              player, t.item(), sample, t.quantity(), t.unitPrice(), t.options());
        },
        orphan -> {},
        () -> player.sendMessage(s.messages().prefixed("general.database-error")));
  }

  private void delete(Player player, String[] args) {
    if (!validName(player, args)) {
      return;
    }
    String name = args[1];
    Async.supply(
        s.scheduler(),
        s.logger(),
        player,
        () ->
            s.storage().transact(uow -> uow.templates().delete(player.getUniqueId(), name)).join(),
        removed ->
            player.sendMessage(
                removed
                    ? s.messages().prefixed("template.deleted", Messages.text("name", name))
                    : s.messages().prefixed("template.not-found", Messages.text("name", name))),
        orphan -> {},
        () -> player.sendMessage(s.messages().prefixed("general.database-error")));
  }

  private void list(Player player) {
    Async.supply(
        s.scheduler(),
        s.logger(),
        player,
        () -> s.storage().transact(uow -> uow.templates().list(player.getUniqueId())).join(),
        (List<OrderTemplate> all) -> {
          if (all.isEmpty()) {
            player.sendMessage(s.messages().prefixed("template.empty"));
            return;
          }
          player.sendMessage(s.messages().prefixed("template.list-header"));
          for (OrderTemplate t : all) {
            Optional<ItemStack> stack = ItemService.decode(s.codec(), t.item());
            player.sendMessage(
                s.messages()
                    .get(
                        "template.list-entry",
                        Messages.text("name", t.name()),
                        Messages.number("quantity", t.quantity()),
                        Messages.component(
                            "item",
                            stack
                                .map(ItemService::nameOf)
                                .orElse(net.kyori.adventure.text.Component.text("?"))),
                        Messages.text("unit", Money.format(t.unitPrice()))));
          }
        },
        orphan -> {},
        () -> player.sendMessage(s.messages().prefixed("general.database-error")));
  }
}
