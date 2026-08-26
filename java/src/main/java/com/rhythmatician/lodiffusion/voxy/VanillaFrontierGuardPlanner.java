package com.rhythmatician.lodiffusion.voxy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Plans complete L1-to-L0 transactions that keep the vanilla frontier covered. */
public final class VanillaFrontierGuardPlanner {
    static final int L1_PARENT_WIDTH_BLOCKS = WorldSectionCoord.worldSectionWidth(Level.L1.value());
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
    public record ParentTransaction(int level, int wsX, int wsY, int wsZ) {
        public ParentTransaction {
            if (level != Level.L1.value() || wsY != 0 && wsY != 1) {
                throw new IllegalArgumentException("frontier guards are L1 parents at y=0 or y=1");
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
                result.add(new ParentTransaction(Level.L1.value(), tileX, 0, tileZ));
                result.add(new ParentTransaction(Level.L1.value(), tileX, 1, tileZ));
            }
        }
        result.sort(Comparator
                .comparingDouble((ParentTransaction parent) -> -projectedDistance(parent, input))
                .thenComparingInt(ParentTransaction::wsZ)
                .thenComparingInt(ParentTransaction::wsX)
                .thenComparingInt(ParentTransaction::wsY));
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
        double centerX = (parent.wsX() + 0.5) * L1_PARENT_WIDTH_BLOCKS;
        double centerZ = (parent.wsZ() + 0.5) * L1_PARENT_WIDTH_BLOCKS;
        return ((centerX - input.playerBlockX()) * input.horizontalVelocityX())
                + ((centerZ - input.playerBlockZ()) * input.horizontalVelocityZ());
    }
}
