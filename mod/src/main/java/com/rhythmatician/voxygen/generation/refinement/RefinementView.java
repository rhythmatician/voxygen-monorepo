package com.rhythmatician.voxygen.generation.refinement;

import java.util.List;
import com.rhythmatician.voxygen.semantic.Level;
import com.rhythmatician.voxygen.semantic.SectionPos;

/** Immutable camera-relative planes copied at the client rendering boundary. */
public record RefinementView(long capturedMillis, double cameraX, double cameraY,
                             double cameraZ, List<Plane> planes) {
    private static final long MAX_AGE_MILLIS = 1000;

    public record Plane(double x, double y, double z, double w) {}

    public RefinementView {
        planes = planes == null ? List.of() : List.copyOf(planes);
    }

    public boolean usable(long nowMillis) {
        if (nowMillis < capturedMillis || nowMillis - capturedMillis > MAX_AGE_MILLIS
                || !Double.isFinite(cameraX) || !Double.isFinite(cameraY)
                || !Double.isFinite(cameraZ) || planes.size() != 6) return false;
        for (Plane plane : planes) {
            if (!Double.isFinite(plane.x) || !Double.isFinite(plane.y)
                    || !Double.isFinite(plane.z) || !Double.isFinite(plane.w)
                    || Math.abs(plane.x) + Math.abs(plane.y) + Math.abs(plane.z) == 0) return false;
        }
        return true;
    }

    public boolean sameGeometry(RefinementView other) {
        return other != null && cameraX == other.cameraX && cameraY == other.cameraY
                && cameraZ == other.cameraZ && planes.equals(other.planes);
    }

    /** Reject only when every point of the represented parent is outside a plane. */
    public boolean intersects(Level level, SectionPos origin) {
        double minX = origin.x() * 16.0 - cameraX;
        double minY = origin.y() * 16.0 - cameraY;
        double minZ = origin.z() * 16.0 - cameraZ;
        double size = 32L << level.value();
        for (Plane plane : planes) {
            double x = plane.x >= 0 ? minX + size : minX;
            double y = plane.y >= 0 ? minY + size : minY;
            double z = plane.z >= 0 ? minZ + size : minZ;
            double distance = plane.x * x + plane.y * y + plane.z * z + plane.w;
            // Copied renderer planes have float precision. Retain uncertain boundary cases.
            double tolerance = 1e-5 * (1 + Math.abs(plane.x * x)
                    + Math.abs(plane.y * y) + Math.abs(plane.z * z) + Math.abs(plane.w));
            if (distance < -tolerance) return false;
        }
        return true;
    }
}
