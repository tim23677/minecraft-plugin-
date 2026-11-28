package dev.orderhub.gui;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.bukkit.Material;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.DragType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryType.SlotType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** S1: Item-Dupe durch GUI-Klick (Shift, Zahlentaste, Offhand, Drag, Doppelklick, ...). */
class GuiSecurityTest extends GuiTestSupport {

  private static final int PLAYER_SLOT_OFFSET = 27; // Menue hat 3 Reihen

  private int playerSlot(int index) {
    return PLAYER_SLOT_OFFSET + index;
  }

  // ---- Klickarten, die in einem Menue immer gesperrt sind ----

  @ParameterizedTest
  @EnumSource(
      value = ClickType.class,
      names = {
        "SHIFT_LEFT",
        "SHIFT_RIGHT",
        "DOUBLE_CLICK",
        "DROP",
        "CONTROL_DROP",
        "MIDDLE",
        "CREATIVE",
        "SWAP_OFFHAND",
        "WINDOW_BORDER_LEFT",
        "WINDOW_BORDER_RIGHT",
        "UNKNOWN"
      })
  void forbiddenClickTypesAreCancelledOnMenuSlots(ClickType type) {
    openInputMenu();
    for (int slot : new int[] {InputMenu.BUTTON, InputMenu.INPUT_A, 5}) {
      InventoryClickEvent event = fire(type, InventoryAction.NOTHING, slot);
      assertThat(event.isCancelled()).as("%s on slot %d", type, slot).isTrue();
    }
  }

  @ParameterizedTest
  @EnumSource(
      value = ClickType.class,
      names = {
        "SHIFT_LEFT",
        "SHIFT_RIGHT",
        "DOUBLE_CLICK",
        "DROP",
        "CONTROL_DROP",
        "MIDDLE",
        "CREATIVE",
        "SWAP_OFFHAND"
      })
  void forbiddenClickTypesAreCancelledInPlayerInventory(ClickType type) {
    openInputMenu();
    InventoryClickEvent event = fire(type, InventoryAction.MOVE_TO_OTHER_INVENTORY, playerSlot(3));
    assertThat(event.isCancelled()).as(type.name()).isTrue();
  }

  @Test
  void shiftClickFromPlayerInventoryIntoMenuIsCancelledAndMovesNothing() {
    InputMenu menu = openInputMenu();
    player.getInventory().setItem(3, new ItemStack(Material.DIAMOND, 5));
    InventoryClickEvent event =
        fire(ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY, playerSlot(3));
    assertThat(event.isCancelled()).isTrue();
    assertThat(menu.inputContents()).isEmpty();
    assertThat(inPlayerInventory(Material.DIAMOND)).isEqualTo(5);
  }

  @Test
  void shiftClickFromMenuInputSlotOutIsCancelled() {
    InputMenu menu = openInputMenu();
    place(new ItemStack(Material.DIAMOND, 4), InputMenu.INPUT_A);
    InventoryClickEvent event =
        fire(ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY, InputMenu.INPUT_A);
    assertThat(event.isCancelled()).isTrue();
    assertThat(menu.inputContents()).hasSize(1);
  }

  @Test
  void numberKeyHotbarSwapIsCancelledOnEverySlot() {
    openInputMenu();
    for (int key = 0; key < 9; key++) {
      for (int slot : new int[] {InputMenu.INPUT_A, InputMenu.BUTTON, playerSlot(2)}) {
        InventoryClickEvent event = fireHotbar(slot, key, InventoryAction.HOTBAR_SWAP);
        assertThat(event.isCancelled()).as("key %d slot %d", key, slot).isTrue();
      }
    }
  }

