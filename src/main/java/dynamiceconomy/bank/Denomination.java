package dynamiceconomy.bank;

import net.minecraft.world.item.Item;

/** One kind of coin a bank pays out and takes in, and what it's worth in bits (= spurs). */
public record Denomination(Item item, int value) {
}
