package dev.orderhub.testsupport;

import dev.orderhub.domain.ItemSnapshot;
import dev.orderhub.domain.NewOrder;
import dev.orderhub.domain.Order;
import dev.orderhub.domain.OrderQuery;
import dev.orderhub.domain.OrderStatus;
import dev.orderhub.storage.AuditRepository;
import dev.orderhub.storage.Dialect;
import dev.orderhub.storage.JournalRepository;
import dev.orderhub.storage.LedgerRepository;
import dev.orderhub.storage.OrderRepository;
import dev.orderhub.storage.ParcelRepository;
import dev.orderhub.storage.PayoutRepository;
import dev.orderhub.storage.Storage;
import dev.orderhub.storage.UnitOfWork;
import dev.orderhub.tx.InvalidTransitionException;
import dev.orderhub.tx.JournalEntry;
import dev.orderhub.tx.TxState;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * Thread-sichere In-Memory-Implementierung des Storage-Vertrags fuer Tests. Transaktionen sind
 * serialisiert (eine globale Sperre), arbeiten auf einer Kopie und werden bei einer Ausnahme
 * verworfen. Compare-and-Set, UNIQUE-Schluessel und CHECK-Regeln verhalten sich wie in der
 * Datenbank. Fehlerinjektion: {@link #failTransaction}, {@link #setOutage}, {@link
 * #crashAfterCommit}.
 */
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP",
    justification = "Testfixture: die internen Listen werden bewusst direkt gelesen und geprueft")
public final class InMemoryStorage implements Storage {

  /** Stelle, an der eine Transaktion scheitert. */
  public enum FailAt {
    /** Vor der Arbeit, nichts passiert. */
    BEFORE_WORK,
    /** Nach der Arbeit, vor dem Commit: Rollback. */
    BEFORE_COMMIT,
    /** Nach dem Commit, der Aufrufer sieht aber einen Fehler (verlorene Bestaetigung). */
    AFTER_COMMIT
  }

