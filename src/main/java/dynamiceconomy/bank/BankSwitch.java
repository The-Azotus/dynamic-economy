package dynamiceconomy.bank;

import dynamiceconomy.compat.NumismaticsBridge;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongConsumer;

/**
 * Moves a world from one bank to the other: every player's balance goes across, then the world records its new bank.
 * Only an admin does this ({@code /market admin bank switch}); a world never switches on its own.
 * <p>
 * Every account is looked up before any money moves. Money then moves account by account, each amount taken from
 * one bank and paid into the other straight away, so none is ever lost: Numismatics' hidden overflow above its
 * displayed cap is drained too. Numismatics' bank has no team accounts, so moving to it first pays each team's
 * balance to the team's owner; a team that no longer exists keeps its balance in the mod's bank, where it waits for a
 * switch back. Coins can't be moved: those players still carry stop counting once the bank changes. Server thread only.
 */
public final class BankSwitch {
    private BankSwitch() {
    }

    /**
     * What a switch would do.
     *
     * @param balances      every personal balance to move, by player (Numismatics' as displayed: an amount at its cap
     *                      may hide more, which moves too)
     * @param teams         team balances paid to their owners first (moving to Numismatics' bank only)
     * @param lostTeams     team balances whose team no longer exists, kept in the mod's bank
     * @param coinCarriers  online players carrying coins of the bank being left
     * @param carriedCoins  what those coins are worth
     */
    public record Plan(BankKind from, BankKind to, Map<UUID, Long> balances, Map<BankTeam, Long> teams,
                       Map<UUID, Long> lostTeams, int coinCarriers, long carriedCoins) {
        public long total() {
            return balances.values().stream().mapToLong(Long::longValue).sum()
                    + teams.values().stream().mapToLong(Long::longValue).sum();
        }
    }

    /** What would happen, or why the world can't switch to {@code to}. */
    public static Result plan(MinecraftServer server, BankKind to) {
        BankKind from = WorldBank.kind(server);
        if (from == to) {
            return Result.problem("This world already uses " + describe(to));
        }
        if (NumismaticsBridge.bank().isEmpty()) {
            return Result.problem("Numismatics isn't installed, or its bank doesn't match this mod, so its balances can't be moved");
        }
        Bank leaving = WorldBank.of(server);
        if (!leaving.canListAccounts()) {
            return Result.problem("The accounts of " + describe(from) + " can't be listed, so they can't be moved");
        }
        Map<BankTeam, Long> teams = new LinkedHashMap<>();
        Map<UUID, Long> lostTeams = new LinkedHashMap<>();
        if (from == BankKind.OWN) {
            OwnBank.get(server).teamBalances().forEach((id, balance) -> BankTeams.byId(server, id).ifPresentOrElse(
                    team -> teams.put(team, balance), () -> lostTeams.put(id, balance)));
        }
        int carriers = 0;
        long carried = 0;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            long value = carriedValue(player.getInventory(), leaving.coins());
            if (value > 0) {
                carriers++;
                carried += value;
            }
        }
        Map<UUID, Long> balances = new HashMap<>(leaving.playerBalances());
        balances.values().removeIf(balance -> balance <= 0);
        return Result.of(new Plan(from, to, Map.copyOf(balances), teams, lostTeams, carriers, carried));
    }

    /** Switches the world to {@code to}, moving every balance. Returns what was moved, or why nothing was. */
    public static Result switchTo(MinecraftServer server, BankKind to) {
        Result planned = plan(server, to);
        if (planned.plan().isEmpty()) {
            return planned;
        }
        Plan plan = planned.plan().get();
        Bank numismatics = NumismaticsBridge.bank().orElseThrow();
        OwnBank own = OwnBank.get(server);

        // Look every account up first, so a failed lookup stops the switch before any money moves.
        Map<UUID, Bank.Account> numismaticsAccounts = new HashMap<>();
        Set<UUID> players = new HashSet<>(plan.balances().keySet());
        plan.teams().keySet().forEach(team -> players.add(team.owner()));
        for (UUID player : players) {
            Optional<Bank.Account> account = NumismaticsBridge.accountById(player);
            if (account.isEmpty()) {
                return Result.problem("Numismatics' account for " + player + " couldn't be found, so nothing was moved");
            }
            numismaticsAccounts.put(player, account.get());
        }

        if (to == BankKind.NUMISMATICS) {
            plan.teams().keySet().forEach(team -> own.closeTeam(team.id(), team.owner()));
            for (UUID player : players) {
                deposit(numismaticsAccounts.get(player), own.drain(player));
            }
        } else {
            for (UUID player : players) {
                drain(numismaticsAccounts.get(player), numismatics.displayCap(), amount -> own.credit(player, amount));
            }
        }
        WorldBank.record(server, to);
        return planned;
    }

    /** Pays {@code amount} in, a transaction at a time. A deposit can't fail. */
    private static void deposit(Bank.Account account, long amount) {
        for (long left = amount; left > 0; left -= Integer.MAX_VALUE) {
            account.deposit((int) Math.min(left, Integer.MAX_VALUE));
        }
    }

    /**
     * Empties an account, handing each amount taken to {@code paid} at once. A balance at the display cap may hide
     * more, so it's taken a whole transaction at a time while that works, then as shown, until nothing is left.
     */
    public static long drain(Bank.Account account, long displayCap, LongConsumer paid) {
        long moved = 0;
        while (true) {
            long shown = account.balance();
            if (shown <= 0) {
                return moved;
            }
            int take = shown >= displayCap && account.deduct(Integer.MAX_VALUE) ? Integer.MAX_VALUE : -1;
            if (take < 0) {
                take = (int) Math.min(shown, Integer.MAX_VALUE);
                if (!account.deduct(take)) {
                    throw new IllegalStateException("Account shows " + shown + " but can't pay " + take);
                }
            }
            paid.accept(take);
            moved += take;
        }
    }

    /** What the coins of {@code coins} anywhere in an inventory are worth, renamed ones included. */
    private static long carriedValue(Inventory inventory, List<Denomination> coins) {
        long total = 0;
        for (List<ItemStack> compartment : List.of(inventory.items, inventory.armor, inventory.offhand)) {
            for (ItemStack stack : compartment) {
                for (Denomination coin : coins) {
                    if (stack.is(coin.item())) {
                        total += (long) coin.value() * stack.getCount();
                    }
                }
            }
        }
        return total;
    }

    public static String describe(BankKind kind) {
        return kind == BankKind.OWN ? "the mod's own bank" : "Numismatics' bank";
    }

    /** A plan, or why there's none. */
    public record Result(Optional<Plan> plan, String problem) {
        static Result of(Plan plan) {
            return new Result(Optional.of(plan), "");
        }

        static Result problem(String why) {
            return new Result(Optional.empty(), why);
        }
    }
}
