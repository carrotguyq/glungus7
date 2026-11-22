package dev.e.shopscan;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ShopScan - client-side, read-only.
 * Reads ChestShop signs that the server already sent to your client
 * (loaded chunks only) and compares prices. It sends nothing to the server.
 *
 * Commands:
 *   /shopscan [radius]      scan + summary (default radius 64)
 *   /shopscan list          per-item comparison (cheapest buy / best sell)
 *   /shopscan item <name>   all shops for one item, cheapest first
 *   /shopscan export        write shopscan.csv into the config folder
 */
public class ShopScan implements ClientModInitializer {

    // Sign layout (ChestShop): 1 owner, 2 quantity, 3 "B 10 : S 5", 4 item
    record Shop(BlockPos pos, String owner, String item, int qty, Double buy, Double sell) {
        Double buyUnit()  { return buy  == null ? null : buy  / qty; }
        Double sellUnit() { return sell == null ? null : sell / qty; }
    }

    private static final List<Shop> shops = new ArrayList<>();

    private static final String NUM = "(\\d+(?:[.,]\\d+)?|free)";
    private static final Pattern B1 = Pattern.compile("(?i)\\bB\\s*" + NUM);
    private static final Pattern B2 = Pattern.compile("(?i)" + NUM + "\\s*B\\b");
    private static final Pattern S1 = Pattern.compile("(?i)\\bS\\s*" + NUM);
    private static final Pattern S2 = Pattern.compile("(?i)" + NUM + "\\s*S\\b");