  /** Verletzung einer Datenbank-Constraint (UNIQUE, CHECK, Vertragsregel). */
  public static final class ConstraintViolation extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ConstraintViolation(String message) {
      super(message);
    }
  }

  private static final class State {
    long nextOrderId = 1;
    long nextParcelId = 1;
    Map<Long, Order> orders = new LinkedHashMap<>();
    Map<UUID, JournalEntry> journal = new LinkedHashMap<>();
    Map<String, UUID> journalKeys = new HashMap<>();
    List<LedgerRepository.Entry> ledger = new ArrayList<>();
    Map<UUID, PayoutRepository.Item> payouts = new LinkedHashMap<>();
    Map<String, UUID> payoutKeys = new HashMap<>();
    Map<UUID, String> reviewReasons = new HashMap<>();
    Map<Long, ParcelRepository.Parcel> claims = new LinkedHashMap<>();
    Map<String, Long> claimKeys = new HashMap<>();
    Map<Long, ParcelRepository.Parcel> returns = new LinkedHashMap<>();
    Map<String, Long> returnKeys = new HashMap<>();
    List<dev.orderhub.storage.AuditRepository.Entry> audit = new ArrayList<>();
    Map<UUID, dev.orderhub.storage.HoldRepository.Hold> holds = new LinkedHashMap<>();
    Map<Long, dev.orderhub.domain.OrderOptions> options = new HashMap<>();
    Map<String, dev.orderhub.domain.OrderTemplate> templates = new LinkedHashMap<>();
    long nextTemplateId = 1;

    State copy() {
      State s = new State();
      s.nextOrderId = nextOrderId;
      s.nextParcelId = nextParcelId;
      s.orders = new LinkedHashMap<>(orders);
      s.journal = new LinkedHashMap<>(journal);
      s.journalKeys = new HashMap<>(journalKeys);
      s.ledger = new ArrayList<>(ledger);
      s.payouts = new LinkedHashMap<>(payouts);
      s.payoutKeys = new HashMap<>(payoutKeys);
      s.reviewReasons = new HashMap<>(reviewReasons);
      s.claims = new LinkedHashMap<>(claims);
      s.claimKeys = new HashMap<>(claimKeys);
      s.returns = new LinkedHashMap<>(returns);
      s.returnKeys = new HashMap<>(returnKeys);
      s.audit = new ArrayList<>(audit);
      s.holds = new LinkedHashMap<>(holds);
      s.options = new HashMap<>(options);
      s.templates = new LinkedHashMap<>(templates);
      s.nextTemplateId = nextTemplateId;
      return s;
    }
  }

  private final ReentrantLock lock = new ReentrantLock();
  private final ExecutorService executor =
      Executors.newFixedThreadPool(
          4,
          r -> {
            Thread t = new Thread(r, "inmemory-db");
            t.setDaemon(true);
            return t;
          });
  private final Clock clock;
  private final String serverId;
  private State committed = new State();
  private boolean down;
  private boolean outage;
  private long txCounter;
  private long commitCounter;
  private long crashAtCommit = -1;
  private final Map<Long, FailAt> planned = new HashMap<>();
  private int ownerLockCalls;

  public InMemoryStorage(Clock clock, String serverId) {
    this.clock = clock;
    this.serverId = serverId;
  }

  // ---------------------------------------------------------------- Storage

  @Override
  public <T> CompletableFuture<T> transact(Function<UnitOfWork, T> work) {
    return CompletableFuture.supplyAsync(() -> execute(work), executor);
  }

  @Override
  public Dialect dialect() {
    return Dialect.SQLITE;
  }

  @Override
  public String serverId() {
    return serverId;
  }

  @Override
  public void close() {
    executor.shutdownNow();
  }

  private <T> T execute(Function<UnitOfWork, T> work) {
    lock.lock();
    try {
      if (down) {
        throw new SimulatedCrash("storage down");
      }
      if (outage) {
        throw new SimulatedDbFailure("outage");
      }
      long n = ++txCounter;
      FailAt fail = planned.remove(n);
      if (fail == FailAt.BEFORE_WORK) {
        throw new SimulatedDbFailure("before work");
      }
      State working = committed.copy();
      T result = work.apply(new Uow(working, clock.millis()));
      if (fail == FailAt.BEFORE_COMMIT) {
        throw new SimulatedDbFailure("before commit");
      }
      committed = working;
      commitCounter++;
      if (commitCounter == crashAtCommit) {
        down = true;
        throw new SimulatedCrash("crash after commit " + commitCounter);
      }
      if (fail == FailAt.AFTER_COMMIT) {
        throw new SimulatedDbFailure("ack lost after commit");
      }
      return result;
    } finally {
      lock.unlock();
    }
  }

  // ---------------------------------------------------------------- Fehlerinjektion

  /** Die n-te Transaktion ab jetzt (1 = naechste) scheitert an der gegebenen Stelle. */
  public void failTransaction(int nth, FailAt at) {
    lock.lock();
    try {
      planned.put(txCounter + nth, at);
    } finally {
      lock.unlock();
    }
  }

  /** Alle Transaktionen scheitern vor der Arbeit (Datenbank nicht erreichbar). */
  public void setOutage(boolean value) {
    lock.lock();
    try {
      outage = value;
    } finally {
      lock.unlock();
    }
  }

  /** Nach dem n-ten Commit ab jetzt (1 = naechster) ist der Prozess "tot": alles scheitert. */
  public void crashAfterCommit(int nth) {
    lock.lock();
    try {
      crashAtCommit = commitCounter + nth;
    } finally {
      lock.unlock();
    }
  }

  /** Neuer Prozess: gespeicherte Daten bleiben, Fehlerplaene und Absturz sind aufgehoben. */
  public void restart() {
    lock.lock();
    try {
      down = false;
      outage = false;
      crashAtCommit = -1;
      planned.clear();
    } finally {
      lock.unlock();
    }
  }

  public long commitCount() {
    lock.lock();
    try {
      return commitCounter;
    } finally {
      lock.unlock();
    }
  }

  public int ownerLockCalls() {
    lock.lock();
    try {
      return ownerLockCalls;
    } finally {
      lock.unlock();
    }
  }

  /** Liest den festgeschriebenen Zustand, unabhaengig von Fehlerinjektion. */
  public <T> T read(Function<UnitOfWork, T> reader) {
    lock.lock();
    try {
      return reader.apply(new Uow(committed.copy(), clock.millis()));
    } finally {
      lock.unlock();
    }
  }

  /** Grund einer REVIEW-Markierung. */
  public String reviewReason(UUID payoutId) {
    lock.lock();
    try {
      return committed.reviewReasons.get(payoutId);
    } finally {
      lock.unlock();
    }
  }

  /** Test-Hilfe: schreibt einen Journal-Eintrag direkt (z. B. um fremde Zustaende zu bauen). */
  public void putJournal(JournalEntry entry) {
    lock.lock();
    try {
      committed.journal.put(entry.id(), entry);
      committed.journalKeys.put(entry.idempotencyKey(), entry.id());
    } finally {
      lock.unlock();
    }
  }

  /** Gesamter festgeschriebener Zustand fuer Invarianten-Pruefungen. */
  public record Snapshot(
      List<Order> orders,
      List<JournalEntry> journal,
      List<LedgerRepository.Entry> ledger,
      List<PayoutRepository.Item> payouts,
      List<ParcelRepository.Parcel> claims,
      List<ParcelRepository.Parcel> returns) {}

  public java.util.List<dev.orderhub.storage.HoldRepository.Hold> holds() {
    lock.lock();
    try {
      return List.copyOf(committed.holds.values());
    } finally {
      lock.unlock();
    }
  }

  public Snapshot snapshot() {
    lock.lock();
    try {
      return new Snapshot(
          List.copyOf(committed.orders.values()),
          List.copyOf(committed.journal.values()),
          List.copyOf(committed.ledger),
          List.copyOf(committed.payouts.values()),
          List.copyOf(committed.claims.values()),
          List.copyOf(committed.returns.values()));
    } finally {
      lock.unlock();
    }
  }

  // ---------------------------------------------------------------- Unit of Work

  private final class Uow implements UnitOfWork {
    private final State s;
    private final long now;
    private final Set<UUID> lockedOwners = new HashSet<>();
    private final OrderRepository orders = new Orders();
    private final JournalRepository journal = new Journal();
    private final LedgerRepository ledger = new Ledger();
    private final PayoutRepository payouts = new Payouts();
    private final ParcelRepository claims;
    private final ParcelRepository returns;

    Uow(State s, long now) {
      this.s = s;
      this.now = now;
      this.claims = new Parcels(() -> s.claims, () -> s.claimKeys);
      this.returns = new Parcels(() -> s.returns, () -> s.returnKeys);
    }

    @Override
    public OrderRepository orders() {
      return orders;
    }

    @Override
    public JournalRepository journal() {
      return journal;
    }

    @Override
    public LedgerRepository ledger() {
      return ledger;
    }

    @Override
    public PayoutRepository payouts() {
      return payouts;
    }

    @Override
    public AuditRepository audit() {
      return new AuditRepository() {
        @Override
        public void append(AuditRepository.Entry entry) {
          s.audit.add(entry);
        }

        @Override
        public java.util.List<AuditRepository.Entry> recent(int limit) {
          int from = Math.max(0, s.audit.size() - limit);
          java.util.List<AuditRepository.Entry> out =
              new java.util.ArrayList<>(s.audit.subList(from, s.audit.size()));
          java.util.Collections.reverse(out);
          return out;
        }
      };
    }

    @Override
    public ParcelRepository claims() {
      return claims;
    }

    @Override
    public dev.orderhub.storage.TemplateRepository templates() {
      return new dev.orderhub.storage.TemplateRepository() {
        private String key(UUID owner, String name) {
          return owner + "/" + name;
        }

        @Override
        public boolean save(dev.orderhub.domain.OrderTemplate t, int maxPerPlayer) {
          String k = key(t.owner(), t.name());
          boolean exists = s.templates.containsKey(k);
          long own = s.templates.values().stream().filter(x -> x.owner().equals(t.owner())).count();
          if (!exists && own >= maxPerPlayer) {
            return false;
          }
          long id = exists ? s.templates.get(k).id() : s.nextTemplateId++;
          s.templates.put(
              k,
              new dev.orderhub.domain.OrderTemplate(
                  id,
                  t.owner(),
                  t.name(),
                  t.item(),
                  t.unitPrice(),
                  t.quantity(),
                  t.minDelivery(),
                  t.lifetimeHours(),
                  t.options(),
                  t.autoRenew(),
                  t.createdAt()));
          return true;
        }

        @Override
        public java.util.Optional<dev.orderhub.domain.OrderTemplate> find(UUID owner, String name) {
          return java.util.Optional.ofNullable(s.templates.get(key(owner, name)));
        }

        @Override
        public List<dev.orderhub.domain.OrderTemplate> list(UUID owner) {
          return s.templates.values().stream()
              .filter(x -> x.owner().equals(owner))
              .sorted(Comparator.comparing(dev.orderhub.domain.OrderTemplate::name))
              .toList();
        }

        @Override
        public boolean delete(UUID owner, String name) {
          return s.templates.remove(key(owner, name)) != null;
        }
      };
    }

    @Override
    public dev.orderhub.storage.HoldRepository holds() {
      return new dev.orderhub.storage.HoldRepository() {
        @Override
        public void add(
            UUID txId,
            UUID owner,
            Long orderId,
            dev.orderhub.domain.ItemSnapshot item,
            int quantity,
            long at) {
          if (quantity <= 0) {
            throw new ConstraintViolation("hold quantity must be positive");
          }
          s.holds.putIfAbsent(
              txId, new Hold(txId, owner, orderId, item, quantity, State.HELD, at, at));
        }

        @Override
        public java.util.Optional<Hold> find(UUID txId) {
          return java.util.Optional.ofNullable(s.holds.get(txId));
        }

        @Override
        public boolean finish(UUID txId, State target, long at) {
          Hold h = s.holds.get(txId);
          if (h == null || h.state() != State.HELD) {
            return false;
          }
          s.holds.put(
              txId,
              new Hold(
                  h.txId(),
                  h.owner(),
                  h.orderId(),
                  h.item(),
                  h.quantity(),
                  target,
                  h.createdAt(),
                  at));
          return true;
        }
      };
    }

    @Override
    public ParcelRepository returns() {
      return returns;
    }

    @Override
    public long now() {
      return now;
    }

    private final class Orders implements OrderRepository {
      @Override
      public Order insert(NewOrder o, long createdAt) {
        Order order =
            new Order(
                s.nextOrderId++,
                o.owner(),
                o.serverId(),
                o.item(),
                o.unitPrice(),
                o.quantity(),
                0,
                o.minDelivery(),
                o.escrow(),
                OrderStatus.ACTIVE,
                createdAt,
                o.expiresAt(),
                createdAt,
                0,
                o.autoRenew(),
                o.category());
        s.orders.put(order.id(), order);
        return order;
      }

      @Override
      public Optional<Order> find(long id) {
        return Optional.ofNullable(s.orders.get(id));
      }

      @Override
      public Optional<Order> findForUpdate(long id) {
        return find(id);
      }

      @Override
      public boolean update(Order next) {
        Order stored = s.orders.get(next.id());
        if (stored == null || stored.version() != next.version() - 1) {
          return false;
        }
        if (next.escrowRemaining() < 0
            || next.quantityFilled() < 0
            || next.quantityFilled() > next.quantityTotal()) {
          throw new ConstraintViolation("order check failed");
        }
        s.orders.put(next.id(), next);
        return true;
      }

      @Override
      public List<Order> search(OrderQuery q) {
        // Reihenfolge und Cursor entsprechen den SQL-Sortierungen in JdbcOrderRepository.
        Comparator<Order> cmp =
            switch (q.sort()) {
              case NEWEST ->
                  Comparator.comparingLong(Order::createdAt)
                      .thenComparingLong(Order::id)
                      .reversed();
              case HIGHEST_PRICE ->
                  Comparator.comparingLong(Order::unitPrice)
                      .reversed()
                      .thenComparingLong(Order::id);
              case LOWEST_PRICE ->
                  Comparator.comparingLong(Order::unitPrice).thenComparingLong(Order::id);
              case ALMOST_COMPLETED ->
                  Comparator.comparingInt(Order::fillPermille)
                      .reversed()
                      .thenComparingLong(Order::id);
              case HIGHEST_DEMAND ->
                  Comparator.comparingInt(Order::remaining)
                      .reversed()
                      .thenComparing(Comparator.comparingLong(Order::escrowRemaining).reversed())
                      .thenComparingLong(Order::id);
            };
        List<Order> sorted =
            s.orders.values().stream().filter(o -> matches(o, q)).sorted(cmp).toList();
        if (q.after() != null) {
          OrderQuery.Cursor c = q.after();
          int from = 0;
          for (Order o : sorted) {
            // Erste Zeile, die in der Sortierung hinter dem Cursor liegt.
            OrderQuery.Cursor oc = OrderQuery.Cursor.of(q.sort(), o);
            if (isAfter(q.sort(), oc, c)) {
              break;
            }
            from++;
          }
          return sorted.stream().skip(from).limit(q.limit()).toList();
        }
        return sorted.stream().skip(q.offset()).limit(q.limit()).toList();
      }

      private boolean isAfter(OrderQuery.Sort sort, OrderQuery.Cursor row, OrderQuery.Cursor c) {
        return switch (sort) {
          case NEWEST -> row.k1() < c.k1() || (row.k1() == c.k1() && row.id() < c.id());
          case HIGHEST_PRICE, ALMOST_COMPLETED ->
              row.k1() < c.k1() || (row.k1() == c.k1() && row.id() > c.id());
          case LOWEST_PRICE -> row.k1() > c.k1() || (row.k1() == c.k1() && row.id() > c.id());
          case HIGHEST_DEMAND ->
              row.k1() < c.k1()
                  || (row.k1() == c.k1() && row.k2() < c.k2())
                  || (row.k1() == c.k1() && row.k2() == c.k2() && row.id() > c.id());
        };
      }

      @Override
      public List<CategoryRow> findLegacyCategories(long afterId, int limit) {
        return s.orders.values().stream()
            .filter(o -> o.id() > afterId)
            .filter(o -> o.status() == OrderStatus.ACTIVE || o.status() == OrderStatus.PAUSED)
            .filter(o -> java.util.Set.of("blocks", "food", "items").contains(o.category()))
            .sorted(Comparator.comparingLong(Order::id))
            .limit(limit)
            .map(o -> new CategoryRow(o.id(), o.item().itemKey(), o.category()))
            .toList();
      }

      @Override
      public void setCategory(long orderId, String category) {
        Order o = s.orders.get(orderId);
        s.orders.put(
            orderId,
            new Order(
                o.id(),
                o.owner(),
                o.serverId(),
                o.item(),
                o.unitPrice(),
                o.quantityTotal(),
                o.quantityFilled(),
                o.minDelivery(),
                o.escrowRemaining(),
                o.status(),
                o.createdAt(),
                o.expiresAt(),
                o.updatedAt(),
                o.version(),
                o.autoRenew(),
                category));
      }

      @Override
      public dev.orderhub.domain.OrderOptions options(long orderId) {
        return s.options.getOrDefault(orderId, dev.orderhub.domain.OrderOptions.EXACT);
      }

      @Override
      public void saveOptions(long orderId, dev.orderhub.domain.OrderOptions options) {
        s.options.put(orderId, options);
      }

      @Override
      public long count(OrderQuery q) {
        return s.orders.values().stream().filter(o -> matches(o, q)).count();
      }

      private boolean matches(Order o, OrderQuery q) {
        return (q.owner() == null || q.owner().equals(o.owner()))
            && (q.itemKey() == null || q.itemKey().equals(o.item().itemKey()))
            && (q.category() == null || q.category().equals(o.category()))
            && (q.text() == null || o.item().itemKey().contains(q.text()))
            && o.status() == q.status();
      }

      @Override
      public void lockOwner(UUID owner) {
        lockedOwners.add(owner);
        ownerLockCalls++;
      }

      @Override
      public int countOpenByOwner(UUID owner) {
        if (!lockedOwners.contains(owner)) {
          throw new ConstraintViolation("countOpenByOwner without lockOwner");
        }
        return (int)
            s.orders.values().stream()
                .filter(o -> o.owner().equals(owner))
                .filter(o -> o.status() == OrderStatus.ACTIVE || o.status() == OrderStatus.PAUSED)
                .count();
      }

      @Override
      public List<Order> findExpired(long at, int limit) {
        return s.orders.values().stream()
            .filter(o -> o.status() == OrderStatus.ACTIVE && o.expiresAt() <= at)
            .sorted(Comparator.comparingLong(Order::expiresAt))
            .limit(limit)
            .toList();
      }
    }

    private final class Journal implements JournalRepository {
      @Override
      public InsertResult insertIfAbsent(JournalEntry entry) {
        UUID existing = s.journalKeys.get(entry.idempotencyKey());
        if (existing != null) {
          return new InsertResult(s.journal.get(existing), false);
        }
        if (s.journal.containsKey(entry.id())) {
          throw new ConstraintViolation("duplicate journal id");
        }
        s.journal.put(entry.id(), entry);
        s.journalKeys.put(entry.idempotencyKey(), entry.id());
        return new InsertResult(entry, true);
      }

      @Override
      public Optional<JournalEntry> find(UUID id) {
        return Optional.ofNullable(s.journal.get(id));
      }

      @Override
      public Optional<JournalEntry> findByKey(String key) {
        UUID id = s.journalKeys.get(key);
        return id == null ? Optional.empty() : Optional.of(s.journal.get(id));
      }

      @Override
      public boolean transition(JournalEntry current, TxState target, String detail, long at) {
        if (!current.state().canTransitionTo(target)) {
          throw new InvalidTransitionException(current.state(), target);
        }
        JournalEntry stored = s.journal.get(current.id());
        if (stored == null
            || stored.state() != current.state()
            || stored.version() != current.version()) {
          return false;
        }
        s.journal.put(
            current.id(),
            new JournalEntry(
                stored.id(),
                stored.idempotencyKey(),
                stored.orderId(),
                stored.playerId(),
                stored.serverId(),
                stored.type(),
                stored.item(),
                stored.quantity(),
                stored.amount(),
                target,
                stored.version() + 1,
                stored.createdAt(),
                at,
                detail));
        return true;
      }

      @Override
      public List<JournalEntry> findByStates(EnumSet<TxState> states, int limit) {
        return s.journal.values().stream()
            .filter(e -> states.contains(e.state()))
            .sorted(Comparator.comparingLong(JournalEntry::updatedAt))
            .limit(limit)
            .toList();
      }

      @Override
      public List<JournalEntry> findByPlayer(UUID player, int offset, int limit) {
        return s.journal.values().stream()
            .filter(e -> e.playerId().equals(player))
            .skip(offset)
            .limit(limit)
            .toList();
      }

      @Override
      public List<JournalEntry> findByOrder(long orderId) {
        return s.journal.values().stream()
            .filter(e -> e.orderId() != null && e.orderId() == orderId)
            .toList();
      }

      @Override
      public List<JournalEntry> findRecent(int offset, int limit) {
        return s.journal.values().stream().skip(offset).limit(limit).toList();
      }
    }

    private final class Ledger implements LedgerRepository {
      @Override
      public void append(Entry entry) {
        if (entry.amount() <= 0) {
          throw new ConstraintViolation("ledger amount must be positive");
        }
        if (s.ledger.stream().anyMatch(e -> e.id().equals(entry.id()))) {
          throw new ConstraintViolation("duplicate ledger id");
        }
        s.ledger.add(entry);
      }

      @Override
      public List<Entry> findByTx(UUID txId) {
        return s.ledger.stream().filter(e -> txId.equals(e.txId())).toList();
      }

      @Override
      public List<Entry> findAll(int offset, int limit) {
        return s.ledger.stream().skip(offset).limit(limit).toList();
      }

      @Override
      public List<Entry> findByPlayer(UUID player, int offset, int limit) {
        return s.ledger.stream()
            .filter(e -> player.equals(e.player()))
            .skip(offset)
            .limit(limit)
            .toList();
      }
    }

    private final class Payouts implements PayoutRepository {
      @Override
      public void enqueue(UUID txId, UUID player, Kind kind, long amount, long at) {
        String key = txId + ":" + kind;
        if (s.payoutKeys.containsKey(key)) {
          throw new ConstraintViolation("UNIQUE(tx_id, kind) " + key);
        }
        if (amount <= 0) {
          throw new ConstraintViolation("payout amount must be positive");
        }
        Item item = new Item(UUID.randomUUID(), txId, player, kind, amount, State.PENDING, 0, at);
        s.payouts.put(item.id(), item);
        s.payoutKeys.put(key, item.id());
      }

      @Override
      public List<Item> claimBatch(int limit, long at) {
        List<Item> claimed = new ArrayList<>();
        List<Item> pending =
            s.payouts.values().stream()
                .filter(i -> i.state() == State.PENDING)
                .sorted(Comparator.comparingLong(Item::updatedAt))
                .limit(limit)
                .toList();
        for (Item i : pending) {
          Item next = copy(i, State.IN_PROGRESS, i.attempts(), at);
          s.payouts.put(i.id(), next);
          claimed.add(next);
        }
        return claimed;
      }

      @Override
      public Optional<Item> findByTx(UUID txId, Kind kind) {
        UUID id = s.payoutKeys.get(txId + ":" + kind);
        return id == null ? Optional.empty() : Optional.of(s.payouts.get(id));
      }

      @Override
      public boolean markCompleted(UUID id, long at) {
        Item i = s.payouts.get(id);
        if (i == null || i.state() != State.IN_PROGRESS) {
          return false;
        }
        s.payouts.put(id, copy(i, State.COMPLETED, i.attempts(), at));
        return true;
      }

      @Override
      public boolean release(UUID id, long at) {
        Item i = s.payouts.get(id);
        if (i == null || i.state() != State.IN_PROGRESS) {
          return false;
        }
        s.payouts.put(id, copy(i, State.PENDING, i.attempts() + 1, at));
        return true;
      }

      @Override
      public boolean markReview(UUID id, String reason, long at) {
        Item i = s.payouts.get(id);
        if (i == null || i.state() != State.IN_PROGRESS) {
          return false;
        }
        s.payouts.put(id, copy(i, State.REVIEW, i.attempts(), at));
        s.reviewReasons.put(id, reason);
        return true;
      }

      @Override
      public boolean markReviewIfUnchanged(
          UUID id, long expectedUpdatedAt, String reason, long at) {
        Item i = s.payouts.get(id);
        if (i == null || i.state() != State.IN_PROGRESS || i.updatedAt() != expectedUpdatedAt) {
          return false;
        }
        return markReview(id, reason, at);
      }

      @Override
      public boolean resolveReview(UUID txId, Kind kind, State target, long at) {
        UUID id = s.payoutKeys.get(txId + ":" + kind);
        Item i = id == null ? null : s.payouts.get(id);
        if (i == null || i.state() != State.REVIEW) {
          return false;
        }
        s.payouts.put(id, copy(i, target, i.attempts(), at));
        return true;
      }

      @Override
      public List<Item> findByState(State state, int limit) {
        return s.payouts.values().stream().filter(i -> i.state() == state).limit(limit).toList();
      }

      private Item copy(Item i, State state, int attempts, long at) {
        return new Item(i.id(), i.txId(), i.player(), i.kind(), i.amount(), state, attempts, at);
      }
    }

    private final class Parcels implements ParcelRepository {
      private final java.util.function.Supplier<Map<Long, Parcel>> map;
      private final java.util.function.Supplier<Map<String, Long>> keys;

      Parcels(
          java.util.function.Supplier<Map<Long, Parcel>> map,
          java.util.function.Supplier<Map<String, Long>> keys) {
        this.map = map;
        this.keys = keys;
      }

      @Override
      public long add(
          UUID owner, Long orderId, ItemSnapshot item, int quantity, UUID sourceTx, long at) {
        String key = sourceTx + ":" + owner;
        Long existing = keys.get().get(key);
        if (existing != null) {
          return existing;
        }
        if (quantity < 0) {
          throw new ConstraintViolation("quantity >= 0");
        }
        long id = s.nextParcelId++;
        map.get()
            .put(
                id,
                new Parcel(
                    id, owner, orderId, item, quantity, State.AVAILABLE, null, sourceTx, at, at));
        keys.get().put(key, id);
        return id;
      }

      @Override
      public Optional<Parcel> find(long id) {
        return Optional.ofNullable(map.get().get(id));
      }

      @Override
      public List<Parcel> listAvailable(UUID owner, int offset, int limit) {
        return map.get().values().stream()
            .filter(p -> p.owner().equals(owner) && p.state() == State.AVAILABLE)
            .skip(offset)
            .limit(limit)
            .toList();
      }

      @Override
      public int countAvailable(UUID owner) {
        return (int)
            map.get().values().stream()
                .filter(p -> p.owner().equals(owner) && p.state() == State.AVAILABLE)
                .count();
      }

      @Override
      public boolean reserve(long id, UUID tx, long at) {
        Parcel p = map.get().get(id);
        if (p == null || p.state() != State.AVAILABLE) {
          return false;
        }
        map.get().put(id, with(p, p.quantity(), State.RESERVED, tx, at));
        return true;
      }

      @Override
      public boolean settle(long id, UUID tx, int transferred, long at) {
        Parcel p = map.get().get(id);
        if (p == null
            || p.state() != State.RESERVED
            || !tx.equals(p.reservedBy())
            || transferred < 0
            || transferred > p.quantity()) {
          return false;
        }
        int rest = p.quantity() - transferred;
        map.get().put(id, with(p, rest, rest > 0 ? State.AVAILABLE : State.CLAIMED, null, at));
        return true;
      }

      @Override
      public boolean releaseReservation(long id, UUID tx, long at) {
        Parcel p = map.get().get(id);
        if (p == null || p.state() != State.RESERVED || !tx.equals(p.reservedBy())) {
          return false;
        }
        map.get().put(id, with(p, p.quantity(), State.AVAILABLE, null, at));
        return true;
      }

      @Override
      public List<Parcel> findReserved(int limit) {
        return map.get().values().stream()
            .filter(p -> p.state() == State.RESERVED)
            .limit(limit)
            .toList();
      }

      private Parcel with(Parcel p, int quantity, State state, UUID reservedBy, long at) {
        return new Parcel(
            p.id(),
            p.owner(),
            p.orderId(),
            p.item(),
            quantity,
            state,
            reservedBy,
            p.sourceTx(),
            p.createdAt(),
            at);
      }
    }
  }
}
