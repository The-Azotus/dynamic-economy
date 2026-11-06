package dynamiceconomy.api;

import dynamiceconomy.bank.BankKind;

/**
 * The bank a world keeps players' money in. It is chosen once per world and changes only when an admin switches it.
 * Amounts mean the same in both: 1 bit = 1 spur.
 */
public enum BankType {
    /** Dynamic-Economy's own bank, in bits, with its own coins. */
    OWN,
    /** Create: Numismatics' bank, in spurs. */
    NUMISMATICS;

    static BankType from(BankKind kind) {
        return switch (kind) {
            case OWN -> OWN;
            case NUMISMATICS -> NUMISMATICS;
        };
    }
}
