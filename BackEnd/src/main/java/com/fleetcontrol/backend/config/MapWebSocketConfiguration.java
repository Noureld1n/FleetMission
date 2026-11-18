package com.fleetcontrol.backend.config;

import com.fleetcontrol.backend.controller.MapWebSocketHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import java.util.Map;

@Configuration
public class MapWebSocketConfiguration {
    @Bean
    HandlerMapping mapWebSocketMapping(MapWebSocketHandler handler) {
        SimpleUrlHandlerMapping mapping = new SimpleUrlHandlerMapping();
        mapping.setUrlMap(Map.of("/ws/map", handler));
        mapping.setOrder(-1);
        return mapping;
    }
}
