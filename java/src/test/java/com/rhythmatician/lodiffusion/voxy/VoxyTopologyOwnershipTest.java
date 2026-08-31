package com.rhythmatician.lodiffusion.voxy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import com.rhythmatician.voxygen.backend.voxy.VoxyTopologyOwnership;
import com.rhythmatician.voxygen.backend.voxy.VoxyWorldBinding;
import com.rhythmatician.voxygen.semantic.Level;

class VoxyTopologyOwnershipTest {
    @AfterEach
    void clearOwnership() {
        VoxyTopologyOwnership.clearForTest();
    }

    @Test
    void ownedParentSuppressesPartialNativeTopology() {
        Object parent = new Object();

        VoxyTopologyOwnership.registerGeneratedFallback(parent, Level.L4.value());

        assertTrue(VoxyTopologyOwnership.shouldSuppressNativePromotion(parent));
    }

    @Test
    void unownedParentRemainsNative() {
        assertFalse(VoxyTopologyOwnership.shouldSuppressNativePromotion(new Object()));
    }

    @Test
    void ownershipUsesObjectIdentityRatherThanCoordinateLikeEquality() {
        Object owned = new EqualSection("same-coordinate");
        Object otherWorldSection = new EqualSection("same-coordinate");

        VoxyTopologyOwnership.registerGeneratedFallback(owned, Level.L3.value());

        assertTrue(VoxyTopologyOwnership.isOwned(owned));
        assertFalse(VoxyTopologyOwnership.isOwned(otherWorldSection));
    }

    @Test
    void handoffReleasesExactlyOnce() {
        Object parent = new Object();
        VoxyTopologyOwnership.registerGeneratedFallback(parent, Level.L2.value());

        assertTrue(VoxyTopologyOwnership.releaseAfterHandoff(parent));
        assertFalse(VoxyTopologyOwnership.isOwned(parent));
        assertFalse(VoxyTopologyOwnership.releaseAfterHandoff(parent));
    }

    @Test
    void generatedChildIsOwnedBeforeItsParentHandoff() {
        Object parent = new Object();
        Object generatedChild = new Object();
        VoxyTopologyOwnership.registerGeneratedFallback(parent, Level.L4.value());
        VoxyTopologyOwnership.registerGeneratedFallback(generatedChild, Level.L3.value());

        assertTrue(VoxyTopologyOwnership.isOwned(generatedChild));
        assertTrue(VoxyTopologyOwnership.releaseAfterHandoff(parent));
        assertTrue(VoxyTopologyOwnership.isOwned(generatedChild));
    }

    @Test
    void solidGeneratedL4FallbackRemainsALeafUntilExplicitRefinementPublication() {
        Object generatedChild = new Object();
        byte partialNativeNec = 0b0010_0100;

        VoxyTopologyOwnership.registerGeneratedFallback(generatedChild, Level.L3.value());

        assertEquals((byte) 0, VoxyWorldBinding.fallbackPresentationNec(
                VoxyTopologyOwnership.isOwned(generatedChild), partialNativeNec));

        byte publishedMask = VoxyWorldBinding.completeHandoffMask(
                (byte) 0b0000_0101, (byte) 0b0100_0000);
        assertEquals((byte) 0b0100_0101, publishedMask);
    }

    @Test
    void unownedNativePresentationKeepsItsNecWhenOwnCoarseDataIsEmpty() {
        byte nativeNec = 0x5D;

        assertEquals(nativeNec, VoxyWorldBinding.fallbackPresentationNec(false, nativeNec));
    }

    @Test
    void l1ToL0DoesNotOwnTerminalL0() {
        Object terminalChild = new Object();

        assertFalse(VoxyTopologyOwnership.registerGeneratedFallback(terminalChild, Level.L0.value()));
        assertFalse(VoxyTopologyOwnership.isOwned(terminalChild));
    }

    private static final class EqualSection {
        private final String coordinate;

        private EqualSection(String coordinate) {
            this.coordinate = coordinate;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof EqualSection section && coordinate.equals(section.coordinate);
        }

        @Override
        public int hashCode() {
            return coordinate.hashCode();
        }
    }
}
