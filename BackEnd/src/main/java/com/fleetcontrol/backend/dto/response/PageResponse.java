package com.fleetcontrol.backend.dto.response;

import java.util.List;

public record PageResponse<T>(List<T> items, String nextCursor) {
    public PageResponse { items = List.copyOf(items); }
}
