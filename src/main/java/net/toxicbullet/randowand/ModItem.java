package net.toxicbullet.randowand;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class ModItem {

    public static DataComponentType<List<ItemStack>> WAND_PALETTE;
    public static Item RANDO_WAND;
    public static ResourceKey<CreativeModeTab> RANDOWAND_TAB_KEY;
    public static CreativeModeTab RANDOWAND_TAB;

    public static void registerModItems() {
        WandConfig.loadConfig();

        WAND_PALETTE = Registry.register(
                BuiltInRegistries.DATA_COMPONENT_TYPE,
                RandoWand.id("wand_palette"),
                DataComponentType.<List<ItemStack>>builder()
                        .persistent(com.mojang.serialization.Codec.list(ItemStack.CODEC))
                        .networkSynchronized(StreamCodec.composite(
                                ItemStack.STREAM_CODEC.apply(ByteBufCodecs.list()),
                                i -> i, i -> i
                        ))
                        .build()
        );

        RANDO_WAND = Registry.register(
                BuiltInRegistries.ITEM,
                RandoWand.id("randowand"),
                new RandoWandItem(new Item.Properties()
                        .stacksTo(1)
                        .durability(WandConfig.getMaxDurability())
                        .component(WAND_PALETTE, List.of())));
        RANDOWAND_TAB_KEY = ResourceKey.create(Registries.CREATIVE_MODE_TAB, RandoWand.id("randowand_tab"));
        RANDOWAND_TAB = Registry.register(
                BuiltInRegistries.CREATIVE_MODE_TAB,
                RANDOWAND_TAB_KEY,
                FabricItemGroup.builder()
                        .title(Component.translatable("itemGroup.randowand"))
                        .icon(() -> new ItemStack(RANDO_WAND))
                        .build()
        );

        ItemGroupEvents.modifyEntriesEvent(RANDOWAND_TAB_KEY).register(content -> {
            content.accept(RANDO_WAND);
        });

        RandoWand.LOGGER.info("Registered Mod Items and Config for " + RandoWand.MOD_ID);
    }

    public static class RandoWandItem extends Item {
        private static final Random RANDOM = new Random();
        // Minecraft's ItemStack codec rejects counts above 99, so each palette entry is capped
        private static final int MAX_PER_ENTRY = 99;

        public RandoWandItem(Properties properties) {
            super(properties);
        }

        /** Puts a stack in the player's inventory; anything that doesn't fit is dropped on the ground. */
        private static void giveBack(Player player, ItemStack stack) {
            ItemStack remaining = stack.copy();
            while (!remaining.isEmpty()) {
                ItemStack part = remaining.split(Math.min(remaining.getCount(), remaining.getMaxStackSize()));
                player.getInventory().add(part);
                if (!part.isEmpty()) {
                    player.drop(part, false);
                }
            }
        }

        @Override
        public void appendHoverText(ItemStack stack, Item.TooltipContext context,
                                    List<Component> tooltipComponents, TooltipFlag tooltipFlag) {
            tooltipComponents.add(Component.literal("Controls:").withStyle(ChatFormatting.GOLD));
            tooltipComponents.add(Component.literal("Right-click a block: place a random palette block")
                    .withStyle(ChatFormatting.GRAY));
            tooltipComponents.add(Component.literal("Shift + right-click a block: add your off-hand blocks to the palette")
                    .withStyle(ChatFormatting.GRAY));
            tooltipComponents.add(Component.literal("Shift + right-click air: take back the last added blocks")
                    .withStyle(ChatFormatting.GRAY));

            List<ItemStack> palette = stack.getOrDefault(WAND_PALETTE, List.of());
            int total = palette.stream().mapToInt(ItemStack::getCount).sum();
            tooltipComponents.add(Component.literal("Palette (" + total + " blocks):")
                    .withStyle(ChatFormatting.GOLD));
            if (palette.isEmpty()) {
                tooltipComponents.add(Component.literal("  Empty").withStyle(ChatFormatting.DARK_GRAY));
            } else {
                for (ItemStack entry : palette) {
                    tooltipComponents.add(Component.literal("  - ")
                            .withStyle(ChatFormatting.GRAY)
                            .append(entry.getHoverName())
                            .append(Component.literal(" x" + entry.getCount()).withStyle(ChatFormatting.YELLOW)));
                }
            }
        }

        @Override
        public InteractionResult useOn(UseOnContext context) {
            Level level = context.getLevel();
            Player player = context.getPlayer();

            // Shift + right-click a block: add off-hand blocks to the palette
            if (player != null && player.isShiftKeyDown()) {
                if (!level.isClientSide()) {
                    ItemStack offHandStack = player.getOffhandItem();

                    if (!offHandStack.isEmpty()) {
                        if (!(offHandStack.getItem() instanceof BlockItem)) {
                            player.displayClientMessage(Component.literal("Only blocks can be added to the wand."), true);
                        } else {
                            ItemStack wandStack = context.getItemInHand();
                            List<ItemStack> palette = new ArrayList<>(wandStack.getOrDefault(WAND_PALETTE, List.of()));

                            int idx = -1;
                            for (int i = 0; i < palette.size(); i++) {
                                if (ItemStack.isSameItemSameComponents(palette.get(i), offHandStack)) {
                                    idx = i;
                                    break;
                                }
                            }

                            int current = idx >= 0 ? palette.get(idx).getCount() : 0;
                            int toMove = Math.min(offHandStack.getCount(), MAX_PER_ENTRY - current);

                            if (toMove <= 0) {
                                player.displayClientMessage(Component.literal(
                                        "The wand can't hold more of that block (max " + MAX_PER_ENTRY + ")."), true);
                            } else {
                                // Re-add at the end of the list so "last added" is always the most recent
                                if (idx >= 0) {
                                    palette.remove(idx);
                                }
                                ItemStack merged = offHandStack.copyWithCount(current + toMove);
                                palette.add(merged);
                                wandStack.set(WAND_PALETTE, palette);

                                String name = merged.getHoverName().getString();
                                offHandStack.shrink(toMove);
                                player.displayClientMessage(Component.literal(
                                        "Added " + toMove + " x " + name + " (" + merged.getCount() + " in wand)"), true);
                            }
                        }
                    }
                }
                return InteractionResult.sidedSuccess(level.isClientSide());
            }

            if (level.isClientSide()) {
                return InteractionResult.SUCCESS;
            }

            ItemStack wandStack = context.getItemInHand();
            List<ItemStack> palette = wandStack.getOrDefault(WAND_PALETTE, List.of());

            if (palette.isEmpty()) {
                if (player != null) {
                    player.displayClientMessage(Component.literal(
                            "Wand palette is empty! Hold blocks in your off-hand and Shift + right-click a block to add them."), true);
                }
                return InteractionResult.FAIL;
            }

            // Pick a random block from the palette
            int index = RANDOM.nextInt(palette.size());
            ItemStack picked = palette.get(index);

            if (picked.getItem() instanceof BlockItem blockItem) {
                BlockPlaceContext placeContext = new BlockPlaceContext(
                        level, player, context.getHand(), picked.copyWithCount(1),
                        new BlockHitResult(context.getClickLocation(), context.getClickedFace(),
                                context.getClickedPos(), context.isInside()));
                InteractionResult result = blockItem.place(placeContext);

                if (result.consumesAction()
                        && player instanceof ServerPlayer serverPlayer
                        && level instanceof ServerLevel serverLevel) {

                    // Use up one block from the palette (not in Creative)
                    List<ItemStack> updated = new ArrayList<>();
                    for (ItemStack entry : palette) {
                        updated.add(entry.copy());
                    }
                    if (!serverPlayer.getAbilities().instabuild) {
                        ItemStack entry = updated.get(index);
                        entry.shrink(1);
                        if (entry.isEmpty()) {
                            updated.remove(index);
                        }
                    }
                    wandStack.set(WAND_PALETTE, updated);

                    // If this use breaks the wand, give everything left inside back to the player
                    List<ItemStack> leftovers = new ArrayList<>();
                    for (ItemStack entry : updated) {
                        leftovers.add(entry.copy());
                    }
                    wandStack.hurtAndBreak(1, serverLevel, serverPlayer, brokenItem -> {
                        for (ItemStack leftover : leftovers) {
                            giveBack(serverPlayer, leftover);
                        }
                        if (!leftovers.isEmpty()) {
                            serverPlayer.displayClientMessage(Component.literal("The wand broke! Your blocks were returned."), true);
                        }
                    });
                }

                return result;
            }

            return InteractionResult.PASS;
        }

        @Override
        public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
            ItemStack wandStack = player.getItemInHand(hand);

            // Shift + right-click air: take the last added blocks back
            if (player.isShiftKeyDown()) {
                if (!level.isClientSide()) {
                    List<ItemStack> palette = new ArrayList<>(wandStack.getOrDefault(WAND_PALETTE, List.of()));
                    if (palette.isEmpty()) {
                        player.displayClientMessage(Component.literal("Palette is already empty."), true);
                    } else {
                        ItemStack last = palette.remove(palette.size() - 1);
                        wandStack.set(WAND_PALETTE, palette);
                        giveBack(player, last.copy());
                        player.displayClientMessage(Component.literal(
                                "Took back " + last.getCount() + " x " + last.getHoverName().getString()), true);
                    }
                }
                return InteractionResultHolder.sidedSuccess(wandStack, level.isClientSide());
            }
            return InteractionResultHolder.pass(wandStack);
        }
    }

    // --- JSON Config System ---
    public static class WandConfig {
        private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
        private static final Path CONFIG_DIR = FabricLoader.getInstance().getConfigDir();
        private static final File CONFIG_FILE = CONFIG_DIR.resolve("randowand.json").toFile();

        public static class ConfigData {
            public int maxDurability = 1000;
            public boolean allowTileEntities = false;
        }

        public static ConfigData data = new ConfigData();

        public static void loadConfig() {
            if (!CONFIG_FILE.exists()) {
                saveConfig();
                RandoWand.LOGGER.info("Created default RandoWand config at: " + CONFIG_FILE.getAbsolutePath());
                return;
            }

            try (FileReader reader = new FileReader(CONFIG_FILE)) {
                ConfigData loaded = GSON.fromJson(reader, ConfigData.class);
                if (loaded != null) {
                    data = loaded;
                    RandoWand.LOGGER.info("Loaded RandoWand config: " + CONFIG_FILE.getAbsolutePath());
                } else {
                    data = new ConfigData();
                    saveConfig();
                    RandoWand.LOGGER.warn("Config file was empty or invalid. Reset to defaults.");
                }
            } catch (IOException e) {
                RandoWand.LOGGER.error("Failed to load RandoWand config file", e);
                data = new ConfigData();
            }
        }

        public static void reloadConfig() {
            loadConfig();
        }

        public static int getMaxDurability() {
            return data.maxDurability;
        }

        public static boolean getAllowTileEntities() {
            return data.allowTileEntities;
        }

        public static void saveConfig() {
            CONFIG_DIR.toFile().mkdirs();
            try (FileWriter writer = new FileWriter(CONFIG_FILE)) {
                GSON.toJson(data, writer);
                RandoWand.LOGGER.info("Saved RandoWand config to: " + CONFIG_FILE.getAbsolutePath());
            } catch (IOException e) {
                RandoWand.LOGGER.error("Failed to save RandoWand config file", e);
            }
        }
    }
}