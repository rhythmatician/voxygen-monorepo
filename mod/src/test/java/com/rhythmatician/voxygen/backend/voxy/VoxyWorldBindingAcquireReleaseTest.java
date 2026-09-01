package com.rhythmatician.voxygen.backend.voxy;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.rhythmatician.voxygen.generation.refinement.CompleteChildHandoff;

/**
 * Issue #228: Every Voxy WorldSection acquire must be exception-safe.
 * Focused failure-injection tests proving reference count returns to zero
 * on success, early-return/skip gates, and injected failures after acquisition
 * (data access, topology, markDirty), with suppressed retention on double failure.
 */
class VoxyWorldBindingAcquireReleaseTest {

    // ------------------------------------------------------------------ //
    //  Fake Voxy runtime
    // ------------------------------------------------------------------ //

    public static class FakeWorldSection {
        public long[] data = new long[32 * 32 * 32];
        public byte nonEmptyChildren = 0;
        public final AtomicInteger refCount = new AtomicInteger(0);
        public boolean failRelease = false;

        public void release() {
            if (failRelease) {
                throw new RuntimeException("fake release failure");
            }
            int after = refCount.decrementAndGet();
            if (after < 0) throw new IllegalStateException("double release, refCount negative");
        }
    }

    public static class FakeWorldEngine {
        final Map<String, FakeWorldSection> sections = new ConcurrentHashMap<>();
        volatile boolean failMarkDirty = false;
        volatile boolean failChildProbe = false;
        volatile boolean returnNullForAcquireIfExists = false;

        private String key(int lvl, int x, int y, int z) {
            return lvl + ":" + x + ":" + y + ":" + z;
        }

        public FakeWorldSection acquire(int lvl, int x, int y, int z) {
            String k = key(lvl, x, y, z);
            FakeWorldSection sec = sections.computeIfAbsent(k, kk -> new FakeWorldSection());
            sec.refCount.incrementAndGet();
            return sec;
        }

        public FakeWorldSection acquireIfExists(int lvl, int x, int y, int z) {
            if (failChildProbe) {
                throw new RuntimeException("fake child probe failure");
            }
            if (returnNullForAcquireIfExists) return null;
            String k = key(lvl, x, y, z);
            FakeWorldSection sec = sections.get(k);
            if (sec == null) return null;
            sec.refCount.incrementAndGet();
            return sec;
        }

        public void markDirty(Object section, int flags, int unused) {
            if (failMarkDirty) throw new RuntimeException("fake markDirty failure");
        }

        int totalRefs() {
            int sum = 0;
            for (FakeWorldSection s : sections.values()) sum += s.refCount.get();
            return sum;
        }

        void clear() {
            sections.clear();
            failMarkDirty = false;
            failChildProbe = false;
            returnNullForAcquireIfExists = false;
        }
    }

    private FakeWorldEngine fakeEngine;
    private Object savedAvailable;
    private boolean savedEngineReady;
    private Method savedAcquire;
    private Method savedAcquireIfExists;
    private Method savedRelease;
    private Method savedMarkDirty;
    private Field savedDataField;
    private Field savedNecField;
    private VarHandle savedNecHandle;
    private boolean savedWorldSectionReady;

