package dynamiceconomy.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Backups of the market's state: stock, price factors and admin catalog edits, as one JSON file each in
 * {@code <world>/dynamic_economy/backups/}. Catalog files aren't included (they're datapack files; imports keep
 * {@code .json.bak} copies), nor are price history and money counts. A backup is checked in full before anything is
 * restored, and restoring a part replaces exactly that part.
 */
public final class MarketBackup {
    public static final int FORMAT = 1;
    /** Automatic backups (taken before a restore or prune) beyond this many are deleted, oldest first. */
    public static final int AUTOMATIC_KEPT = 20;
    private static final String AUTOMATIC = "_auto-";
    private static final DateTimeFormatter NAME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private MarketBackup() {
    }

    /** Which part of the market a restore replaces. */
    public enum Part {
        STOCK, PRICES, EDITS, ALL;

        boolean includes(Part part) {
            return this == ALL || this == part;
        }
    }

    /**
     * What a backup holds.
     *
     * @param created  when it was taken (an ISO instant)
     * @param gameTime the overworld game time it was taken at
     */
    public record Contents(String created, long gameTime, Map<ResourceLocation, Long> stock,
                           Map<ResourceLocation, MarketStockData.Factor> factors, MarketOverrides.Snapshot edits) {
    }

    /** How a backup differs from the market now, counted for the parts being restored. */
    public record Difference(int stock, int prices, int itemEdits, int tagRules, Set<ResourceLocation> unknownItems) {
        public boolean none() {
            return stock == 0 && prices == 0 && itemEdits == 0 && tagRules == 0;
        }
    }

    public static Path folder(MinecraftServer server) {
        return AuditLog.worldFolder(server).resolve("backups");
    }

    /**
     * Writes a backup of the market now and returns its name. {@code reason} marks it as automatic (e.g. "restore");
     * automatic backups beyond {@link #AUTOMATIC_KEPT} are then deleted, oldest first. Manual ones are never deleted.
     */
    public static String create(MinecraftServer server, @Nullable String reason) throws IOException {
        Path folder = folder(server);
        Files.createDirectories(folder);
        String base = LocalDateTime.now().format(NAME) + (reason == null ? "" : AUTOMATIC + reason);
        String name = base;
        for (int n = 2; Files.exists(folder.resolve(name + ".json")); n++) {
            name = base + "_" + n;
        }
        JsonObject json = toJson(MarketStockData.get(server), MarketOverrides.get(server).snapshot(), MarketStockData.now(server));
        Files.writeString(folder.resolve(name + ".json"), GSON.toJson(json), StandardCharsets.UTF_8);
        if (reason != null) {
            List<String> automatic = list(server).stream().filter(existing -> existing.contains(AUTOMATIC)).toList();
            for (String old : automatic.subList(Math.min(AUTOMATIC_KEPT, automatic.size()), automatic.size())) {
                Files.deleteIfExists(folder.resolve(old + ".json"));
            }
        }
        return name;
    }

