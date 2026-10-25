package dynamiceconomy.service;

import com.google.gson.JsonObject;
import dynamiceconomy.catalog.CatalogEntry;
import dynamiceconomy.catalog.FieldSource;
import dynamiceconomy.catalog.MarketCatalog;
import dynamiceconomy.data.MarketOverrides;
import dynamiceconomy.data.MarketStockData;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * Looks over the catalog, admin edits and saved market state for things an admin probably wants to know about. It only
 * reads: nothing is changed. Severity says how sure it is that something is wrong: an error means something was left
 * out of the market, a warning is very likely a mistake, and a note may well be deliberate policy.
 */
public final class MarketDiagnostics {
    /** A tag rule covering more items than this is noted, so the admin can check it covers only what they meant. */
    public static final int BROAD_TAG = 64;

    private MarketDiagnostics() {
    }

    public enum Severity { ERROR, WARNING, NOTE }

    public record Finding(Severity severity, String message) {
    }

    /**
     * @param files        the catalog files as loaded, with their problems
     * @param entries      the live catalog
     * @param sources      where each listed item's fields came from
     * @param editProblems admin edits that couldn't be applied
     */
    public static List<Finding> run(MarketCatalog.Loaded files, Collection<CatalogEntry> entries,
                                    Function<Item, Map<String, List<FieldSource>>> sources,
                                    Map<ResourceLocation, String> editProblems, MarketOverrides.Snapshot overrides,
                                    MarketStockData market, long now, Function<ResourceLocation, Collection<Item>> tagMembers) {
        List<Finding> findings = new ArrayList<>();
        files.problems().forEach(problem -> findings.add(new Finding(problem.skipped() ? Severity.ERROR : Severity.WARNING,
                problem.message())));
        editProblems.values().forEach(problem -> findings.add(new Finding(Severity.ERROR, "Admin edit not applied: " + problem)));
        if (files.unpriced() > 0) {
            findings.add(new Finding(Severity.NOTE, files.unpriced()
                    + " catalog entries have base_price 0 and are skipped (unfilled templates)"));
        }

        for (CatalogEntry entry : entries) {
            ResourceLocation id = entry.itemId();
            PriceModel.Inputs inputs = PriceModel.inputs(entry);
            PriceModel.arbitrageRisk(inputs).ifPresent(reason -> findings.add(new Finding(Severity.WARNING,
                    id + ": a round trip can make a profit (" + reason + ")")));
            if (!entry.unlimitedStock() && entry.canBuy() && !entry.canSell() && market.getStock(id) == 0) {
                findings.add(new Finding(Severity.WARNING, id + " can never be bought: the market doesn't buy it, so its stock "
                        + "stays at 0 unless an admin sets it (start_stock or /market admin setstock)"));
            }
            if (entry.dynamic()) {
                double factor = MarketService.currentFactor(entry, inputs, market, now);
                if (factor <= inputs.floor()) {
                    findings.add(new Finding(Severity.WARNING, id + " is at its price floor (" + percent(factor)
                            + " of base): players are selling far more than they buy"));
                } else if (factor >= inputs.ceiling()) {
                    findings.add(new Finding(Severity.WARNING, id + " is at its price ceiling (" + percent(factor)
                            + " of base): players are buying far more than they sell"));
                }
            }
            if (entry.unlimitedStock() && entry.canSell()) {
                findings.add(new Finding(Severity.NOTE, id + " has unlimited stock and can be sold, so farms can sell it "
                        + "forever. Fine if intended; otherwise set can_sell false or a sell_fee"));
            }
            if (!entry.canBuy() && !entry.canSell()) {
                findings.add(new Finding(Severity.NOTE, id + " is listed but can be neither bought nor sold; "
                        + "/market admin remove takes it off the list"));
            }
            JsonObject edit = overrides.items().get(id);
            if (edit != null) {
                Map<String, List<FieldSource>> fields = sources.apply(entry.item());
                for (String field : edit.keySet()) {
                    List<FieldSource> layers = fields.get(field);
                    if (layers != null && layers.size() >= 2
                            && layers.get(layers.size() - 1).value().equals(layers.get(layers.size() - 2).value())) {
                        findings.add(new Finding(Severity.WARNING, "The admin edit of " + field + " on " + id + " has no effect: "
                                + layers.get(layers.size() - 2).layer() + " already sets " + layers.get(layers.size() - 1).value()
                                + " (/market admin unset " + id + " " + field + " removes it)"));
                    }
                }
            }
        }

        for (MarketOverrides.TagRule rule : overrides.tags()) {
            int size = tagMembers.apply(rule.tag()).size();
            if (size == 0) {
                findings.add(new Finding(Severity.WARNING, "Tag rule #" + rule.tag() + " matches no items: check the "
                        + "spelling, or whether the mod that defines it was removed"));
            } else if (size > BROAD_TAG) {
                findings.add(new Finding(Severity.NOTE, "Tag rule #" + rule.tag() + " covers " + size
                        + " items; /market admin tag " + rule.tag() + " info shows how many are listed"));
            }
        }

        SortedSet<ResourceLocation> gone = unregisteredItems(market, overrides);
        if (!gone.isEmpty()) {
            findings.add(new Finding(Severity.NOTE, gone.size() + " item(s) with saved market data no longer exist in "
                    + "the game (e.g. " + gone.first() + "); /market admin prune shows and removes them"));
        }

        findings.sort(Comparator.comparing(Finding::severity).thenComparing(Finding::message));
        return findings;
    }

    /**
     * Item IDs that have saved stock, prices, history, money counts or admin edits but aren't in the item registry,
     * usually because the mod that added them was removed.
     */
    public static SortedSet<ResourceLocation> unregisteredItems(MarketStockData market, MarketOverrides.Snapshot overrides) {
        SortedSet<ResourceLocation> ids = new TreeSet<>();
        ids.addAll(market.view().keySet());
        ids.addAll(market.factorsView().keySet());
        ids.addAll(market.historyView().keySet());
        ids.addAll(market.itemTotalsView().keySet());
        ids.addAll(overrides.items().keySet());
        ids.removeIf(BuiltInRegistries.ITEM::containsKey);
        return ids;
    }

    private static String percent(double factor) {
        return Math.round(factor * 100) + "%";
    }
}
