package WorldEdit;

import arc.Core;
import arc.Events;
import arc.files.Fi;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Lines;
import arc.input.KeyCode;
import arc.struct.IntSet;
import arc.struct.ObjectSet;
import arc.struct.Seq;
import arc.struct.StringMap;
import arc.util.Log;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.core.GameState;
import mindustry.core.World;
import mindustry.game.EventType;
import mindustry.game.EventType.ClientLoadEvent;
import mindustry.game.Schematic;
import mindustry.gen.Building;
import mindustry.graphics.Drawf;
import mindustry.graphics.Pal;
import mindustry.input.Placement;
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
    private static int menuID;
    private static int menuID2;
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

    private static final ArrayList<String> blockBlackList = new ArrayList<>() {{
        add("air");
        add("spawn");
        add("cliff");
    }};

    private static final String safeToolsWarning = "[accent]Safe Tools [white]limited your action to 5000 blocks!\n[gray]Go to settings to disable.";

    private static int copyX = -1;
    private static int copyY = -1;

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
        public final Block before;
        public final Block after;

        public actionLogData(actionType type, Seq<Tile> affected, Block before, Block after) {
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
            coreBundle.put("setting.weInstantBake.name", "(WE) Instantly Bake Tools");
            addBooleanGameSetting("weInstantBake", false);

            menuID = Menus.registerMenu((player, selection) -> {
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
                                if (t.block() instanceof Floor && !(t.block() instanceof OreBlock) && !(t.block() instanceof AirBlock) && !(t.block() instanceof SpawnBlock)) {
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
                        String[][] out = new String[tool.values().length + 1][1];
                        int i = 0;
                        for (tool t : tool.values()) {
                            out[i++][0] = t.name;
                        }
                        out[i][0] = "None";
                        Menus.menu(menuID2, "Tool Selection", "[gray]Press [ esc ] to exit this menu", out);
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
            menuID2 = Menus.registerMenu((player, selection) -> {
                if (selection == tool.values().length) {
                    currentTool = null;
                } else if (selection != -1) {
                    currentTool = tool.values()[selection];
                }
                callWorldEditMenu();
            });

            if (!Core.settings.getBool("weExitCodeZero", true)) {
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

        Events.run(EventType.Trigger.draw, () -> {
            if (Core.input.keyDown(KeyCode.controlLeft) && Core.input.keyDown(KeyCode.c) && copyX != -1) {
                drawSelection(copyX, copyY, World.toTile(Core.input.mouseWorld().x), World.toTile(Core.input.mouseWorld().y));
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
                                    Block before;
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
                                            final actionType finalActionType = Core.settings.getBool("weInstantBake") ? type : actionType.block;
                                            currentAction = new actionLogData(finalActionType, new Seq<>(), finalActionType == actionType.block ? selectedTile.block() : before, block);
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

                                                        if (a != null && type.check(a, before) && a.block().isAir()) {
                                                            queue.add(a);
                                                        }
                                                        if (b != null && type.check(b, before) && b.block().isAir()) {
                                                            queue.add(b);
                                                        }
                                                        if (c != null && type.check(c, before) && c.block().isAir()) {
                                                            queue.add(c);
                                                        }
                                                        if (d != null && type.check(d, before) && d.block().isAir()) {
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
                                            final actionType finalActionType = Core.settings.getBool("weInstantBake") ? type : actionType.block;
                                            currentAction = new actionLogData(finalActionType, new Seq<>(), finalActionType == actionType.block ? selectedTile.block() : before, block);
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

                                                        if (a != null && type.check(a, currentAction.before) && a.block().isAir()) {
                                                            if (!ignored.contains(a)) {
                                                                ignored.add(a);
                                                                queue.add(a);
                                                            }
                                                        } else {
                                                            if (!currentAction.affected.contains(tile)) {
                                                                currentAction.affected.add(tile);
                                                            }
                                                        }
                                                        if (b != null && type.check(b, currentAction.before) && b.block().isAir()) {
                                                            if (!ignored.contains(b)) {
                                                                ignored.add(b);
                                                                queue.add(b);
                                                            }
                                                        } else {
                                                            if (!currentAction.affected.contains(tile)) {
                                                                currentAction.affected.add(tile);
                                                            }
                                                        }
                                                        if (c != null && type.check(c, currentAction.before) && c.block().isAir()) {
                                                            if (!ignored.contains(c)) {
                                                                ignored.add(c);
                                                                queue.add(c);
                                                            }
                                                        } else {
                                                            if (!currentAction.affected.contains(tile)) {
                                                                currentAction.affected.add(tile);
                                                            }
                                                        }
                                                        if (d != null && type.check(d, currentAction.before) && d.block().isAir()) {
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
                                            if (currentAction.type == actionType.block) {
                                                for (var b : currentAction.affected) {
                                                    currentAction.type.set(b, currentAction.after);
                                                }
                                                if (!(currentAction.after instanceof Prop)) {
                                                    for (var b : currentAction.affected) {
                                                        b.setTeam(player.team());
                                                    }
                                                }
                                            } else {
                                                for (var b : currentAction.affected) {
                                                    currentAction.type.set(b, currentAction.after);
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

                    if (Core.input.keyDown(KeyCode.controlLeft)) {
                        if (Core.input.keyTap(KeyCode.c)) {
                            copyX = World.toTile(Core.input.mouseWorld().x);
                            copyY = World.toTile(Core.input.mouseWorld().y);
                        } else if (Core.input.keyRelease(KeyCode.c)) {
                            control.input.lastSchematic = create(copyX, copyY, World.toTile(Core.input.mouseWorld().x), World.toTile(Core.input.mouseWorld().y));
                            control.input.useSchematic(control.input.lastSchematic);
                            if (control.input.selectRequests.isEmpty()) {
                                control.input.lastSchematic = null;
                            }
                        }
                    } else if (copyX != -1) {
                        copyX = -1;
                        copyY = -1;
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
        Menus.menu(menuID, "World Edit Menu", "[gray]Press [ esc ] to exit this menu", buttons);
    }

    public static void unlockTheWorld() {
        if (editing) return; //triggering utw twice messes up reset()
        Core.settings.put("weExitCodeZero", false);
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
        Vars.state.rules.revealedBlocks = state.map.rules().revealedBlocks;
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
        state.set(GameState.State.playing);
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

    void drawSelection(int x1, int y1, int x2, int y2) {
        //todo: fix weird bloom effect
        Draw.reset();
        Draw.alpha(1);

        Placement.NormalizeDrawResult result = Placement.normalizeDrawArea(Blocks.air, x1, y1, x2, y2, false, 500, 1f);
        Placement.NormalizeResult dresult = Placement.normalizeArea(x1, y1, x2, y2, 0, false, 500);

        for (int x = dresult.x; x <= dresult.x2; x++) {
            for (int y = dresult.y; y <= dresult.y2; y++) {
                Tile tile = world.tileBuilding(x, y);
                if (tile == null || tile.block().isAir()) continue;
                int cx = tile.build == null ? x : tile.build.tileX();
                int cy = tile.build == null ? y : tile.build.tileY();
                drawSelected(cx, cy, tile.block(), Pal.accent);
            }
        }

        Lines.stroke(2f);

        Draw.color(Pal.accentBack);
        Lines.rect(result.x, result.y - 1, result.x2 - result.x, result.y2 - result.y);
        Draw.color(Pal.accent);
        Lines.rect(result.x, result.y, result.x2 - result.x, result.y2 - result.y);
    }

    private static void drawSelected(int x, int y, Block block, Color color) {
        Drawf.selected(x, y, block, color);
    }

    public Schematic create(int x, int y, int x2, int y2) {
        Placement.NormalizeResult result = Placement.normalizeArea(x, y, x2, y2, 0, false, maxSchematicSize);
        x = result.x;
        y = result.y;
        x2 = result.x2;
        y2 = result.y2;

        int ox = x, oy = y, ox2 = x2, oy2 = y2;

        Seq<Schematic.Stile> tiles = new Seq<>();

        int minx = x2, miny = y2, maxx = x, maxy = y;
        boolean found = false;
        for (int cx = x; cx <= x2; cx++) {
            for (int cy = y; cy <= y2; cy++) {
                Building linked = world.build(cx, cy);
                Block realBlock = linked == null ? null : linked instanceof ConstructBlock.ConstructBuild cons ? cons.current : linked.block;

                if (linked != null && realBlock != null) {
                    int top = realBlock.size / 2;
                    int bot = realBlock.size % 2 == 1 ? -realBlock.size / 2 : -(realBlock.size - 1) / 2;
                    minx = Math.min(linked.tileX() + bot, minx);
                    miny = Math.min(linked.tileY() + bot, miny);
                    maxx = Math.max(linked.tileX() + top, maxx);
                    maxy = Math.max(linked.tileY() + top, maxy);
                } else {
                    minx = Math.min(cx, minx);
                    miny = Math.min(cy, miny);
                    maxx = Math.max(cx, maxx);
                    maxy = Math.max(cy, maxy);
                }
                found = true;
            }
        }

        if (found) {
            x = minx;
            y = miny;
            x2 = maxx;
            y2 = maxy;
        } else {
            return new Schematic(new Seq<>(), new StringMap(), 1, 1);
        }

        int width = x2 - x + 1, height = y2 - y + 1;
        int offsetX = -x, offsetY = -y;
        IntSet counted = new IntSet();
        for (int cx = ox; cx <= ox2; cx++) {
            for (int cy = oy; cy <= oy2; cy++) {
                Tile t = world.tile(cx, cy);
                Building tile = world.build(cx, cy);
                Block realBlock = tile == null ? t.block() : tile instanceof ConstructBlock.ConstructBuild cons ? cons.current : tile.block;

                if (t != null && !counted.contains(t.pos()) && realBlock != null && !realBlock.isAir()) {
                    if (tile == null) {
                        tiles.add(new Schematic.Stile(realBlock, cx + offsetX, cy + offsetY, null, (byte) 0));
                        counted.add(t.pos());
                    } else {
                        Object config = tile instanceof ConstructBlock.ConstructBuild cons ? cons.lastConfig : tile.config();

                        tiles.add(new Schematic.Stile(realBlock, tile.tileX() + offsetX, tile.tileY() + offsetY, config, (byte) tile.rotation));
                        counted.add(tile.pos());
                    }
                }
            }
        }

        return new Schematic(tiles, new StringMap(), width, height);
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
