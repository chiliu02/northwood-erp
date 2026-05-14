package com.northwood.purchasing.api;

import com.northwood.purchasing.application.dto.SagaRowView;
import com.northwood.purchasing.application.saga.SagaConsoleQueryPort;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Read-only API for the Saga Console — purchasing's purchase-to-pay saga.
 * See sales-service's {@code SagaApiController} for the design rationale.
 */
@RestController
@RequestMapping("/api/sagas")
public class SagaApiController {

    private static final Logger log = LoggerFactory.getLogger(SagaApiController.class);

    private final SagaConsoleQueryPort sagas;
    private final List<SseEmitter> subscribers = new CopyOnWriteArrayList<>();
    private final Map<UUID, Long> seenVersion = new ConcurrentHashMap<>();

    public SagaApiController(SagaConsoleQueryPort sagas) {
        this.sagas = sagas;
    }

    @GetMapping
    public List<SagaRowView> list() {
        return sagas.listSagas();
    }

    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        SseEmitter emitter = new SseEmitter(0L);
        subscribers.add(emitter);
        emitter.onCompletion(() -> subscribers.remove(emitter));
        emitter.onTimeout(() -> subscribers.remove(emitter));
        emitter.onError(e -> subscribers.remove(emitter));
        try {
            emitter.send(SseEmitter.event().comment("connected"));
        } catch (Exception ignored) {
            subscribers.remove(emitter);
        }
        return emitter;
    }

    @Scheduled(fixedDelay = 1000)
    public void pump() {
        if (subscribers.isEmpty()) return;
        for (SagaRowView row : list()) {
            Long last = seenVersion.get(row.sagaId());
            if (last == null || last < row.version()) {
                seenVersion.put(row.sagaId(), row.version());
                broadcast(row);
            }
        }
    }

    private void broadcast(SagaRowView row) {
        for (SseEmitter emitter : subscribers) {
            try {
                emitter.send(SseEmitter.event().name("saga").data(row));
            } catch (Exception e) {
                log.debug("SSE subscriber dropped: {}", e.getMessage());
                subscribers.remove(emitter);
            }
        }
    }
}
