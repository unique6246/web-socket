package com.example.websocket.kafka;

import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Intercepts every consumer invocation and:
 *  1. Records a KafkaEvent entry in an in-memory ring buffer (last 200 events)
 *  2. Pushes the event to all connected SSE clients (the kafka-monitor page)
 *
 * Designed to be zero-overhead when no SSE clients are watching:
 *  the ring buffer write is a CAS + array assignment, the SSE push is
 *  a no-op when the emitter list is empty.
 */
@Service
public class KafkaMonitorService {

    private static final int RING_SIZE = 200;
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private final AtomicLong sequence = new AtomicLong(0);
    /** Ring buffer — thread-safe via volatile array slot writes */
    private final KafkaEvent[] ring = new KafkaEvent[RING_SIZE];
    /** Live SSE clients */
    private final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    // ── Consumer group display names ──────────────────────────────────────────
    public static final String GROUP_BROADCAST    = "ws-broadcast";
    public static final String GROUP_PERSISTENCE  = "chat-persistence";
    public static final String GROUP_PUSH         = "push-notifications";

    /**
     * Called by each @KafkaListener method the moment an event arrives.
     *
     * @param consumerGroup  one of the GROUP_* constants
     * @param event          the deserialized ChatMessageEvent
     * @param actionTaken    human-readable description of what this consumer did
     * @param skipped        true if this consumer decided to skip the event (e.g. persistence skips TYPING)
     */
    public void record(String consumerGroup, ChatMessageEvent event,
                       String actionTaken, boolean skipped) {
        long seq = sequence.incrementAndGet();
        KafkaEvent ke = new KafkaEvent(
                seq,
                LocalDateTime.now().format(FMT),
                consumerGroup,
                event.getEventType() != null ? event.getEventType().name() : "UNKNOWN",
                event.getSender()   != null ? event.getSender()   : "",
                event.getRoomName() != null ? event.getRoomName() : "",
                truncate(event.getContent(),  80),
                truncate(event.getFileName(), 40),
                event.getPresenceStatus() != null ? event.getPresenceStatus() : "",
                event.getMessageId() != null ? event.getMessageId().toString() : "",
                actionTaken,
                skipped,
                event.getOriginServerId() != null ? event.getOriginServerId() : ""
        );

        ring[(int)(seq % RING_SIZE)] = ke;
        push(ke);
    }

    /** Returns last N events from the ring buffer, oldest first */
    public List<KafkaEvent> getRecent(int n) {
        long current = sequence.get();
        int  count   = (int) Math.min(current, Math.min(n, RING_SIZE));
        List<KafkaEvent> out = new ArrayList<>(count);
        for (long i = current - count + 1; i <= current; i++) {
            KafkaEvent e = ring[(int)(i % RING_SIZE)];
            if (e != null) out.add(e);
        }
        return out;
    }

    /** Register an SSE emitter from the browser */
    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(0L); // no timeout
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(()    -> emitters.remove(emitter));
        emitter.onError(e       -> emitters.remove(emitter));
        return emitter;
    }

    private void push(KafkaEvent ke) {
        if (emitters.isEmpty()) return;
        String json = toJson(ke);
        List<SseEmitter> dead = new ArrayList<>();
        for (SseEmitter em : emitters) {
            try {
                em.send(SseEmitter.event().name("kafka-event").data(json));
            } catch (Exception e) {
                dead.add(em);
            }
        }
        emitters.removeAll(dead);
    }

    private String toJson(KafkaEvent k) {
        return String.format(
            "{\"seq\":%d,\"time\":\"%s\",\"group\":\"%s\",\"eventType\":\"%s\"," +
            "\"sender\":\"%s\",\"room\":\"%s\",\"content\":\"%s\",\"file\":\"%s\"," +
            "\"presence\":\"%s\",\"messageId\":\"%s\",\"action\":\"%s\"," +
            "\"skipped\":%b,\"server\":\"%s\"}",
            k.seq(), k.time(), esc(k.group()), esc(k.eventType()),
            esc(k.sender()), esc(k.room()), esc(k.content()), esc(k.file()),
            esc(k.presence()), esc(k.messageId()), esc(k.action()),
            k.skipped(), esc(k.server())
        );
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\","\\\\").replace("\"","\\\"").replace("\n"," ").replace("\r","");
    }
    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    /** Immutable value object for a single recorded Kafka event */
    public record KafkaEvent(
            long   seq,
            String time,
            String group,
            String eventType,
            String sender,
            String room,
            String content,
            String file,
            String presence,
            String messageId,
            String action,
            boolean skipped,
            String server
    ) {}
}
