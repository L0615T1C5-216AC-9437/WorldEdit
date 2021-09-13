package WorldEdit;

import arc.Core;
import arc.Events;
import arc.input.KeyCode;
import arc.struct.ObjectSet;
import arc.util.Log;
import mindustry.Vars;
import mindustry.game.EventType;
import mindustry.game.EventType.ClientLoadEvent;
import mindustry.mod.Mod;
import mindustry.ui.Menus;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.blocks.ConstructBlock;
import mindustry.world.blocks.environment.*;
import mindustry.world.blocks.legacy.LegacyBlock;

import java.util.ArrayList;
import java.util.HashMap;

public class we extends Mod {
    private static final int maxSelection = 500;

    private static boolean editing = false;

    private static class blockData { //not record due to compile errors
        public final boolean breakable;
        public final boolean floating;
        public final boolean placableLiquid;

        blockData(boolean breakable, boolean floating, boolean placableLiquid) {
            this.breakable = breakable;
            this.floating = floating;
            this.placableLiquid = placableLiquid;
        };
    }
    private static final HashMap<Block, blockData> defaultBlockData = new HashMap<>();
    private static ObjectSet<Block> defaultRevealedBlocks = null;

    private static final ArrayList<String> blockBlackList = new ArrayList<>() {{
        add("air");
        add("spawn");
        add("cliff");
    }};

    public we() {
        Log.info("Loading Events in World Edit.");

        //listen for game load event
        Events.on(ClientLoadEvent.class, event -> {
            var coreBundle = Core.bundle.getProperties();
            coreBundle.put("setting.weSafeExit.name", "(WE) Safe Exit");
            addBooleanGameSetting("weSafeExit", true);

            Menus.registerMenu(30989378, (player, selection) -> {
                switch (selection) {
                    default -> {}
                    case 0 -> {
                        if (Vars.state.rules.infiniteResources) {
                            if (editing) {
                                reset();
                            } else {
                                unlockTheWorld();
                            }
                            callWorldEditMenu();
                        } else {
                            Vars.ui.showInfo("World Edit requires [accent]Sand Box [gray](Infinite Resources Rule) [white]to be enabled!");
                        }
                    }
                    case 1 -> Vars.ui.showConfirm("Baking [accent]Floors [white]will [scarlet]change the map[white].\nWould you like to proceed?", () -> {
                        for (int x = 0; x < Vars.state.map.width; x++) {
                            for (int y = 0; y < Vars.state.map.height; y++) {
                                Tile t = Vars.world.tile(x, y);
                                if (t.block() instanceof Floor && !(t.block() instanceof AirBlock) && !(t.block() instanceof SpawnBlock)) {
                                    Floor ore = t.overlay();
                                    t.setFloor((Floor) t.block());
                                    t.setAir();
                                    if (!Core.settings.getBool("we.placingFloorRemovesOre", true)) {
                                        if (ore != null) {
                                            t.setOverlay(ore);
                                        }
                                    }
                                }
                            }
                        }
                    });
                    case 2 -> Vars.ui.showConfirm("Baking [accent]Ores [white]will [scarlet]change the map[white].\nWould you like to proceed?", () -> {
                        for (int x = 0; x < Vars.state.map.width; x++) {
                            for (int y = 0; y < Vars.state.map.height; y++) {
                                Tile t = Vars.world.tile(x, y);
                                if (t.block() instanceof OreBlock) {
                                    t.setOverlay(t.block());
                                    t.setAir();
                                }
                            }
                        }
                    });
                    case 3 -> {
                        Core.settings.put("we.placingFloorRemovesOre", !Core.settings.getBool("we.placingFloorRemovesOre", true));
                        callWorldEditMenu();
                    }
                }
            });
        });
        Events.run(EventType.Trigger.update, () -> {
            if (Core.input.keyTap(KeyCode.f2)) {
                callWorldEditMenu();
            }
            if (Core.input.keyTap(KeyCode.escape)) {
                if (editing && !Core.scene.hasDialog()) { //if World Edit is enabled and there is no Dialogue (Menu/InfoMessage) open
                    if (Core.settings.getBool("weSafeExit")) {
                        reset();
                        Vars.ui.showInfo("[accent]For your safety World Edit was Disabled.\n\n[white]Remember to [accent]Always [white]disable [accent]World Edit [white]before saving and quiting a save.");
                    }
                }
            }
        });
        Events.on(EventType.BlockBuildBeginEvent.class, event -> {
            if (editing) {
                Block b = event.tile.build instanceof ConstructBlock.ConstructBuild cb ? cb.current : event.tile.block();
                if (b instanceof Prop) {
                    event.tile.setBlock(b);
                }
            }
        });
    }

    public static void callWorldEditMenu() {
        String[][] buttons = new String[][] {
                new String[] { "[white]World Edit: " + (editing ? "[lime]Enabled\n[black]lol" : "[scarlet]Disabled\n[black]Unlock Za Warudo")},
                new String[] { "Bake Floors", "Bake Ores" },
                new String[] { "Placing Floor removes Ore? " + booleanColor(Core.settings.getBool("we.placingFloorRemovesOre", true)) }
        };
        Menus.menu(30989378, "World Edit Menu", "[gray]Press [ esc ] to exit this menu", buttons);
    }

    public static void unlockTheWorld() {
        if (editing) return; //triggering utw twice messes up reset()
        if (defaultRevealedBlocks == null) defaultRevealedBlocks = Vars.state.rules.revealedBlocks;
        editing = true;
        Vars.state.rules.editor = true;
        Vars.state.rules.revealedBlocks = new ObjectSet<>();
        Vars.content.blocks().forEach(b -> {
            if (!blockBlackList.contains(b.name) && !(b instanceof LegacyBlock)) {
                Vars.state.rules.revealedBlocks.add(b);
                defaultBlockData.put(b, new blockData(b.breakable, b.floating, b.placeableLiquid));
                b.breakable = true;
                b.floating = true;
                b.placeableLiquid = true;
            }
        });
        if (!Core.settings.getBool("weSafeExit")) {
            Vars.ui.showInfo("[scarlet]////////// - WARNING - \\\\\\\\\\\\\\\\\\\\\n\n[white]Remember to [accent]Disable World Edit [white]before saving and/or exiting.\nDisabling World Edit removes Wall/Boulders/Floors/etc. from build menu.\nIf [accent]World Edit [white]is [scarlet]Disabled [white]and you place a [accent]Wall/Boulder [white]your save [scarlet]WILL [white]be corrupted irreversibly!\n\n[gray]It is highly recommended for you to leave Safe Exit Enabled!");
        }
    }
    public static void reset() {
        if (defaultRevealedBlocks != null) Vars.state.rules.revealedBlocks = defaultRevealedBlocks;
        editing = false;
        Vars.state.rules.editor = false;
        Vars.content.blocks().forEach(b -> {
            blockData bd = defaultBlockData.get(b);
            if (bd != null) {
                b.breakable = bd.breakable;
                b.floating = bd.floating;
                b.placeableLiquid = bd.placableLiquid;
            }
        });
        defaultBlockData.clear();
    }

    public static void addBooleanGameSetting(String key, boolean defaultBooleanValue){
        Vars.ui.settings.game.checkPref(key, Core.settings.getBool(key, defaultBooleanValue));
    }

    public static String booleanColor(boolean b) {
        return (b ? "[lime]" : "[scarlet]") + b;
    }
}
