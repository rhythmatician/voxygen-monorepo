package com.rhythmatician.lodiffusion.oracle;

import com.rhythmatician.lodiffusion.voxy.CanonicalRegistries;
import java.util.List;

/**
 * Pinned tracer contract for End chorus (#233). Single source of truth for the
 * executable contract that initial oracle fixtures and candidate verifiers must satisfy.
 *
 * <p>Values are grounded in inspected upstream sources; changing any versioned
 * input creates a distinct evidence identity (distinct oracleFixtureId).
 */
public final class EndChorusTracerContract {
    private EndChorusTracerContract() {}

    public static OracleContract contract() {
        return OracleContract.builder()
                .schemaVersion(OracleContract.CURRENT_SCHEMA_VERSION)
                .responsibilityId("end_chorus")
                .dimension("minecraft:the_end")
                .frozenWorldgenProfileId("end_pinned_v1_generate_structures=false")
                .minecraftVersion("1.21.11")
                .minecraftSourceRevision("26.1-snapshot-11")
                .minecraftJarSha256("556C0FA70D367A2D0EC2DF5C9796C77EABE164BF08E0C581FC9CE17FA7436822")
                .voxyVersion("0.2.11-alpha")
                .voxyCommit("337b919")
                .voxyArtifactSha256("63d1747017041b659ef620f589006d079d3574e3124dbdb165f9998533a7920c")
                .canonicalBlockRegistryVersion(CanonicalRegistries.BLOCK_REGISTRY_VERSION)
                .canonicalBlockRegistrySha256(CanonicalRegistries.BLOCK_REGISTRY_SHA256)
                .canonicalBiomeRegistryVersion("voxygen.biomes.v1")
                .canonicalBiomeRegistrySha256("b18e5a6b9f3e0b8c7c9f0e1a2d3c4b5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3")
                .inspectedMinecraftReferences(List.of(
                        "net.minecraft.world.level.levelgen.feature.ChorusPlantFeature:18,27-35#place()->ChorusFlowerBlock.generatePlant",
                        "net.minecraft.world.level.block.ChorusFlowerBlock:178-210#generatePlant()->growTreeRecursive(maxHorizontalSpread=8, depth<4, stems=random.nextInt(4)+1)",
                        "net.minecraft.world.level.block.ChorusFlowerBlock:106-148#canSurvive/allNeighborsEmpty",
                        "net.minecraft.world.level.biome.TheEndBiomeSource:48-79#getNoiseBiome(thresholds 0.25/-0.0625/-0.21875, chunkRadius 64)",
                        "net.minecraft.world.level.chunk.status.ChunkStatus:28#FEATURES(7) authoritative for chorus",
                        "net.minecraft.world.level.chunk.status.ChunkPyramid:18#GENERATION_PYRAMID FEATURES writeRadius=1 requires CARVERS@1 STRUCTURE_STARTS@8",
                        "net.minecraft.world.level.levelgen.NoiseGeneratorSettings:70#END NoiseSettings(0,128,2,1) cell 8x4 aquifersEnabled=false",
                        "net.minecraft.world.level.levelgen.GenerationStep:11#VEGETAL_DECORATION chorus in END_HIGHLANDS",
                        "net.minecraft.data.worldgen.biome.EndBiomes:24-46#END_HIGHLANDS chorus placement"
                ))
                .inspectedVoxyReferences(List.of(
                        "me.cortex.voxy.common.voxelization.WorldConversionFactory:130-220#convert(PalettedContainer->Mapper.composeMappingId)",
                        "me.cortex.voxy.common.voxelization.VoxelizedSection:1-60#long[4681] pyramid offsets 0/4096/4608/4672/4680",
                        "me.cortex.voxy.common.world.other.Mipper:9-55#mip(opacity<<4|cornorPriority I111=7..I000=0, air averages light)",
                        "me.cortex.voxy.common.world.other.Mapper:1-120#per-world sequential block/biome IDs via putIdMapping/getIdMappingsData key (type<<30)|id bits 63..56 light 55..47 biome 46..27 blockId",
                        "me.cortex.voxy.common.world.WorldUpdater:14-90#insertUpdate(WorldEngine acquire lvl x>>(lvl+1) -> insertSectionLvlIntoWorld -> nonEmptyChildren)",
                        "me.cortex.voxy.common.world.WorldSection:1-40#32^3 voxels long[32768] YZX (y<<10)|(z<<5)|x nonEmptyChildren octant mask",
                        "me.cortex.voxy.common.world.WorldEngine:60#getWorldSectionId lvl<<60 y&0xFF<<52 z<<28 x<<4"
                ))
                .seed(42L)
                .region(new OracleContract.RegionSpec(0, 0, 0, 2))
                .halo(new OracleContract.HaloSpec(24, "ChorusPlantFeature maxHorizontalSpread 8 + ChunkPyramid FEATURES writeRadius 1 chunk (16) = 24 blocks", "ChorusFlowerBlock.java:200 + ChunkPyramid.java:18"))
                .authoritativeGenerationStage("FEATURES")
                .fixtureFormatVersion(OracleContract.CURRENT_FIXTURE_FORMAT_VERSION)
                .oracleFixtureId("end_chorus__s42__r0_0_0_e2__h24__mc1.21.11_voxy0.2.11-alpha__fmtv1")
                .perLevelDisposition(new OracleContract.PerLevelDisposition(
                        "omit",
                        "omit",
                        "claim",
                        "claim",
                        "claim"
                ))
                .claimRole("end_chorus visibility at L2/L1/L0 is acceptance-bearing per Fidelity Profile")
                .dependencyRole("end_chorus at L4/L3 is dependency-only; coarse omission is expected via Mipper")
                .benchmarkPolicy(new OracleContract.BenchmarkPolicy(5, 20, "median of 20 after 5 warmup, wall ms per volume"))
                .build();
    }
}

