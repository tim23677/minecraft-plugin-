package dev.orderhub.flow;

import static org.assertj.core.api.Assertions.assertThat;

import dev.orderhub.bootstrap.Settings;
import dev.orderhub.tx.DeliveryCommitted;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PairWatchTest extends FlowTestBase {

  private static final UUID SUPPLIER = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
  private long orderId;

  private Services withThreshold(int threshold) throws Exception {
    YamlConfiguration yaml = new YamlConfiguration();
    try (var in = getClass().getClassLoader().getResourceAsStream("config.yml")) {
      yaml.load(new InputStreamReader(in, StandardCharsets.UTF_8));
    }
    yaml.set("anti-exploit.pair-trades-per-day", threshold);
    Settings settings = Settings.from(yaml);
    Services base = services;
    return new Services(
        base.logger(),
        base.storage(),
        base.scheduler(),
        base.messages(),
        () -> settings,
        base.rates(),
        base.orders(),
        base.delivery(),
        base.claims(),
        base.returns(),
        base.payouts(),
        base.codec(),
        base.matcher(),
        base.menus(),
        base.insights(),
        base.discord());
  }

  private java.util.List<dev.orderhub.storage.AuditRepository.Entry> audit() {
    java.util.List<dev.orderhub.storage.AuditRepository.Entry> rows =
        h.storage.read(uow -> uow.audit().recent(10));
    return rows;
  }

  @BeforeEach
  void setUp() {
    h.economy.setBalance(OWNER, 1_000_000_000);
    orderId = h.create(OWNER, 100, 100, 1).orderId();
  }

  private DeliveryCommitted deliver(UUID supplier, UUID owner) {
    h.deliver(orderId, supplier, 1);
    return new DeliveryCommitted(
        UUID.randomUUID(),
        orderId,
        "minecraft:diamond",
        supplier,
        owner,
        1,
        100,
        100,
        0,
        100,
        false,
        0,
        "t");
  }

  @Test
  void theThresholdWritesOneAuditEntryAndNotifiesAdminsOnce() throws Exception {
    var admin = server.addPlayer();
    admin.addAttachment(plugin, "orderhub.admin.view", true);
    PairWatch watch = new PairWatch(withThreshold(3));

    int c1 = watch.check(deliver(SUPPLIER, OWNER));
    int c2 = watch.check(deliver(SUPPLIER, OWNER));
    assertThat(audit()).isEmpty();
    int c3 = watch.check(deliver(SUPPLIER, OWNER));
    int c4 = watch.check(deliver(SUPPLIER, OWNER));

    assertThat(new int[] {c1, c2, c3, c4}).containsExactly(1, 2, 3, 4);
    var audit = audit();
    assertThat(audit).hasSize(1);
    assertThat(audit.get(0).action()).isEqualTo("suspicious.pair");
    assertThat(audit.get(0).target()).isEqualTo(SUPPLIER.toString());
    String message = null;
    net.kyori.adventure.text.Component c;
    while ((c = admin.nextComponentMessage()) != null) {
      message =
          net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
              .serialize(c);
    }
    assertThat(message).contains("delivered 3 times").contains("within 24 hours");
  }

  @Test
  void aPlayerWithoutTheAdminPermissionIsNotNotified() throws Exception {
    var plain = server.addPlayer();
    PairWatch watch = new PairWatch(withThreshold(1));

    watch.check(deliver(SUPPLIER, OWNER));

    assertThat(plain.nextComponentMessage()).isNull();
    assertThat(audit()).hasSize(1);
  }

  @Test
  void zeroSwitchesTheCheckOffAndOtherPairsAreCountedSeparately() throws Exception {
    PairWatch off = new PairWatch(withThreshold(0));
    assertThat(off.check(deliver(SUPPLIER, OWNER))).isZero();
    PairWatch watch = new PairWatch(withThreshold(2));
    UUID other = UUID.fromString("00000000-0000-0000-0000-0000000000a2");

    watch.check(deliver(SUPPLIER, OWNER)); // zweite Lieferung des Paares insgesamt
    int otherCount = watch.check(deliver(other, OWNER));

    assertThat(otherCount).isEqualTo(1);
  }
}
