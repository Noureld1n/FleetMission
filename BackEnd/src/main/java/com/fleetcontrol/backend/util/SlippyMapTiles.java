package com.fleetcontrol.backend.util;

import com.fleetcontrol.backend.dto.message.MapSocketMessage.Viewport;
import java.util.LinkedHashSet;
import java.util.Set;

/** Converts validated geographic bounds to a bounded set of fixed-zoom slippy map tiles. */
public final class SlippyMapTiles {
    public static final int MAX_TILES = 512;
    private static final double MAX_LATITUDE = 85.05112878;
    private SlippyMapTiles() {}

    public static Set<String> forViewport(Viewport viewport, int fixedZoom) {
        if (viewport == null || !Double.isFinite(viewport.west()) || !Double.isFinite(viewport.east())
                || !Double.isFinite(viewport.south()) || !Double.isFinite(viewport.north())
                || viewport.west() < -180 || viewport.west() > 180 || viewport.east() < -180 || viewport.east() > 180
                || viewport.south() < -90 || viewport.north() > 90 || viewport.south() >= viewport.north()
                || viewport.zoom() < 0 || viewport.zoom() > 22 || viewport.viewportVersion() < 0) {
            throw new IllegalArgumentException("Invalid viewport bounds, zoom, or viewportVersion.");
        }
        int z = Math.min(fixedZoom, viewport.zoom());
        int n = 1 << z;
        int y0 = y(viewport.north(), n), y1 = y(viewport.south(), n);
        int x0 = x(viewport.west(), n), x1 = x(viewport.east(), n);
        Set<String> tiles = new LinkedHashSet<>();
        int x = x0;
        while (true) {
            for (int ty = y0; ty <= y1; ty++) {
                tiles.add(x + ":" + ty);
                if (tiles.size() > MAX_TILES) throw new IllegalArgumentException("Viewport covers too many map tiles.");
            }
            if (x == x1) break;
            x = (x + 1) % n;
        }
        return Set.copyOf(tiles);
    }
    private static int x(double lon, int n) { return Math.min(n - 1, Math.max(0, (int)Math.floor((lon + 180) / 360 * n))); }
    private static int y(double lat, int n) {
        double safe = Math.max(-MAX_LATITUDE, Math.min(MAX_LATITUDE, lat));
        double rad = Math.toRadians(safe);
        return Math.min(n - 1, Math.max(0, (int)Math.floor((1 - Math.log(Math.tan(rad) + 1 / Math.cos(rad)) / Math.PI) / 2 * n)));
    }
}