    @Override
    public void onInitializeClient() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) ->
            dispatcher.register(ClientCommandManager.literal("shopscan")
                .executes(c -> scan(c.getSource(), 64))
                .then(ClientCommandManager.argument("radius", IntegerArgumentType.integer(8, 512))
                    .executes(c -> scan(c.getSource(), IntegerArgumentType.getInteger(c, "radius"))))
                .then(ClientCommandManager.literal("list")
                    .executes(c -> list(c.getSource())))
                .then(ClientCommandManager.literal("item")
                    .then(ClientCommandManager.argument("name", StringArgumentType.greedyString())
                        .executes(c -> item(c.getSource(), StringArgumentType.getString(c, "name")))))
                .then(ClientCommandManager.literal("export")
                    .executes(c -> export(c.getSource())))));
    }

    // ---------- scanning ----------

    private static int scan(FabricClientCommandSource src, int radius) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return 0;

        BlockPos origin = mc.player.blockPosition();
        long r2 = (long) radius * radius;
        int cr = (radius >> 4) + 1;
        int pcx = origin.getX() >> 4, pcz = origin.getZ() >> 4;

        shops.clear();
        int signs = 0;
        for (int cx = pcx - cr; cx <= pcx + cr; cx++) {
            for (int cz = pcz - cr; cz <= pcz + cr; cz++) {
                ChunkAccess ca = mc.level.getChunk(cx, cz, ChunkStatus.FULL, false);
                if (!(ca instanceof LevelChunk chunk)) continue;
                for (BlockEntity be : new ArrayList<>(chunk.getBlockEntities().values())) {
                    if (!(be instanceof SignBlockEntity sign)) continue;
                    if (be.getBlockPos().distSqr(origin) > r2) continue;
                    signs++;
                    parse(sign).ifPresent(shops::add);
                }
            }
        }

        Set<String> items = new HashSet<>();
        for (Shop s : shops) items.add(s.item().toLowerCase(Locale.ROOT));
        msg(src, "Tarama bitti: " + signs + " tabela, " + shops.size() + " shop, "
                + items.size() + " farkli esya. (/shopscan list)");
        return shops.size();
    }

    private static Optional<Shop> parse(SignBlockEntity sign) {
        SignText t = sign.getFrontText();
        String owner = line(t, 0);
        String qtyS = line(t, 1).replaceAll("\\D", "");
        String price = line(t, 2);
        String item = line(t, 3);

        if (qtyS.isEmpty() || item.isEmpty() || item.equals("?")) return Optional.empty();
        int qty;
        try { qty = Integer.parseInt(qtyS); } catch (NumberFormatException e) { return Optional.empty(); }
        if (qty <= 0) return Optional.empty();

        Double buy = find(price, B1, B2);
        Double sell = find(price, S1, S2);
        if (buy == null && sell == null) return Optional.empty();

        return Optional.of(new Shop(sign.getBlockPos().immutable(), owner, item, qty, buy, sell));
    }

    private static String line(SignText t, int i) {
        return t.getMessage(i, false).getString().trim();
    }

    private static Double find(String s, Pattern a, Pattern b) {
        Matcher m = a.matcher(s);
        if (!m.find()) { m = b.matcher(s); if (!m.find()) return null; }
        String v = m.group(1).replace(',', '.');
        return v.equalsIgnoreCase("free") ? 0.0 : Double.parseDouble(v);
    }

    // ---------- comparing ----------

    private static int list(FabricClientCommandSource src) {
        if (shops.isEmpty()) { msg(src, "Once /shopscan ile tara."); return 0; }

        Map<String, List<Shop>> byItem = new TreeMap<>();
        for (Shop s : shops) byItem.computeIfAbsent(s.item().toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(s);

        for (var e : byItem.entrySet()) {
            Shop cheapest = null, best = null;
            for (Shop s : e.getValue()) {
                if (s.buyUnit() != null && (cheapest == null || s.buyUnit() < cheapest.buyUnit())) cheapest = s;
                if (s.sellUnit() != null && (best == null || s.sellUnit() > best.sellUnit())) best = s;
            }
            StringBuilder sb = new StringBuilder(e.getKey()).append(" (").append(e.getValue().size()).append("): ");
            if (cheapest != null) sb.append("en ucuz AL ").append(fmt(cheapest.buyUnit())).append("/adet ").append(xyz(cheapest.pos())).append("  ");
            if (best != null) sb.append("en iyi SAT ").append(fmt(best.sellUnit())).append("/adet ").append(xyz(best.pos()));
            if (cheapest != null && best != null && best.sellUnit() > cheapest.buyUnit())
                sb.append("  [ARBITRAJ +").append(fmt(best.sellUnit() - cheapest.buyUnit())).append("/adet]");
            msg(src, sb.toString());
        }
        return byItem.size();
    }

    private static int item(FabricClientCommandSource src, String name) {
        String q = name.toLowerCase(Locale.ROOT).trim();
        List<Shop> hits = new ArrayList<>();
        for (Shop s : shops) if (s.item().toLowerCase(Locale.ROOT).contains(q)) hits.add(s);
        if (hits.isEmpty()) { msg(src, "Bulunamadi: " + name); return 0; }

        hits.sort(Comparator.comparing((Shop s) -> s.buyUnit() == null ? Double.MAX_VALUE : s.buyUnit()));
        int n = 0;
        for (Shop s : hits) {
            if (n++ >= 15) { msg(src, "... +" + (hits.size() - 15) + " shop daha (/shopscan export)"); break; }
            msg(src, s.item() + " x" + s.qty() + " | "
                    + (s.buy() != null ? "AL " + fmt(s.buyUnit()) : "-") + " | "
                    + (s.sell() != null ? "SAT " + fmt(s.sellUnit()) : "-") + " | "
                    + s.owner() + " " + xyz(s.pos()));
        }
        return hits.size();
    }

    private static int export(FabricClientCommandSource src) {
        if (shops.isEmpty()) { msg(src, "Once /shopscan ile tara."); return 0; }
        Path file = FabricLoader.getInstance().getConfigDir().resolve("shopscan.csv");
        StringBuilder sb = new StringBuilder("item,owner,qty,buy,sell,buy_per_unit,sell_per_unit,x,y,z\n");
        for (Shop s : shops) {
            sb.append(csv(s.item())).append(',').append(csv(s.owner())).append(',').append(s.qty()).append(',')
              .append(s.buy() == null ? "" : s.buy()).append(',').append(s.sell() == null ? "" : s.sell()).append(',')
              .append(s.buyUnit() == null ? "" : s.buyUnit()).append(',').append(s.sellUnit() == null ? "" : s.sellUnit()).append(',')
              .append(s.pos().getX()).append(',').append(s.pos().getY()).append(',').append(s.pos().getZ()).append('\n');
        }
        try {
            Files.writeString(file, sb.toString());
            msg(src, "Kaydedildi: " + file);
        } catch (Exception e) {
            msg(src, "Yazilamadi: " + e.getMessage());
        }
        return shops.size();
    }

    // ---------- helpers ----------

    private static String csv(String s) { return '"' + s.replace("\"", "\"\"") + '"'; }
    private static String fmt(double d) { return String.format(Locale.ROOT, "%.2f", d); }
    private static String xyz(BlockPos p) { return "(" + p.getX() + " " + p.getY() + " " + p.getZ() + ")"; }
    private static void msg(FabricClientCommandSource src, String s) { src.sendFeedback(Component.literal(s)); }
}
