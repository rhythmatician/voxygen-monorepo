package com.rhythmatician.voxygen.backend.voxy;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;
import net.minecraft.world.biome.Biome;
import com.rhythmatician.voxygen.semantic.CanonicalRegistries;
import com.rhythmatician.voxygen.generation.session.GenerationSession;

/**
 * Internal factory that builds canonical → Voxy ID maps without requiring an ONNX model. Keeps all Voxy Mapper reflection
 * and canonical registry details behind the {@link RealVoxyVolumeWriter} deep module.
 *
 * <p>Package-private: not a public strategy interface. Used by both the learned
 * and fallback writer-setup paths in {@link GenerationSession}.
 */
public final class CanonicalVoxyMaps {
    private CanonicalVoxyMaps() {}

    /**
     * Build grouped {@link VoxyIdMaps} from a Voxy Mapper and biome registry
     * without needing a model.
     */
    public static VoxyIdMaps from(Object voxyMapper, Registry<Biome> biomeRegistry) {
        int[] biomeMap = buildBiomeMap(voxyMapper, biomeRegistry);
        int[] blockMap = buildBlockMap(voxyMapper);
        return new VoxyIdMaps(biomeMap, blockMap);
    }

    public static int[] buildBiomeMap(Object voxyMapper, Registry<Biome> biomeRegistry) {
        return VoxyBlockMapper.resolveBiomeMappings(voxyMapper, biomeRegistry);
    }

    // Object: Voxy Mapper not on compile classpath — narrowed via reflection immediately (deep-module seam, see RealVoxyVolumeWriter)
    public static int[] buildBlockMap(Object voxyMapper) {
        try {
            java.lang.reflect.Method m =
                    voxyMapper.getClass().getMethod("getIdForBlockState", BlockState.class);
            int[] map = new int[CanonicalRegistries.BLOCK_COUNT];
            for (int i = 0; i < map.length; i++) {
                String name = canonicalName(i);
                Identifier id = Identifier.of(name);
                Block block = Registries.BLOCK.get(id);
                BlockState state = block.getDefaultState();
                Object voxyId = m.invoke(voxyMapper, state);
                map[i] = voxyId instanceof Number n ? n.intValue() : 0;
            }
            return map;
        } catch (Exception e) {
            throw new RuntimeException("CanonicalVoxyMaps.buildBlockMap failed", e);
        }
    }

    public static String canonicalName(int canonicalId) {
        return CanonicalRegistries.canonicalName(canonicalId);
    }
}
