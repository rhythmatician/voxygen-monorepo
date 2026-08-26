package com.rhythmatician.lodiffusion.voxy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Plans complete L1-to-L0 transactions that keep the vanilla frontier covered. */
public final class VanillaFrontierGuardPlanner {
    static final int L1_PARENT_WIDTH_BLOCKS = WorldSectionCoord.worldSectionWidth(Level.L1.value());
    static final int L1_PARENT_WIDTH_SECTIONS =
            L1_PARENT_WIDTH_BLOCKS / WorldSectionCoord.BLOCKS_PER_SECTION;
    static final int MINIMUM_LEAD_BLOCKS = L1_PARENT_WIDTH_BLOCKS;

    private VanillaFrontierGuardPlanner() {}

    /** Immutable, client-independent frontier inputs expressed in block units. */
    public record Input(int playerBlockX, int playerBlockZ,
                        double horizontalVelocityX, double horizontalVelocityZ,
                        int vanillaRadiusBlocks, int leadBlocks) {
        public Input {
            if (vanillaRadiusBlocks < 0) {
                throw new IllegalArgumentException("vanillaRadiusBlocks must be non-negative");
            }
        }

        int effectiveLeadBlocks() {
            return Math.max(MINIMUM_LEAD_BLOCKS, leadBlocks);
        }
    }

    /** Small client-tick snapshot with no client-only type dependency. */
    public record FrontierSnapshot(int playerBlockX, int playerBlockZ,
                                   double horizontalVelocityX, double horizontalVelocityZ,
                                   int clientViewDistanceChunks, int simulationDistanceChunks) {
        public FrontierSnapshot {
            if (clientViewDistanceChunks < 0 || simulationDistanceChunks < 0) {
                throw new IllegalArgumentException("vanilla distances must be non-negative");
            }
        }

        public Input toInput(int configuredLeadTicks) {
            int effectiveRadiusBlocks = Math.max(clientViewDistanceChunks, simulationDistanceChunks) * 16;
            double speed = Math.hypot(horizontalVelocityX, horizontalVelocityZ);
            int additionalLead = (int) Math.ceil(Math.max(0.0, speed * Math.max(0, configuredLeadTicks)));
            return new Input(playerBlockX, playerBlockZ, horizontalVelocityX, horizontalVelocityZ,
                    effectiveRadiusBlocks, Math.addExact(MINIMUM_LEAD_BLOCKS, additionalLead));
        }
    }

    /** One atomic L1 parent transaction in the End's [0, 128) responsibility. */
    public record ParentTransaction(SectionPos origin) {
        public ParentTransaction {
            if (origin == null
                    || Math.floorMod(origin.x(), L1_PARENT_WIDTH_SECTIONS) != 0
                    || Math.floorMod(origin.y(), L1_PARENT_WIDTH_SECTIONS) != 0
                    || Math.floorMod(origin.z(), L1_PARENT_WIDTH_SECTIONS) != 0
                    || origin.y() != 0 && origin.y() != L1_PARENT_WIDTH_SECTIONS) {
                throw new IllegalArgumentException(
                        "frontier guards are aligned L1 parent origins at section y=0 or y="
                                + L1_PARENT_WIDTH_SECTIONS);
            }
        }
    }

    /**
     * Returns every L1 parent tile that intersects the Chebyshev frontier band
     * [vanillaRadius, vanillaRadius + lead]. The result is complete coverage,
     * ordered toward movement without omitting the trailing edge.
     */
    public static List<ParentTransaction> plan(Input input) {
        int outerRadius = Math.addExact(input.vanillaRadiusBlocks(), input.effectiveLeadBlocks());
        int minTileX = Math.floorDiv(input.playerBlockX() - outerRadius, L1_PARENT_WIDTH_BLOCKS);
        int maxTileX = Math.floorDiv(input.playerBlockX() + outerRadius, L1_PARENT_WIDTH_BLOCKS);
        int minTileZ = Math.floorDiv(input.playerBlockZ() - outerRadius, L1_PARENT_WIDTH_BLOCKS);
        int maxTileZ = Math.floorDiv(input.playerBlockZ() + outerRadius, L1_PARENT_WIDTH_BLOCKS);

        List<ParentTransaction> result = new ArrayList<>();
        for (int tileZ = minTileZ; tileZ <= maxTileZ; tileZ++) {
            for (int tileX = minTileX; tileX <= maxTileX; tileX++) {
                if (!intersectsBand(tileX, tileZ, input.playerBlockX(), input.playerBlockZ(),
                        input.vanillaRadiusBlocks(), outerRadius)) {
                    continue;
                }
                result.add(new ParentTransaction(
                        new SectionPos(tileX * L1_PARENT_WIDTH_SECTIONS, 0,
                                tileZ * L1_PARENT_WIDTH_SECTIONS)));
                result.add(new ParentTransaction(new SectionPos(
                        tileX * L1_PARENT_WIDTH_SECTIONS, L1_PARENT_WIDTH_SECTIONS,
                        tileZ * L1_PARENT_WIDTH_SECTIONS)));
            }
        }
        result.sort(Comparator
                .comparingDouble((ParentTransaction parent) -> -projectedDistance(parent, input))
                .thenComparingInt(parent -> parent.origin().z())
                .thenComparingInt(parent -> parent.origin().x())
                .thenComparingInt(parent -> parent.origin().y()));
        return List.copyOf(result);
    }

    private static boolean intersectsBand(int tileX, int tileZ, int playerX, int playerZ,
                                          int innerRadius, int outerRadius) {
        int minX = tileX * L1_PARENT_WIDTH_BLOCKS;
        int maxX = minX + L1_PARENT_WIDTH_BLOCKS - 1;
        int minZ = tileZ * L1_PARENT_WIDTH_BLOCKS;
        int maxZ = minZ + L1_PARENT_WIDTH_BLOCKS - 1;
        int minDistance = Math.max(distanceToRange(playerX, minX, maxX),
                distanceToRange(playerZ, minZ, maxZ));
        int maxDistance = Math.max(maxDistanceToRange(playerX, minX, maxX),
                maxDistanceToRange(playerZ, minZ, maxZ));
        return minDistance <= outerRadius && maxDistance >= innerRadius;
    }

    private static int distanceToRange(int value, int min, int max) {
        if (value < min) return min - value;
        if (value > max) return value - max;
        return 0;
    }

    private static int maxDistanceToRange(int value, int min, int max) {
        return Math.max(Math.abs(value - min), Math.abs(value - max));
    }

    private static double projectedDistance(ParentTransaction parent, Input input) {
        double centerX = WorldSectionCoord.sectionToBlockMin(parent.origin().x())
                + L1_PARENT_WIDTH_BLOCKS / 2.0;
        double centerZ = WorldSectionCoord.sectionToBlockMin(parent.origin().z())
                + L1_PARENT_WIDTH_BLOCKS / 2.0;
        return ((centerX - input.playerBlockX()) * input.horizontalVelocityX())
                + ((centerZ - input.playerBlockZ()) * input.horizontalVelocityZ());
    }
}
