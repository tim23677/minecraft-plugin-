package dev.orderhub.flow;

import static org.assertj.core.api.Assertions.assertThat;

import dev.orderhub.gui.ConfirmMenu;
import java.util.UUID;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Vorlagen: speichern, auflisten, benutzen (mit Bestaetigung), loeschen, Limit und Eingaben. */
class TemplateFlowTest extends FlowTestBase {

  private static final long START = 1_000_000;

  private UUID player;
  private TemplateFlow templates;
  private CreateFlow create;

  @BeforeEach
  void setUp() {
    player = supplier.getUniqueId();
    h.economy.setBalance(player, START);
    supplier.getInventory().setItemInMainHand(new ItemStack(Material.DIAMOND, 1));
    ChatInput chat = new ChatInput(scheduler, () -> "cancel", nanos::incrementAndGet);
    create = new CreateFlow(services, chat);
    templates = new TemplateFlow(services, create);
  }

  private String messages() {
    StringBuilder out = new StringBuilder();
    net.kyori.adventure.text.Component c;
    while ((c = supplier.nextComponentMessage()) != null) {
      out.append(PlainTextComponentSerializer.plainText().serialize(c)).append('\n');
    }
    return out.toString();
  }

  private long orders() {
    return h.storage.snapshot().orders().size();
  }

  @Test
  void savedTemplateIsListedAndUsedLikeCreate() {
    templates.handle(supplier, new String[] {"save", "ores", "5", "100"});
    assertThat(messages()).contains("ores");
    templates.handle(supplier, new String[] {"list"});
    assertThat(messages()).contains("ores").contains("5x");

    // The item in hand does not matter when a template is used.
    supplier.getInventory().setItemInMainHand(new ItemStack(Material.DIRT, 1));
    templates.handle(supplier, new String[] {"use", "ores"});
    click(ConfirmMenu.CONFIRM_SLOT);

    assertThat(orders()).isEqualTo(1);
    assertThat(h.economy.balance(player)).isEqualTo(START - 50_000);
  }

  @Test
  void usingWithoutConfirmingChargesNothing() {
    templates.handle(supplier, new String[] {"save", "ores", "5", "100"});
    templates.handle(supplier, new String[] {"use", "ores"});
    click(ConfirmMenu.CANCEL_SLOT);
    assertThat(orders()).isZero();
    assertThat(h.economy.mutationCount()).isZero();
  }

  @Test
  void unknownTemplateAndDeleteReportNotFound() {
    templates.handle(supplier, new String[] {"use", "nothing"});
    assertThat(messages()).contains("nothing");
    assertThat(orders()).isZero();

    templates.handle(supplier, new String[] {"save", "ores", "5", "100"});
    messages();
    templates.handle(supplier, new String[] {"delete", "ores"});
    assertThat(messages()).contains("Deleted");
    templates.handle(supplier, new String[] {"delete", "ores"});
    assertThat(messages()).contains("No template");
  }

  @Test
  void limitStopsTheEleventhButAllowsReplacingByName() {
    for (int i = 0; i < TemplateFlow.MAX_PER_PLAYER; i++) {
      templates.handle(supplier, new String[] {"save", "t" + i, "5", "100"});
    }
    messages();
    templates.handle(supplier, new String[] {"save", "extra", "5", "100"});
    assertThat(messages()).contains("limit");
    templates.handle(supplier, new String[] {"save", "t0", "7", "100"});
    assertThat(messages()).contains("Saved");
  }

  @Test
  void invalidInputSavesNothing() {
    templates.handle(supplier, new String[] {"save", "bad name!", "5", "100"});
    templates.handle(supplier, new String[] {"save", "x", "0", "100"});
    templates.handle(supplier, new String[] {"save", "x", "5", "abc"});
    templates.handle(supplier, new String[] {"save", "x", "5"});
    templates.handle(supplier, new String[] {});
    messages();
    templates.handle(supplier, new String[] {"list"});
    assertThat(messages()).contains("no templates");
  }

  @Test
  void templatesAreNotSharedBetweenPlayers() {
    templates.handle(supplier, new String[] {"save", "mine", "5", "100"});
    messages();
    var other = server.addPlayer();
    templates.handle(other, new String[] {"use", "mine"});
    assertThat(other.nextComponentMessage()).isNotNull();
    assertThat(orders()).isZero();
  }
}
