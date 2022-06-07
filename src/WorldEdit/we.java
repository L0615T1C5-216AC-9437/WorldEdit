package WorldEdit;

import arc.Core;
import arc.Events;
import arc.func.Boolf;
import arc.func.Cons;
import arc.input.KeyCode;
import arc.math.geom.Point2;
import arc.struct.*;
import mindustry.Vars;
import mindustry.core.GameState;
import mindustry.core.World;
import mindustry.game.EventType;
import mindustry.game.Schematic;
import mindustry.gen.Building;
import mindustry.input.Placement;
import mindustry.mod.Plugin;
import mindustry.ui.Menus;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.blocks.ConstructBlock;
import mindustry.world.blocks.environment.Floor;
import mindustry.world.blocks.environment.Prop;
import mindustry.world.meta.BuildVisibility;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static mindustry.Vars.*;

public class we extends Plugin {
    //text stuff
    private static final String chatPrefix = "[salmon]World Edit[]: ";
    //static
    private static final ObjectMap<Short, BlockData> defaultBlockSettings = new ObjectMap<>();
    private static final ThreadPoolExecutor executor = new ThreadPoolExecutor(0, 1, 5, TimeUnit.SECONDS, new LinkedBlockingQueue<>());

    static {
        //generate middle mouse button menu buttons
        int a = 2;
        String[][] temp = new String[a + Tool.size][];
        temp[0] = new String[]{"Set Block"};
        temp[1] = new String[]{"Set Air"};
        for (Tool t : Tool.values()) {
            temp[a + t.ordinal()] = new String[]{t.name()};
        }

        mmbButtons = temp;
    }

    //menus
    private static int f2Menu = -1;
    private static int mmbMenu = -1;
    private static int mmbPos = -1;
    private static final String[][] mmbButtons;

    //tools
    private static final AtomicBoolean toolProcessing = new AtomicBoolean(false);

    public enum Tool {
        fill((t, b) -> {
            Boolf<Tile> tester;
            Cons<Tile> setter;

            if (b.isOverlay()) {
                Block dest = t.overlay();
                if (dest == b) return;
                tester = e -> e.overlay() == dest && (e.floor().hasSurface() || !e.floor().needsSurface);
                setter = e -> e.setOverlay(editor.drawBlock);
            } else if (b.isFloor()) {
                Block dest = t.floor();
                if (dest == b) return;
                tester = e -> e.floor() == dest;
                setter = e -> e.setFloorUnder(b.asFloor());
            } else {
                Block dest = t.block();
                if (dest == b) return;
                tester = e -> e.block() == dest;
                setter = e -> e.setBlock(b, Vars.player.team());
            }

            Seq<Tile> area = fill(t.x, t.y, tester);

            Core.app.post(() -> {
                for (Tile t1 : area) {
                    setter.get(t1);
                }
            });
        }),
        outline((t, b) -> {
            Boolf<Tile> tester;
            Cons<Tile> setter;

            if (b.isOverlay()) {
                Block dest = t.overlay();
                if (dest == b) return;
                tester = e -> e.overlay() == dest && (e.floor().hasSurface() || !e.floor().needsSurface);
                setter = e -> e.setOverlay(editor.drawBlock);
            } else if (b.isFloor()) {
                Block dest = t.floor();
                if (dest == b) return;
                tester = e -> e.floor() == dest;
                setter = e -> e.setFloorUnder(b.asFloor());
            } else {
                Block dest = t.block();
                if (dest == b) return;
                tester = e -> e.block() == dest;
                if (b instanceof Prop) {
                    setter = e -> e.setBlock(b);
                } else {
                    setter = e -> e.setBlock(b, Vars.player.team());
                }
            }

            Seq<Tile> area = fill(t.x, t.y, tester);

            Seq<Tile> outlineTiles = new Seq<>();
            for (Tile t1 : area) {
                for (int x = t1.x - 1; x < t1.x + 2; x++) {
                    for (int y = t1.y - 1; y < t1.y + 2; y++) {
                        Tile t2 = Vars.world.tile(x, y);
                        if (t2 != null && !tester.get(t2)) {
                            outlineTiles.add(t1);
                            x = t1.x * 5;
                            break;
                        }
                    }
                }
            }
            Core.app.post(() -> {
                for (Tile t1 : outlineTiles) {
                    setter.get(t1);
                }
            });
        });

        public static final int size = values().length;

        public final ToolRunner r;

        Tool(ToolRunner r) {
            this.r = r;
        }

