package dev.orderhub.item;

import dev.orderhub.domain.ItemSnapshot;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.IntSupplier;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;

/**
 * Wandelt {@link ItemStack} (Einzelitem) in {@link ItemSnapshot} und zurueck.
 *
 * <p>Format: {@code [Magic 'OHI1'][formatVersion int][dataVersion int][ItemStack.serializeAsBytes]}.
 * Es wird nie Java-Deserialisierung eigener Daten verwendet. Dekodieren prueft Magic, Versionen,
 * Groesse, Menge 1, Material gegen {@code itemKey} und lehnt AIR ab. Jeder Fehler ist eine {@link
 * ItemCodecException}, es gibt keinen Teilzustand.
 *
 * <p>Thread-Hinweis: Erzeugt und liest Bukkit-Objekte, daher nur im passenden Server-, Region- oder
 * Entity-Kontext verwenden. Die erzeugten {@link ItemSnapshot}s sind reine Bytes und duerfen
 * beliebig weitergereicht werden.
 */
public final class ItemCodec {

  /** Aktuelle Version des OrderHub-Formats. */
  public static final int FORMAT_VERSION = 1;

  private static final byte[] MAGIC = {'O', 'H', 'I', '1'};
  private static final int HEADER_BYTES = MAGIC.length + Integer.BYTES + Integer.BYTES;

  private final IntSupplier serverDataVersion;

  /** Verwendet die Datenversion des laufenden Servers. */
  public ItemCodec() {
    this(ItemCodec::currentServerDataVersion);
  }

  /** Fuer Tests und Migrationen: Datenversion des Servers frei waehlbar. */
  public ItemCodec(IntSupplier serverDataVersion) {
    this.serverDataVersion = Objects.requireNonNull(serverDataVersion, "serverDataVersion");
  }

  @SuppressWarnings("deprecation") // Paper bietet fuer die Datenversion nur UnsafeValues an
  private static int currentServerDataVersion() {
    return Bukkit.getUnsafe().getDataVersion();
  }

  /** Kodiert {@code stack} als Einzelitem (Menge wird auf 1 normalisiert). */
  public ItemSnapshot encode(ItemStack stack) throws ItemCodecException {
    if (stack == null || stack.isEmpty()) {
      throw new ItemCodecException(ItemCodecException.Reason.EMPTY_ITEM, "no item");
    }
    ItemStack one = stack.clone();
    one.setAmount(1);
    byte[] payload;
    try {
      payload = one.serializeAsBytes();
    } catch (RuntimeException e) {
      throw new ItemCodecException(
          ItemCodecException.Reason.SERIALIZATION_FAILED, "item cannot be serialized", e);
    }
    if (payload == null || payload.length == 0) {
      throw new ItemCodecException(
          ItemCodecException.Reason.SERIALIZATION_FAILED, "empty serialization");
    }
    if (HEADER_BYTES + payload.length > ItemSnapshot.MAX_DATA_BYTES) {
      throw new ItemCodecException(
          ItemCodecException.Reason.TOO_LARGE,
          (HEADER_BYTES + payload.length) + " > " + ItemSnapshot.MAX_DATA_BYTES);
    }
    ByteBuffer buffer = ByteBuffer.allocate(HEADER_BYTES + payload.length);
    buffer.put(MAGIC).putInt(FORMAT_VERSION).putInt(serverDataVersion.getAsInt()).put(payload);
    return new ItemSnapshot(one.getType().getKey().toString(), FORMAT_VERSION, buffer.array());
  }

  /** Dekodiert zu einem Einzelitem (Menge 1) oder wirft {@link ItemCodecException}. */
  public ItemStack decode(ItemSnapshot snapshot) throws ItemCodecException {
    Objects.requireNonNull(snapshot, "snapshot");
    byte[] data = snapshot.data();
    if (data.length > ItemSnapshot.MAX_DATA_BYTES) {
      throw new ItemCodecException(ItemCodecException.Reason.TOO_LARGE, "size " + data.length);
    }
    if (data.length <= HEADER_BYTES) {
      throw new ItemCodecException(ItemCodecException.Reason.TRUNCATED, "size " + data.length);
    }
    if (!Arrays.equals(Arrays.copyOfRange(data, 0, MAGIC.length), MAGIC)) {
      throw new ItemCodecException(ItemCodecException.Reason.BAD_MAGIC, "unknown header");
    }
    ByteBuffer buffer = ByteBuffer.wrap(data);
    buffer.position(MAGIC.length);
    int formatVersion = buffer.getInt();
    int dataVersion = buffer.getInt();
    if (formatVersion != FORMAT_VERSION || snapshot.formatVersion() != FORMAT_VERSION) {
      throw new ItemCodecException(
          ItemCodecException.Reason.UNSUPPORTED_FORMAT_VERSION,
          "stored " + formatVersion + ", snapshot " + snapshot.formatVersion());
    }
    if (dataVersion <= 0 || dataVersion > serverDataVersion.getAsInt()) {
      throw new ItemCodecException(
          ItemCodecException.Reason.UNSUPPORTED_DATA_VERSION, "data version " + dataVersion);
    }
    byte[] payload = Arrays.copyOfRange(data, HEADER_BYTES, data.length);
    ItemStack stack;
    try {
      stack = ItemStack.deserializeBytes(payload);
    } catch (RuntimeException e) {
      throw new ItemCodecException(
          ItemCodecException.Reason.CORRUPT_PAYLOAD, "payload cannot be read", e);
    }
    if (stack == null || stack.isEmpty()) {
      throw new ItemCodecException(ItemCodecException.Reason.EMPTY_ITEM, "decoded to air");
    }
    if (stack.getAmount() != 1) {
      throw new ItemCodecException(
          ItemCodecException.Reason.INVALID_AMOUNT, "amount " + stack.getAmount());
    }
    String actualKey = stack.getType().getKey().toString();
    if (!actualKey.equals(snapshot.itemKey())) {
      throw new ItemCodecException(
          ItemCodecException.Reason.KEY_MISMATCH, snapshot.itemKey() + " != " + actualKey);
    }
    return stack;
  }
}
