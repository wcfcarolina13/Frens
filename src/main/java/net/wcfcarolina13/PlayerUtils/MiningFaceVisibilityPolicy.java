package net.wcfcarolina13.PlayerUtils;

import java.util.ArrayList;
import java.util.List;

/** Points just inside a target's visible faces. Every point still requires a collision raycast. */
public final class MiningFaceVisibilityPolicy {
    private MiningFaceVisibilityPolicy() {}
    public record Point(double x, double y, double z) {}

    public static List<Point> samples(int x, int y, int z, double eyeX, double eyeY, double eyeZ) {
        List<Point> points = new ArrayList<>();
        points.add(new Point(x + .5, y + .999, z + .5));
        if (eyeX < x) points.add(new Point(x + .001, y + .5, z + .5));
        else if (eyeX > x + 1) points.add(new Point(x + .999, y + .5, z + .5));
        if (eyeZ < z) points.add(new Point(x + .5, y + .5, z + .001));
        else if (eyeZ > z + 1) points.add(new Point(x + .5, y + .5, z + .999));
        // At a deep ditch's lip only the far part of the top face may be visible over the bank.
        for (double dx : new double[]{.001, .999}) {
            for (double dz : new double[]{.001, .999}) {
                points.add(new Point(x + dx, y + .999, z + dz));
            }
        }
        points.add(new Point(x + .5, y + .5, z + .5));
        return points;
    }
}
