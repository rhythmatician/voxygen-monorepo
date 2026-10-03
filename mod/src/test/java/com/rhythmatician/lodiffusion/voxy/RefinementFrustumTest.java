package com.rhythmatician.lodiffusion.voxy;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.ArrayList;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import net.minecraft.client.render.Frustum;
import net.minecraft.util.math.Box;
import org.junit.jupiter.api.Test;
import com.rhythmatician.voxygen.generation.refinement.RefinementView;
import com.rhythmatician.voxygen.semantic.Level;
import com.rhythmatician.voxygen.semantic.SectionPos;

class RefinementFrustumTest {
    static RefinementView view(double direction) {
        return new RefinementView(100, 0, 0, 0, List.of(
                new RefinementView.Plane(direction, 0, 0, 0),
                new RefinementView.Plane(-direction, 0, 0, 10000),
                new RefinementView.Plane(0, 1, 0, 10000),
                new RefinementView.Plane(0, -1, 0, 10000),
                new RefinementView.Plane(0, 0, 1, 10000),
                new RefinementView.Plane(0, 0, -1, 10000)));
    }

    @Test
    void turningTheViewReversesAdmissionWithoutChangingTerrain() {
        SectionPos ahead = new SectionPos(32, 0, 0);
        SectionPos behind = new SectionPos(-64, 0, 0);
        assertTrue(view(1).intersects(Level.L4, ahead));
        assertFalse(view(1).intersects(Level.L4, behind));
        assertFalse(view(-1).intersects(Level.L4, ahead));
        assertTrue(view(-1).intersects(Level.L4, behind));
    }

    @Test
    void boundaryContactIsRetainedAtEveryLevelIncludingNegativeCoordinates() {
        for (Level level : Level.values()) {
            assertTrue(view(1).intersects(level, new SectionPos(-level.regionSections(), 0, 0)));
            assertFalse(view(1).intersects(level, new SectionPos(-2 * level.regionSections(), 0, 0)));
        }
    }

    @Test
    void staleFutureAndMalformedViewsAreUnavailable() {
        assertTrue(view(1).usable(100));
        assertFalse(view(1).usable(99));
        assertFalse(view(1).usable(1101));
        assertFalse(new RefinementView(100, Double.NaN, 0, 0, view(1).planes()).usable(100));
        assertFalse(new RefinementView(100, 0, 0, 0, List.of()).usable(100));
    }

    private static RefinementView projectedView(float fov, double cameraX) {
        Matrix4f projection = new Matrix4f().perspective(fov, 1, 0.05f, 8192);
        var planes = new ArrayList<RefinementView.Plane>();
        for (int i = 0; i < 6; i++) {
            Vector4f plane = projection.frustumPlane(i, new Vector4f());
            planes.add(new RefinementView.Plane(plane.x, plane.y, plane.z, plane.w));
        }
        return new RefinementView(100, cameraX, 0, 0, planes);
    }

    @Test
    void actualProjectionAndZoomMatchPinnedMinecraftFrustumAtLargeWorldCoordinates() {
        double cameraX = 1_000_000;
        var candidate = new SectionPos(62504, 0, -8);
        Box bounds = new Box(1_000_064, 0, -128, 1_000_096, 32, -96);
        for (float fov : new float[]{(float) Math.toRadians(100), (float) Math.toRadians(20)}) {
            Frustum vanilla = new Frustum(new Matrix4f(),
                    new Matrix4f().perspective(fov, 1, 0.05f, 8192));
            vanilla.setPosition(cameraX, 0, 0);
            assertEquals(vanilla.isVisible(bounds), projectedView(fov, cameraX).intersects(Level.L0, candidate));
        }
        assertTrue(projectedView((float) Math.toRadians(100), cameraX).intersects(Level.L0, candidate));
        assertFalse(projectedView((float) Math.toRadians(20), cameraX).intersects(Level.L0, candidate));
    }
}
