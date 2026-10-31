package dynamiceconomy.command;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dynamiceconomy.bank.BankKind;
import dynamiceconomy.bank.BankTeam;
import dynamiceconomy.bank.BankTeams;
import dynamiceconomy.bank.OwnBank;
import dynamiceconomy.bank.OwnTeams;
import dynamiceconomy.bank.WorldBank;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code /bank team}: the mod's own teams, whose members share a team account in the mod's own bank. Only when FTB
 * Teams isn't installed (then its parties are the teams) and the world uses the mod's own bank (Numismatics' has no
 * team accounts). Members choose which account they use in the market screen's Bank tab.
 */
public final class BankCommand {
    private BankCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("bank").then(Commands.literal("team")
                .executes(BankCommand::info)
                .then(Commands.literal("info").executes(BankCommand::info))
                .then(Commands.literal("create").then(Commands.argument("name", StringArgumentType.word()).executes(BankCommand::create)))
                .then(Commands.literal("invite").then(Commands.argument("player", EntityArgument.player()).executes(BankCommand::invite)))
                .then(Commands.literal("join").then(Commands.argument("name", StringArgumentType.word()).executes(BankCommand::join)))
                .then(Commands.literal("leave").executes(BankCommand::leave))
                .then(Commands.literal("kick").then(Commands.argument("player", GameProfileArgument.gameProfile()).executes(BankCommand::kick)))));
    }

    /** The own teams, or empty after telling the player why they're not in use here. */
    private static Optional<OwnTeams> teams(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        if (WorldBank.kind(server) != BankKind.OWN) {
            source.sendFailure(Component.translatable("command.dynamic_economy.team.numismatics"));
            return Optional.empty();
        }
        if (BankTeams.fromFtbTeams()) {
            source.sendFailure(Component.translatable("command.dynamic_economy.team.ftb"));
            return Optional.empty();
        }
        return Optional.of(OwnTeams.get(server));
    }

    private static int info(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        MinecraftServer server = ctx.getSource().getServer();
        if (WorldBank.kind(server) != BankKind.OWN) {
            ctx.getSource().sendFailure(Component.translatable("command.dynamic_economy.team.numismatics"));
            return 0;
        }
        // Works with FTB Teams too: it shows the party's account.
        Optional<BankTeam> team = BankTeams.teamOf(server, player.getUUID());
        if (team.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.translatable(BankTeams.fromFtbTeams()
                    ? "command.dynamic_economy.team.none_ftb" : "command.dynamic_economy.team.none"), false);
            return 0;
        }
        BankTeam t = team.get();
        long balance = OwnBank.get(server).teamAccount(t.id()).balance();
        List<String> names = t.members().stream().map(id -> name(server, id)).sorted(String.CASE_INSENSITIVE_ORDER).toList();
        ctx.getSource().sendSuccess(() -> Component.translatable("command.dynamic_economy.team.info", t.name(),
                name(server, t.owner()), String.join(", ", names), String.format(Locale.ROOT, "%,d", balance)), false);
        return 1;
    }

    private static int create(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Optional<OwnTeams> found = teams(ctx.getSource());
        if (found.isEmpty()) {
            return 0;
        }
        OwnTeams teams = found.get();
        String name = StringArgumentType.getString(ctx, "name");
        if (teams.teamOf(player.getUUID()).isPresent()) {
            return fail(ctx, "command.dynamic_economy.team.already_in");
        }
        if (!OwnTeams.validName(name)) {
            return fail(ctx, "command.dynamic_economy.team.bad_name");
        }
        if (teams.byName(name).isPresent()) {
            return fail(ctx, "command.dynamic_economy.team.name_taken", name);
        }
        teams.create(name, player.getUUID());
        ctx.getSource().sendSuccess(() -> Component.translatable("command.dynamic_economy.team.created", name), false);
        return 1;
    }

    private static int invite(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
        Optional<OwnTeams> found = teams(ctx.getSource());
        if (found.isEmpty()) {
            return 0;
        }
        OwnTeams teams = found.get();
        Optional<BankTeam> team = teams.teamOf(player.getUUID());
        if (team.isEmpty() || !team.get().owner().equals(player.getUUID())) {
            return fail(ctx, "command.dynamic_economy.team.owner_only");
        }
        if (teams.teamOf(target.getUUID()).isPresent()) {
            return fail(ctx, "command.dynamic_economy.team.target_in_team", target.getGameProfile().getName());
        }
        teams.invite(team.get().id(), target.getUUID());
        target.sendSystemMessage(Component.translatable("command.dynamic_economy.team.invited_you",
                player.getGameProfile().getName(), team.get().name()));
        ctx.getSource().sendSuccess(() -> Component.translatable("command.dynamic_economy.team.invited",
                target.getGameProfile().getName()), false);
        return 1;
    }

    private static int join(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Optional<OwnTeams> found = teams(ctx.getSource());
        if (found.isEmpty()) {
            return 0;
        }
        OwnTeams teams = found.get();
        String name = StringArgumentType.getString(ctx, "name");
        if (teams.teamOf(player.getUUID()).isPresent()) {
            return fail(ctx, "command.dynamic_economy.team.already_in");
        }
        Optional<BankTeam> team = teams.byName(name);
        if (team.isEmpty() || !teams.invited(player.getUUID(), team.get().id())) {
            return fail(ctx, "command.dynamic_economy.team.not_invited", name);
        }
        teams.join(team.get().id(), player.getUUID());
        ctx.getSource().sendSuccess(() -> Component.translatable("command.dynamic_economy.team.joined", team.get().name()), false);
        return 1;
    }

    private static int leave(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Optional<OwnTeams> found = teams(ctx.getSource());
        if (found.isEmpty()) {
            return 0;
        }
        OwnTeams teams = found.get();
        Optional<BankTeam> team = teams.teamOf(player.getUUID());
        if (team.isEmpty()) {
            return fail(ctx, "command.dynamic_economy.team.not_in");
        }
        Optional<BankTeam> ended = teams.remove(player.getUUID());
        if (ended.isPresent()) {
            // The last member takes the team's money, so none is lost.
            long paid = OwnBank.get(ctx.getSource().getServer()).closeTeam(ended.get().id(), player.getUUID());
            ctx.getSource().sendSuccess(() -> Component.translatable("command.dynamic_economy.team.ended", team.get().name(),
                    String.format(Locale.ROOT, "%,d", paid)), false);
        } else {
            ctx.getSource().sendSuccess(() -> Component.translatable("command.dynamic_economy.team.left", team.get().name()), false);
        }
        return 1;
    }

    private static int kick(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Collection<GameProfile> targets = GameProfileArgument.getGameProfiles(ctx, "player");
        Optional<OwnTeams> found = teams(ctx.getSource());
        if (found.isEmpty()) {
            return 0;
        }
        OwnTeams teams = found.get();
        Optional<BankTeam> team = teams.teamOf(player.getUUID());
        if (team.isEmpty() || !team.get().owner().equals(player.getUUID())) {
            return fail(ctx, "command.dynamic_economy.team.owner_only");
        }
        int removed = 0;
        for (GameProfile target : targets) {
            UUID id = target.getId();
            if (id.equals(player.getUUID()) || !team.get().members().contains(id)) {
                ctx.getSource().sendFailure(Component.translatable("command.dynamic_economy.team.not_member", target.getName()));
                continue;
            }
            teams.remove(id);
            removed++;
            ctx.getSource().sendSuccess(() -> Component.translatable("command.dynamic_economy.team.kicked", target.getName()), false);
        }
        return removed;
    }

    private static int fail(CommandContext<CommandSourceStack> ctx, String key, Object... args) {
        ctx.getSource().sendFailure(Component.translatable(key, args));
        return 0;
    }

    /** A player's last known name, or their UUID if the server never saw them. */
    private static String name(MinecraftServer server, UUID id) {
        ServerPlayer online = server.getPlayerList().getPlayer(id);
        if (online != null) {
            return online.getGameProfile().getName();
        }
        return server.getProfileCache() == null ? id.toString()
                : server.getProfileCache().get(id).map(GameProfile::getName).orElse(id.toString());
    }
}
