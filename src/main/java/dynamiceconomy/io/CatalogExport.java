package dynamiceconomy.io;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dynamiceconomy.catalog.CatalogEntry;
import dynamiceconomy.catalog.FieldSource;
import dynamiceconomy.catalog.ItemClasses;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Writes the live item registry to a timestamped folder for pricing: {@code items.csv} with every item (for listed
 * ones, the values some catalog layer sets; fields left to the item's class or a default stay blank, so importing the
 * sheet doesn't pin them) and {@code templates/<modid>.json} catalog files holding only unlisted items at
 * {@code base_price: 0}, which the loader skips until they are priced. Never writes into the live catalog.
 */
public final class CatalogExport {
    static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final DateTimeFormatter FOLDER_NAME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    /** Where the files went and what was in them. {@code modsWithTemplates} counts the template files written. */
    public record Result(Path folder, int total, int priced, int unpriced, int modsWithTemplates) {
    }

    private CatalogExport() {
    }

    /**
     * @param exportsRoot usually {@code config/dynamic_economy/exports}
     * @param modId       only export this mod's items; empty for every mod
     */
    public static Result export(Path exportsRoot, Optional<String> modId, Collection<CatalogEntry> catalog,
                                Function<Item, Map<String, List<FieldSource>>> sources) throws IOException {
        Map<Item, CatalogEntry> listed = catalog.stream().collect(Collectors.toMap(CatalogEntry::item, Function.identity()));
        List<Item> items = new ArrayList<>();
        for (Item item : BuiltInRegistries.ITEM) {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
            if (item != Items.AIR && (modId.isEmpty() || id.getNamespace().equals(modId.get()))) {
                items.add(item);
            }
        }
        items.sort(Comparator.comparing(item -> BuiltInRegistries.ITEM.getKey(item).toString()));

        Path folder = newFolder(exportsRoot);
        StringBuilder csv = new StringBuilder("﻿"); // Lets Excel open non-ASCII names as UTF-8.
        csv.append(Csv.line(CatalogColumns.HEADER));
        Map<String, JsonArray> templates = new TreeMap<>();
        int priced = 0;
        for (Item item : items) {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
            CatalogEntry entry = listed.get(item);
            csv.append(Csv.line(row(item, id, entry, entry == null ? FieldSource.NONE : sources.apply(item))));
            if (entry != null) {
                priced++;
            } else {
                JsonObject template = new JsonObject();
                template.addProperty("item", id.toString());
                template.addProperty("base_price", 0);
                templates.computeIfAbsent(id.getNamespace(), ns -> new JsonArray()).add(template);
            }
        }
        Files.writeString(folder.resolve("items.csv"), csv, StandardCharsets.UTF_8);

        Path templateFolder = Files.createDirectories(folder.resolve("templates"));
        for (Map.Entry<String, JsonArray> template : templates.entrySet()) {
            JsonObject file = new JsonObject();
            file.add("entries", template.getValue());
            Files.writeString(templateFolder.resolve(template.getKey() + ".json"), GSON.toJson(file), StandardCharsets.UTF_8);
        }
        return new Result(folder, items.size(), priced, items.size() - priced, templates.size());
    }

    /** One item's row. With no recorded sources, every value counts as set. */
    private static List<String> row(Item item, ResourceLocation id, CatalogEntry entry, Map<String, List<FieldSource>> set) {
        ItemStack stack = new ItemStack(item);
        List<String> row = new ArrayList<>(List.of(id.toString(), id.getNamespace(), stack.getHoverName().getString(),
                String.valueOf(stack.getMaxStackSize()), String.valueOf(entry != null)));
        if (entry == null) {
            CatalogColumns.HEADER.subList(row.size(), CatalogColumns.HEADER.size()).forEach(column -> row.add(""));
            return row;
        }
        CatalogEntry.Overrides o = entry.overrides();
        Map<String, String> values = new HashMap<>();
        values.put("class", entry.itemClass().map(ItemClasses::shortName).orElse(""));
        values.put("unlimited_stock", String.valueOf(entry.unlimitedStock()));
        values.put("markup", CatalogColumns.decimal(entry.markup()));
        values.put("min_buy_markup", entry.minBuyMarkup().map(CatalogColumns::decimal).orElse(""));
        values.put("sell_fee", CatalogColumns.decimal(entry.sellFee()));
        values.put("can_sell", String.valueOf(entry.canSell()));
        values.put("can_buy", String.valueOf(entry.canBuy()));
        values.put("start_stock", String.valueOf(entry.startStock()));
        values.put("dynamic", String.valueOf(entry.dynamic()));
        values.put("sell_impact", o.sellImpact().map(CatalogColumns::decimal).orElse(""));
        values.put("buy_impact", o.buyImpact().map(CatalogColumns::decimal).orElse(""));
        values.put("recovery", o.recovery().map(CatalogColumns::decimal).orElse(""));
        values.put("floor", o.floor().map(CatalogColumns::decimal).orElse(""));
        values.put("ceiling", o.ceiling().map(CatalogColumns::decimal).orElse(""));
        row.add(String.valueOf(entry.basePrice()));
        for (String field : CatalogColumns.OPTIONAL_FIELDS.keySet()) {
            row.add(set.isEmpty() || set.containsKey(field) ? values.get(field) : "");
        }
        return row;
    }

    /** A new timestamped folder; adds a suffix rather than reuse one, so an earlier export is never overwritten. */
    private static Path newFolder(Path exportsRoot) throws IOException {
        Files.createDirectories(exportsRoot);
        String name = LocalDateTime.now().format(FOLDER_NAME);
        Path folder = exportsRoot.resolve(name);
        for (int n = 2; Files.exists(folder); n++) {
            folder = exportsRoot.resolve(name + "_" + n);
        }
        return Files.createDirectory(folder);
    }
}