    @BeforeEach
    void installFake() throws Exception {
        fakeEngine = new FakeWorldEngine();

        // Save originals
        savedAvailable = VoxyDetection.available;
        savedEngineReady = VoxyEngine.engineBindingsReady;
        savedAcquire = VoxyEngine.acquireMethod;
        savedAcquireIfExists = VoxyEngine.acquireIfExistsMethod;
        savedRelease = VoxyEngine.worldSectionReleaseMethod;
        savedMarkDirty = VoxyEngine.markDirtyWithFlagsMethod;
        savedDataField = VoxyWorldBinding.worldSectionDataField;
        savedNecField = VoxyWorldBinding.worldSectionNonEmptyChildrenField;
        savedNecHandle = VoxyWorldBinding.worldSectionNecVarHandle;
        Field f = VoxyWorldBinding.class.getDeclaredField("worldSectionFieldsReady");
        f.setAccessible(true);
        savedWorldSectionReady = f.getBoolean(null);

        // Install fake availability and readiness
        VoxyDetection.available = true;
        VoxyEngine.engineBindingsReady = true;
        f.setBoolean(null, true);

        VoxyEngine.acquireMethod = FakeWorldEngine.class.getMethod("acquire", int.class, int.class, int.class, int.class);
        VoxyEngine.acquireIfExistsMethod = FakeWorldEngine.class.getMethod("acquireIfExists", int.class, int.class, int.class, int.class);
        VoxyEngine.worldSectionReleaseMethod = FakeWorldSection.class.getMethod("release");
        VoxyEngine.markDirtyWithFlagsMethod = FakeWorldEngine.class.getMethod("markDirty", Object.class, int.class, int.class);

        VoxyWorldBinding.worldSectionDataField = FakeWorldSection.class.getField("data");
        VoxyWorldBinding.worldSectionNonEmptyChildrenField = FakeWorldSection.class.getField("nonEmptyChildren");
        VoxyWorldBinding.worldSectionNecVarHandle = MethodHandles.lookup().findVarHandle(FakeWorldSection.class, "nonEmptyChildren", byte.class);

        VoxyTopologyOwnership.clearForTest();
    }

    @AfterEach
    void restore() throws Exception {
        VoxyDetection.available = (Boolean) savedAvailable;
        VoxyEngine.engineBindingsReady = savedEngineReady;
        VoxyEngine.acquireMethod = savedAcquire;
        VoxyEngine.acquireIfExistsMethod = savedAcquireIfExists;
        VoxyEngine.worldSectionReleaseMethod = savedRelease;
        VoxyEngine.markDirtyWithFlagsMethod = savedMarkDirty;
        VoxyWorldBinding.worldSectionDataField = savedDataField;
        VoxyWorldBinding.worldSectionNonEmptyChildrenField = savedNecField;
        VoxyWorldBinding.worldSectionNecVarHandle = savedNecHandle;
        Field f = VoxyWorldBinding.class.getDeclaredField("worldSectionFieldsReady");
        f.setAccessible(true);
        f.setBoolean(null, savedWorldSectionReady);
        VoxyTopologyOwnership.clearForTest();
        if (fakeEngine != null) fakeEngine.clear();
    }

    private long[] solidVoxels(int lvl) {
        if (lvl >= 1 && lvl <= 4) {
            int cells = 16 >> lvl;
            int n = cells * cells * cells;
            long[] v = new long[n];
            long solid = 1L << VoxyWorldBinding.BLOCK_ID_SHIFT;
            for (int i = 0; i < n; i++) v[i] = solid;
            return v;
        }
        long[] v = new long[32 * 32 * 32];
        long solid = 1L << VoxyWorldBinding.BLOCK_ID_SHIFT;
        for (int i = 0; i < v.length; i++) v[i] = solid;
        return v;
    }

    private long[] airVoxels(int lvl) {
        if (lvl >= 1 && lvl <= 4) {
            int cells = 16 >> lvl;
            return new long[cells * cells * cells];
        }
        return new long[32 * 32 * 32];
    }

    // ------------------------------------------------------------------ //
    //  writeAtLevel
    // ------------------------------------------------------------------ //

    @Test
    void writeAtLevel_success_releasesExactlyOnce() {
        long[] voxels = solidVoxels(1);
        int n = VoxyWorldBinding.writeAtLevel(fakeEngine, 1, 0, 0, 0, voxels);
        assertTrue(n > 0);
        assertEquals(0, fakeEngine.totalRefs(), "leaked after success");
    }

    @Test
    void writeAtLevel_earlyReturn_childBitSet_releasesExactlyOnce() throws Exception {
        // Pre-create section with childBit already set so writeAtLevel early-returns
        FakeWorldSection sec = fakeEngine.acquire(1, 0, 0, 0);
        sec.nonEmptyChildren = 1; // octant 0
        sec.release(); // return to zero before test
        assertEquals(0, fakeEngine.totalRefs());

        long[] voxels = solidVoxels(1);
        int n = VoxyWorldBinding.writeAtLevel(fakeEngine, 1, 0, 0, 0, voxels);
        assertEquals(0, n);
        assertEquals(0, fakeEngine.totalRefs(), "early return must release exactly once, no leak or double release");
    }

