package dev.orderhub.api;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.inventory.ItemStack;

/** Auftraege lesen, erstellen und stornieren. */
public interface OrderService {

  /**
   * Wunsch nach einem neuen Auftrag. Der Item-Zustand wird beim Aufruf kopiert und serverseitig
   * geprueft (Sperrliste, Verzauberungen, Container). {@code requestId} ist der Idempotency Key:
   * Derselbe Wert fuehrt nie zu einem zweiten Auftrag.
   */
  record CreateRequest(
      UUID requestId,
      UUID owner,
      ItemStack item,
      int quantity,
      long unitPriceMinor,
      Duration lifetime) {
    public CreateRequest {
      item = item == null ? null : item.clone();
    }

    @Override
    public ItemStack item() {
      return item == null ? null : item.clone();
    }
  }

  /** Ergebnis der Erstellung. */
  enum CreateStatus {
    CREATED,
    ALREADY_CREATED,
    ITEM_REJECTED,
    INVALID_PRICE,
    INVALID_QUANTITY,
    LIMIT_REACHED,
    INSUFFICIENT_FUNDS,
    ECONOMY_UNAVAILABLE,
    ERROR
  }

  record CreateResult(CreateStatus status, Long orderId, String detail) {
    public boolean success() {
      return status == CreateStatus.CREATED || status == CreateStatus.ALREADY_CREATED;
    }
  }

  /** Ein Auftrag. {@code status}: ACTIVE, PAUSED, COMPLETED, CANCELLED, EXPIRED oder ARCHIVED. */
  record OrderView(
      long id,
      UUID owner,
      String itemKey,
      long unitPriceMinor,
      int quantityTotal,
      int quantityFilled,
      long escrowRemainingMinor,
      String status,
      long createdAt,
      long expiresAt) {}

  /**
   * Filter fuer {@link #getOpenOrders}. {@code null} bedeutet kein Filter. Seitengroesse max. 100.
   */
  record Filter(String itemKey, UUID owner, int offset, int limit) {}

  /** Stornierung. */
  enum CancelStatus {
    CANCELLED,
    ALREADY_DONE,
    NOT_FOUND,
    NOT_OWNER,
    INVALID_STATE,
    ERROR
  }

  record CancelResult(CancelStatus status, long refundedMinor) {}

  CompletableFuture<CreateResult> createOrder(CreateRequest request);

  CompletableFuture<Optional<OrderView>> getOrder(long orderId);

  CompletableFuture<List<OrderView>> getOpenOrders(Filter filter);

  /** Storniert einen Auftrag. Nur der Besitzer ({@code actor}) darf stornieren. */
  CompletableFuture<CancelResult> cancelOrder(long orderId, UUID actor);
}
