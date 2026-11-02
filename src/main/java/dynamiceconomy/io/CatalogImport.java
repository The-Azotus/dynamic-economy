package dynamiceconomy.io;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.serialization.DataResult;
import dynamiceconomy.catalog.CatalogEntry;
import dynamiceconomy.catalog.MarketCatalog;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Turns a priced spreadsheet back into catalog files. Each row is checked with the catalog loader's own rules, so
 * a row is accepted only if the same entry would load from a catalog file. Importing never touches saved stock or
 * price factors.
 */
public final class CatalogImport {
    /** The world datapack imported files go into. */
    public static final String PACK_FOLDER = "dynamic_economy_catalog";
    public static final String PACK_ID = "file/" + PACK_FOLDER;
    /** Data pack format for Minecraft 1.21.1. */
    private static final int PACK_FORMAT = 48;

    private CatalogImport() {
    }

    /**
     * What a CSV would import.
     *
     * @param byMod        accepted entries as catalog JSON, grouped by mod ID; what would be written
     * @param decoded      the same entries as the loader will read them
     * @param skippedBlank rows with no base_price (left unpriced on purpose)
     * @param rejected     one reason per rejected row, with its line number
     */
    public record Report(Map<String, List<JsonObject>> byMod, List<CatalogEntry> decoded, int skippedBlank,
                         List<String> rejected) {
        public int toWrite() {
            return decoded.size();
        }
    }

    public static Report analyze(String csvText) {
        Csv.Table table = Csv.parse(csvText);
        List<String> rejected = new ArrayList<>();
        Map<String, List<JsonObject>> byMod = new TreeMap<>();
        List<CatalogEntry> decoded = new ArrayList<>();
        if (!table.headers().contains(CatalogColumns.ITEM_ID) || !table.headers().contains(CatalogColumns.BASE_PRICE)) {
            rejected.add("The header row needs \"item_id\" and \"base_price\" columns (found: " + table.headers() + ")");
            return new Report(byMod, decoded, 0, rejected);
        }

        int skippedBlank = 0;
        Map<ResourceLocation, Integer> seen = new HashMap<>();
        for (Csv.Row row : table.rows()) {
            String itemText = row.get(CatalogColumns.ITEM_ID);
            String where = "line " + row.line() + (itemText.isEmpty() ? "" : " (" + itemText + ")");
            String price = row.get(CatalogColumns.BASE_PRICE);
            if (price.isEmpty()) {
                skippedBlank++;
                continue;
            }
            ResourceLocation id = ResourceLocation.tryParse(itemText);
            if (id == null || itemText.isEmpty()) {
                rejected.add(where + ": \"" + itemText + "\" is not a valid item ID");
                continue;
            }
            Integer earlier = seen.putIfAbsent(id, row.line());
            if (earlier != null) {
                rejected.add(where + ": duplicate of line " + earlier);
                continue;
            }

            JsonObject json = new JsonObject();
            json.addProperty("item", id.toString());
            String error = putPrice(json, price);
            for (Map.Entry<String, CatalogColumns.Kind> field : CatalogColumns.OPTIONAL_FIELDS.entrySet()) {
                if (error != null) {
                    break;
                }
                String value = row.get(field.getKey());
                if (!value.isEmpty()) {
                    error = putField(json, field.getKey(), field.getValue(), value);
                }
            }
            if (error != null) {
                rejected.add(where + ": " + error);
                continue;
            }
            DataResult<CatalogEntry> entry = MarketCatalog.decode(json);
            if (entry.error().isPresent()) {
                rejected.add(where + ": " + entry.error().get().message());
                continue;
            }
            byMod.computeIfAbsent(id.getNamespace(), ns -> new ArrayList<>()).add(json);
            decoded.add(entry.result().orElseThrow());
        }
        return new Report(byMod, decoded, skippedBlank, rejected);
    }

    /** Writes the result of an import. {@code createdPack} means the datapack folder didn't exist before. */
    public record Written(List<Path> files, List<Path> backups, boolean createdPack) {
    }

