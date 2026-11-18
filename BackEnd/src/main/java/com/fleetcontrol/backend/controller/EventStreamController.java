package com.fleetcontrol.backend.controller;

import com.fleetcontrol.backend.service.EventStreamService;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/stream")
public class EventStreamController {
    private final EventStreamService service;
    public EventStreamController(EventStreamService service) { this.service=service; }

    @GetMapping(path="/events",produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> events(@RequestHeader(name="Last-Event-ID",required=false) String lastEventId) {
        return service.stream(lastEventId);
    }
}
