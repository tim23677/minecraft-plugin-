package dev.orderhub.flow;

import dev.orderhub.bootstrap.Messages;
import dev.orderhub.domain.Order;
import dev.orderhub.domain.OrderQuery;
import dev.orderhub.domain.OrderStatus;
import dev.orderhub.gui.Button;
import dev.orderhub.gui.ControlledMenu;
import dev.orderhub.gui.MenuLayout;
import dev.orderhub.item.Categorizer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Marktplatz mit Suche nach Itemname, Kategorie, Sortierung und Keyset-Paginierung in der
 * Datenbank. Jede Seite wird asynchron geladen, das Menue zeigt nur die Seite und merkt sich die
 * Startcursor der vorigen Seiten fuer "zurueck".
 *
 * @since 2.0.0
 */
public final class MarketFlow {

  /** Filter und Sortierung des Marktplatzes. */
  public record Filter(OrderQuery.Sort sort, String category, String text) {
    public Filter {
      sort = sort == null ? OrderQuery.Sort.NEWEST : sort;
      text = OrderQuery.normalizeText(text);
    }

    public static Filter all() {
      return new Filter(OrderQuery.Sort.NEWEST, null, null);
    }
  }

  static final int PAGE_SIZE = 45;
  private static final int DEFAULT_PREVIOUS = 45;
  private static final int DEFAULT_CATEGORY = 47;
  private static final int DEFAULT_SORT = 49;
  private static final int DEFAULT_SEARCH = 51;
  private static final int DEFAULT_NEXT = 53;

  private final Services s;
  private final ChatInput chat;
  private final DeliveryFlow delivery;
  private final Function<Order, ItemStack> icons;
  private final Supplier<MenuLayout> layout;
  private final SearchThrottle throttle;

  public MarketFlow(
      Services services,
      ChatInput chat,
      DeliveryFlow delivery,
      Function<Order, ItemStack> icons,
      Supplier<MenuLayout> layout) {
    this(services, chat, delivery, icons, layout, System::currentTimeMillis);
  }

  MarketFlow(
      Services services,
      ChatInput chat,
      DeliveryFlow delivery,
      Function<Order, ItemStack> icons,
      Supplier<MenuLayout> layout,
      java.util.function.LongSupplier clock) {
    this.throttle = new SearchThrottle(clock);
    this.s = services;
    this.chat = chat;
    this.delivery = delivery;
    this.icons = icons;
    this.layout = layout;
  }

  /** Zustand eines geoeffneten Marktplatzes. */
  private static final class State {
    Filter filter;
    final Deque<OrderQuery.Cursor> history = new ArrayDeque<>();
    OrderQuery.Cursor start;
    OrderQuery.Cursor next;
    ControlledMenu menu;
    final java.util.concurrent.atomic.AtomicInteger generation =
        new java.util.concurrent.atomic.AtomicInteger();

    State(Filter filter) {
      this.filter = filter;
    }
  }

  /** Oeffnet den Marktplatz mit dem Filter. */
  public void open(Player player, Filter filter) {
    load(player, new State(filter));
  }

  private void load(Player player, State st) {
    OrderQuery.Cursor start = st.start;
    Filter f = st.filter;
    if (f.text() != null && !throttle.tryAcquire(player.getUniqueId())) {
      player.sendMessage(s.messages().prefixed("market.search-slow"));
      return;
    }
    int gen = st.generation.incrementAndGet();
    Async.supply(
        s.scheduler(),
        s.logger(),
        player,
        () ->
            s.storage()
                .transact(uow ->
                    uow.orders()
                        .search(
                            new OrderQuery(
                                null,
                                null,
                                f.category(),
                                OrderStatus.ACTIVE,
                                f.sort(),
                                0,
                                PAGE_SIZE + 1,
                                f.text(),
                                start)))
                .join(),
        rows -> {
          // Ignore answers of an older filter or page that arrive after a newer request.
          if (gen == st.generation.get()) {
            show(player, st, rows);
          }
        },
        orphan -> {},
        () -> player.sendMessage(s.messages().prefixed("general.database-error")));
  }

  private Component text(String key, String messageKey, TagResolver... resolvers) {
    String custom = layout.get().text(key);
    return custom == null
        ? s.messages().get(messageKey, resolvers)
        : MiniMessage.miniMessage().deserialize(custom, resolvers);
  }

  private ItemStack icon(String key, Material fallback, Component name, Component... lore) {
    ItemStack stack = new ItemStack(layout.get().material(key, fallback));
    stack.editMeta(meta -> {
      meta.displayName(name);
      meta.lore(List.of(lore));
    });
    return stack;
  }

