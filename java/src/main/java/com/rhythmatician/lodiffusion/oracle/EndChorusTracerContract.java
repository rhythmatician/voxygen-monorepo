package com.rhythmatician.lodiffusion.oracle;

import com.rhythmatician.voxygen.semantic.CanonicalRegistries;
import java.util.List;
import com.rhythmatician.voxygen.semantic.Level;
import com.rhythmatician.voxygen.semantic.SectionPos;

public final class EndChorusTracerContract {
    private EndChorusTracerContract() {}

    public static OracleContract contract() {
        // Outer-island END_HIGHLANDS tracer for seed 42: block 1600,64,128 extent 32 (SectionPos 100,4,8) - Y=64 contains chorus surface; X/Z outer highlands.
        // Per-Level WorldSections derived independently via floorDiv (L0 ws 48,2,0; L4 ws 3,0,0). Deterministic anchor will be verified by inspecting real outer-island chunks for actual chorus_plant at seed 42 and pinning the containing L0 WorldSection.
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
                .canonicalBiomeRegistrySha256("66848491c9e88c0d992075ad197c8d4734dff9f11927b7995efb0c1e45e9fc4e")
                .inspectedMinecraftReferences(List.of(
                        "net.minecraft.world.level.levelgen.feature.ChorusPlantFeature:27-35#place()->ChorusFlowerBlock.generatePlant",
                        "net.minecraft.world.level.block.ChorusFlowerBlock:178-210#generatePlant()->growTreeRecursive(maxHorizontalSpread=8, depth<4, stems=random.nextInt(4)+1)",
                        "net.minecraft.world.level.block.ChorusFlowerBlock:106-148#canSurvive/allNeighborsEmpty",
                        "net.minecraft.world.level.biome.TheEndBiomeSource:48-79#getNoiseBiome(thresholds 0.25/-0.0625/-0.21875, chunkRadius 64)",
                        "net.minecraft.world.level.chunk.status.ChunkStatus:28#FEATURES(7) authoritative for chorus",
                        "net.minecraft.world.level.chunk.status.ChunkPyramid:18#GENERATION_PYRAMID FEATURES writeRadius=1 requires CARVERS@1 STRUCTURE_STARTS@8",
                        "net.minecraft.world.level.levelgen.NoiseGeneratorSettings:70#END NoiseSettings(0,128,2,1) cell 8x4 aquifersEnabled=false",
                        "net.minecraft.world.level.levelgen.GenerationStep:11#VEGETAL_DECORATION chorus in END_HIGHLANDS",
                        "net.minecraft.data.worldgen.biome.EndBiomes:24-46#END_HIGHLANDS chorus placement",
                        "net.minecraft.data.worldgen.placement.EndPlacements:22#CHORUS_PLANT CountPlacement 0-4 InSquare HEIGHTMAP BiomeFilter",
                        // RNG: decoration uses WorldgenRandom#setDecorationSeed(worldSeed, blockX, blockZ) inside ChunkGenerator#applyBiomeDecoration
                        // which seeds a legacy WorldgenRandom (not Xoroshiro) per chunk for PlacedFeature placement.
                        // Fabric 1.21.11 pinned source: ChunkGenerator.java 310-360 inspection shows decoration loop via PlacedFeature.placeWithContext using WorldgenRandom.
                        "net.minecraft.world.level.levelgen.WorldgenRandom:22#setDecorationSeed(long worldSeed, int blockX, int blockZ) -> seeds legacy Random for decoration",
                        "net.minecraft.world.level.chunk.ChunkGenerator:342#applyBiomeDecoration(ServerLevel, ChunkAccess, StructureManager) -> iterates PlacedFeature at VEGETAL_DECORATION; FEATURES output may depend on chunk generation order (neighbor reads/writes shared borders) - must record request order squared distance to center -> X -> Z (explicit, not Morton) and prove determinism via 2x clean-world contentSha recomparison",
                        "net.minecraft.world.level.levelgen.placement.PlacedFeature:42-61#placeWithContext -> PlacementContext + decoration seed via WorldgenRandom#setDecorationSeed",
                        "net.minecraft.world.level.levelgen.RandomState:122#getOrCreateRandomFactory(HolderGetter<PlacedFeature>) -> PositionalRandomFactory (alternative path, verify vs WorldgenRandom)"
                ))
                .inspectedVoxyReferences(List.of(
                        "me.cortex.voxy.common.voxelization.WorldConversionFactory:130-220#convert(PalettedContainer->Mapper.composeMappingId)",
                        "me.cortex.voxy.common.voxelization.VoxelizedSection:1-60#long[4681] pyramid offsets 0/4096/4608/4672/4680",
                        "me.cortex.voxy.common.world.other.Mipper:9-55#mip(opacity<<4|cornerPriority I111=7..I000=0, air averages light)",
                        "me.cortex.voxy.common.world.other.Mapper:1-120#per-world sequential block/biome IDs via putIdMapping/getIdMappingsData key (type<<30)|id bits 63..56 light 55..47 biome 46..27 blockId",
                        "me.cortex.voxy.common.world.WorldUpdater:14-90#insertUpdate(WorldEngine acquire lvl x>>(lvl+1) -> insertSectionLvlIntoWorld -> nonEmptyChildren)",
                        "me.cortex.voxy.common.world.WorldSection:1-40#32^3 voxels long[32768] YZX (y<<10)|(z<<5)|x nonEmptyChildren octant mask",
                        "me.cortex.voxy.common.world.WorldEngine:60#getWorldSectionId lvl<<60 y&0xFF<<52 z<<28 x<<4",
                        "me.cortex.voxy.common.world.WorldEngine:110#acquireIfExists(lvl,x,y,z) -> WorldSection + ActiveSectionTracker:1024/2048 MRU"
                ))
                .seed(42L)
                .region(new OracleContract.RegionSpec(100, 4, 8, 2))
                .blockRegion(new OracleContract.BlockRegionSpec(1600, 64, 128, 32))
                .halo(new OracleContract.HaloSpec(
                        8, "Chorus max horizontal spread 8 blocks from origin (maxHorizontalSpread parameter in generatePlant)", "ChorusFlowerBlock.java:178-210 growTreeRecursive maxHorizontalSpread=8",
                        1, "FEATURES reads CARVERS@1 and STRUCTURE_STARTS@8, writes 1 chunk; need +1 chunk halo to make placement well-defined at boundary", "ChunkPyramid.java:18 ChunkStatus.java:28",
                        1, "Voxy 2x2x2 Mipper group crossing WorldSection boundary needs 1 block halo", "Mipper.java:9-55 + WorldSection.java YZX",
                        25))
                .generationOrder(OracleContract.EXPECTED_GENERATION_ORDER)
                .authoritativeGenerationStage("FEATURES")
                .fixtureFormatVersion(OracleContract.CURRENT_FIXTURE_FORMAT_VERSION)
                .provenanceId("end_chorus__s42__b1600_64_128_e32__fh8_gh1c_vh1_ch25__mc1.21.11_voxy0.2.11-alpha__fmtv3")
                .perLevelDecisions(new OracleContract.PerLevelPartitionDecisions(
                        OracleContract.PartitionDecision.unresolved("L4 chorus visibility requires real oracle evidence; coarse mip may suppress thin features via opacity"),
                        OracleContract.PartitionDecision.unresolved("L3 chorus visibility requires real oracle evidence; coarse mip may suppress"),
                        OracleContract.PartitionDecision.unresolved("L2 chorus is UNRESOLVED until real capture; honest omission vs learned vs deterministic not decided"),
                        OracleContract.PartitionDecision.unresolved("L1 chorus is UNRESOLVED until real capture"),
                        OracleContract.PartitionDecision.unresolved("L0 chorus is UNRESOLVED until real capture; closest to vanilla FEATURES")
                ))
                .roles(new OracleContract.ClaimDependencyRoles(
                        "end_chorus claim at L0 is not yet decided; all Levels are UNRESOLVED per partition",
                        "end_chorus dependency at coarse Levels not yet decided",
                        "L4..L0 partition decisions remain UNRESOLVED until real vanilla->Voxy fixture provides evidence; claim/dependency orthogonal to disposition"
                ))
                .benchmarkPolicy(new OracleContract.BenchmarkPolicy(5, 20, "median of 20 after 5 warmup, wall ms per volume"))
                .build();
    }
}
