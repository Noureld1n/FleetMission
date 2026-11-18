package com.fleetcontrol.backend.mapper;

import com.fleetcontrol.backend.dto.request.CreateDistrictRequest;
import com.fleetcontrol.backend.dto.request.DashboardRequest;
import com.fleetcontrol.backend.dto.response.VehicleHistoryResponse;
import com.fleetcontrol.backend.dto.response.FleetMetricsResponse;
import com.fleetcontrol.backend.model.VehicleMinuteAggregate;
import com.fleetcontrol.backend.model.FleetMinuteAggregate;
import com.fleetcontrol.backend.model.WidgetType;
import java.util.stream.Collectors;

/** Pure conversions shared by Phase 4 orchestration and persistence. */
public final class DomainMapper {
    private DomainMapper() {}
    public static String toGeoJson(CreateDistrictRequest.Polygon polygon) {
        String rings=polygon.coordinates().stream().map(r->"["+r.stream()
                .map(pt->"["+pt.longitude()+","+pt.latitude()+"]").collect(Collectors.joining(","))+"]")
                .collect(Collectors.joining(","));
        return "{\"type\":\"Polygon\",\"coordinates\":["+rings+"]}";
    }
    public static String widgetType(DashboardRequest.Widget widget) {
        return WidgetType.valueOf(widget.type().trim().replace('-','_').toUpperCase()).value();
    }
    public static VehicleHistoryResponse.Point toVehicleHistoryPoint(VehicleMinuteAggregate aggregate) {
        return new VehicleHistoryResponse.Point(aggregate.bucket(),aggregate.batteryAverage(),aggregate.batteryMinimum(),
                aggregate.speedAverage(),aggregate.speedMaximum(),aggregate.sampleCount());
    }
    public static FleetMetricsResponse.Point toFleetMetricsPoint(FleetMinuteAggregate aggregate) {
        return new FleetMetricsResponse.Point(aggregate.bucket(),aggregate.batteryAverage(),aggregate.batteryMinimum(),aggregate.sampleCount());
    }
}
