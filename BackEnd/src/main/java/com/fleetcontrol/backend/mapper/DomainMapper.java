package com.fleetcontrol.backend.mapper;

import com.fleetcontrol.backend.dto.request.CreateDistrictRequest;
import com.fleetcontrol.backend.dto.request.DashboardRequest;
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
}