    /** Backup names, newest first. */
    public static List<String> list(MinecraftServer server) throws IOException {
        Path folder = folder(server);
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(folder)) {
            return files.map(file -> file.getFileName().toString())
                    .filter(file -> file.endsWith(".json"))
                    .map(file -> file.substring(0, file.length() - ".json".length()))
                    .sorted(Comparator.reverseOrder())
                    .toList();
        }
    }

    /** The file for a backup name, refusing anything that isn't a plain name (no paths). */
    public static Path file(MinecraftServer server, String name) {
        if (!name.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("\"" + name + "\" isn't a backup name; /market admin backups lists them");
        }
        return folder(server).resolve(name + ".json");
    }

    public static JsonObject toJson(MarketStockData market, MarketOverrides.Snapshot edits, long gameTime) {
        JsonObject json = new JsonObject();
        json.addProperty("format", FORMAT);
        json.addProperty("created", Instant.now().toString());
        json.addProperty("game_time", gameTime);
        JsonObject stock = new JsonObject();
        market.view().forEach((id, amount) -> stock.addProperty(id.toString(), amount));
        json.add("stock", stock);
        JsonObject factors = new JsonObject();
        market.factorsView().forEach((id, factor) -> {
            JsonObject entry = new JsonObject();
            entry.addProperty("s", factor.value());
            entry.addProperty("t", factor.lastTick());
            factors.add(id.toString(), entry);
        });
        json.add("factors", factors);
        JsonObject items = new JsonObject();
        edits.items().forEach((id, patch) -> items.add(id.toString(), patch.deepCopy()));
        JsonArray tags = new JsonArray();
        edits.tags().forEach(rule -> {
            JsonObject entry = new JsonObject();
            entry.addProperty("tag", rule.tag().toString());
            entry.add("patch", rule.patch().deepCopy());
            tags.add(entry);
        });
        JsonObject editJson = new JsonObject();
        editJson.add("items", items);
        editJson.add("tags", tags);
        json.add("edits", editJson);
        return json;
    }

    /** Reads and checks a backup. Either the contents, or every problem found; never a partly read backup. */
    public static ParseResult read(Path file) {
        if (!Files.isRegularFile(file)) {
            return ParseResult.failed("There's no backup called " + file.getFileName());
        }
        try {
            return parse(JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)));
        } catch (IOException | RuntimeException e) {
            return ParseResult.failed("The backup can't be read: " + e.getMessage());
        }
    }

    /** A checked backup, or why it can't be used. */
    public record ParseResult(Optional<Contents> contents, List<String> problems) {
        static ParseResult failed(String problem) {
            return new ParseResult(Optional.empty(), List.of(problem));
        }
    }

    public static ParseResult parse(JsonElement root) {
        List<String> problems = new ArrayList<>();
        if (!(root instanceof JsonObject json)) {
            return ParseResult.failed("The backup isn't a JSON object");
        }
        if (!json.has("format") || json.get("format").getAsInt() != FORMAT) {
            return ParseResult.failed("Unknown backup format " + json.get("format") + "; this version reads format " + FORMAT);
        }
        Map<ResourceLocation, Long> stock = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : object(json, "stock", problems).entrySet()) {
            ResourceLocation id = id(entry.getKey(), "stock", problems);
            Long amount = wholeNumber(entry.getValue());
            if (amount == null || amount < 0) {
                problems.add("stock of " + entry.getKey() + " must be a whole number of at least 0, not " + entry.getValue());
            } else if (id != null) {
                stock.put(id, amount);
            }
        }
        Map<ResourceLocation, MarketStockData.Factor> factors = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : object(json, "factors", problems).entrySet()) {
            ResourceLocation id = id(entry.getKey(), "factors", problems);
            if (!(entry.getValue() instanceof JsonObject factor) || !factor.has("s") || !factor.has("t")) {
                problems.add("price factor of " + entry.getKey() + " must have \"s\" and \"t\"");
                continue;
            }
            double value = factor.get("s").getAsDouble();
            Long tick = wholeNumber(factor.get("t"));
            if (!(value > 0) || !Double.isFinite(value) || tick == null) {
                problems.add("price factor of " + entry.getKey() + " is invalid: " + factor);
            } else if (id != null) {
                factors.put(id, new MarketStockData.Factor(value, tick));
            }
        }
        JsonObject edits = object(json, "edits", problems);
        Map<ResourceLocation, JsonObject> items = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : object(edits, "items", problems).entrySet()) {
            ResourceLocation id = id(entry.getKey(), "edits", problems);
            if (!entry.getValue().isJsonObject()) {
                problems.add("admin edit of " + entry.getKey() + " must be an object");
            } else if (id != null) {
                items.put(id, entry.getValue().getAsJsonObject().deepCopy());
            }
        }
        List<MarketOverrides.TagRule> tags = new ArrayList<>();
        JsonElement tagJson = edits.get("tags");
        if (tagJson != null && !tagJson.isJsonArray()) {
            problems.add("\"tags\" must be a list");
        } else if (tagJson != null) {
            for (JsonElement element : tagJson.getAsJsonArray()) {
                ResourceLocation tag = element instanceof JsonObject rule && rule.has("tag")
                        ? ResourceLocation.tryParse(rule.get("tag").getAsString()) : null;
                if (tag == null || !element.getAsJsonObject().has("patch") || !element.getAsJsonObject().get("patch").isJsonObject()) {
                    problems.add("tag rule " + element + " must have a valid \"tag\" and a \"patch\" object");
                } else {
                    tags.add(new MarketOverrides.TagRule(tag, element.getAsJsonObject().getAsJsonObject("patch").deepCopy()));
                }
            }
        }
        if (!problems.isEmpty()) {
            return new ParseResult(Optional.empty(), List.copyOf(problems));
        }
        String created = json.has("created") ? json.get("created").getAsString() : "unknown";
        long gameTime = json.has("game_time") ? json.get("game_time").getAsLong() : 0;
        return new ParseResult(Optional.of(new Contents(created, gameTime, stock, factors,
                new MarketOverrides.Snapshot(items, tags))), List.of());
    }

    /** Counts what restoring {@code part} would change. Changes nothing. */
    public static Difference difference(Contents backup, Part part, MarketStockData market, MarketOverrides.Snapshot edits) {
        int stock = 0;
        int prices = 0;
        int itemEdits = 0;
        int tagRules = 0;
        if (part.includes(Part.STOCK)) {
            stock = changed(backup.stock(), market.view(), 0L);
        }
        if (part.includes(Part.PRICES)) {
            Map<ResourceLocation, Double> before = new LinkedHashMap<>();
            backup.factors().forEach((id, factor) -> before.put(id, factor.value()));
            Map<ResourceLocation, Double> now = new LinkedHashMap<>();
            market.factorsView().forEach((id, factor) -> now.put(id, factor.value()));
            prices = changed(before, now, 1.0);
        }
        if (part.includes(Part.EDITS)) {
            itemEdits = changed(backup.edits().items(), edits.items(), null);
            Map<ResourceLocation, JsonObject> before = new LinkedHashMap<>();
            backup.edits().tags().forEach(rule -> before.put(rule.tag(), rule.patch()));
            Map<ResourceLocation, JsonObject> now = new LinkedHashMap<>();
            edits.tags().forEach(rule -> now.put(rule.tag(), rule.patch()));
            tagRules = changed(before, now, null);
        }
        Set<ResourceLocation> unknown = new HashSet<>();
        if (part.includes(Part.STOCK)) {
            unknown.addAll(backup.stock().keySet());
        }
        if (part.includes(Part.PRICES)) {
            unknown.addAll(backup.factors().keySet());
        }
        if (part.includes(Part.EDITS)) {
            unknown.addAll(backup.edits().items().keySet());
        }
        unknown.removeIf(BuiltInRegistries.ITEM::containsKey);
        return new Difference(stock, prices, itemEdits, tagRules, unknown);
    }

    /**
     * Replaces {@code part} of the market with the backup's. Restored price factors take effect from {@code now}, so
     * the prices come back as they were rather than jumping by the recovery that elapsed since the backup.
     */
    public static void apply(Contents backup, Part part, MarketStockData market, MarketOverrides edits, long now) {
        if (part.includes(Part.STOCK)) {
            market.replaceStock(backup.stock());
        }
        if (part.includes(Part.PRICES)) {
            Map<ResourceLocation, MarketStockData.Factor> factors = new LinkedHashMap<>();
            backup.factors().forEach((id, factor) -> factors.put(id, new MarketStockData.Factor(factor.value(), now)));
            market.replaceFactors(factors);
        }
        if (part.includes(Part.EDITS)) {
            edits.restore(backup.edits());
        }
    }

    /** How many keys differ between two maps, treating a missing key as {@code missing}. */
    private static <V> int changed(Map<ResourceLocation, V> a, Map<ResourceLocation, V> b, @Nullable V missing) {
        Set<ResourceLocation> keys = new HashSet<>(a.keySet());
        keys.addAll(b.keySet());
        int count = 0;
        for (ResourceLocation key : keys) {
            if (!Objects.equals(a.getOrDefault(key, missing), b.getOrDefault(key, missing))) {
                count++;
            }
        }
        return count;
    }

    private static JsonObject object(JsonObject parent, String key, List<String> problems) {
        JsonElement value = parent.get(key);
        if (value == null) {
            return new JsonObject();
        }
        if (!value.isJsonObject()) {
            problems.add("\"" + key + "\" must be an object");
            return new JsonObject();
        }
        return value.getAsJsonObject();
    }

    @Nullable
    private static ResourceLocation id(String key, String where, List<String> problems) {
        ResourceLocation id = ResourceLocation.tryParse(key);
        if (id == null) {
            problems.add("\"" + key + "\" in " + where + " isn't a valid ID");
        }
        return id;
    }

    @Nullable
    private static Long wholeNumber(JsonElement value) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            return null;
        }
        try {
            return new BigDecimal(value.getAsString()).longValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            return null;
        }
    }
}