  private void show(Player player, State st, List<Order> rows) {
    MenuLayout l = layout.get();
    boolean hasNext = rows.size() > PAGE_SIZE;
    List<Order> page = hasNext ? rows.subList(0, PAGE_SIZE) : rows;
    st.next = hasNext ? OrderQuery.Cursor.of(st.filter.sort(), page.get(page.size() - 1)) : null;
    boolean fresh = st.menu == null;
    if (fresh) {
      Component title =
          l.title().isEmpty()
              ? s.messages().get("menu.marketplace-title")
              : MiniMessage.miniMessage().deserialize(l.title());
      st.menu = new ControlledMenu(title, l.rows());
    }
    ControlledMenu menu = st.menu;
    List<Integer> content = l.slots("content", range(0, PAGE_SIZE - 1));
    for (int slot : content) {
      menu.remove(slot);
    }
    for (int i = 0; i < page.size() && i < content.size(); i++) {
      Order order = page.get(i);
      menu.put(
          content.get(i),
          Button.of(
              icons.apply(order),
              ctx -> {
                ctx.player().closeInventory();
                delivery.open(ctx.player(), order.id());
              }));
    }
    controls(menu, player, st, l);
    if (page.isEmpty()) {
      player.sendMessage(s.messages().prefixed("market.empty"));
    }
    if (fresh) {
      s.menus().open(player, menu);
    }
  }

  private void controls(ControlledMenu menu, Player player, State st, MenuLayout l) {
    int previousSlot = l.slot("previous", DEFAULT_PREVIOUS);
    int nextSlot = l.slot("next", DEFAULT_NEXT);
    if (st.history.isEmpty()) {
      menu.remove(previousSlot);
    } else {
      menu.put(
          previousSlot,
          Button.of(
              icon("previous", Material.ARROW, text("previous", "menu.previous")),
              ctx -> {
                OrderQuery.Cursor back = st.history.pop();
                st.start = back == NONE ? null : back;
                load(ctx.player(), st);
              }));
    }
    if (st.next == null) {
      menu.remove(nextSlot);
    } else {
      menu.put(
          nextSlot,
          Button.of(
              icon("next", Material.ARROW, text("next", "menu.next")),
              ctx -> {
                st.history.push(st.start == null ? NONE : st.start);
                st.start = st.next;
                load(ctx.player(), st);
              }));
    }
    menu.put(
        l.slot("category", DEFAULT_CATEGORY),
        Button.of(
            icon(
                "category",
                Material.COMPASS,
                text(
                    "category",
                    "menu.market.category",
                    Messages.text("category", categoryName(st.filter.category()))),
                s.messages().get("menu.market.category-hint")),
            ctx -> {
              st.filter =
                  new Filter(
                      st.filter.sort(), nextCategory(st.filter.category()), st.filter.text());
              reset(ctx.player(), st);
            }));
    menu.put(
        l.slot("sort", DEFAULT_SORT),
        Button.of(
            icon(
                "sort",
                Material.HOPPER,
                text("sort", "menu.market.sort", Messages.text("sort", sortName(st.filter.sort()))),
                s.messages().get("menu.market.sort-hint")),
            ctx -> {
              OrderQuery.Sort[] all = OrderQuery.Sort.values();
              OrderQuery.Sort next = all[(st.filter.sort().ordinal() + 1) % all.length];
              st.filter = new Filter(next, st.filter.category(), st.filter.text());
              reset(ctx.player(), st);
            }));
    menu.put(
        l.slot("search", DEFAULT_SEARCH),
        Button.of(
            icon(
                "search",
                Material.OAK_SIGN,
                text(
                    "search",
                    "menu.market.search",
                    Messages.text("text", st.filter.text() == null ? "-" : st.filter.text())),
                s.messages().get("menu.market.search-hint")),
            ctx -> askSearch(ctx.player(), st)));
  }

  /** Marker fuer "Start der ersten Seite" im Verlauf (Cursor ohne Wirkung). */
  private static final OrderQuery.Cursor NONE = new OrderQuery.Cursor(Long.MIN_VALUE, 0, 0);

  private void reset(Player player, State st) {
    st.history.clear();
    st.start = null;
    load(player, st);
  }

  private void askSearch(Player player, State st) {
    player.closeInventory();
    player.sendMessage(
        s.messages()
            .prefixed(
                "market.search-prompt", Messages.text("cancel", s.settings().get().cancelWord())));
    chat.await(
        player,
        s.settings().get().inputTimeoutSeconds(),
        input -> {
          String text = "-".equals(input.trim()) ? null : input;
          open(player, new Filter(st.filter.sort(), st.filter.category(), text));
        },
        () -> player.sendMessage(s.messages().prefixed("create.cancelled")));
  }

  private static String nextCategoryKey(String current) {
    List<String> order = new ArrayList<>();
    order.add(null);
    order.addAll(Categorizer.ALL);
    int index = order.indexOf(current);
    return order.get((index + 1) % order.size());
  }

  static String nextCategory(String current) {
    return nextCategoryKey(current);
  }

  private String categoryName(String category) {
    return s.messages().raw("category." + (category == null ? "all" : category));
  }

  private String sortName(OrderQuery.Sort sort) {
    return s.messages().raw("sort." + sort.name().toLowerCase(Locale.ROOT).replace('_', '-'));
  }

  private static List<Integer> range(int from, int to) {
    List<Integer> out = new ArrayList<>();
    for (int i = from; i <= to; i++) {
      out.add(i);
    }
    return out;
  }
}
