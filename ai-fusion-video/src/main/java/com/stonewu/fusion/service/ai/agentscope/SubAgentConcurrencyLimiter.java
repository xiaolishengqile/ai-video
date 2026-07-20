package com.stonewu.fusion.service.ai.agentscope;

import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

@Component
public class SubAgentConcurrencyLimiter {

    private static final Map<String, Integer> LIMITS = Map.of(
            "episode_scene_writer", 3,
            "episode_storyboard_writer", 3,
            "match_storyboard_item_assets", 3);

    private final ConcurrentHashMap<String, Semaphore> semaphores = new ConcurrentHashMap<>();

    public <T> Mono<T> limit(String toolName, Mono<T> source) {
        Integer limit = LIMITS.get(toolName);
        if (limit == null || limit <= 0) {
            return source;
        }
        Semaphore semaphore = semaphores.computeIfAbsent(toolName, ignored -> new Semaphore(limit));
        return Mono.fromCallable(() -> {
            semaphore.acquire();
            return semaphore;
        }).subscribeOn(Schedulers.boundedElastic()).flatMap(acquired ->
                source.doFinally(signalType -> acquired.release()));
    }
}
