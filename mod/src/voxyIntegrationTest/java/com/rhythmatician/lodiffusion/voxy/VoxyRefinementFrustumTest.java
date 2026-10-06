package com.rhythmatician.lodiffusion.voxy;

import com.rhythmatician.voxygen.semantic.Level;
import com.rhythmatician.voxygen.semantic.SectionPos;
import com.rhythmatician.voxygen.generation.refinement.RefinementView;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.util.DepthFramebuffer;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3i;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class VoxyRefinementFrustumTest {
    @Test
    void actualViewportUpdatePlanesMatchJomlAcrossRotatedViewsAndLevels() throws Exception {
        HeadlessViewport viewport = viewportWithoutGpuConstructor();
        int visible = 0;
        int outside = 0;
        for (float near : new float[]{8, 16}) {
            for (float fov : new float[]{20, 100}) {
                for (float yaw : new float[]{0, .7f, (float) Math.PI}) {
                    // Voxy's projection uses a 48,000 far plane; Sodium supplies this modelview.
                    viewport.setProjection(new Matrix4f().perspective((float) Math.toRadians(fov),
                            16f / 9f, near, 48_000));
                    viewport.setModelView(new Matrix4f().rotateX(.35f).rotateY(yaw)
                            .translate(.125f, -.25f, .5f));
                    viewport.setCamera(1_000_000.25, -250.125, -3_000_000.75);
                    viewport.setScreenSize(1920, 1080);
                    assertSame(viewport, viewport.update());
                    assertSame(pinnedPlanesField().get(viewport.frustum), viewport.frustumPlanes);
                    RefinementView snapshot = snapshot(viewport);
                    assertTrue(snapshot.usable(100));
                    for (int level = 0; level <= 4; level++) {
                        int sectionSpan = 2 << level;
                        int baseX = Math.floorDiv((int) Math.floor(viewport.cameraX / 16), sectionSpan)
                                * sectionSpan;
                        int baseY = Math.floorDiv((int) Math.floor(viewport.cameraY / 16), sectionSpan)
                                * sectionSpan;
                        int baseZ = Math.floorDiv((int) Math.floor(viewport.cameraZ / 16), sectionSpan)
                                * sectionSpan;
                        for (int x = -6; x <= 6; x += 3) {
                            for (int y = -6; y <= 6; y += 3) {
                                for (int z = -6; z <= 6; z += 3) {
                                    SectionPos origin = new SectionPos(baseX + x * sectionSpan,
                                            baseY + y * sectionSpan, baseZ + z * sectionSpan);
                                    boolean expected = testAab(viewport, level, origin);
                                    assertEquals(expected, snapshot.intersects(Level.values()[level], origin),
                                            "near=" + near + " fov=" + fov + " yaw=" + yaw
                                                    + " level=" + level + " origin=" + origin);
                                    if (expected) visible++; else outside++;
                                }
                            }
                        }
                    }
                }
            }
        }
        assertTrue(visible > 0);
        assertTrue(outside > 0);
        assertEquals(7500, visible + outside);
    }

    @Test
    void copiedSnapshotSurvivesViewportPlaneMutationAndNextUpdate() throws Exception {
        HeadlessViewport viewport = viewportWithoutGpuConstructor();
        viewport.setProjection(new Matrix4f().perspective(1.1f, 16f / 9f, 16, 48_000));
        viewport.setModelView(new Matrix4f().rotateY(.7f));
        viewport.setCamera(1_000_000.25, -250.125, -3_000_000.75).setScreenSize(1920, 1080).update();
        RefinementView snapshot = snapshot(viewport);
        List<RefinementView.Plane> frozen = List.copyOf(snapshot.planes());
        SectionPos origin = new SectionPos(62500, -16, -187510);
        boolean initial = snapshot.intersects(Level.L2, origin);
        viewport.frustumPlanes[0].set(0, 0, 0, Float.NaN);
        assertEquals(frozen, snapshot.planes());
        assertEquals(initial, snapshot.intersects(Level.L2, origin));
        viewport.setModelView(new Matrix4f().rotateY((float) Math.PI)).update();
        assertNotEquals(snapshot.planes(), snapshot(viewport).planes());
        assertEquals(frozen, snapshot.planes());
        assertEquals(initial, snapshot.intersects(Level.L2, origin));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.planes().clear());
    }

    private static boolean testAab(HeadlessViewport viewport, int level, SectionPos origin) {
        double x = origin.x() * 16.0 - viewport.cameraX;
        double y = origin.y() * 16.0 - viewport.cameraY;
        double z = origin.z() * 16.0 - viewport.cameraZ;
        double size = 32L << level;
        return viewport.frustum.testAab((float) x, (float) y, (float) z,
                (float) (x + size), (float) (y + size), (float) (z + size));
    }

    private static RefinementView snapshot(Viewport<?> viewport) {
        ArrayList<RefinementView.Plane> planes = new ArrayList<>();
        for (Vector4f plane : viewport.frustumPlanes) {
            planes.add(new RefinementView.Plane(plane.x, plane.y, plane.z, plane.w));
        }
        return new RefinementView(100, viewport.cameraX, viewport.cameraY, viewport.cameraZ, planes);
    }

    private static Field pinnedPlanesField() throws Exception {
        Field field = Viewport.class.getDeclaredField("planesField");
        field.setAccessible(true);
        return (Field) field.get(null);
    }

    private static HeadlessViewport viewportWithoutGpuConstructor() throws Exception {
        // Viewport eagerly constructs GPU shaders, samplers, and buffers. Only that GPU setup
        // and DepthFramebuffer.resize are substituted; update and JOML plane population are real.
        Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        HeadlessViewport viewport = (HeadlessViewport) ((Unsafe) unsafeField.get(null))
                .allocateInstance(HeadlessViewport.class);
        set(viewport, "MVP", new Matrix4f());
        set(viewport, "projection", new Matrix4f());
        set(viewport, "modelView", new Matrix4f());
        set(viewport, "section", new Vector3i());
        set(viewport, "innerTranslation", new Vector3f());
        set(viewport, "frustum", new FrustumIntersection());
        set(viewport, "frustumPlanes", pinnedPlanesField().get(viewport.frustum));
        set(viewport, "depthBoundingBuffer", mock(DepthFramebuffer.class));
        return viewport;
    }

    private static void set(Viewport<?> viewport, String name, Object value) throws Exception {
        Field field = Viewport.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(viewport, value);
    }

    private static final class HeadlessViewport extends Viewport<HeadlessViewport> {
        @Override
        public me.cortex.voxy.client.core.gl.GlBuffer getRenderList() {
            return null;
        }
    }
}