    @Test
    void writeAtLevel_markDirtyFailure_releasesAndPrimaryRetained() {
        fakeEngine.failMarkDirty = true;
        long[] voxels = solidVoxels(1);
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> VoxyWorldBinding.writeAtLevel(fakeEngine, 1, 2, 0, 0, voxels));
        assertTrue(ex.getMessage().contains("writeAtLevel"));
        assertEquals(0, fakeEngine.totalRefs(), "must release even when markDirty fails");
        // release did not fail, so no suppressed; primary is the wrapped markDirty failure
        assertNotNull(ex.getCause());
    }

    @Test
    void writeAtLevel_doubleFailure_primarySuppressed() throws Exception {
        fakeEngine.failMarkDirty = true;
        FakeWorldSection pre = fakeEngine.acquire(1, 1, 0, 0);
        pre.nonEmptyChildren = 0;
        // Avoid calling release that would throw; just reset count and set flag
        pre.refCount.set(0);
        pre.failRelease = true;

        long[] voxels = solidVoxels(1);
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> VoxyWorldBinding.writeAtLevel(fakeEngine, 1, 4, 0, 0, voxels));
        // The close failure should be suppressed on the markDirty failure
        Throwable cause = ex.getCause();
        boolean hasSuppressed = ex.getSuppressed().length > 0 || (cause != null && cause.getSuppressed().length > 0);
        assertTrue(hasSuppressed, "release failure must be suppressed on primary, ex=" + ex + " cause=" + cause);
        // Cleanup
        pre.failRelease = false;
        pre.refCount.set(0);
        assertEquals(0, fakeEngine.totalRefs());
    }

    // ------------------------------------------------------------------ //
    //  publishCompleteChildMask
    // ------------------------------------------------------------------ //

    @Test
    void publishCompleteChildMask_success_releases() {
        CompleteChildHandoff handoff = CompleteChildHandoff.ofMasks(0x0F, 0xF0);
        VoxyWorldBinding.publishCompleteChildMask(fakeEngine, 1, 0, 0, 0, handoff);
        assertEquals(0, fakeEngine.totalRefs());
    }

    @Test
    void publishCompleteChildMask_markDirtyFailure_releasesPrimaryRetained() {
        // Need to create parent section first so markDirty has something to dirty
        // publishCompleteChildMask acquires parent then calls markDirty if topology changed.
        // Make topology change by ensuring finalMask non-zero.
        fakeEngine.failMarkDirty = true;
        // Pre-create parent with nonEmptyChildren=0 so publishing will change it
        FakeWorldSection parent = fakeEngine.acquire(2, 0, 0, 0);
        parent.nonEmptyChildren = 0;
        parent.release();
        assertEquals(0, fakeEngine.totalRefs());

        CompleteChildHandoff handoff = CompleteChildHandoff.ofMasks(0xFF, 0x00);
        // The storedChildren probe will scan children (none exist) so finalMask=0xFF, publish will merge and then markDirty fails
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> VoxyWorldBinding.publishCompleteChildMask(fakeEngine, 2, 0, 0, 0, handoff));
        assertEquals(0, fakeEngine.totalRefs(), "must release even when markDirty fails");
        assertNotNull(ex.getCause());
    }

    // ------------------------------------------------------------------ //
    //  writeFullWorldSection
    // ------------------------------------------------------------------ //

    @Test
    void writeFullWorldSection_success_releases() {
        long[] voxels = solidVoxels(0);
        int n = VoxyWorldBinding.writeFullWorldSection(fakeEngine, 1, 0, 0, 0, voxels, (byte) 0);
        assertTrue(n > 0);
        assertEquals(0, fakeEngine.totalRefs());
    }

    @Test
    void writeFullWorldSection_earlyReturn_existingFullL0_releases() {
        // L0 path: create existing section with NEC=0xFF and all octants occupied
        FakeWorldSection existing = fakeEngine.acquire(0, 0, 0, 0);
        existing.nonEmptyChildren = (byte) 0xFF;
        // Fill data to make every octant occupied
        long solid = 1L << VoxyWorldBinding.BLOCK_ID_SHIFT;
        for (int i = 0; i < existing.data.length; i++) existing.data[i] = solid;
        existing.release();
        assertEquals(0, fakeEngine.totalRefs());

        long[] voxels = solidVoxels(0);
        int n = VoxyWorldBinding.writeFullWorldSection(fakeEngine, 0, 0, 0, 0, voxels, (byte) 0);
        assertEquals(0, n, "should skip when every octant genuinely has blocks");
        assertEquals(0, fakeEngine.totalRefs(), "early return must not leak existingSection");
    }

    @Test
    void writeFullWorldSection_earlyReturn_verifiedChildren_releases() {
        // L1 path: need existing parent with advertised and stored both 0xFF to trigger skip
        // Create parent and all 8 children with data
        FakeWorldSection parent = fakeEngine.acquire(1, 0, 0, 0);
        parent.nonEmptyChildren = (byte) 0xFF;
        parent.release();
        for (int oct = 0; oct < 8; oct++) {
            int cx = (0 << 1) + (oct & 1);
            int cy = (0 << 1) + ((oct >> 2) & 1);
            int cz = (0 << 1) + ((oct >> 1) & 1);
            FakeWorldSection child = fakeEngine.acquire(0, cx, cy, cz);
            child.nonEmptyChildren = 1;
            child.data[0] = 1L << VoxyWorldBinding.BLOCK_ID_SHIFT;
            child.release();
        }
        assertEquals(0, fakeEngine.totalRefs());
        long[] voxels = solidVoxels(0);
        int n = VoxyWorldBinding.writeFullWorldSection(fakeEngine, 1, 0, 0, 0, voxels, (byte) 0);
        assertEquals(0, n);
        assertEquals(0, fakeEngine.totalRefs(), "verified-children skip must not leak");
    }

    @Test
    void writeFullWorldSection_allAir_skipDoesNotLeak() {
        long[] air = airVoxels(0);
        int n = VoxyWorldBinding.writeFullWorldSection(fakeEngine, 1, 0, 0, 0, air, (byte) 0);
        assertEquals(0, n);
        assertEquals(0, fakeEngine.totalRefs());
    }

    @Test
    void writeFullWorldSection_topologyFailureAfterFirstAcquire_releases() {
        // Make child probe fail after acquiring existingSection
        // Create existing parent to ensure first acquire succeeds
        FakeWorldSection existing = fakeEngine.acquire(1, 1, 0, 0);
        existing.nonEmptyChildren = 0;
        existing.release();
        assertEquals(0, fakeEngine.totalRefs());

        fakeEngine.failChildProbe = true;
        long[] voxels = solidVoxels(0);
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> VoxyWorldBinding.writeFullWorldSection(fakeEngine, 1, 1, 0, 0, voxels, (byte) 0));
        assertEquals(0, fakeEngine.totalRefs(), "first acquire must be released even when topology probe fails");
        fakeEngine.failChildProbe = false;
    }

    @Test
    void writeFullWorldSection_markDirtyFailureAfterSecondAcquire_releases() {
        fakeEngine.failMarkDirty = true;
        long[] voxels = solidVoxels(0);
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> VoxyWorldBinding.writeFullWorldSection(fakeEngine, 1, 2, 0, 0, voxels, (byte) 0));
        assertEquals(0, fakeEngine.totalRefs());
        fakeEngine.failMarkDirty = false;
    }

    @Test
    void writeFullWorldSection_noReleaseForNullAcquireIfExists() {
        // Ensure acquireIfExists returns null (no existing section)
        fakeEngine.returnNullForAcquireIfExists = false; // actually our fake returns null when map empty
        // Map is empty, so existingSection will be null
        long[] voxels = solidVoxels(0);
        int n = VoxyWorldBinding.writeFullWorldSection(fakeEngine, 1, 5, 0, 0, voxels, (byte) 0);
        assertTrue(n > 0);
        assertEquals(0, fakeEngine.totalRefs(), "null acquireIfExists must not attempt release");
    }

    // ------------------------------------------------------------------ //
    //  getOccupiedOctantMask
    // ------------------------------------------------------------------ //

    @Test
    void getOccupiedOctantMask_success_releases() {
        FakeWorldSection sec = fakeEngine.acquire(1, 0, 0, 0);
        sec.data[0] = 1L << VoxyWorldBinding.BLOCK_ID_SHIFT;
        sec.release();
        assertEquals(0, fakeEngine.totalRefs());
        byte mask = VoxyWorldBinding.getOccupiedOctantMask(fakeEngine, 1, 0, 0, 0);
        assertNotEquals(0, mask);
        assertEquals(0, fakeEngine.totalRefs());
    }

    @Test
    void getOccupiedOctantMask_nullReturnsZero_noRelease() {
        byte mask = VoxyWorldBinding.getOccupiedOctantMask(fakeEngine, 1, 9, 9, 9);
        assertEquals(0, mask);
        assertEquals(0, fakeEngine.totalRefs());
    }

    @Test
    void getOccupiedOctantMask_dataAccessFailure_releases() {
        FakeWorldSection sec = fakeEngine.acquire(1, 3, 0, 0);
        sec.data = null; // will cause NPE in computeOccupiedOctantMask after acquire
        sec.release();
        assertEquals(0, fakeEngine.totalRefs());
        byte mask = VoxyWorldBinding.getOccupiedOctantMask(fakeEngine, 1, 3, 0, 0);
        assertEquals(0, mask); // returns 0 on failure
        assertEquals(0, fakeEngine.totalRefs(), "must release even when data access fails");
        // cleanup: restore data to avoid leaking?
        sec.data = new long[32768];
    }

    // ------------------------------------------------------------------ //
    //  allOctantsPopulated
    // ------------------------------------------------------------------ //

    @Test
    void allOctantsPopulated_success_releases() {
        FakeWorldSection sec = fakeEngine.acquire(0, 0, 0, 0);
        long solid = 1L << VoxyWorldBinding.BLOCK_ID_SHIFT;
        for (int i = 0; i < sec.data.length; i++) sec.data[i] = solid;
        sec.release();
        assertTrue(VoxyWorldBinding.allOctantsPopulated(fakeEngine, 0, 0, 0, 0));
        assertEquals(0, fakeEngine.totalRefs());
    }

    @Test
    void allOctantsPopulated_nullReturnsFalse_noLeak() {
        assertFalse(VoxyWorldBinding.allOctantsPopulated(fakeEngine, 1, 9, 9, 9));
        assertEquals(0, fakeEngine.totalRefs());
    }

    // ------------------------------------------------------------------ //
    //  readWorldSectionBlocks
    // ------------------------------------------------------------------ //

    @Test
    void readWorldSectionBlocks_success_releases() {
        FakeWorldSection sec = fakeEngine.acquire(1, 0, 0, 0);
        long solid = 1L << VoxyWorldBinding.BLOCK_ID_SHIFT;
        sec.data[0] = solid;
        sec.release();
        int[][][] out = VoxyWorldBinding.readWorldSectionBlocks(fakeEngine, 1, 0, 0, 0);
        assertNotNull(out);
        assertEquals(0, fakeEngine.totalRefs());
    }

    @Test
    void readWorldSectionBlocks_nullReturnsNull_noRelease() {
        int[][][] out = VoxyWorldBinding.readWorldSectionBlocks(fakeEngine, 1, 9, 9, 9);
        assertNull(out);
        assertEquals(0, fakeEngine.totalRefs());
    }

    @Test
    void readWorldSectionBlocks_dataFailure_releases() {
        FakeWorldSection sec = fakeEngine.acquire(1, 4, 0, 0);
        sec.data = null;
        sec.release();
        int[][][] out = VoxyWorldBinding.readWorldSectionBlocks(fakeEngine, 1, 4, 0, 0);
        assertNull(out);
        assertEquals(0, fakeEngine.totalRefs());
        sec.data = new long[32768];
    }

    // ------------------------------------------------------------------ //
    //  sectionExistsAtLevel
    // ------------------------------------------------------------------ //

    @Test
    void sectionExistsAtLevel_success_releases() {
        FakeWorldSection sec = fakeEngine.acquire(1, 6, 0, 0);
        sec.release();
        assertTrue(VoxyWorldBinding.sectionExistsAtLevel(fakeEngine, 1, 6, 0, 0));
        assertEquals(0, fakeEngine.totalRefs());
    }

    @Test
    void sectionExistsAtLevel_absent_noRelease() {
        assertFalse(VoxyWorldBinding.sectionExistsAtLevel(fakeEngine, 1, 9, 9, 9));
        assertEquals(0, fakeEngine.totalRefs());
    }
}
