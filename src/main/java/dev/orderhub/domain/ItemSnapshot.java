package dev.orderhub.domain;

import java.util.Arrays;
import java.util.Objects;

/**
 * Unveraenderlicher, serialisierter Einzel-Item-Zustand (Menge 1). Das Format erzeugt und prueft
 * {@code dev.orderhub.item.ItemCodec}.
 *
 * @param itemKey Namespaced Material-Key, z. B. {@code minecraft:diamond}, fuer Suche und Index
 * @param formatVersion Version des OrderHub-Serialisierungsformats
 * @param data serialisierte Bytes
 */
public record ItemSnapshot(String itemKey, int formatVersion, byte[] data) {

  /** Maximale Groesse der serialisierten Daten in Bytes. */
  public static final int MAX_DATA_BYTES = 16 * 1024;

  public ItemSnapshot {
    Objects.requireNonNull(itemKey, "itemKey");
    Objects.requireNonNull(data, "data");
    if (data.length == 0 || data.length > MAX_DATA_BYTES) {
      throw new IllegalArgumentException("item data size out of range: " + data.length);
    }
    data = data.clone();
  }

  @Override
  public byte[] data() {
    return data.clone();
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof ItemSnapshot other
        && itemKey.equals(other.itemKey)
        && formatVersion == other.formatVersion
        && Arrays.equals(data, other.data);
  }

  @Override
  public int hashCode() {
    return Objects.hash(itemKey, formatVersion, Arrays.hashCode(data));
  }

  @Override
  public String toString() {
    return "ItemSnapshot[" + itemKey + ", v" + formatVersion + ", " + data.length + " bytes]";
  }
}
