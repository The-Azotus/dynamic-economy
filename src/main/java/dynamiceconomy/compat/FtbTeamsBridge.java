package dynamiceconomy.compat;

import dev.ftb.mods.ftbteams.api.FTBTeamsAPI;
import dev.ftb.mods.ftbteams.api.Team;
import dev.ftb.mods.ftbteams.api.TeamManager;
import dev.ftb.mods.ftbteams.api.event.TeamEvent;
import dynamiceconomy.DynamicEconomy;
import dynamiceconomy.bank.BankTeam;
import net.neoforged.fml.ModList;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The only class allowed to import FTB Teams, an optional dependency. Without it nothing here touches its classes:
 * they're only referenced from {@link Present}, which loads only when FTB Teams does.
 * <p>
 * Only party teams count. FTB Teams gives every player a personal "player team" too; that isn't a shared account.
 * Checked against 2101.1.11 by reflection once; if the API doesn't match, the mod's own teams are used instead.
 * Server thread only.
 */
public final class FtbTeamsBridge {
    public static final String MOD_ID = "ftbteams";
    private static final boolean AVAILABLE = ModList.get().isLoaded(MOD_ID) && Present.probe();

    private FtbTeamsBridge() {
    }

    /** Whether FTB Teams is installed and its API matches, so its parties are the teams. */
    public static boolean isAvailable() {
        return AVAILABLE;
    }

    /** The party {@code player} belongs to; empty when they're in none, or teams aren't loaded yet. */
    public static Optional<BankTeam> teamOf(UUID player) {
        if (!AVAILABLE) {
            return Optional.empty();
        }
        try {
            return Present.teamOf(player);
        } catch (RuntimeException | LinkageError e) {
            DynamicEconomy.LOGGER.error("FTB Teams lookup failed", e);
            return Optional.empty();
        }
    }

    /** The party with the ID {@code team}, if it still exists. */
    public static Optional<BankTeam> teamById(UUID team) {
        if (!AVAILABLE) {
            return Optional.empty();
        }
        try {
            return Present.teamById(team);
        } catch (RuntimeException | LinkageError e) {
            DynamicEconomy.LOGGER.error("FTB Teams lookup failed", e);
            return Optional.empty();
        }
    }

    /** Calls {@code onDeleted} when a party is deleted (its last member left, or it was disbanded). */
    public static void onPartyDeleted(Consumer<BankTeam> onDeleted) {
        if (AVAILABLE) {
            Present.onPartyDeleted(onDeleted);
        }
    }

    /** Everything that touches FTB Teams classes. */
    private static final class Present {
        static boolean probe() {
            try {
                requireReturn(FTBTeamsAPI.class.getMethod("api").getReturnType(), FTBTeamsAPI.API.class);
                requireReturn(FTBTeamsAPI.API.class.getMethod("getManager").getReturnType(), TeamManager.class);
                requireReturn(TeamManager.class.getMethod("getTeamForPlayerID", UUID.class).getReturnType(), Optional.class);
                requireReturn(TeamManager.class.getMethod("getTeamByID", UUID.class).getReturnType(), Optional.class);
                requireReturn(Team.class.getMethod("isPartyTeam").getReturnType(), boolean.class);
                requireReturn(Team.class.getMethod("getId").getReturnType(), UUID.class);
                requireReturn(Team.class.getMethod("getOwner").getReturnType(), UUID.class);
                requireReturn(Team.class.getMethod("getMembers").getReturnType(), Set.class);
                requireReturn(Team.class.getMethod("getShortName").getReturnType(), String.class);
                TeamEvent.class.getField("DELETED");
                DynamicEconomy.LOGGER.info("FTB Teams found: its parties share team accounts in the mod's own bank");
                return true;
            } catch (ReflectiveOperationException | LinkageError e) {
                DynamicEconomy.LOGGER.error("FTB Teams is installed but its team API does not match what this mod was built "
                        + "against (2101.1.11); the mod's own /bank team commands are used instead", e);
                return false;
            }
        }

        static Optional<BankTeam> teamOf(UUID player) {
            FTBTeamsAPI.API api = FTBTeamsAPI.api();
            if (api == null || !api.isManagerLoaded()) {
                return Optional.empty();
            }
            return api.getManager().getTeamForPlayerID(player).filter(Team::isPartyTeam).map(Present::bankTeam);
        }

        static Optional<BankTeam> teamById(UUID id) {
            FTBTeamsAPI.API api = FTBTeamsAPI.api();
            if (api == null || !api.isManagerLoaded()) {
                return Optional.empty();
            }
            return api.getManager().getTeamByID(id).filter(Team::isPartyTeam).map(Present::bankTeam);
        }

        static void onPartyDeleted(Consumer<BankTeam> onDeleted) {
            TeamEvent.DELETED.register(event -> {
                Team team = event.getTeam();
                if (team.isPartyTeam()) {
                    onDeleted.accept(bankTeam(team));
                }
            });
        }

        private static BankTeam bankTeam(Team team) {
            return new BankTeam(team.getId(), team.getShortName(), team.getOwner(), team.getMembers());
        }

        private static void requireReturn(Class<?> actual, Class<?> expected) throws NoSuchMethodException {
            if (actual != expected) {
                throw new NoSuchMethodException("expected " + expected.getName() + " but found " + actual.getName());
            }
        }
    }
}
