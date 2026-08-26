package com.rhythmatician.lodiffusion.voxy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lodiffusion.worldgen.ShaderSectionWriter;
import net.minecraft.util.Identifier;
import org.junit.jupiter.api.Test;

class TerrainPublicationRouteTest {
    @Test
    void endHasOneTopDownRouteAndRejectsCompatibilityPublishers() {
        TerrainPublicationRoute route = TerrainPublicationRoute.forDimensionId(
                Identifier.of("minecraft", "the_end"));

        assertTrue(route.usesTopDownEndRoute());
        assertFalse(route.allowsCompatibilityTerrainPublication());
    }

    @Test
    void nonEndKeepsCompatibilityTerrainToolingAvailable() {
        TerrainPublicationRoute route = TerrainPublicationRoute.forDimensionId(
                Identifier.of("minecraft", "overworld"));

        assertFalse(route.usesTopDownEndRoute());
        assertTrue(route.allowsCompatibilityTerrainPublication());
    }

    @Test
    void missingDimensionIdentityRejectsEveryTerrainPublisher() {
        TerrainPublicationRoute route = TerrainPublicationRoute.forDimensionId(null);

        assertFalse(route.usesTopDownEndRoute());
        assertFalse(route.allowsCompatibilityTerrainPublication());
    }

    @Test
    void missingWorldIdentityRejectsEveryTerrainPublisher() {
        TerrainPublicationRoute route = TerrainPublicationRoute.forWorld(null);

        assertFalse(route.usesTopDownEndRoute());
        assertFalse(route.allowsCompatibilityTerrainPublication());
        assertThrows(IllegalArgumentException.class,
                () -> ShaderSectionWriter.create(null, new Object(), 0));
    }
}
