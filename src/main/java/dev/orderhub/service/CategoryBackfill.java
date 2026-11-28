package dev.orderhub.service;

import dev.orderhub.item.Categorizer;
import dev.orderhub.storage.OrderRepository;
import dev.orderhub.storage.Storage;
import dev.orderhub.tx.TxRunner;
import java.util.List;

/**
 * Ordnet offene Auftraege mit Kategorien aelterer Versionen (blocks, food, items) den Kategorien
 * von 2.0 zu. Laeuft beim Start einmalig im Hintergrund und ist idempotent: Eine Zeile, deren neue
 * Kategorie wieder "blocks" lautet, bleibt unveraendert und wird nicht erneut geschrieben.
 *
 * @since 2.0.0
 */
public final class CategoryBackfill {

  private static final int BATCH = 200;

  private CategoryBackfill() {}

  /**
   * @return Anzahl geaenderter Auftraege
   */
  public static int run(Storage storage) {
    int changed = 0;
    long after = 0;
    while (true) {
      final long from = after;
      List<OrderRepository.CategoryRow> rows =
          TxRunner.run(storage, uow -> uow.orders().findLegacyCategories(from, BATCH));
      if (rows.isEmpty()) {
        return changed;
      }
      for (OrderRepository.CategoryRow row : rows) {
        String target = Categorizer.of(row.itemKey());
        if (!target.equals(row.category())) {
          TxRunner.run(
              storage,
              uow -> {
                uow.orders().setCategory(row.id(), target);
                return null;
              });
          changed++;
        }
        after = row.id();
      }
    }
  }
}
