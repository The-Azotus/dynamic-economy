package dynamiceconomy.bank;

import java.util.Locale;
import java.util.Optional;

/** The banks a world can use. Plain data, so the client can know it too. */
public enum BankKind {
    /** Create: Numismatics' bank, in spurs. */
    NUMISMATICS("spurs"),
    /** This mod's own bank, in bits. 1 bit = 1 spur. */
    OWN("bits");

    private final String unit;

    BankKind(String unit) {
        this.unit = unit;
    }

    /** The money unit's plural, as commands print it. */
    public String unit() {
        return unit;
    }

    /** The name saved with the world and typed in commands. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<BankKind> byId(String id) {
        for (BankKind kind : values()) {
            if (kind.id().equals(id)) {
                return Optional.of(kind);
            }
        }
        return Optional.empty();
    }
}
