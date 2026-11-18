package com.fleetcontrol.backend.controller;

import com.fleetcontrol.backend.dto.request.TelemetryBatchRequest;
import com.fleetcontrol.backend.dto.response.TelemetryAcceptedResponse;
import com.fleetcontrol.backend.service.TelemetryIngestionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping(path="/ingest/v1",produces=MediaType.APPLICATION_JSON_VALUE)
public class TelemetryIngestionController {
    private final TelemetryIngestionService service;
    public TelemetryIngestionController(TelemetryIngestionService service) { this.service=service; }
    @PostMapping(path="/telemetry",consumes=MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<TelemetryAcceptedResponse> telemetry(@Valid @RequestBody TelemetryBatchRequest request) {
        return service.accept(request);
    }
}
