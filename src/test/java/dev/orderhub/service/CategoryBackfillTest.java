package dev.orderhub.service;

import static org.assertj.core.api.Assertions.assertThat;

import dev.orderhub.domain.NewOrder;
import dev.orderhub.testsupport.Harness;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CategoryBackfillTest {

  private static void insert(Harness h, String key, String category) {
    dev.orderhub.tx.TxRunner.run(
        h.storage,
        uow -> {
          uow.orders()
              .insert(
                  new NewOrder(
                      UUID.randomUUID(),
                      "s",
                      Harness.item(key),
                      100,
                      5,
                      1,
                      Harness.START + 100_000,
                      false,
                      category),
                  Harness.START);
          return null;
        });
  }

  private static String categoryOf(Harness h, long id) {
    return h.storage.read(uow -> uow.orders().find(id).orElseThrow()).category();
  }

  @Test
  void legacyCategoriesAreReassignedAndNewOnesLeftAlone() {
    Harness h = new Harness();
    insert(h, "minecraft:diamond_ore", "blocks");
    insert(h, "minecraft:bread", "food");
    insert(h, "minecraft:diamond_sword", "items");
    insert(h, "minecraft:oak_planks", "blocks");
    insert(h, "minecraft:potion", "potions");

    assertThat(CategoryBackfill.run(h.storage)).isEqualTo(3);
    assertThat(categoryOf(h, 1)).isEqualTo("ores");
    assertThat(categoryOf(h, 2)).isEqualTo("farm");
    assertThat(categoryOf(h, 3)).isEqualTo("tools");
    assertThat(categoryOf(h, 4)).isEqualTo("blocks");
    assertThat(categoryOf(h, 5)).isEqualTo("potions");
  }

  @Test
  void aSecondRunChangesNothingAndTerminates() {
    Harness h = new Harness();
    insert(h, "minecraft:oak_planks", "blocks");
    insert(h, "minecraft:stick", "items");
    CategoryBackfill.run(h.storage);
    assertThat(CategoryBackfill.run(h.storage)).isZero();
  }

  @Test
  void manyRowsAreProcessedInBatches() {
    Harness h = new Harness();
    for (int i = 0; i < 450; i++) {
      insert(h, "minecraft:wheat", "items");
    }
    assertThat(CategoryBackfill.run(h.storage)).isEqualTo(450);
  }
}
