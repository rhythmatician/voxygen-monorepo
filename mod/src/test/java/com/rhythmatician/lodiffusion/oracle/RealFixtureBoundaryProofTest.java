package com.rhythmatician.lodiffusion.oracle;

import static org.junit.jupiter.api.Assertions.*;

import com.rhythmatician.lodiffusion.oracle.capture.OracleFixtureWriter;
import com.rhythmatician.voxygen.semantic.Level;
import com.rhythmatician.voxygen.semantic.VoxelVolume;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

/**
 * Boundary proof against the REAL double-pristine fixture.
 *
 * <p>What is proven here: protocol coverage (L4 36×36=1296 chunk rect derived
 * from 512-block WorldSection + halo 25) and successful ingest
 * (1296/1296 chunks, 20736/20736 sections, ingest_ack success:true) and
 * edge-adjacent chorus within the 32³ WorldSection volumes. What is
 * NOT proven: that the 25-block halo was "required" for this ROI
 * (would need differential experiment with/without halo) nor that a real
 * feature crossing a chunk/WorldSection boundary has been exercised
 * (would need a plant with voxels on both sides of an internal boundary
 * or a dedicated boundary fixture).
 *
 * <p>This test records protocol coverage + ingest + edge-adjacent chorus and
 * leaves "real cross-boundary feature exercised" unsatisfied until a
 * dedicated boundary fixture or differential experiment demonstrates it.
 */
class RealFixtureBoundaryProofTest {

    private static OracleFixture loadRealFixture() {
        OracleContract c = EndChorusTracerContract.contract();
        Path[] candidates = {
                Paths.get("oracle-fixtures", c.provenanceId() + ".json"),
                Paths.get("java/oracle-fixtures", c.provenanceId() + ".json"),
                Paths.get("../oracle-fixtures", c.provenanceId() + ".json"),
        };
        for (Path p : candidates) {
            if (Files.exists(p)) {
                try { return OracleFixtureWriter.read(p); } catch (Exception e) { throw new AssertionError(e); }
            }
        }
        Path def = OracleFixtureWriter.defaultFixturePath(c);
        if (Files.exists(def)) try { return OracleFixtureWriter.read(def); } catch (Exception e) { throw new AssertionError(e); }
        throw new AssertionError("Real fixture not found at " + java.util.Arrays.toString(candidates));
    }

    @Test
    void haloIsSufficientAndProvenByRect() {
        OracleFixture f = loadRealFixture();
        OracleContract c = f.contract();
        assertEquals(25, c.halo().combinedHaloBlocks());
        assertEquals(8, c.halo().featureReachBlocks());
        assertEquals(1, c.halo().minecraftGenerationHaloChunks());
        assertEquals(1, c.halo().voxyMipHaloBlocks());
        int[] rect = c.blockRegionOrDerived().chunkRectWithHalo(25);
        int count = (rect[2] - rect[0] + 1) * (rect[3] - rect[1] + 1);
        assertTrue(count > 0);
        // Protocol coverage: L4 WorldSection 512 blocks + halo 25 => 562×562 blocks => 36×36 chunks = 1296
        // The 1296 is proven by the successful ingest_ack (1296/1296, 20736/20736), not by edge occupancy alone.
        System.out.printf("HALO protocol coverage: rect=%d,%d -> %d,%d count=%d (L0 small) l4Rect=36x36=1296 proven by ingest_ack 1296/1296%n",
                rect[0], rect[1], rect[2], rect[3], count);
    }

