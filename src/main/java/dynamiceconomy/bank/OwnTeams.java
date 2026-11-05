package dynamiceconomy.bank;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The mod's own teams, used for team accounts when FTB Teams isn't installed: a name, an owner and members, saved
 * with the world. A player is in at most one team. The owner invites and removes members; anyone can leave. When the
 * owner leaves, the longest-standing member becomes owner; when the last member leaves, the team is gone.
 * <p>
 * Invitations last until the server stops. This class only keeps the membership; {@code BankCommand} checks who may
 * do what and moves the account's balance when a team ends. Server thread only.
 */
public final class OwnTeams extends SavedData {
    public static final String ID = "dynamic_economy_teams";
    private static final SavedData.Factory<OwnTeams> FACTORY = new SavedData.Factory<>(OwnTeams::new, OwnTeams::fromTag, null);
    /** Letters, digits, '_' and '-', 1 to 24 of them: one word, so commands can take it unquoted. */
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,24}");

    /** Members in the order they joined; the first is the owner. */
    private final Map<UUID, Team> teams = new LinkedHashMap<>();
    /** Pending invitations: invited player to team. Not saved. */
    private final Map<UUID, UUID> invites = new HashMap<>();

    private static final class Team {
        final UUID id;
        final String name;
        final List<UUID> members = new ArrayList<>();

        Team(UUID id, String name) {
            this.id = id;
            this.name = name;
        }

        BankTeam view() {
            return new BankTeam(id, name, members.get(0), Set.copyOf(members));
        }
    }

    public static OwnTeams get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, ID);
    }

    public static boolean validName(String name) {
        return NAME.matcher(name).matches();
    }

    public Optional<BankTeam> teamOf(UUID player) {
        for (Team team : teams.values()) {
            if (team.members.contains(player)) {
                return Optional.of(team.view());
            }
        }
        return Optional.empty();
    }

    public Optional<BankTeam> byId(UUID team) {
        return Optional.ofNullable(teams.get(team)).map(Team::view);
    }

    /** Names match ignoring case, so two teams can't differ only by it. */
    public Optional<BankTeam> byName(String name) {
        return teams.values().stream().filter(team -> team.name.equalsIgnoreCase(name)).findFirst().map(Team::view);
    }

    /** Starts a team with {@code owner} as its only member. The caller has checked the name and that they're in none. */
    public BankTeam create(String name, UUID owner) {
        Team team = new Team(UUID.randomUUID(), name);
        team.members.add(owner);
        teams.put(team.id, team);
        invites.remove(owner);
        setDirty();
        return team.view();
    }

    public void invite(UUID team, UUID player) {
        invites.put(player, team);
    }

    public boolean invited(UUID player, UUID team) {
        return team.equals(invites.get(player));
    }

    /** Adds {@code player}, who must be in no team, and uses up their invitation. */
    public void join(UUID team, UUID player) {
        teams.get(team).members.add(player);
        invites.remove(player);
        setDirty();
    }

    /**
     * Takes {@code player} out of their team. Returns the team as it was if that left it empty, so the caller can
     * pay out its account; the team itself is gone.
     */
    public Optional<BankTeam> remove(UUID player) {
        for (Team team : teams.values()) {
            if (team.members.remove(player)) {
                setDirty();
                if (team.members.isEmpty()) {
                    teams.remove(team.id);
                    invites.values().removeIf(team.id::equals);
                    return Optional.of(new BankTeam(team.id, team.name, player, Set.of(player)));
                }
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    public List<BankTeam> all() {
        return teams.values().stream().map(Team::view).toList();
    }

    public static OwnTeams fromTag(CompoundTag tag, HolderLookup.Provider registries) {
        OwnTeams data = new OwnTeams();
        ListTag list = tag.getList("teams", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            if (!entry.hasUUID("id") || !validName(entry.getString("name"))) {
                continue;
            }
            Team team = new Team(entry.getUUID("id"), entry.getString("name"));
            for (Tag member : entry.getList("members", Tag.TAG_INT_ARRAY)) {
                UUID id = NbtUtils.loadUUID(member);
                if (data.teamOf(id).isEmpty() && !team.members.contains(id)) {
                    team.members.add(id);
                }
            }
            if (!team.members.isEmpty()) {
                data.teams.put(team.id, team);
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Team team : teams.values()) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("id", team.id);
            entry.putString("name", team.name);
            ListTag members = new ListTag();
            team.members.forEach(member -> members.add(NbtUtils.createUUID(member)));
            entry.put("members", members);
            list.add(entry);
        }
        tag.put("teams", list);
        return tag;
    }
}