        public void run(Tile t, Block b) {
            if (toolProcessing.compareAndSet(false, true)) {
                executor.submit(() -> {
                    try {
                        r.accept(t, b);
                    } catch (Exception e) {
                        e.printStackTrace();
                    } finally {
                        toolProcessing.set(false);
                    }
                });
            } else {
                Menus.warningToast(0, "Previous action is still being processed, please wait.");
            }
        }

        private interface ToolRunner {
            void accept(Tile t, Block b);
        }

        private static Seq<Tile> fill(int x, int y, Boolf<Tile> tester) {
            Seq<Tile> out = new Seq<>();
            int x1;

            IntSeq stack = new IntSeq();
            stack.add(Point2.pack(x, y));

            try {
                while (stack.size > 0 && stack.size < Vars.world.width() * Vars.world.height()) {
                    int popped = stack.pop();
                    x = Point2.x(popped);
                    y = Point2.y(popped);

                    x1 = x;
                    while (x1 >= 0 && tester.get(Vars.world.tile(x1, y))) x1--;
                    x1++;
                    boolean spanAbove = false, spanBelow = false;
                    while (x1 < Vars.world.width() && tester.get(Vars.world.tile(x1, y))) {
                        Tile t = Vars.world.tile(x1, y);
                        out.add(t);

                        Tile t1 = Vars.world.tile(x1, y - 1);
                        if (!spanAbove && y > 0 && !out.contains(t1) && tester.get(t1)) {
                            stack.add(Point2.pack(x1, y - 1));
                            spanAbove = true;
                        } else if (spanAbove && !out.contains(t1) && !tester.get(t1)) {
                            spanAbove = false;
                        }

                        Tile t2 = Vars.world.tile(x1, y + 1);
                        if (!spanBelow && y < Vars.world.height() - 1 && !out.contains(t2) && tester.get(t2)) {
                            stack.add(Point2.pack(x1, y + 1));
                            spanBelow = true;
                        } else if (spanBelow && y < Vars.world.height() - 1 && !out.contains(t2) && !tester.get(t2)) {
                            spanBelow = false;
                        }
                        x1++;
                    }
                }
            } catch (OutOfMemoryError ignored) {
                Vars.ui.showInfo("[scarlet]Failed to complete operation! Out of memory!");
                stack = null;
                System.gc();
            }
            return out;
        }
    }

    private static boolean enabled = false;

    public we() {
        Events.on(EventType.ClientLoadEvent.class, event -> {
            if (Vars.mobile) {
                Vars.ui.showInfo("This mod only works on PC! There is no support for mobile devices at all!");
                return;
            }
            //load default block visibility
            for (var a : Vars.content.blocks()) {
                defaultBlockSettings.put(a.id, new BlockData(a.buildVisibility, a.breakable, a.floating, a.placeableLiquid));
            }
            //add custom settings
            //todo
            //register menus
            f2Menu = Menus.registerMenu((p, sel) -> {
                switch (sel) {
                    case -1 -> {
                    }
                    case 0 -> {
                        if (Vars.state.rules.infiniteResources) {
                            if (enabled) {
                                disable();
                            } else {
                                enable();
                            }
                            callF2Menu();
                        } else {
                            Vars.ui.showInfo("This can only be done in sandbox!");
                        }
                    }
                    case 1 -> Vars.ui.showConfirm("Baking [accent]Floors [white]will [scarlet]change the map[white].\nWould you like to proceed?", () -> {
                        for (Tile t : Vars.world.tiles) {
                            if (t.block().id > 1 && t.block().isFloor() && !t.block().isOverlay()) {// t.block().id > 1; <- if block not air or spawn
                                if (Core.settings.getBool("wePlacingFloorRemovesOre", true)) {
                                    t.setFloor((Floor) t.block());
                                } else {
                                    t.setFloorUnder((Floor) t.block());
                                }
                                t.setAir();
                            }
                        }
                    });
                    case 2 ->
                            Vars.ui.showConfirm("Baking [accent]Ores [white]will [scarlet]change the map[white].\nWould you like to proceed?", () -> {
                                for (Tile t : Vars.world.tiles) {
                                    if (t.block().isOverlay()) {
                                        t.setOverlay(t.block());
                                        t.setAir();
                                    }
                                }
                            });
                }
            });
            mmbMenu = Menus.registerMenu((p, sel) -> {
                Point2 p2 = Point2.unpack(mmbPos);
                Tile t = Vars.world.tile(p2.x, p2.y);
                Block b = Vars.control.input.block;
                if (t == null || b == null) return;
                switch (sel) {
                    case -1 -> {
                    }
                    case 0 -> {
                        t.setBlock(b);
                        sendChatMessage(String.format("Set block at (%d,%d) to %s.", t.x, t.y, b.name));
                    }
                    case 1 -> {
                        t.setAir();
                        sendChatMessage(String.format("Set block at (%d,%d) to air.", t.x, t.y));
                    }
                    default -> {
                        if (sel - 2 < Tool.size) {
                            Tool tool = Tool.values()[sel - 2];
                            sendChatMessage(String.format("Performing %s %s at (%d, %d)", b.name, tool.name(), t.x, t.y));
                            tool.run(t, b);
                        }
                    }
                }
            });
        });
        Events.on(EventType.WorldLoadEvent.class, event -> {
            disable();
            sendChatMessage("Press F2 to enable world edit.");
        });
        Events.run(EventType.Trigger.update, () -> {
            if (Vars.state.isPlaying() && !Core.scene.hasDialog()) { //if playing and nothing else is on the screen
                if (Core.input.keyTap(KeyCode.f2)) callF2Menu();
                if (enabled) {
                    if (Core.input.keyTap(KeyCode.mouseMiddle)) {
                        Tile t = Vars.world.tile(World.toTile(Core.input.mouseWorld().x), World.toTile(Core.input.mouseWorld().y));
                        if (t == null) return;
                        Block b = Vars.control.input.block;
                        if (b == null) {
                            if (t.build == null) {
                                if (t.block().isAir()) {
                                    b = t.floor();
                                } else {
                                    b = t.block();
                                }
                                Vars.control.input.block = b;
                                Vars.ui.hudfrag.blockfrag.currentCategory = b.category;//todo: reload category to show new blocks
                            }
                        } else {
                            mmbPos = t.pos();
                            Menus.menu(mmbMenu, "Tile Action", "", mmbButtons);
                        }
                    } else if (Core.input.keyTap(KeyCode.escape)) {
                        Vars.ui.showConfirm("Do want to disable World Edit?", we::disable);
                    }
                }
            }
        });
    }

