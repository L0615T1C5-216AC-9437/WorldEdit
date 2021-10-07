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

    private static final String safeToolsWarning = "[accent]Safe Tools [white]limited your action to 5000 blocks!\n[gray]Go to settings to disable.";

    //undo redo
    private enum actionType {
        block {
            @Override
            public boolean check(Tile a, Object b) {
                return a.block() == b;
            }

            @Override
            public void set(Tile a, Object b) {
                a.setBlock((Block) b);
            }
        },
        ore {
            @Override
            public boolean check(Tile a, Object b) {
                return a.overlay() == b;
            }

            @Override
            public void set(Tile a, Object b) {
                a.setOverlay((Block) b);
            }
        },
        floor {
            @Override
            public boolean check(Tile a, Object b) {
                return a.floor() == b;
            }

            @Override
            public void set(Tile a, Object b) {
                a.setFloor((Floor) b);
            }
        };

        public boolean check(Tile a, Object b) {
            return false;
        }

        public void set(Tile a, Object b) {

        }
    }

    private static class actionLogData {
        public final actionType type;
        public final Seq<Tile> affected;
        public final Object before;
        public final Object after;

        public actionLogData(actionType type, Seq<Tile> affected, Object before, Object after) {
            this.type = type;
            this.affected = affected;
            this.before = before;
            this.after = after;
        }
    }

    private static final actionLogArray actionHistory = new actionLogArray();
    private static actionLogData currentAction = null;

    //tools
    private enum tool {
        fill("Fill"),
        outline("Outline");

        public final String name;

        tool(String name) {
            this.name = name;
        }
    }

    private static tool currentTool = null;

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
            coreBundle.put("setting.weSafeTools.name", "(WE) Safe Tools");
            addBooleanGameSetting("weSafeTools", true);

            Menus.registerMenu(30989378, (player, selection) -> {
                switch (selection) {
                    default -> Vars.ui.showInfo("""
                            [accent]Auto Save[white]:
                            Due to how jank this mod is, this mod [accent]automatically saves [white]a snapshot of the map you are editing.
                            [accent]World Edit [white]autosaves can be found in the [accent]saves/WorldEdit[white] directory.
                            You will need to [accent]manually import [white]autosaves for them to show in the [load game] tab.
                            You can edit how often autosaves are performed and how many of them can be kept in [accent]settings > game
                            """);
                    case -1 -> {
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
                                    if (!Core.settings.getBool("wePlacingFloorRemovesOre", true)) {
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
                        Core.settings.put("wePlacingFloorRemovesOre", !Core.settings.getBool("wePlacingFloorRemovesOre", true));
                        callWorldEditMenu();
                    }
                    case 4 -> {
                        //todo: add menu to choose tool
                        if (currentTool == null) {
                            currentTool = tool.values()[0];
                        } else if (currentTool.ordinal() == tool.values().length - 1) {
                            currentTool = null;
                        } else {
                            currentTool = tool.values()[currentTool.ordinal() + 1];
                        }
                        callWorldEditMenu();
                    }
                    case 5 -> {
                        if (actionHistory.size() > 0) {
                            Vars.ui.showConfirm("This will [scarlet]undo [white]your last action!\nWould you like to proceed?", () -> {
                                actionLogData lastAction = actionHistory.get();
                                for (var b : lastAction.affected) {
                                    lastAction.type.set(b, lastAction.before);
                                }
                                actionHistory.removeFirst();
                            });
                        } else {
                            Vars.ui.showInfo("Unable to undo!");
                        }
                    }
                }
            });

            if (!Core.settings.getBool("weExitCodeZero")) {
                Vars.ui.showCustomConfirm("World Edit didnt shut down correctly!",
                        """
                                World Edit was not disabled before the game exit.
                                This may have caused the save to corrupt!
                                Luckily we have autosaves that save your map every so often.""",
                        "Open Autosave Folder",
                        "Ignore",
                        () -> {
                            Core.app.openFolder(autoSaveDirectory.absolutePath());
                            Core.settings.put("weExitCodeZero", true);
                        },
                        () -> Core.settings.put("weExitCodeZero", true));
            }
        });
        Events.on(EventType.BlockBuildBeginEvent.class, event -> {
            if (editing) {
                Block block = event.tile.build instanceof ConstructBlock.ConstructBuild cb ? cb.current : event.tile.block();
                if (block instanceof Prop) {
                    event.tile.setBlock(block);
                }

            }
        });
        Events.run(EventType.Trigger.update, () -> {
            if (Vars.state.isPlaying()) {
                if (Core.input.keyTap(KeyCode.f2)) {
                    callWorldEditMenu();
                }
                if (editing) {
                    if (Core.input.keyTap(KeyCode.escape)) {
                        if (!Core.scene.hasDialog()) { //if World Edit is enabled and there is no Dialogue (Menu/InfoMessage) open
                            if (Core.settings.getBool("weSafeExit")) {
                                reset();
                                Vars.ui.showInfo("[accent]For your safety World Edit was Disabled.\n\n[white]Remember to [accent]Always [white]disable [accent]World Edit [white]before saving and quiting a save.");
                            }
                        }
                    }
                    if (Core.input.keyTap(KeyCode.mouseMiddle)) {
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
                    if (Core.input.keyTap(KeyCode.mouseLeft)) {
                        if (!Core.scene.hasDialog()) {
                            if (currentTool != null) {
                                Block block = Vars.control.input.block;
                                if (block != null && currentAction == null) {
                                    int rawCursorX = World.toTile(Core.input.mouseWorld().x), rawCursorY = World.toTile(Core.input.mouseWorld().y);
                                    final Tile selectedTile = Vars.world.tile(rawCursorX, rawCursorY);

                                    actionType type;
                                    Object before;
                                    if (block instanceof OverlayFloor) {
                                        type = actionType.ore;
                                        before = selectedTile.overlay();
                                    } else if (block instanceof Floor) {
                                        type = actionType.floor;
                                        before = selectedTile.floor();
                                    } else {
                                        type = actionType.block;
                                        before = selectedTile.block();
                                    }

                                    switch (currentTool) {
                                        case fill -> {
                                            currentAction = new actionLogData(type, new Seq<>(), before, block);
                                            ArrayList<Tile> actionQueue = new ArrayList<>();
                                            actionQueue.add(selectedTile);
                                            ArrayList<Tile> queue;
                                            while (!actionQueue.isEmpty()) {
                                                queue = new ArrayList<>();
                                                for (var tile : actionQueue) {
                                                    if (!currentAction.affected.contains(tile)) {
                                                        currentAction.affected.add(tile);

                                                        Tile a = world.tile(tile.x + 1, tile.y);
                                                        Tile b = world.tile(tile.x, tile.y + 1);
                                                        Tile c = world.tile(tile.x - 1, tile.y);
                                                        Tile d = world.tile(tile.x, tile.y - 1);

                                                        if (a != null && currentAction.type.check(a, currentAction.before) && a.block().isAir()) {
                                                            queue.add(a);
                                                        }
                                                        if (b != null && currentAction.type.check(b, currentAction.before) && b.block().isAir()) {
                                                            queue.add(b);
                                                        }
                                                        if (c != null && currentAction.type.check(c, currentAction.before) && c.block().isAir()) {
                                                            queue.add(c);
                                                        }
                                                        if (d != null && currentAction.type.check(d, currentAction.before) && d.block().isAir()) {
                                                            queue.add(d);
                                                        }
                                                    }
                                                }
                                                actionQueue = queue;
                                                if (Core.settings.getBool("weSafeTools") && currentAction.affected.size + actionQueue.size() > 5000) {
                                                    Vars.ui.showInfo(safeToolsWarning);
                                                    break;
                                                }
                                            }
                                        }
                                        case outline -> {
                                            currentAction = new actionLogData(type, new Seq<>(), before, block);
                                            ArrayList<Tile> actionQueue = new ArrayList<>();
                                            actionQueue.add(selectedTile);
                                            ArrayList<Tile> queue;
                                            ArrayList<Tile> ignored = new ArrayList<>();
                                            while (!actionQueue.isEmpty()) {
                                                queue = new ArrayList<>();
                                                for (var tile : actionQueue) {
                                                    if (!currentAction.affected.contains(tile)) {
                                                        Tile a = world.tile(tile.x + 1, tile.y);
                                                        Tile b = world.tile(tile.x, tile.y + 1);
                                                        Tile c = world.tile(tile.x - 1, tile.y);
                                                        Tile d = world.tile(tile.x, tile.y - 1);

                                                        if (a != null && currentAction.type.check(a, currentAction.before) && a.block().isAir()) {
                                                            if (!ignored.contains(a)) {
                                                                ignored.add(a);
                                                                queue.add(a);
                                                            }
                                                        } else {
                                                            if (!currentAction.affected.contains(tile)) {
                                                                currentAction.affected.add(tile);
                                                            }
                                                        }
                                                        if (b != null && currentAction.type.check(b, currentAction.before) && b.block().isAir()) {
                                                            if (!ignored.contains(b)) {
                                                                ignored.add(b);
                                                                queue.add(b);
                                                            }
                                                        } else {
                                                            if (!currentAction.affected.contains(tile)) {
                                                                currentAction.affected.add(tile);
                                                            }
                                                        }
                                                        if (c != null && currentAction.type.check(c, currentAction.before) && c.block().isAir()) {
                                                            if (!ignored.contains(c)) {
                                                                ignored.add(c);
                                                                queue.add(c);
                                                            }
                                                        } else {
                                                            if (!currentAction.affected.contains(tile)) {
                                                                currentAction.affected.add(tile);
                                                            }
                                                        }
                                                        if (d != null && currentAction.type.check(d, currentAction.before) && d.block().isAir()) {
                                                            if (!ignored.contains(d)) {
                                                                ignored.add(d);
                                                                queue.add(d);
                                                            }
                                                        } else {
                                                            if (!currentAction.affected.contains(tile)) {
                                                                currentAction.affected.add(tile);
                                                            }
                                                        }
                                                    }
                                                }
                                                actionQueue = queue;
                                                if (Core.settings.getBool("weSafeTools") && currentAction.affected.size + actionQueue.size() > 5000) {
                                                    Vars.ui.showInfo(safeToolsWarning);
                                                    break;
                                                }
                                            }
                                        }
                                    }
                                    if (currentAction != null) {
                                        Vars.ui.showCustomConfirm(currentAction.affected.size + " Tiles will be affected.", "Would you like to continue?", "Yes", "No", () -> {
                                            actionHistory.add(currentAction);
                                            for (var b : currentAction.affected) {
                                                currentAction.type.set(b, currentAction.after);
                                            }
                                            if (currentAction.type == actionType.block) {
                                                if (!(currentAction.after instanceof Prop)) {
                                                    for (var b : currentAction.affected) {
                                                        b.setTeam(player.team());
                                                    }
                                                }
                                            }
                                            currentAction = null;
                                        }, () -> currentAction = null);
                                    }
                                }
                            }
                        }
                    }
                    if (Core.settings.getBool("weAutoSave", true)) {
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
                }
            }
        });
    }

    public static void callWorldEditMenu() {
        String[][] buttons = new String[][]{
                new String[]{"[white]World Edit: " + (editing ? "[lime]Enabled\n[black]lol" : "[scarlet]Disabled\n[black]Unlock Za Warudo")},
                new String[]{"Bake Floors", "Bake Ores"},
                new String[]{"Placing Floor removes Ore? " + booleanColor(Core.settings.getBool("wePlacingFloorRemovesOre", true))},
                new String[]{"Tool: " + (currentTool == null ? "None" : currentTool.name)},
                new String[]{"Undo"},
                new String[]{"More Info"}
        };
        Menus.menu(30989378, "World Edit Menu", "[gray]Press [ esc ] to exit this menu", buttons);
    }

    public static void unlockTheWorld() {
        if (editing) return; //triggering utw twice messes up reset()
        Core.settings.put("weExitCodeZero", false);
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
        Core.settings.put("weExitCodeZero", true);
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

    private static class actionLogArray {
        private actionLogData[] data;

        public actionLogArray() {
            this.data = new actionLogData[]{};
        }

        public void add(actionLogData ald) {
            data = grow();
            data[0] = ald;
        }

        public actionLogData get() {
            return data[0];
        }

        public int size() {
            return data.length;
        }

        public actionLogData[] grow() {
            actionLogData[] out = new actionLogData[data.length + 1];
            System.arraycopy(data, 0, out, 1, data.length);
            return out;
        }

        public void removeFirst() {
            actionLogData[] out = new actionLogData[data.length - 1];
            System.arraycopy(data, 1, out, 0, data.length - 1);
            data = out;
        }
    }
}
