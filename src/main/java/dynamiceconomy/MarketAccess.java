package dynamiceconomy;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.world.entity.player.Player;

/**
 * Who may use the {@code /market} commands. In {@link MarketConfig.AccessMode#BLOCK_ONLY} only operators (and the
 * console) can; everyone else has to use a Market Terminal block. Terminals work in every mode.
 */
public final class MarketAccess {
    private MarketAccess() {
    }

    public static boolean commandsAllowed(CommandSourceStack source) {
        return commandsAllowed(MarketConfig.accessMode(), source.hasPermission(Commands.LEVEL_GAMEMASTERS));
    }

    public static boolean commandsAllowed(Player player) {
        return commandsAllowed(MarketConfig.accessMode(), player.hasPermissions(Commands.LEVEL_GAMEMASTERS));
    }

    public static boolean commandsAllowed(MarketConfig.AccessMode mode, boolean operator) {
        return operator || mode == MarketConfig.AccessMode.COMMAND_AND_BLOCK;
    }
}
