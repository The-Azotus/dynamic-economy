package dynamiceconomy.service;

import com.mojang.authlib.GameProfile;
import dynamiceconomy.MarketConfig;
import dynamiceconomy.bank.WorldBank;
import dynamiceconomy.data.ContractBoard;
import dynamiceconomy.data.ExchangeData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.GameProfileCache;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The richest players by balance in the world's bank, plus the money the exchange holds for them (open buy orders and
 * owed payments) and contract payouts owed to them. Only personal accounts of known players count: shared accounts (Numismatics' Blaze Bankers), team
 * accounts and accounts with no player profile never appear, nor do empty accounts. Bank balances are what the bank
 * displays. Server thread only: it reads the bank.
 */
public final class Leaderboard {
    private Leaderboard() {
    }

    /** One ranked player. */
    public record Entry(UUID id, String name, long balance) {
    }

    /**
     * What one player is shown: the top of the board, and their own place on it.
     *
     * @param ownRank the player's rank from 1, or 0 when they aren't on the board (no money, or excluded)
     */
    public record View(List<Entry> top, int ownRank, int ranked) {
    }

    /** The board as {@code viewer} sees it, ranked now. */
    public static View viewFor(ServerPlayer viewer) {
        return viewFor(viewer, id -> profile(viewer.server, id));
    }

    /** The same, naming players with {@code profiles} instead of the server's (GameTests have no profile cache). */
    public static View viewFor(ServerPlayer viewer, Function<UUID, Optional<GameProfile>> profiles) {
        MinecraftServer server = viewer.server;
        Predicate<GameProfile> excluded = MarketConfig.leaderboardExcludeOperators()
                ? profile -> server.getPlayerList().isOp(profile)
                : profile -> false;
        Predicate<Optional<UUID>> paysTeam = team -> PayoutService.paysTeam(server, team);
        List<Entry> ranked = rank(withHeld(WorldBank.of(server).playerBalances(), ExchangeData.get(server).heldForPlayers(paysTeam),
                ContractBoard.get(server).owed().forPlayers(paysTeam)), profiles, excluded);
        int ownRank = 0;
        for (int i = 0; i < ranked.size(); i++) {
            if (ranked.get(i).id().equals(viewer.getUUID())) {
                ownRank = i + 1;
                break;
            }
        }
        return new View(List.copyOf(ranked.subList(0, Math.min(MarketConfig.leaderboardSize(), ranked.size()))),
                ownRank, ranked.size());
    }

    /** Bank balances with money held for players elsewhere (the exchange, owed payouts) added, by player. */
    @SafeVarargs
    public static Map<UUID, Long> withHeld(Map<UUID, Long> balances, Map<UUID, Long>... held) {
        Map<UUID, Long> total = new HashMap<>(balances);
        for (Map<UUID, Long> more : held) {
            more.forEach((id, amount) -> total.merge(id, amount, Long::sum));
        }
        return total;
    }

    /**
     * Every account worth listing, richest first; equal balances by name. Accounts with no balance, no known
     * profile, or an excluded profile are left out.
     */
    public static List<Entry> rank(Map<UUID, Long> balances, Function<UUID, Optional<GameProfile>> profiles,
                                   Predicate<GameProfile> excluded) {
        List<Entry> entries = new ArrayList<>();
        balances.forEach((id, balance) -> {
            if (balance <= 0) {
                return;
            }
            profiles.apply(id)
                    .filter(profile -> profile.getName() != null && !profile.getName().isEmpty() && !excluded.test(profile))
                    .ifPresent(profile -> entries.add(new Entry(id, profile.getName(), balance)));
        });
        entries.sort(Comparator.comparingLong(Entry::balance).reversed()
                .thenComparing(Entry::name, String.CASE_INSENSITIVE_ORDER));
        return entries;
    }

    /** An online player's profile, or one the server has seen before (offline players, by their last name). */
    public static Optional<GameProfile> profile(MinecraftServer server, UUID id) {
        ServerPlayer online = server.getPlayerList().getPlayer(id);
        if (online != null) {
            return Optional.of(online.getGameProfile());
        }
        GameProfileCache cache = server.getProfileCache();
        return cache == null ? Optional.empty() : cache.get(id);
    }
}
