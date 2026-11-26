package dev.orderhub.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import org.bukkit.inventory.ItemStack;

/**
 * Menue mit Seiten. Die Inhaltsslots, die Navigations-Slots und die Icons legt der Aufrufer fest;
 * Texte der Icons kommen von aussen (MessageSource).
 */
public final class PagedMenu<T> extends Menu {

  private final List<Integer> contentSlots;
  private final int previousSlot;
  private final int nextSlot;
  private final ItemStack previousIcon;
  private final ItemStack nextIcon;
  private final Function<T, Button> mapper;
  private List<T> items = List.of();
  private int page;

  public PagedMenu(
      Component title,
      int rows,
      List<Integer> contentSlots,
      int previousSlot,
      int nextSlot,
      ItemStack previousIcon,
      ItemStack nextIcon,
      Function<T, Button> mapper) {
    super(title, rows, SlotPolicy.none());
    this.contentSlots = List.copyOf(contentSlots);
    if (this.contentSlots.isEmpty()) {
      throw new IllegalArgumentException("contentSlots must not be empty");
    }
    this.previousSlot = previousSlot;
    this.nextSlot = nextSlot;
    this.previousIcon = previousIcon.clone();
    this.nextIcon = nextIcon.clone();
    this.mapper = Objects.requireNonNull(mapper, "mapper");
    renderPage();
  }

  /** Ersetzt die Eintraege und geht zurueck auf eine gueltige Seite. */
  public final void setItems(List<T> newItems) {
    this.items = new ArrayList<>(newItems);
    this.page = Math.min(page, Math.max(0, pageCount() - 1));
    renderPage();
  }

  public final int pageSize() {
    return contentSlots.size();
  }

  /** Mindestens 1, auch ohne Eintraege. */
  public final int pageCount() {
    return Math.max(1, (items.size() + pageSize() - 1) / pageSize());
  }

  /** Aktuelle Seite, 0-basiert. */
  public final int page() {
    return page;
  }

  public final boolean hasNextPage() {
    return page + 1 < pageCount();
  }

  public final boolean hasPreviousPage() {
    return page > 0;
  }

  public final void nextPage() {
    if (hasNextPage()) {
      page++;
      renderPage();
    }
  }

  public final void previousPage() {
    if (hasPreviousPage()) {
      page--;
      renderPage();
    }
  }

  private void renderPage() {
    for (int slot : contentSlots) {
      clearButton(slot);
    }
    int from = page * pageSize();
    int to = Math.min(items.size(), from + pageSize());
    for (int i = from; i < to; i++) {
      setButton(contentSlots.get(i - from), mapper.apply(items.get(i)));
    }
    if (hasPreviousPage()) {
      setButton(previousSlot, Button.of(previousIcon, context -> previousPage()));
    } else {
      clearButton(previousSlot);
    }
    if (hasNextPage()) {
      setButton(nextSlot, Button.of(nextIcon, context -> nextPage()));
    } else {
      clearButton(nextSlot);
    }
  }
}
