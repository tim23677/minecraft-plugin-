package dev.orderhub.item;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.orderhub.domain.ItemSnapshot;
import java.nio.ByteBuffer;
import java.util.Arrays;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

/**
 * Codec-Tests. MockBukkit serialisiert Items ueber Java-Serialisierung der ItemMeta, nicht ueber
 * NBT/Datenkomponenten: Material und Menge laufen im Roundtrip, per setData gesetzte Komponenten
 * nicht. Die Format- und Manipulationspruefungen gehoeren dem Codec und sind davon unabhaengig.
 */
class ItemCodecTest {

  private final ItemCodec codec = new ItemCodec(() -> 1);

  @BeforeEach
  void setUp() {
    MockBukkit.mock();
  }

  @AfterEach
  void tearDown() {
    MockBukkit.unmock();
  }

  private static byte[] header(int formatVersion, int dataVersion, byte[] payload) {
    return ByteBuffer.allocate(12 + payload.length)
        .put(new byte[] {'O', 'H', 'I', '1'})
        .putInt(formatVersion)
        .putInt(dataVersion)
        .put(payload)
        .array();
  }

  private ItemSnapshot valid() throws ItemCodecException {
    return codec.encode(new ItemStack(Material.DIAMOND, 7));
  }

  @Test
  void roundtripNormalizesAmountToOne() throws ItemCodecException {
    ItemSnapshot snapshot = codec.encode(new ItemStack(Material.DIAMOND, 7));
    ItemStack decoded = codec.decode(snapshot);
    assertThat(snapshot.itemKey()).isEqualTo("minecraft:diamond");
    assertThat(snapshot.formatVersion()).isEqualTo(ItemCodec.FORMAT_VERSION);
    assertThat(decoded.getType()).isEqualTo(Material.DIAMOND);
    assertThat(decoded.getAmount()).isEqualTo(1);
  }

  @Test
  void encodeDoesNotMutateInput() throws ItemCodecException {
    ItemStack input = new ItemStack(Material.DIAMOND, 7);
    codec.encode(input);
    assertThat(input.getAmount()).isEqualTo(7);
  }

  @Test
  void roundtripKeepsItemMeta() throws ItemCodecException {
    ItemStack input = new ItemStack(Material.DIAMOND_SWORD);
    ItemMeta meta = input.getItemMeta();
    meta.displayName(Component.text("n"));
    input.setItemMeta(meta);
    ItemStack decoded = codec.decode(codec.encode(input));
    assertThat(decoded.getType()).isEqualTo(Material.DIAMOND_SWORD);
    assertThat(decoded.getItemMeta().hasDisplayName()).isTrue();
  }

  @Test
  void snapshotStartsWithMagicAndVersions() throws ItemCodecException {
    byte[] data = valid().data();
    assertThat(Arrays.copyOf(data, 4)).containsExactly('O', 'H', 'I', '1');
    assertThat(ByteBuffer.wrap(data).getInt(4)).isEqualTo(ItemCodec.FORMAT_VERSION);
    assertThat(ByteBuffer.wrap(data).getInt(8)).isEqualTo(1);
  }

  @Test
  void encodeRejectsNullAirAndEmpty() {
    assertThatThrownBy(() -> codec.encode(null)).isInstanceOf(ItemCodecException.class);
    assertReason(
        () -> codec.encode(new ItemStack(Material.AIR)), ItemCodecException.Reason.EMPTY_ITEM);
  }

  @Test
  void encodeRejectsOversizedItem() {
    ItemStack big = new ItemStack(Material.DIAMOND);
    ItemMeta meta = big.getItemMeta();
    meta.displayName(Component.text("x".repeat(ItemSnapshot.MAX_DATA_BYTES * 2)));
    big.setItemMeta(meta);
    assertReason(() -> codec.encode(big), ItemCodecException.Reason.TOO_LARGE);
  }

