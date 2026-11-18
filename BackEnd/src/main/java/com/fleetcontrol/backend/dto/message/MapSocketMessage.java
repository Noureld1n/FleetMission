package com.fleetcontrol.backend.dto.message;

import com.fleetcontrol.backend.model.TelemetryVehicle;
import java.util.List;
import java.util.Map;

/** Typed JSON protocol for the live map socket. */
public final class MapSocketMessage {
    private MapSocketMessage() {}
    public record Inbound(String type, String token, Viewport viewport) {}
    public record Viewport(double west, double south, double east, double north, int zoom, long viewportVersion) {}
    public record Outbound(String type, String code, String message, Long viewportVersion,
                           List<TelemetryVehicle> vehicles, Map<String, Long> clusters) {
        public static Outbound ready() { return new Outbound("ready", null, null, null, null, null); }
        public static Outbound error(String code, String message) { return new Outbound("error", code, message, null, null, null); }
        public static Outbound snapshot(long version, List<TelemetryVehicle> vehicles) { return new Outbound("snapshot", null, null, version, vehicles, null); }
        public static Outbound delta(long version, List<TelemetryVehicle> vehicles) { return new Outbound("delta", null, null, version, vehicles, null); }
        public static Outbound clusters(long version, Map<String, Long> counts) { return new Outbound("clusters", null, null, version, null, counts); }
    }
}