    /**
     * Writes {@code imported_<modid>.json} per mod into the catalog datapack under {@code datapacksDir}, creating the
     * pack if needed and backing up any file it replaces to {@code <name>.json.bak}.
     */
    public static Written write(Report report, Path datapacksDir) throws IOException {
        Path pack = datapacksDir.resolve(PACK_FOLDER);
        boolean created = !Files.exists(pack);
        Path market = Files.createDirectories(pack.resolve("data/dynamic_economy/market"));
        Path mcmeta = pack.resolve("pack.mcmeta");
        if (!Files.exists(mcmeta)) {
            JsonObject meta = new JsonObject();
            JsonObject packInfo = new JsonObject();
            packInfo.addProperty("pack_format", PACK_FORMAT);
            packInfo.addProperty("description", "Market catalog imported by Dynamic-Economy");
            meta.add("pack", packInfo);
            Files.writeString(mcmeta, CatalogExport.GSON.toJson(meta), StandardCharsets.UTF_8);
        }

        List<Path> files = new ArrayList<>();
        List<Path> backups = new ArrayList<>();
        for (Map.Entry<String, List<JsonObject>> mod : report.byMod().entrySet()) {
            Path file = market.resolve("imported_" + mod.getKey() + ".json");
            if (Files.exists(file)) {
                Path backup = file.resolveSibling(file.getFileName() + ".bak");
                Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
                backups.add(backup);
            }
            JsonArray entries = new JsonArray();
            mod.getValue().forEach(entries::add);
            JsonObject catalog = new JsonObject();
            catalog.add("entries", entries);
            Files.writeString(file, CatalogExport.GSON.toJson(catalog), StandardCharsets.UTF_8);
            files.add(file);
        }
        return new Written(files, backups, created);
    }

    /**
     * Resolves a user-typed path against {@code root} and refuses anything that could leave it: absolute paths,
     * drive letters and {@code ..} segments are rejected outright, and the result must still be inside root.
     */
    public static Path resolveInside(Path root, String userPath) {
        String cleaned = userPath.trim().replace('\\', '/');
        if (cleaned.isEmpty() || cleaned.startsWith("/") || cleaned.matches("^[A-Za-z]:.*") || cleaned.contains(":")) {
            throw new IllegalArgumentException("Use a path relative to config/dynamic_economy/, e.g. exports/<folder>/items.csv");
        }
        for (String segment : cleaned.split("/")) {
            if (segment.equals("..")) {
                throw new IllegalArgumentException("\"..\" is not allowed in the path");
            }
        }
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path resolved = normalizedRoot.resolve(cleaned).normalize();
        if (!resolved.startsWith(normalizedRoot)) {
            throw new IllegalArgumentException("The path must stay inside config/dynamic_economy/");
        }
        return resolved;
    }

    private static String putPrice(JsonObject json, String price) {
        if (!price.matches("\\d+")) {
            return "base_price \"" + price + "\" must be a positive whole number of bits";
        }
        BigDecimal value = new BigDecimal(price);
        if (value.signum() <= 0 || value.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0) {
            return "base_price " + price + " must be between 1 and " + Integer.MAX_VALUE;
        }
        json.addProperty("base_price", value.intValueExact());
        return null;
    }

    /** Adds one optional field, or returns why its text is unreadable. Range checks are left to the catalog rules. */
    private static String putField(JsonObject json, String name, CatalogColumns.Kind kind, String text) {
        switch (kind) {
            case BOOLEAN -> {
                switch (text.toLowerCase(Locale.ROOT)) {
                    case "true", "yes", "1" -> json.addProperty(name, true);
                    case "false", "no", "0" -> json.addProperty(name, false);
                    default -> {
                        return name + " \"" + text + "\" must be true or false";
                    }
                }
            }
            case WHOLE_NUMBER -> {
                if (!text.matches("-?\\d+")) {
                    return name + " \"" + text + "\" must be a whole number";
                }
                try {
                    json.addProperty(name, Long.parseLong(text));
                } catch (NumberFormatException e) {
                    return name + " \"" + text + "\" is too large";
                }
            }
            case TEXT -> json.addProperty(name, text);
            case DECIMAL -> {
                // Spreadsheets in some locales write a decimal comma: accept "0,5" as 0.5.
                String normalized = text.indexOf('.') < 0 && text.chars().filter(c -> c == ',').count() == 1
                        ? text.replace(',', '.') : text;
                try {
                    json.addProperty(name, Double.parseDouble(normalized));
                } catch (NumberFormatException e) {
                    return name + " \"" + text + "\" must be a number";
                }
            }
        }
        return null;
    }
}
