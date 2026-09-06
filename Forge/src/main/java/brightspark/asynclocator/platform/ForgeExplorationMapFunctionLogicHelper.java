package brightspark.asynclocator.platform;

import brightspark.asynclocator.ALConstants;
import brightspark.asynclocator.logic.CommonLogic;
import brightspark.asynclocator.platform.services.ExplorationMapFunctionLogicHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.saveddata.maps.MapDecoration;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.IItemHandlerModifiable;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Forge platform helper for updating exploration-map results in their source container.
 *
 * <p>Lootr compatibility note: certain mod-provided chest block entities (most
 * notably <a href="https://github.com/noobanidus/lootr/issues/793">Lootr</a>'s
 * per-player loot chests) deliberately do not expose an {@link IItemHandler}
 * capability on the world block entity. The original behaviour — looking up
 * {@code ForgeCapabilities.ITEM_HANDLER} on the BE and writing into it — fails
 * for those chests, emits noisy {@code WARN} log spam at every locate completion,
 * and on a busy server contributes to chunk-load contention. See
 * <a href="https://github.com/Alvaro842DEV/AsyncLocator-Refined/issues/5">AsyncLocator-Refined #5</a>
 * for the same issue diagnosed on 1.21.1 NeoForge.
 *
 * <p>Fix: walk every online player's open container menu and inventory first,
 * matching the in-progress map by JVM {@link ItemStack} reference equality. The
 * map gets updated through the menu/inventory the player is actually looking at;
 * Lootr is never asked for a capability it does not expose. The BE
 * item-handler path is kept as a fallback for the original behaviour. The
 * "no item-handler capability" log line is demoted to DEBUG since it is no
 * longer a failure condition.
 *
 * <p>This change is a strict superset of the prior behaviour:
 * <ul>
 *   <li>All previously-working vanilla / sophisticated-storage / etc. chests continue to work.</li>
 *   <li>Lootr chests (and any other BE without an item-handler cap) now work too.</li>
 *   <li>Players who closed the source chest before locate completed are still handled
 *       via the inventory walk.</li>
 * </ul>
 */
public class ForgeExplorationMapFunctionLogicHelper implements ExplorationMapFunctionLogicHelper {
    @Override
    public void invalidateMap(ItemStack mapStack, ServerLevel level, BlockPos pos) {
        handleUpdateMapInChest(mapStack, level, pos, (handler, slot) -> {
            if (handler instanceof IItemHandlerModifiable modifiableHandler) {
                modifiableHandler.setStackInSlot(slot, new ItemStack(Items.MAP));
            } else {
                handler.extractItem(slot, Item.MAX_STACK_SIZE, false);
                handler.insertItem(slot, new ItemStack(Items.MAP), false);
            }
        }, slot -> slot.set(new ItemStack(Items.MAP)));
    }

    @Override
    public void updateMap(
            ItemStack mapStack,
            ServerLevel level,
            BlockPos pos,
            int scale,
            MapDecoration.Type destinationType,
            BlockPos invPos,
            Component displayName
    ) {
        CommonLogic.updateMap(mapStack, level, pos, scale, destinationType, displayName);
        // Shouldn't need to set the stack in its slot again, as we're modifying the same instance.
        // We still walk so we can broadcast a slot-change to the viewing player when applicable.
        handleUpdateMapInChest(mapStack, level, invPos, (handler, slot) -> {}, slot -> {});
    }

    private static void handleUpdateMapInChest(
            ItemStack mapStack,
            ServerLevel level,
            BlockPos invPos,
            BiConsumer<IItemHandler, Integer> handleSlotFound,
            Consumer<Slot> handleMenuSlotFound
    ) {
        // 1. Walk online players' open container menus and inventories first.
        //    This is the path that works for Lootr and any other BE that does
        //    not expose an item-handler capability.
        if (walkPlayerSlots(level.getServer(), mapStack, handleMenuSlotFound)) {
            return;
        }

        // 2. Fall back to the BE item-handler capability — original behaviour.
        BlockEntity be = level.getBlockEntity(invPos);
        if (be != null) {
            be.getCapability(ForgeCapabilities.ITEM_HANDLER).resolve().ifPresentOrElse(
                    itemHandler -> {
                        for (int i = 0; i < itemHandler.getSlots(); i++) {
                            ItemStack slotStack = itemHandler.getStackInSlot(i);
                            if (slotStack == mapStack) {
                                handleSlotFound.accept(itemHandler, i);
                                CommonLogic.broadcastChestChanges(level, be);
                                return;
                            }
                        }
                    },
                    // Demoted from WARN to DEBUG: with the player-slot walk above, an
                    // absent ITEM_HANDLER capability is no longer a failure — it's the
                    // expected state for Lootr and similar mods.
                    () -> ALConstants.logDebug(
                            "No item handler capability on chest {} at {} — relying on player-slot walk",
                            be.getClass().getSimpleName(), invPos
                    )
            );
        } else {
            ALConstants.logWarn(
                    "Couldn't find block entity on chest {} at {}",
                    level.getBlockState(invPos), invPos
            );
        }
    }

    /**
     * Walk every online player's open container menu (first) and inventory (second),
     * looking for the supplied map stack by reference equality. Applies
     * {@code handleMenuSlotFound} to the matching menu slot if found.
     *
     * @return true if the map was located and handled, false otherwise.
     */
    private static boolean walkPlayerSlots(MinecraftServer server, ItemStack mapStack, Consumer<Slot> handleMenuSlotFound) {
        if (server == null) return false;
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        for (ServerPlayer player : players) {
            // 1a. Open menu slots — chest, barrel, shulker, etc. that the player has open right now.
            AbstractContainerMenu menu = player.containerMenu;
            if (menu != null) {
                for (Slot slot : menu.slots) {
                    if (slot.getItem() == mapStack) {
                        handleMenuSlotFound.accept(slot);
                        menu.broadcastChanges();
                        return true;
                    }
                }
            }
            // 1b. Player inventory — covers the case where the player closed the source
            //     chest after the merchant trade / locate command but before locate completed.
            Inventory inv = player.getInventory();
            for (int i = 0; i < inv.getContainerSize(); i++) {
                if (inv.getItem(i) == mapStack) {
                    // The map is already the live ItemStack the player holds — updating
                    // the NBT in CommonLogic.updateMap mutates it in place. We still want
                    // the client to see the change, so broadcast the menu if one's open.
                    if (player.containerMenu != null) {
                        player.containerMenu.broadcastChanges();
                    }
                    return true;
                }
            }
        }
        return false;
    }
}