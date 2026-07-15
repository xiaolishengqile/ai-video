package com.stonewu.fusion.service.ai.pipeline;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Slf4j
public class PipelineRunLock {

    private static final String KEY_PREFIX = "fv:ai:pipeline:lock:";
    private static final Duration TTL = Duration.ofMinutes(2);
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('get', KEYS[1]) == ARGV[1] then
                return redis.call('del', KEYS[1])
            end
            return 0
            """, Long.class);
    private static final DefaultRedisScript<Long> RENEW_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('get', KEYS[1]) == ARGV[1] then
                return redis.call('pexpire', KEYS[1], ARGV[2])
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;
    private final Map<String, Lease> activeLeases = new ConcurrentHashMap<>();

    public PipelineRunLock(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public Optional<Lease> acquire(String runId) {
        return acquire(runId, UUID.randomUUID().toString());
    }

    public Optional<Lease> acquire(String runId, String owner) {
        Boolean acquired = redis.opsForValue().setIfAbsent(key(runId), owner, TTL);
        if (!Boolean.TRUE.equals(acquired)) {
            return Optional.empty();
        }
        Lease lease = new Lease(runId, owner);
        activeLeases.put(runId, lease);
        return Optional.of(lease);
    }

    public boolean release(String runId, String owner) {
        activeLeases.remove(runId, new Lease(runId, owner));
        Long released = redis.execute(RELEASE_SCRIPT, List.of(key(runId)), owner);
        return released != null && released > 0;
    }

    @Scheduled(fixedDelay = 30_000)
    void renewActiveLeases() {
        activeLeases.forEach((runId, lease) -> {
            try {
                Long renewed = redis.execute(
                        RENEW_SCRIPT,
                        List.of(key(runId)),
                        lease.owner(),
                        Long.toString(TTL.toMillis()));
                if (renewed == null || renewed == 0) {
                    activeLeases.remove(runId, lease);
                }
            } catch (RuntimeException error) {
                log.warn("Pipeline 锁续期失败: runId={}, message={}", runId, error.getMessage());
            }
        });
    }

    private String key(String runId) {
        return KEY_PREFIX + runId;
    }

    public record Lease(String runId, String owner) {
    }
}
