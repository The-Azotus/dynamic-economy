package dynamiceconomy.data;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Money that add-ons paid players through the API while the bank couldn't be reached, saved with the world so a
 * restart never loses it. It is paid on the server tick as soon as the bank is back, and counts on the leaderboard
 * meanwhile. Server thread only.
 */
public final class ApiPayments extends SavedData {
    public static final String ID = "dynamic_economy_api_payments";
    private static final SavedData.Factory<ApiPayments> FACTORY = new SavedData.Factory<>(ApiPayments::new, ApiPayments::fromTag, null);

    private final OwedPayouts owed = new OwedPayouts(this);

    public static ApiPayments get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, ID);
    }

    /** API payments owed while the bank couldn't be reached. */
    public OwedPayouts owed() {
        return owed;
    }

    public static ApiPayments fromTag(CompoundTag tag, HolderLookup.Provider registries) {
        ApiPayments data = new ApiPayments();
        data.owed.read(tag.getList("owed", Tag.TAG_COMPOUND));
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.put("owed", owed.write());
        return tag;
    }
}
