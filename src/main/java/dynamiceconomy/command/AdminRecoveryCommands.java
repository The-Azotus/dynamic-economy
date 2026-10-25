package dynamiceconomy.command;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dynamiceconomy.DynamicEconomy;
import dynamiceconomy.data.AuditLog;
import dynamiceconomy.data.MarketBackup;
import dynamiceconomy.data.MarketOverrides;
import dynamiceconomy.data.MarketStockData;
import dynamiceconomy.network.MarketSync;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code /market admin backup}, {@code backups}, {@code restore} and {@code audit}: saving and restoring the market's
 * state, and reading the log of admin actions.
 */
final class AdminRecoveryCommands {
    private static final int BACKUPS_SHOWN = 10;
    private static final int AUDIT_PER_PAGE = 10;
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private AdminRecoveryCommands() {
    }

    static void addTo(LiteralArgumentBuilder<CommandSourceStack> admin) {
        admin.then(Commands.literal("backup").executes(AdminRecoveryCommands::backup));
        admin.then(Commands.literal("backups").executes(AdminRecoveryCommands::listBackups));
        var name = Commands.argument("name", StringArgumentType.word())
                .suggests((ctx, builder) -> {
                    try {
                        return SharedSuggestionProvider.suggest(MarketBackup.list(ctx.getSource().getServer()), builder);
                    } catch (IOException e) {
                        return builder.buildFuture();
                    }
                });
        for (MarketBackup.Part part : MarketBackup.Part.values()) {
            name.then(Commands.literal(part.name().toLowerCase(Locale.ROOT))
                    .executes(ctx -> restore(ctx, part, false))
                    .then(Commands.literal("confirm").executes(ctx -> restore(ctx, part, true))));
        }
        admin.then(Commands.literal("restore").then(name));
        admin.then(Commands.literal("audit").executes(ctx -> audit(ctx, 1))
                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                        .executes(ctx -> audit(ctx, IntegerArgumentType.getInteger(ctx, "page")))));
    }

    private static int backup(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        try {
            String name = MarketBackup.create(source.getServer(), null);
            AuditLog.record(source, "backup", name, "ok", null, null);
            source.sendSuccess(() -> Component.literal(("Backed up the market's stock, prices and admin edits as \"%s\". "
                    + "Restore with /market admin restore %s <stock|prices|edits|all>").formatted(name, name)), true);
            return 1;
        } catch (IOException e) {
            DynamicEconomy.LOGGER.error("Market backup failed", e);
            source.sendFailure(Component.literal("Backup failed: " + e.getMessage()));
            return 0;
        }
    }

    private static int listBackups(CommandContext<CommandSourceStack> ctx) {
        try {
            List<String> names = MarketBackup.list(ctx.getSource().getServer());
            if (names.isEmpty()) {
                ctx.getSource().sendSuccess(() -> Component.literal("No backups yet. Make one with /market admin backup"), false);
                return 0;
            }
            ctx.getSource().sendSuccess(() -> Component.literal("Backups, newest first (\"auto\" ones were taken before a restore or prune):"), false);
            names.stream().limit(BACKUPS_SHOWN).forEach(name -> ctx.getSource().sendSuccess(() -> Component.literal("  " + name), false));
            if (names.size() > BACKUPS_SHOWN) {
                ctx.getSource().sendSuccess(() -> Component.literal("  ...and %d older; all are in <world>/dynamic_economy/backups/"
                        .formatted(names.size() - BACKUPS_SHOWN)), false);
            }
            return names.size();
        } catch (IOException e) {
            ctx.getSource().sendFailure(Component.literal("Couldn't list backups: " + e.getMessage()));
            return 0;
        }
    }

    /**
     * Without {@code confirm}: checks the backup and says what restoring would change. With it: backs up the current
     * state automatically, then replaces the chosen part.
     */
    private static int restore(CommandContext<CommandSourceStack> ctx, MarketBackup.Part part, boolean confirm) {
        CommandSourceStack source = ctx.getSource();
        MinecraftServer server = source.getServer();
        String name = StringArgumentType.getString(ctx, "name");
        String what = part.name().toLowerCase(Locale.ROOT);
        Path file;
        try {
            file = MarketBackup.file(server, name);
        } catch (IllegalArgumentException e) {
            source.sendFailure(Component.literal(e.getMessage()));
            return 0;
        }
        MarketBackup.ParseResult read = MarketBackup.read(file);
        if (read.contents().isEmpty()) {
            source.sendFailure(Component.literal("Backup \"%s\" can't be restored; nothing was changed:".formatted(name)));
            read.problems().stream().limit(5).forEach(problem -> source.sendFailure(Component.literal("  " + problem)));
            if (confirm) {
                AuditLog.record(source, "restore " + what, name, "refused: " + read.problems().get(0), null, null);
            }
            return 0;
        }
        MarketBackup.Contents backup = read.contents().get();
        MarketStockData market = MarketStockData.get(server);
        MarketOverrides edits = MarketOverrides.get(server);
        MarketBackup.Difference difference = MarketBackup.difference(backup, part, market, edits.snapshot());

        List<String> lines = new ArrayList<>();
        long ticksAgo = MarketStockData.now(server) - backup.gameTime();
        lines.add("Backup \"%s\", taken %s (about %d minutes of game time ago)".formatted(name, when(backup.created()),
                Math.max(0, ticksAgo) / 1200));
        long newer = AuditLog.recent(AuditLog.file(server), 1000).stream()
                .filter(entry -> isAfter(text(entry, "time"), backup.created()))
                .count();
        if (newer > 0) {
            lines.add("  %d admin action(s) have been logged since then; /market admin audit lists them".formatted(newer));
        }
        lines.add("  Restoring %s would change: %d stock level(s), %d price(s), %d item edit(s), %d tag rule(s)"
                .formatted(what, difference.stock(), difference.prices(), difference.itemEdits(), difference.tagRules()));
        if (!difference.unknownItems().isEmpty()) {
            lines.add("  %d item(s) in it no longer exist in the game; their data is restored but unused"
                    .formatted(difference.unknownItems().size()));
        }
        lines.forEach(line -> source.sendSuccess(() -> Component.literal(line), false));
        if (!confirm) {
            source.sendSuccess(() -> Component.literal("Nothing has been changed. Add \"confirm\" to restore; the current "
                    + "state is backed up first"), false);
            return 1;
        }

        String safety;
        try {
            safety = MarketBackup.create(server, "restore");
        } catch (IOException e) {
            source.sendFailure(Component.literal("Couldn't back up the current state first, so nothing was restored: " + e.getMessage()));
            return 0;
        }
        MarketBackup.apply(backup, part, market, edits, MarketStockData.now(server));
        if (part == MarketBackup.Part.EDITS || part == MarketBackup.Part.ALL) {
            DynamicEconomy.catalogReloaded(server);
        } else {
            MarketSync.broadcastSnapshot(server);
        }
        JsonObject counts = new JsonObject();
        counts.addProperty("stock", difference.stock());
        counts.addProperty("prices", difference.prices());
        counts.addProperty("item_edits", difference.itemEdits());
        counts.addProperty("tag_rules", difference.tagRules());
        AuditLog.record(source, "restore " + what, name, "ok", new JsonPrimitive("saved first as " + safety), counts);
        source.sendSuccess(() -> Component.literal("Restored %s from \"%s\". The state before is saved as \"%s\" if you need to go back"
                .formatted(what, name, safety)), true);
        return 1;
    }

    /** The most recent admin actions, newest first, ten per page. */
    private static int audit(CommandContext<CommandSourceStack> ctx, int page) {
        List<JsonObject> entries = AuditLog.recent(AuditLog.file(ctx.getSource().getServer()), page * AUDIT_PER_PAGE + 1);
        List<JsonObject> shown = entries.stream().skip((long) (page - 1) * AUDIT_PER_PAGE).limit(AUDIT_PER_PAGE).toList();
        if (shown.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal(page == 1 ? "No admin actions logged yet"
                    : "No more entries"), false);
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("Admin actions, newest first (page %d; the full log is <world>/dynamic_economy/audit.log):"
                .formatted(page)), false);
        for (JsonObject entry : shown) {
            String line = "  %s %s: %s %s → %s".formatted(when(text(entry, "time")), text(entry, "actor"), text(entry, "action"),
                    text(entry, "target"), text(entry, "result"));
            ctx.getSource().sendSuccess(() -> Component.literal(line), false);
        }
        if (entries.size() > page * AUDIT_PER_PAGE) {
            ctx.getSource().sendSuccess(() -> Component.literal("/market admin audit " + (page + 1) + " for older ones"), false);
        }
        return shown.size();
    }

    private static boolean isAfter(String instant, String reference) {
        try {
            return Instant.parse(instant).isAfter(Instant.parse(reference));
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String text(JsonObject entry, String key) {
        return entry.has(key) ? entry.get(key).getAsString() : "?";
    }

    /** An ISO instant as local date and time, or as it is if it can't be read. */
    private static String when(String instant) {
        try {
            return WHEN.format(Instant.parse(instant));
        } catch (RuntimeException e) {
            return instant;
        }
    }
}
