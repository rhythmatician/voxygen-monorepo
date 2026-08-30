package io.github.lodiffusion.worldgen;

import java.lang.reflect.Method;
import java.nio.IntBuffer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.rhythmatician.voxygen.generation.TerrainPublicationRoute;
import com.rhythmatician.voxygen.backend.voxy.VoxyCompat;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.world.World;

/**
 * Converts the shader's block-material output (binding 11) into Voxy sections
 * and pushes them into the live Voxy world engine.
 *
 * <h3>Material code convention (matches GLSL #defines)</h3>
 * <ul>
 *   <li>0 = AIR   (Voxy block ID 0)
 *   <li>1 = STONE
 *   <li>2 = WATER
 *   <li>3 = GRASS_BLOCK (topmost dry-land solid block)
 *   <li>4 = DIRT  (1-3 blocks below grass surface)
 * </ul>
 *
 * <h3>Array indexing (same as density_out)</h3>
 * <pre>
 *   index = (lx + 16*lz) * Y_LEVELS + yi
 *   where yi = blockY - Y_MIN = blockY + 64
 * </pre>
 *
 * <h3>Section layout</h3>
 * A single 16×384×16 chunk column is split into 24 × 16³ {@code VoxelizedSection}s
 * (section Y coordinates −4 to 19 for the overworld).  Each section is filled,
 * mipped, and inserted via Voxy's {@code insertUpdate} path.
 */
@SuppressWarnings("deprecation")
public final class ShaderSectionWriter {

    /** Signals that the world's terrain-publication route rejects this publisher. */
    public static final class PublicationRejectedException extends IllegalArgumentException {
        private PublicationRejectedException(TerrainPublicationRoute route) {
            super("Compatibility terrain publication is not allowed for route " + route);
        }
    }

    private static final Logger LOGGER = LogManager.getLogger();

    // ---- Dimensions (must match shader constants) ----
    private static final int Y_MIN    = -64;
    private static final int Y_LEVELS = 384;
    private static final int SECTIONS_Y = Y_LEVELS / 16;   // = 24
    /** Section Y at the bottom of the overworld (Y_MIN / 16). */
    private static final int SECTION_Y_BASE = Y_MIN / 16;  // = -4

    // ---- Material codes (must match shader #defines) ----
    private static final int MAT_AIR   = 0;
    private static final int MAT_STONE = 1;
    private static final int MAT_WATER = 2;
    private static final int MAT_GRASS = 3;
    private static final int MAT_DIRT  = 4;
    private static final int MAT_COUNT = 5;

    /** Default light: full sky light, no block light → 0x0F. */
    private static final int DEFAULT_LIGHT = 0x0F;

    // ---- Runtime state ----
    private final Object worldEngine;
    private final Object voxyMapper;   // Voxy Mapper, obtained from voxyEngine
    private final int defaultBiomeVoxyId;
    private final int[] matToVoxyId = new int[MAT_COUNT];  // material code → Voxy block ID

    /**
     * Constructs a ShaderSectionWriter and pre-resolves Voxy block IDs for the
     * five material types.
     *
     * @param worldEngine        Voxy WorldEngine instance (from {@code VoxyCompat.getWorldEngine})
     * @param defaultBiomeVoxyId Voxy biome ID used for every voxel (MVP: single biome)
     * @throws IllegalStateException if Voxy is not available
     */
    private ShaderSectionWriter(Object worldEngine, int defaultBiomeVoxyId) {
        if (!VoxyCompat.isAvailable()) {
            throw new IllegalStateException("Voxy is not available; cannot create ShaderSectionWriter");
        }
        this.worldEngine = worldEngine;
        this.voxyMapper  = VoxyCompat.getMapper(worldEngine);
        this.defaultBiomeVoxyId = defaultBiomeVoxyId;

        // air is always 0 in Voxy
        matToVoxyId[MAT_AIR]   = 0;
        matToVoxyId[MAT_STONE] = resolveVoxyId(Blocks.STONE.getDefaultState());
        matToVoxyId[MAT_WATER] = resolveVoxyId(Blocks.WATER.getDefaultState());
        matToVoxyId[MAT_GRASS] = resolveVoxyId(Blocks.GRASS_BLOCK.getDefaultState());
        matToVoxyId[MAT_DIRT]  = resolveVoxyId(Blocks.DIRT.getDefaultState());

        LOGGER.info("[ShaderSectionWriter] Voxy block IDs — air={} stone={} water={} grass={} dirt={}",
                matToVoxyId[MAT_AIR], matToVoxyId[MAT_STONE], matToVoxyId[MAT_WATER],
                matToVoxyId[MAT_GRASS], matToVoxyId[MAT_DIRT]);
    }

