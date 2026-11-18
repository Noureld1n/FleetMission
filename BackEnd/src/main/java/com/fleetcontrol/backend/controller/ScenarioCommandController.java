package com.fleetcontrol.backend.controller;

import com.fleetcontrol.backend.dto.response.ScenarioCommandResponse;
import com.fleetcontrol.backend.service.ScenarioService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping(path="/ingest/v1/commands",produces=MediaType.APPLICATION_JSON_VALUE)
public class ScenarioCommandController {
    private final ScenarioService service;
    public ScenarioCommandController(ScenarioService service){this.service=service;}
    @GetMapping public Mono<List<ScenarioCommandResponse>> poll(@RequestParam(defaultValue="20") int limit){
        return service.pollCommands(limit);
    }
    @PostMapping("/{id}/acknowledge")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> acknowledge(@PathVariable UUID id){return service.acknowledge(id);}
}
