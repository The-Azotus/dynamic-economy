package dynamiceconomy.bank;

import java.util.Set;
import java.util.UUID;

/**
 * A group of players sharing one account in the mod's own bank: an FTB Teams party when FTB Teams is installed,
 * otherwise one of the mod's own teams. Every member can deposit, withdraw and trade with the account.
 *
 * @param id      the team's own ID, which keys its account; never a player's
 * @param owner   who receives the account's balance if the team is disbanded with money in it
 * @param members every member, owner included
 */
public record BankTeam(UUID id, String name, UUID owner, Set<UUID> members) {
    public BankTeam {
        members = Set.copyOf(members);
    }
}
