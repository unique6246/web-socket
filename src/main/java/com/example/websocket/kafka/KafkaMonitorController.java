package com.example.websocket.kafka;

import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * REST + SSE endpoints for the Kafka Consumer Monitor page.
 * Restricted to ADMIN role only.
 */
@RestController
@RequestMapping("/api/kafka-monitor")
@PreAuthorize("hasRole('ADMIN')")
public class KafkaMonitorController {

    private final KafkaMonitorService monitorService;

    public KafkaMonitorController(KafkaMonitorService monitorService) {
        this.monitorService = monitorService;
    }

    /**
     * Returns the last 200 recorded events as JSON (page-load replay).
     */
    @GetMapping("/events")
    public List<KafkaMonitorService.KafkaEvent> getRecentEvents(
            @RequestParam(defaultValue = "200") int limit) {
        return monitorService.getRecent(Math.min(limit, 200));
    }

    /**
     * SSE stream — browser subscribes once and receives every Kafka event
     * in real time as it is processed by the consumers.
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return monitorService.subscribe();
    }
}