    /**
     * Creates a compatibility terrain publisher for the supplied world.
     *
     * @throws IllegalArgumentException when the world has no dimension identity or
     *                                  belongs to the End top-down route
     */
    public static ShaderSectionWriter create(
            World world, Object worldEngine, int defaultBiomeVoxyId) {
        TerrainPublicationRoute route = TerrainPublicationRoute.forWorld(world);
        if (!route.allowsCompatibilityTerrainPublication()) {
            throw new PublicationRejectedException(route);
        }
        return new ShaderSectionWriter(worldEngine, defaultBiomeVoxyId);
    }

    /**
     * Converts the material output for one chunk column into 24 Voxy sections and
     * pushes each into the world engine.
     *
     * <p>Uses {@code VoxyCompat.createEmptySection()} + fill + {@code mipSection()} +
     * {@code insertUpdate()} for each 16³ slice.  All-air sections are skipped.
     *
     * @param blockMat 98,304-element IntBuffer of material codes,
     *                 indexed as {@code (lx + 16*lz) * 384 + (blockY + 64)}.
     *                 The buffer's position is not modified; we read by absolute index.
     * @param chunkX   Minecraft chunk X coordinate
     * @param chunkZ   Minecraft chunk Z coordinate
     * @return total number of non-air voxels written across all 24 sections
     */
    public int writeColumn(IntBuffer blockMat, int chunkX, int chunkZ) {
        int totalNonAir = 0;

        for (int syi = 0; syi < SECTIONS_Y; syi++) {
            int sectionY = SECTION_Y_BASE + syi;  // -4 … +19
            int baseYi   = syi * 16;               // first yi index in this section

            Object section = VoxyCompat.createEmptySection();
            VoxyCompat.setSectionPosition(section, chunkX, sectionY, chunkZ);
            long[] data = VoxyCompat.getSectionData(section);

            int nonAir = 0;

            for (int lx = 0; lx < 16; lx++) {
                for (int lz = 0; lz < 16; lz++) {
                    int colOff = (lx + 16 * lz) * Y_LEVELS;
                    for (int ly = 0; ly < 16; ly++) {
                        int mat    = readMat(blockMat, colOff + baseYi + ly);
                        int voxyId = (mat >= 0 && mat < MAT_COUNT) ? matToVoxyId[mat] : 0;

                        long voxel = VoxyCompat.composeVoxel(voxyId, defaultBiomeVoxyId, DEFAULT_LIGHT);
                        data[VoxyCompat.l0Index(lx, ly, lz)] = voxel;
                        if (voxyId != 0) nonAir++;
                    }
                }
            }

            if (nonAir == 0) continue;  // skip fully-air sections

            VoxyCompat.setNonAirCount(section, nonAir);
            VoxyCompat.mipSection(section, voxyMapper);
            VoxyCompat.insertUpdate(worldEngine, section);
            totalNonAir += nonAir;
        }

        LOGGER.info("[ShaderSectionWriter] Wrote column ({},{}) — {} non-air voxels across {} sections",
                chunkX, chunkZ, totalNonAir, SECTIONS_Y);
        return totalNonAir;
    }

    // ---- Helpers ----

    private static int readMat(IntBuffer buf, int index) {
        if (index < 0 || index >= buf.capacity()) return MAT_AIR;
        return buf.get(index);
    }

    /**
     * Resolves a Minecraft {@link BlockState} to a Voxy block ID via the Mapper.
     * Returns 0 (air) on failure so the system degrades gracefully.
     */
    private int resolveVoxyId(BlockState state) {
        try {
            Method m = voxyMapper.getClass().getMethod("getIdForBlockState", BlockState.class);
            m.setAccessible(true);
            int id = (int) m.invoke(voxyMapper, state);
            return Math.max(0, id);
        } catch (Exception e) {
            LOGGER.warn("[ShaderSectionWriter] Failed to resolve Voxy ID for {}: {}",
                    state.getBlock().getTranslationKey(), e.getMessage());
            return 0;
        }
    }
}