  @Test
  void snapshotRejectsOversizedDataAtConstruction() {
    byte[] huge = new byte[ItemSnapshot.MAX_DATA_BYTES + 1];
    assertThatThrownBy(() -> new ItemSnapshot("minecraft:diamond", 1, huge))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void decodeRejectsTruncatedHeader() {
    byte[] shortData = {'O', 'H', 'I', '1', 0, 0};
    assertReason(
        () -> codec.decode(new ItemSnapshot("minecraft:diamond", 1, shortData)),
        ItemCodecException.Reason.TRUNCATED);
  }

  @Test
  void decodeRejectsTruncatedPayload() throws ItemCodecException {
    byte[] data = valid().data();
    byte[] cut = Arrays.copyOf(data, data.length / 2);
    assertReason(
        () -> codec.decode(new ItemSnapshot("minecraft:diamond", 1, cut)),
        ItemCodecException.Reason.CORRUPT_PAYLOAD);
  }

  @Test
  void decodeRejectsWrongMagic() throws ItemCodecException {
    byte[] data = valid().data();
    data[0] = 'X';
    assertReason(
        () -> codec.decode(new ItemSnapshot("minecraft:diamond", 1, data)),
        ItemCodecException.Reason.BAD_MAGIC);
  }

  @Test
  void decodeRejectsWrongKey() throws ItemCodecException {
    ItemSnapshot real = valid();
    assertReason(
        () -> codec.decode(new ItemSnapshot("minecraft:stone", 1, real.data())),
        ItemCodecException.Reason.KEY_MISMATCH);
  }

  @Test
  void decodeRejectsAirKey() throws ItemCodecException {
    ItemSnapshot real = valid();
    assertReason(
        () -> codec.decode(new ItemSnapshot("minecraft:air", 1, real.data())),
        ItemCodecException.Reason.KEY_MISMATCH);
  }

  @Test
  void decodeRejectsUnknownFormatVersion() throws ItemCodecException {
    byte[] payload = Arrays.copyOfRange(valid().data(), 12, valid().data().length);
    byte[] data = header(2, 1, payload);
    assertReason(
        () -> codec.decode(new ItemSnapshot("minecraft:diamond", 1, data)),
        ItemCodecException.Reason.UNSUPPORTED_FORMAT_VERSION);
  }

  @Test
  void decodeRejectsSnapshotVersionMismatch() throws ItemCodecException {
    byte[] data = valid().data();
    assertReason(
        () -> codec.decode(new ItemSnapshot("minecraft:diamond", 2, data)),
        ItemCodecException.Reason.UNSUPPORTED_FORMAT_VERSION);
  }

  @Test
  void decodeRejectsDataVersionNewerThanServerOrInvalid() throws ItemCodecException {
    byte[] payload = Arrays.copyOfRange(valid().data(), 12, valid().data().length);
    byte[] newer = header(1, 2, payload);
    byte[] zero = header(1, 0, payload);
    assertReason(
        () -> codec.decode(new ItemSnapshot("minecraft:diamond", 1, newer)),
        ItemCodecException.Reason.UNSUPPORTED_DATA_VERSION);
    assertReason(
        () -> codec.decode(new ItemSnapshot("minecraft:diamond", 1, zero)),
        ItemCodecException.Reason.UNSUPPORTED_DATA_VERSION);
  }

  @Test
  void decodeRejectsAmountDifferentFromOne() {
    byte[] payload = new ItemStack(Material.DIAMOND, 5).serializeAsBytes();
    byte[] data = header(1, 1, payload);
    assertReason(
        () -> codec.decode(new ItemSnapshot("minecraft:diamond", 1, data)),
        ItemCodecException.Reason.INVALID_AMOUNT);
  }

  @Test
  void decodeRejectsGarbagePayload() {
    byte[] data = header(1, 1, new byte[] {1, 2, 3, 4, 5, 6});
    assertReason(
        () -> codec.decode(new ItemSnapshot("minecraft:diamond", 1, data)),
        ItemCodecException.Reason.CORRUPT_PAYLOAD);
  }

  @Test
  void decodeRejectsHeaderOnly() {
    byte[] data = header(1, 1, new byte[0]);
    assertReason(
        () -> codec.decode(new ItemSnapshot("minecraft:diamond", 1, data)),
        ItemCodecException.Reason.TRUNCATED);
  }

  @Test
  void everyBitFlipInHeaderIsRejectedOrYieldsExactItem() throws ItemCodecException {
    byte[] original = valid().data();
    for (int i = 0; i < 12; i++) {
      byte[] mutated = original.clone();
      mutated[i] ^= 0x01;
      try {
        ItemStack stack = codec.decode(new ItemSnapshot("minecraft:diamond", 1, mutated));
        // Ein stilles Akzeptieren waere nur korrekt, wenn das Ergebnis exakt dem Original
        // entspricht.
        assertThat(stack.getType()).isEqualTo(Material.DIAMOND);
        assertThat(i).as("header byte %d must not be silently accepted", i).isGreaterThan(11);
      } catch (ItemCodecException expected) {
        assertThat(expected.reason()).isNotNull();
      }
    }
  }

  private static void assertReason(ThrowingRunnable runnable, ItemCodecException.Reason reason) {
    assertThatThrownBy(runnable::run)
        .isInstanceOfSatisfying(
            ItemCodecException.class, e -> assertThat(e.reason()).isEqualTo(reason));
  }

  @FunctionalInterface
  private interface ThrowingRunnable {
    void run() throws ItemCodecException;
  }
}
