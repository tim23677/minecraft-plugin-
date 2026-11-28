package dev.orderhub.storage;

import dev.orderhub.domain.Order;
import dev.orderhub.domain.OrderQuery;
import dev.orderhub.domain.OrderStatus;
import dev.orderhub.money.Money;
import java.io.IOException;
import java.io.Writer;
import java.util.List;

/**
 * CSV-Export von Auftraegen und Hauptbuch. Blockiert, nur auf Async-Threads aufrufen. Zellen, die
 * mit {@code = + - @} oder einem Steuerzeichen beginnen, bekommen ein vorangestelltes {@code '}
 * (Schutz vor Formeln in Tabellenkalkulationen).
 */
public final class CsvExport {

  private static final int PAGE = 500;

  private CsvExport() {}

  /** Eine Zelle fuer CSV: Formel-Schutz und RFC-4180-Quoting. */
  public static String cell(String raw) {
    String value = raw == null ? "" : raw;
    if (!value.isEmpty()) {
      char first = value.charAt(0);
      if (first == '='
          || first == '+'
          || first == '-'
          || first == '@'
          || first == '\t'
          || first == '\r') {
        value = "'" + value;
      }
    }
    if (value.contains(",")
        || value.contains("\"")
        || value.contains("\n")
        || value.contains("\r")) {
      return "\"" + value.replace("\"", "\"\"") + "\"";
    }
    return value;
  }

  private static void row(Writer out, Object... cells) throws IOException {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < cells.length; i++) {
      if (i > 0) {
        sb.append(',');
      }
      sb.append(cell(cells[i] == null ? "" : String.valueOf(cells[i])));
    }
    sb.append('\n');
    out.write(sb.toString());
  }

  /**
   * @return Anzahl geschriebener Zeilen ohne Kopfzeile
   */
  public static long writeOrders(Storage storage, Writer out) throws IOException {
    row(
        out,
        "id",
        "owner",
        "server",
        "item",
        "unit_price",
        "quantity_total",
        "quantity_filled",
        "min_delivery",
        "escrow_remaining",
        "status",
        "created_at",
        "expires_at",
        "category");
    long rows = 0;
    for (OrderStatus status : OrderStatus.values()) {
      int offset = 0;
      while (true) {
        final int at = offset;
        List<Order> page =
            storage
                .transact(uow ->
                    uow.orders()
                        .search(
                            new OrderQuery(
                                null, null, null, status, OrderQuery.Sort.NEWEST, at, PAGE)))
                .join();
        for (Order o : page) {
          row(
              out,
              o.id(),
              o.owner(),
              o.serverId(),
              o.item().itemKey(),
              Money.format(o.unitPrice()),
              o.quantityTotal(),
              o.quantityFilled(),
              o.minDelivery(),
              Money.format(o.escrowRemaining()),
              o.status(),
              o.createdAt(),
              o.expiresAt(),
              o.category());
          rows++;
        }
        if (page.size() < PAGE) {
          break;
        }
        offset += PAGE;
      }
    }
    return rows;
  }

  /**
   * @return Anzahl geschriebener Zeilen ohne Kopfzeile
   */
  public static long writeLedger(Storage storage, Writer out) throws IOException {
    row(out, "id", "tx_id", "player", "kind", "amount", "created_at", "note");
    long rows = 0;
    int offset = 0;
    while (true) {
      final int at = offset;
      List<LedgerRepository.Entry> page =
          storage.transact(uow -> uow.ledger().findAll(at, PAGE)).join();
      for (LedgerRepository.Entry e : page) {
        row(
            out,
            e.id(),
            e.txId(),
            e.player(),
            e.kind(),
            Money.format(e.amount()),
            e.createdAt(),
            e.note());
        rows++;
      }
      if (page.size() < PAGE) {
        break;
      }
      offset += PAGE;
    }
    return rows;
  }
}