  @Test
  void numberKeyWithItemsInHotbarMovesNothingIntoTheMenu() {
    InputMenu menu = openInputMenu();
    for (int key = 0; key < 9; key++) {
      player.getInventory().setItem(key, new ItemStack(Material.DIAMOND, 2));
    }
    for (int key = 0; key < 9; key++) {
      InventoryClickEvent event = fireHotbar(InputMenu.INPUT_A, key, InventoryAction.HOTBAR_SWAP);
      assertThat(event.isCancelled()).as("key %d", key).isTrue();
    }
    assertThat(menu.inputContents()).isEmpty();
    assertThat(inPlayerInventory(Material.DIAMOND)).isEqualTo(18);
  }

  @Test
  void swapOffhandIsCancelled() {
    openInputMenu();
    InventoryClickEvent event =
        fire(ClickType.SWAP_OFFHAND, InventoryAction.HOTBAR_SWAP, InputMenu.INPUT_A);
    assertThat(event.isCancelled()).isTrue();
  }

  @Test
  void doubleClickCollectToCursorIsCancelledAndDoesNotPullItemsOutOfMenu() {
    InputMenu menu = openInputMenu();
    place(new ItemStack(Material.DIAMOND, 4), InputMenu.INPUT_A);
    player.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 1));
    InventoryClickEvent event =
        fire(ClickType.DOUBLE_CLICK, InventoryAction.COLLECT_TO_CURSOR, playerSlot(0));
    assertThat(event.isCancelled()).isTrue();
    assertThat(menu.inputContents()).hasSize(1);
  }

  @Test
  void dropAndControlDropAreCancelled() {
    openInputMenu();
    assertThat(fire(ClickType.DROP, InventoryAction.DROP_ONE_SLOT, InputMenu.INPUT_A).isCancelled())
        .isTrue();
    assertThat(
            fire(ClickType.CONTROL_DROP, InventoryAction.DROP_ALL_SLOT, InputMenu.INPUT_A)
                .isCancelled())
        .isTrue();
  }

  @Test
  void clickOutsideIsCancelled() {
    openInputMenu();
    InventoryClickEvent event = fire(ClickType.LEFT, InventoryAction.DROP_ALL_CURSOR, -999);
    assertThat(event.isCancelled()).isTrue();
    InventoryClickEvent right = fire(ClickType.RIGHT, InventoryAction.DROP_ONE_CURSOR, -999);
    assertThat(right.isCancelled()).isTrue();
  }

  @Test
  void middleClickCloneIsCancelled() {
    openInputMenu();
    InventoryClickEvent event =
        fire(ClickType.MIDDLE, InventoryAction.CLONE_STACK, InputMenu.BUTTON);
    assertThat(event.isCancelled()).isTrue();
  }

  @Test
  void creativeClickIsCancelledIncludingTheCreativeEvent() {
    openInputMenu();
    InventoryCreativeEvent event =
        new InventoryCreativeEvent(
            player.getOpenInventory(),
            SlotType.CONTAINER,
            InputMenu.BUTTON,
            new ItemStack(Material.DIAMOND_BLOCK, 64));
    server.getPluginManager().callEvent(event);
    assertThat(event.isCancelled()).isTrue();
  }

  @Test
  void unknownAndNothingActionsAreCancelled() {
    openInputMenu();
    assertThat(fire(ClickType.LEFT, InventoryAction.NOTHING, InputMenu.BUTTON).isCancelled())
        .isTrue();
    assertThat(fire(ClickType.LEFT, InventoryAction.UNKNOWN, InputMenu.BUTTON).isCancelled())
        .isTrue();
    assertThat(fire(ClickType.LEFT, InventoryAction.CLONE_STACK, InputMenu.BUTTON).isCancelled())
        .isTrue();
    assertThat(
            fire(ClickType.LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY, InputMenu.INPUT_A)
                .isCancelled())
        .isTrue();
  }

  // ---- Drag und Move ----

  private InventoryDragEvent drag(Map<Integer, ItemStack> slots, DragType type) {
    InventoryDragEvent event =
        new InventoryDragEvent(
            player.getOpenInventory(),
            new ItemStack(Material.DIAMOND, 8),
            new ItemStack(Material.DIAMOND, 8),
            type == DragType.EVEN,
            slots);
    server.getPluginManager().callEvent(event);
    return event;
  }

  @Test
  void dragOverMenuSlotsIsCancelled() {
    openInputMenu();
    assertThat(drag(Map.of(5, new ItemStack(Material.DIAMOND, 4)), DragType.EVEN).isCancelled())
        .isTrue();
    assertThat(
            drag(Map.of(InputMenu.INPUT_A, new ItemStack(Material.DIAMOND, 4)), DragType.SINGLE)
                .isCancelled())
        .isTrue();
  }

  @Test
  void dragAcrossMenuAndPlayerSlotsIsCancelled() {
    openInputMenu();
    InventoryDragEvent event =
        drag(
            Map.of(
                InputMenu.INPUT_A,
                new ItemStack(Material.DIAMOND, 4),
                playerSlot(1),
                new ItemStack(Material.DIAMOND, 4)),
            DragType.EVEN);
    assertThat(event.isCancelled()).isTrue();
  }

  @Test
  void dragInsidePlayerInventoryOnlyIsNotTouched() {
    openInputMenu();
    InventoryDragEvent event =
        drag(Map.of(playerSlot(1), new ItemStack(Material.DIAMOND, 8)), DragType.EVEN);
    assertThat(event.isCancelled()).isFalse();
  }

  @Test
  void dragInForeignInventoryIsNotTouched() {
    Inventory foreign = server.createInventory(null, 9);
    player.openInventory(foreign);
    InventoryDragEvent event = drag(Map.of(2, new ItemStack(Material.STONE, 3)), DragType.EVEN);
    assertThat(event.isCancelled()).isFalse();
  }

  @Test
  void hopperMovingItemsFromOrIntoMenuIsCancelled() {
    InputMenu menu = openInputMenu();
    Inventory other = server.createInventory(null, 9);
    ItemStack item = new ItemStack(Material.DIAMOND);
    InventoryMoveItemEvent intoMenu =
        new InventoryMoveItemEvent(other, item, menu.getInventory(), true);
    InventoryMoveItemEvent outOfMenu =
        new InventoryMoveItemEvent(menu.getInventory(), item, other, false);
    server.getPluginManager().callEvent(intoMenu);
    server.getPluginManager().callEvent(outOfMenu);
    assertThat(intoMenu.isCancelled()).isTrue();
    assertThat(outOfMenu.isCancelled()).isTrue();
  }

  @Test
  void movesBetweenForeignInventoriesAreNotTouched() {
    Inventory a = server.createInventory(null, 9);
    Inventory b = server.createInventory(null, 9);
    InventoryMoveItemEvent event =
        new InventoryMoveItemEvent(a, new ItemStack(Material.DIRT), b, true);
    server.getPluginManager().callEvent(event);
    assertThat(event.isCancelled()).isFalse();
  }

  // ---- Eingabeslots ----

  @Test
  void validItemMayBePlacedIntoInputSlot() {
    InputMenu menu = openInputMenu();
    InventoryClickEvent event = place(new ItemStack(Material.DIAMOND, 3), InputMenu.INPUT_A);
    assertThat(event.isCancelled()).isFalse();
    assertThat(menu.inputContents()).hasSize(1);
    assertThat(menu.inputContents().get(0).getAmount()).isEqualTo(3);
  }

  @Test
  void invalidItemIsRejectedByServerSideValidation() {
    InputMenu menu = openInputMenu();
    InventoryClickEvent event = place(new ItemStack(Material.DIRT, 3), InputMenu.INPUT_A);
    assertThat(event.isCancelled()).isTrue();
    assertThat(menu.inputContents()).isEmpty();
  }

  @Test
  void swapWithCursorValidatesTheCursorItem() {
    InputMenu menu = openInputMenu();
    place(new ItemStack(Material.DIAMOND, 2), InputMenu.INPUT_A);
    player.setItemOnCursor(new ItemStack(Material.DIRT));
    InventoryClickEvent event =
        fire(ClickType.LEFT, InventoryAction.SWAP_WITH_CURSOR, InputMenu.INPUT_A);
    assertThat(event.isCancelled()).isTrue();
    assertThat(menu.inputContents().get(0).getType()).isEqualTo(Material.DIAMOND);
  }

  @Test
  void placingOneAndSomeAreValidatedToo() {
    openInputMenu();
    player.setItemOnCursor(new ItemStack(Material.DIRT, 5));
    assertThat(fire(ClickType.RIGHT, InventoryAction.PLACE_ONE, InputMenu.INPUT_B).isCancelled())
        .isTrue();
    assertThat(fire(ClickType.LEFT, InventoryAction.PLACE_SOME, InputMenu.INPUT_B).isCancelled())
        .isTrue();
  }

  @Test
  void pickingUpFromInputSlotIsAllowed() {
    InputMenu menu = openInputMenu();
    place(new ItemStack(Material.DIAMOND, 3), InputMenu.INPUT_A);
    InventoryClickEvent event = fire(ClickType.LEFT, InventoryAction.PICKUP_ALL, InputMenu.INPUT_A);
    applyAllowed(event);
    assertThat(event.isCancelled()).isFalse();
    assertThat(menu.inputContents()).isEmpty();
    assertThat(player.getItemOnCursor().getAmount()).isEqualTo(3);
  }

  @Test
  void placingIntoNonInputSlotIsCancelledEvenForValidItem() {
    InputMenu menu = openInputMenu();
    InventoryClickEvent event = place(new ItemStack(Material.DIAMOND, 3), 5);
    assertThat(event.isCancelled()).isTrue();
    assertThat(menu.getInventory().getItem(5)).isNull();
  }

  @Test
  void placingOnButtonSlotIsCancelled() {
    InputMenu menu = openInputMenu();
    InventoryClickEvent event = place(new ItemStack(Material.DIAMOND, 3), InputMenu.BUTTON);
    assertThat(event.isCancelled()).isTrue();
    assertThat(menu.getInventory().getItem(InputMenu.BUTTON).getType()).isEqualTo(Material.PAPER);
  }

  @Test
  void plainClicksInPlayerInventoryAreNotCancelled() {
    openInputMenu();
    InventoryClickEvent event = fire(ClickType.LEFT, InventoryAction.PICKUP_ALL, playerSlot(4));
    assertThat(event.isCancelled()).isFalse();
  }

  @Test
  void clicksInForeignInventoriesAreNotTouched() {
    Inventory foreign = server.createInventory(null, 9);
    player.openInventory(foreign);
    for (ClickType type : new ClickType[] {ClickType.SHIFT_LEFT, ClickType.DOUBLE_CLICK}) {
      assertThat(fire(type, InventoryAction.MOVE_TO_OTHER_INVENTORY, 0).isCancelled()).isFalse();
    }
  }

  // ---- Button-Revalidierung ----

  @Test
  void buttonClickRunsHandlerAndIsCancelled() {
    InputMenu menu = openInputMenu();
    InventoryClickEvent event = fire(ClickType.LEFT, InventoryAction.PICKUP_ALL, InputMenu.BUTTON);
    assertThat(event.isCancelled()).isTrue();
    assertThat(menu.buttonClicks).isEqualTo(1);
  }

  @Test
  void buttonClickOfDisconnectedPlayerDoesNothing() {
    InputMenu menu = openInputMenu();
    InventoryClickEvent event =
        new InventoryClickEvent(
            player.getOpenInventory(),
            SlotType.CONTAINER,
            InputMenu.BUTTON,
            ClickType.LEFT,
            InventoryAction.PICKUP_ALL);
    player.disconnect();
    server.getPluginManager().callEvent(event);
    assertThat(event.isCancelled()).isTrue();
    assertThat(menu.buttonClicks).isZero();
  }

  @Test
  void buttonClickOnClosedMenuDoesNothing() {
    InputMenu menu = openInputMenu();
    var view = player.getOpenInventory();
    player.closeInventory();
    InventoryClickEvent event =
        new InventoryClickEvent(
            view, SlotType.CONTAINER, InputMenu.BUTTON, ClickType.LEFT, InventoryAction.PICKUP_ALL);
    server.getPluginManager().callEvent(event);
    assertThat(event.isCancelled()).isTrue();
    assertThat(menu.buttonClicks).isZero();
  }

  @Test
  void buttonClickOnReplacedMenuDoesNothing() {
    InputMenu first = openInputMenu();
    var staleView = player.getOpenInventory();
    InputMenu second = openInputMenu();
    InventoryClickEvent event =
        new InventoryClickEvent(
            staleView,
            SlotType.CONTAINER,
            InputMenu.BUTTON,
            ClickType.LEFT,
            InventoryAction.PICKUP_ALL);
    server.getPluginManager().callEvent(event);
    assertThat(event.isCancelled()).isTrue();
    assertThat(first.buttonClicks).isZero();
    assertThat(second.buttonClicks).isZero();
  }

  @Test
  void emptySlotClickIsCancelledWithoutHandler() {
    InputMenu menu = openInputMenu();
    InventoryClickEvent event = fire(ClickType.LEFT, InventoryAction.PICKUP_ALL, 7);
    assertThat(event.isCancelled()).isTrue();
    assertThat(menu.buttonClicks).isZero();
  }

  @Test
  void buttonHandlerExceptionDoesNotEscapeAndClickStaysCancelled() {
    Menu menu =
        new Menu(net.kyori.adventure.text.Component.text("x"), 1, SlotPolicy.none()) {
          {
            setButton(
                0,
                Button.of(
                    new ItemStack(Material.PAPER),
                    ctx -> {
                      throw new IllegalStateException("boom");
                    }));
          }
        };
    manager.open(player, menu);
    InventoryClickEvent event = fire(ClickType.LEFT, InventoryAction.PICKUP_ALL, 0);
    assertThat(event.isCancelled()).isTrue();
  }

  // ---- Debounce ----

  @Test
  void rapidButtonClicksAreDebounced() {
    InputMenu menu = openInputMenu();
    fire(ClickType.LEFT, InventoryAction.PICKUP_ALL, InputMenu.BUTTON);
    advance(50);
    InventoryClickEvent second = fire(ClickType.LEFT, InventoryAction.PICKUP_ALL, InputMenu.BUTTON);
    assertThat(second.isCancelled()).isTrue();
    assertThat(menu.buttonClicks).isEqualTo(1);
    advance(150);
    fire(ClickType.LEFT, InventoryAction.PICKUP_ALL, InputMenu.BUTTON);
    assertThat(menu.buttonClicks).isEqualTo(2);
  }

  @Test
  void debounceIsPerPlayer() {
    InputMenu menu = openInputMenu();
    var other = server.addPlayer("other");
    InputMenu otherMenu = new InputMenu();
    manager.open(other, otherMenu);
    fire(ClickType.LEFT, InventoryAction.PICKUP_ALL, InputMenu.BUTTON);
    InventoryClickEvent otherClick =
        new InventoryClickEvent(
            other.getOpenInventory(),
            SlotType.CONTAINER,
            InputMenu.BUTTON,
            ClickType.LEFT,
            InventoryAction.PICKUP_ALL);
    server.getPluginManager().callEvent(otherClick);
    assertThat(menu.buttonClicks).isEqualTo(1);
    assertThat(otherMenu.buttonClicks).isEqualTo(1);
  }

  @Test
  void inputSlotInteractionIsNotDebounced() {
    InputMenu menu = openInputMenu();
    place(new ItemStack(Material.DIAMOND), InputMenu.INPUT_A);
    InventoryClickEvent pickup =
        fire(ClickType.LEFT, InventoryAction.PICKUP_ALL, InputMenu.INPUT_A);
    applyAllowed(pickup);
    place(new ItemStack(Material.DIAMOND), InputMenu.INPUT_B);
    assertThat(menu.inputContents()).hasSize(1);
  }
}
