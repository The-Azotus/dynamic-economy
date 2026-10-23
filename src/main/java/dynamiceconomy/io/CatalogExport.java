package dynamiceconomy.io;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dynamiceconomy.catalog.CatalogEntry;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Writes the live item registry to a timestamped folder for pricing: {@code items.csv} with every item (catalog
 * values filled in for listed ones) and {@code templates/<modid>.json} catalog files holding only unlisted items at
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
    public static Result export(Path exportsRoot, Optional<String> modId, Collection<CatalogEntry> catalog) throws IOException {
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
            csv.append(Csv.line(row(item, id, entry)));
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

    private static List<String> row(Item item, ResourceLocation id, CatalogEntry entry) {
        ItemStack stack = new ItemStack(item);
        List<String> row = new ArrayList<>(List.of(id.toString(), id.getNamespace(), stack.getHoverName().getString(),
                String.valueOf(stack.getMaxStackSize()), String.valueOf(entry != null)));
        if (entry == null) {
            CatalogColumns.HEADER.subList(row.size(), CatalogColumns.HEADER.size()).forEach(column -> row.add(""));
            return row;
        }
        CatalogEntry.Overrides o = entry.overrides();
        row.add(String.valueOf(entry.basePrice()));
        row.add(String.valueOf(entry.unlimitedStock()));
        row.add(CatalogColumns.decimal(entry.markup()));
        row.add(entry.minBuyMarkup().map(CatalogColumns::decimal).orElse(""));
        row.add(CatalogColumns.decimal(entry.sellFee()));
        row.add(String.valueOf(entry.canSell()));
        row.add(String.valueOf(entry.canBuy()));
        row.add(String.valueOf(entry.startStock()));
        row.add(String.valueOf(entry.dynamic()));
        row.add(o.sellImpact().map(CatalogColumns::decimal).orElse(""));
        row.add(o.buyImpact().map(CatalogColumns::decimal).orElse(""));
        row.add(o.recovery().map(CatalogColumns::decimal).orElse(""));
        row.add(o.floor().map(CatalogColumns::decimal).orElse(""));
        row.add(o.ceiling().map(CatalogColumns::decimal).orElse(""));
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
