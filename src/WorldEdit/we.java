package WorldEdit;

import arc.Core;
import arc.Events;
import arc.files.Fi;
import arc.input.KeyCode;
import arc.struct.ObjectSet;
import arc.struct.Seq;
import arc.util.Log;
import mindustry.Vars;
import mindustry.core.World;
import mindustry.game.EventType;
import mindustry.game.EventType.ClientLoadEvent;
import mindustry.io.SaveIO;
import mindustry.mod.Mod;
import mindustry.ui.Menus;
import mindustry.ui.dialogs.SettingsMenuDialog;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.blocks.ConstructBlock;
import mindustry.world.blocks.environment.*;
import mindustry.world.blocks.legacy.LegacyBlock;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;

import static arc.util.Log.err;
import static arc.util.Log.info;
import static mindustry.Vars.*;

public class we extends Mod {
    private static boolean editing = false;
    //autoSave
    private static long lastAutoSave = System.currentTimeMillis();
    private static final DateTimeFormatter autoSaveNameFormatter = DateTimeFormatter.ofPattern("MM-dd-yyyy_HH-mm-ss");
    private static final Fi autoSaveDirectory = saveDirectory.child("WorldEdit/");

    private static class blockData { //not record due to compile errors
        public final boolean breakable;
        public final boolean floating;
        public final boolean placeableLiquid;

        blockData(boolean breakable, boolean floating, boolean placableLiquid) {
            this.breakable = breakable;
            this.floating = floating;
            this.placeableLiquid = placableLiquid;
        }
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
            coreBundle.put("setting.weAutoSave.name", "(WE) Auto Save");
            addBooleanGameSetting("weAutoSave", true);
            coreBundle.put("setting.weAutoSaveSpacing.name", "(WE) AutoSave Spacing");
            addSliderGameSetting("weAutoSaveSpacing", 5, 5, 60, 5, i -> i + " minutes");
            coreBundle.put("setting.weAutoSaveCount.name", "(WE) Max AutoSave Count");
            addSliderGameSetting("weAutoSaveCount", 10, 1, 60, 1, i -> i + " AutoSaves");

            Menus.registerMenu(30989378, (player, selection) -> {
                switch (selection) {
                    default -> {
                    }
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
                    case 4 -> {
                        Vars.ui.showInfo("""
                                [accent]Auto Save[white]:
                                Due to how jank this mod is, this mod [accent]automatically saves [white]a snapshot of the map you are editing.
                                [accent]World Edit [white]autosaves can be found in the [accent]saves/WorldEdit[white] directory.
                                You will need to [accent]manually import [white]autosaves for them to show in the [load game] tab.
                                You can edit how often autosaves are performed and how many of them can be kept in [accent]settings > game
                                """);
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
            if (Core.input.keyTap(KeyCode.mouseMiddle)) {
                if (editing) {
                    int rawCursorX = World.toTile(Core.input.mouseWorld().x), rawCursorY = World.toTile(Core.input.mouseWorld().y);
                    final Tile cursorTile = Vars.world.tile(rawCursorX, rawCursorY);
                    if (cursorTile.build == null) {
                        Block b = cursorTile.block();
                        if (b.isAir()) {
                            b = cursorTile.floor();
                        }
                        Vars.control.input.block = b;
                        Vars.ui.hudfrag.blockfrag.currentCategory = b.category;
                    }
                }
            }
            if (editing && Vars.state.isPlaying() && Core.settings.getBool("weAutoSave", true)) {
                if (lastAutoSave < System.currentTimeMillis() - (Core.settings.getInt("weAutoSaveSpacing") * 60 * 1000L)) {
                    lastAutoSave = System.currentTimeMillis();
                    Log.info("AutoSaving");
                    int max = Core.settings.getInt("weAutoSaveCount");
                    //use map file name to make sure it can be saved
                    String mapName = (state.map.file == null ? "unknown" : state.map.file.nameWithoutExtension()).replace(" ", "_");
                    String date = autoSaveNameFormatter.format(LocalDateTime.now());

                    Seq<Fi> autoSaves = autoSaveDirectory.findAll(f -> f.name().startsWith("auto_"));
                    autoSaves.sort(f -> -f.lastModified());

                    //delete older saves
                    if (autoSaves.size >= max) {
                        for (int i = max - 1; i < autoSaves.size; i++) {
                            autoSaves.get(i).delete();
                        }
                    }

                    String fileName = "auto_" + mapName + "_" + date + "." + saveExtension;
                    Fi file = autoSaveDirectory.child(fileName);
                    try {
                        SaveIO.save(file);
                        info("AutoSave completed.");
                    } catch (Throwable e) {
                        err("AutoSave failed.", e);
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
        String[][] buttons = new String[][]{
                new String[]{"[white]World Edit: " + (editing ? "[lime]Enabled\n[black]lol" : "[scarlet]Disabled\n[black]Unlock Za Warudo")},
                new String[]{"Bake Floors", "Bake Ores"},
                new String[]{"Placing Floor removes Ore? " + booleanColor(Core.settings.getBool("we.placingFloorRemovesOre", true))},
                new String[]{"More Info"}
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
                b.placeableLiquid = bd.placeableLiquid;
            }
        });
        defaultBlockData.clear();
    }

    public static void addBooleanGameSetting(String key, boolean defaultBooleanValue) {
        Vars.ui.settings.game.checkPref(key, Core.settings.getBool(key, defaultBooleanValue));
    }

    public static void addSliderGameSetting(String key, int defaultValue, int minValue, int maxValue, int stepValue, SettingsMenuDialog.StringProcessor sp) {
        Vars.ui.settings.game.sliderPref(key, defaultValue, minValue, maxValue, stepValue, sp);
    }

    public static String booleanColor(boolean b) {
        return (b ? "[lime]" : "[scarlet]") + b;
    }
}
