package com.rhythmatician.lodiffusion.voxy;

import net.minecraft.util.Identifier;
import net.minecraft.world.World;

/** The one terrain-publication policy boundary shared by every publisher. */
public enum TerrainPublicationRoute {
    END_TOP_DOWN,
    COMPATIBILITY,
    PUBLICATION_DENIED;

    public static TerrainPublicationRoute forWorld(World world) {
        if (world == null || world.getRegistryKey() == null) return PUBLICATION_DENIED;
        return forDimensionId(world.getRegistryKey().getValue());
    }

    public static TerrainPublicationRoute forDimensionId(Identifier dimensionId) {
        if (dimensionId == null) return PUBLICATION_DENIED;
        return Identifier.of("minecraft", "the_end").equals(dimensionId)
                ? END_TOP_DOWN
                : COMPATIBILITY;
    }

    public boolean usesTopDownEndRoute() {
        return this == END_TOP_DOWN;
    }

    public boolean allowsCompatibilityTerrainPublication() {
        return this == COMPATIBILITY;
    }
}
