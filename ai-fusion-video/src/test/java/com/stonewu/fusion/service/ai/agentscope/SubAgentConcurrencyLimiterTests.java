package com.stonewu.fusion.service.ai.agentscope;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class SubAgentConcurrencyLimiterTests {

    @Test
    void pipelineSubAgentsAllowOnlyThreeConcurrentCalls() throws Exception {
        assertAllowsOnlyThreeConcurrentCalls("episode_scene_writer");
        assertAllowsOnlyThreeConcurrentCalls("episode_storyboard_writer");
        assertAllowsOnlyThreeConcurrentCalls("match_storyboard_item_assets");
    }

    @Test
    void actionMaterialSubAgentAllowsOnlyFiveConcurrentCalls() throws Exception {
        SubAgentConcurrencyLimiter limiter = new SubAgentConcurrencyLimiter();
        CountDownLatch firstFiveStarted = new CountDownLatch(5);
        CountDownLatch releaseCalls = new CountDownLatch(1);
        AtomicInteger running = new AtomicInteger();
        AtomicInteger maxRunning = new AtomicInteger();

        List<Mono<Integer>> calls = IntStream.range(0, 6)
                .mapToObj(index -> limiter.limit("generate_storyboard_action_material", Mono.fromCallable(() -> {
                    int current = running.incrementAndGet();
                    maxRunning.accumulateAndGet(current, Math::max);
                    firstFiveStarted.countDown();
                    releaseCalls.await(2, TimeUnit.SECONDS);
                    running.decrementAndGet();
                    return index;
                }).subscribeOn(Schedulers.boundedElastic())))
                .toList();

        var result = Flux.merge(calls).collectList().toFuture();

        assertThat(firstFiveStarted.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(maxRunning.get()).isEqualTo(5);

        releaseCalls.countDown();
        assertThat(result.get(2, TimeUnit.SECONDS)).hasSize(6);
    }

    private void assertAllowsOnlyThreeConcurrentCalls(String toolName) throws Exception {
        SubAgentConcurrencyLimiter limiter = new SubAgentConcurrencyLimiter();
        CountDownLatch firstThreeStarted = new CountDownLatch(3);
        CountDownLatch releaseCalls = new CountDownLatch(1);
        AtomicInteger running = new AtomicInteger();
        AtomicInteger maxRunning = new AtomicInteger();

        List<Mono<Integer>> calls = IntStream.range(0, 4)
                .mapToObj(index -> limiter.limit(toolName, Mono.fromCallable(() -> {
                    int current = running.incrementAndGet();
                    maxRunning.accumulateAndGet(current, Math::max);
                    firstThreeStarted.countDown();
                    releaseCalls.await(2, TimeUnit.SECONDS);
                    running.decrementAndGet();
                    return index;
                }).subscribeOn(Schedulers.boundedElastic())))
                .toList();

        var result = Flux.merge(calls).collectList().toFuture();

        assertThat(firstThreeStarted.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(maxRunning.get()).isEqualTo(3);

        releaseCalls.countDown();
        assertThat(result.get(2, TimeUnit.SECONDS)).hasSize(4);
    }
}
