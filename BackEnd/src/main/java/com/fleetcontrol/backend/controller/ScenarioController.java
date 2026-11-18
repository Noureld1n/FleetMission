package com.fleetcontrol.backend.controller;

import com.fleetcontrol.backend.dto.request.OutageScenarioRequest;
import com.fleetcontrol.backend.dto.response.OutageScenarioResponse;
import com.fleetcontrol.backend.service.ScenarioService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping(path="/api/scenarios",produces=MediaType.APPLICATION_JSON_VALUE)
public class ScenarioController {
    private final ScenarioService service;
    public ScenarioController(ScenarioService service){this.service=service;}
    @PostMapping(path="/outage",consumes=MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<OutageScenarioResponse> outage(@Valid @RequestBody OutageScenarioRequest request){
        return service.triggerOutage(request);
    }
}
