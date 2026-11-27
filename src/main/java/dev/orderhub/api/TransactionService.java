package dev.orderhub.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Lesender Zugriff auf das Transaction Journal. Es gibt keine schreibenden Methoden. */
public interface TransactionService {

  record TransactionView(
      UUID id,
      Long orderId,
      UUID player,
      String type,
      String state,
      int quantity,
      long amountMinor,
      long createdAt,
      long updatedAt) {}

  CompletableFuture<Optional<TransactionView>> getTransaction(UUID id);

  /** Neueste zuerst, hoechstens 100. */
  CompletableFuture<List<TransactionView>> getTransactions(UUID player, int limit);
}