    private static void enable() {
        enabled = true;
        Vars.state.rules.editor = true;
        //make everything visible
        for (var a : Vars.content.blocks()) {
            a.buildVisibility = BuildVisibility.shown;
            a.breakable = true;
            a.floating = true;
            a.placeableLiquid = true;
        }
        sendChatMessage("World Edit enabled.");
    }

    private static void disable() {
        if (enabled) {
            //reset build visibility
            for (var a : Vars.content.blocks()) {
                if (a.id != 0) {
                    BlockData bd = defaultBlockSettings.get(a.id);
                    a.buildVisibility = bd.buildVisibility;
                    a.breakable = bd.breakable;
                    a.floating = bd.floating;
                    a.placeableLiquid = bd.placeableLiquid;
                }
            }
            sendChatMessage("World Edit disabled.");

            Vars.state.rules.editor = false;
            Vars.state.set(GameState.State.playing);
            enabled = false;
        }
    }

    private static void callF2Menu() {
        String[][] buttons = new String[][]{
                new String[]{"[white]World Edit: " + (enabled ? "[lime]Enabled\n[black]lol" : "[scarlet]Disabled\n[black]Unlock Za Warudo")},
                new String[]{"Bake Floors", "Bake Ores"},
                //new String[]{"Placing Floor removes Ore? " + booleanColor(Core.settings.getBool("wePlacingFloorRemovesOre", true))},
                new String[]{"Undo"}
        };
        Menus.menu(f2Menu, "World Edit Menu", "[gray]Press [ esc ] to exit this menu", buttons);
    }

    private static void sendChatMessage(String message) {
        Vars.ui.chatfrag.addMessage(chatPrefix + message);
    }

    public Schematic create2(int x1, int y1, int x2, int y2) {
        Placement.NormalizeResult result = Placement.normalizeArea(x1, y1, x2, y2, 0, false, Integer.MAX_VALUE);
        x1 = result.x;
        y1 = result.y;
        x2 = result.x2;
        y2 = result.y2;


        return null;
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
                    found = true;
                } else {
                    Tile t = world.tile(cx, cy);
                    if (t != null && t.block().id > 0) {
                        minx = Math.min(cx, minx);
                        miny = Math.min(cy, miny);
                        maxx = Math.max(cx, maxx);
                        maxy = Math.max(cy, maxy);
                        found = true;
                    }
                }
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

    private static class BlockData {
        public final BuildVisibility buildVisibility;
        public final boolean breakable;
        public final boolean floating;
        public final boolean placeableLiquid;

        public BlockData(BuildVisibility buildVisibility, boolean breakable, boolean floating, boolean placeableLiquid) {
            this.buildVisibility = buildVisibility;
            this.breakable = breakable;
            this.floating = floating;
            this.placeableLiquid = placeableLiquid;
        }
    }
}