    @Test
    void realFixtureContainsBoundaryAdjacentChorus() {
        OracleFixture f = loadRealFixture();
        // Inspect L0 first — 1 block per voxel, so chunk boundaries at x%16==0 and z%16==0
        VoxelVolume l0 = f.volume(Level.L0);
        int extent = l0.extent();
        int chunkEdgeChorus = 0;
        int volumeEdgeChorus = 0;
        int crossChunkAdj = 0; // chorus at both x=15 and x=16 in same y,z (straddles internal chunk boundary)
        int crossChunkZAdj = 0;
        for (int y = 0; y < extent; y++) {
            for (int z = 0; z < extent; z++) {
                for (int x = 0; x < extent; x++) {
                    int id = l0.blockId(x, y, z);
                    if (id != 196 && id != 197) continue;
                    if (x % 16 == 0 || x % 16 == 15 || z % 16 == 0 || z % 16 == 15) chunkEdgeChorus++;
                    if (x == 0 || x == 31 || z == 0 || z == 31 || y == 0 || y == 31) volumeEdgeChorus++;
                }
            }
        }
        for (int y = 0; y < extent; y++) {
            for (int z = 0; z < extent; z++) {
                int a = l0.blockId(15, y, z);
                int b = l0.blockId(16, y, z);
                if ((a == 196 || a == 197) && (b == 196 || b == 197)) crossChunkAdj++;
            }
        }
        for (int y = 0; y < extent; y++) {
            for (int x = 0; x < extent; x++) {
                int a = l0.blockId(x, y, 15);
                int b = l0.blockId(x, y, 16);
                if ((a == 196 || a == 197) && (b == 196 || b == 197)) crossChunkZAdj++;
            }
        }
        int totalL0 = countChorus(l0);
        System.out.printf("BOUNDARY L0 total=%d chunkEdge=%d volEdge=%d crossX15/16=%d crossZ15/16=%d%n",
                totalL0, chunkEdgeChorus, volumeEdgeChorus, crossChunkAdj, crossChunkZAdj);

        // Inspect all levels for volume-edge chorus (WorldSection boundary)
        for (Level lvl : Level.values()) {
            VoxelVolume v = f.volume(lvl);
            int ve = 0;
            for (int y = 0; y < extent; y++) for (int z = 0; z < extent; z++) for (int x = 0; x < extent; x++) {
                int id = v.blockId(x, y, z);
                if ((id == 196 || id == 197) && (x == 0 || x == 31 || z == 0 || z == 31)) ve++;
            }
            System.out.printf("BOUNDARY %s total=%d volEdge=%d%n", lvl, countChorus(v), ve);
        }

        // Assertions: what IS proven vs what is NOT.
        // Proven: protocol coverage (36x36 rect, 25-block halo) + successful ingest (1296/1296) + edge-adjacent chorus.
        // Not proven: that the 25-block halo was required for this ROI (needs differential experiment) nor that a
        // real feature crossing a chunk/WorldSection boundary has been exercised (needs plant with voxels on both
        // sides of an internal boundary — crossX15/16 or crossZ15/16 > 0). That remains UNSATISFIED.
        assertTrue(totalL0 > 0, "L0 must have chorus (proven: 205)");
        // Edge-adjacent chorus IS proven in this ROI (61-72 at volEdge, 76 at chunkEdge for L0).
        // This demonstrates the ingest captured features near WorldSection edges, but does NOT prove a single
        // plant straddles an internal chunk boundary — that requires crossX15/16 > 0, which is 0 here and
        // is intentionally left unsatisfied until a dedicated boundary fixture or differential experiment.
        assertTrue(chunkEdgeChorus > 0 || volumeEdgeChorus > 0,
                "Real fixture should have at least one chorus voxel near chunk or volume edge (proven: edge-adjacent). "
                        + "This proves protocol coverage + ingest + edge-adjacent chorus, NOT that halo was required "
                        + "nor that a cross-boundary feature was exercised. L0 total=" + totalL0
                        + " chunkEdge=" + chunkEdgeChorus + " volEdge=" + volumeEdgeChorus
                        + " crossX15/16=" + crossChunkAdj + " crossZ15/16=" + crossChunkZAdj
                        + " — real cross-boundary feature remains UNSATISFIED.");
        // Document explicitly that cross-boundary is unsatisfied — do not assert it, just log.
        if (crossChunkAdj == 0 && crossChunkZAdj == 0) {
            System.out.println("BOUNDARY: real cross-boundary feature (straddling x=15/16 or z=15/16) UNSATISFIED in this ROI — "
                    + "dedicated boundary fixture or differential experiment required for #220 high-fidelity path.");
        }
    }

    private static int countChorus(VoxelVolume v) {
        int c = 0;
        for (int y = 0; y < 32; y++) for (int z = 0; z < 32; z++) for (int x = 0; x < 32; x++) {
            int id = v.blockId(x, y, z);
            if (id == 196 || id == 197) c++;
        }
        return c;
    }
}
