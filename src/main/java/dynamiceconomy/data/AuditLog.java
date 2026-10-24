package dynamiceconomy.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dynamiceconomy.DynamicEconomy;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.storage.LevelResource;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A record of admin actions on the market, one JSON object per line in {@code <world>/dynamic_economy/audit.log}:
 * when, who, what, on what, the outcome, and the old and new values. It lives on the server's disk, out of players'
 * reach, and is size-capped: when the file would pass {@link #MAX_BYTES} it is renamed {@code audit.log.1} (older ones
 * shift up) and at most {@link #KEEP} files are kept. Writing never fails a command; problems are only logged.
 */
public final class AuditLog {
    public static final long MAX_BYTES = 1_000_000;
    /** Files kept in all: the current one plus {@code KEEP - 1} rotated ones. */
    public static final int KEEP = 5;

    private AuditLog() {
    }

    /** The folder for the mod's files in the current world: the audit log and backups. */
    public static Path worldFolder(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve(DynamicEconomy.MOD_ID);
    }

    public static Path file(MinecraftServer server) {
        return worldFolder(server).resolve("audit.log");
    }

    /**
     * Records one admin action.
     *
     * @param result  e.g. {@code ok}, {@code refused: ...}
     * @param before  the target's state before, if it makes sense to record
     * @param after   the target's state after
     */
    public static void record(CommandSourceStack source, String action, String target, String result,
                              @Nullable JsonElement before, @Nullable JsonElement after) {
        JsonObject line = new JsonObject();
        line.addProperty("time", Instant.now().toString());
        line.addProperty("actor", source.getTextName());
        Entity entity = source.getEntity();
        if (entity != null) {
            line.addProperty("actor_uuid", entity.getUUID().toString());
        }
        line.addProperty("action", action);
        line.addProperty("target", target);
        line.addProperty("result", result);
        if (before != null) {
            line.add("before", before);
        }
        if (after != null) {
            line.add("after", after);
        }
        try {
            append(file(source.getServer()), line.toString(), MAX_BYTES, KEEP);
        } catch (IOException e) {
            DynamicEconomy.LOGGER.warn("Could not write the market audit log: {}", e.getMessage());
        }
    }

    /** Appends a line, first rotating the file if the line would take it past {@code maxBytes}. */
    public static void append(Path file, String line, long maxBytes, int keep) throws IOException {
        Files.createDirectories(file.getParent());
        byte[] bytes = (line + "\n").getBytes(StandardCharsets.UTF_8);
        if (Files.exists(file) && Files.size(file) + bytes.length > maxBytes) {
            rotate(file, keep);
        }
        Files.write(file, bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /** {@code audit.log} becomes {@code audit.log.1}, {@code .1} becomes {@code .2}, and the oldest beyond {@code keep} goes. */
    private static void rotate(Path file, int keep) throws IOException {
        Files.deleteIfExists(rotated(file, keep - 1));
        for (int i = keep - 2; i >= 1; i--) {
            if (Files.exists(rotated(file, i))) {
                Files.move(rotated(file, i), rotated(file, i + 1), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Files.move(file, rotated(file, 1), StandardCopyOption.REPLACE_EXISTING);
    }

    static Path rotated(Path file, int index) {
        return file.resolveSibling(file.getFileName() + "." + index);
    }

    /** The newest {@code count} entries, newest first, reading back through rotated files as needed. */
    public static List<JsonObject> recent(Path file, int count) {
        List<JsonObject> entries = new ArrayList<>();
        for (int i = 0; i < KEEP && entries.size() < count; i++) {
            Path path = i == 0 ? file : rotated(file, i);
            if (!Files.exists(path)) {
                break;
            }
            try {
                List<String> lines = new ArrayList<>(Files.readAllLines(path, StandardCharsets.UTF_8));
                Collections.reverse(lines);
                for (String line : lines) {
                    if (entries.size() >= count) {
                        break;
                    }
                    if (!line.isBlank()) {
                        try {
                            entries.add(JsonParser.parseString(line).getAsJsonObject());
                        } catch (RuntimeException e) {
                            // A damaged line (e.g. the server stopped mid-write) is skipped, not fatal.
                        }
                    }
                }
            } catch (IOException e) {
                DynamicEconomy.LOGGER.warn("Could not read the market audit log {}: {}", path, e.getMessage());
                break;
            }
        }
        return entries;
    }
}
