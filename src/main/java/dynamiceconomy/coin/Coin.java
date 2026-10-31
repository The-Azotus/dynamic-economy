package dynamiceconomy.coin;

import net.minecraft.world.item.Rarity;

import java.util.Locale;
import java.util.Optional;

/** The mod's own coins, smallest first. Each is worth 8 of the one before. Values are bits (1 bit = 1 spur). */
public enum Coin {
    BIT(1, Rarity.COMMON),
    CHIP(8, Rarity.COMMON),
    STACK(64, Rarity.COMMON),
    MARK(512, Rarity.COMMON),
    CROWN(4_096, Rarity.UNCOMMON),
    SUN(32_768, Rarity.UNCOMMON),
    DIVINE(262_144, Rarity.RARE),
    OMEGA(2_097_152, Rarity.EPIC);

    private final int value;
    private final Rarity rarity;

    Coin(int value, Rarity rarity) {
        this.value = value;
        this.rarity = rarity;
    }

    /** Worth in bits. */
    public int value() {
        return value;
    }

    public Rarity rarity() {
        return rarity;
    }

    /** The item's path, such as {@code bit}. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The next larger coin, worth 8 of this one; empty for the largest. */
    public Optional<Coin> next() {
        return ordinal() + 1 < values().length ? Optional.of(values()[ordinal() + 1]) : Optional.empty();
    }
}
