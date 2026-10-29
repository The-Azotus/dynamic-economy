package dynamiceconomy.bank;

import dynamiceconomy.compat.FtbTeamsBridge;
import net.minecraft.server.MinecraftServer;

import java.util.Optional;
import java.util.UUID;

/** Where teams come from: FTB Teams parties when FTB Teams is installed, otherwise the mod's own teams. */
public final class BankTeams {
    private BankTeams() {
    }

    /** Whether FTB Teams provides the teams, so the mod's own team commands are off. */
    public static boolean fromFtbTeams() {
        return FtbTeamsBridge.isAvailable();
    }

    public static Optional<BankTeam> teamOf(MinecraftServer server, UUID player) {
        return fromFtbTeams() ? FtbTeamsBridge.teamOf(player) : OwnTeams.get(server).teamOf(player);
    }
}
