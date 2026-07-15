package com.stonewu.fusion.service.ai.pipeline;

import com.stonewu.fusion.common.BusinessException;
import io.agentscope.core.model.exception.AuthenticationException;
import io.agentscope.core.model.exception.BadRequestException;
import io.agentscope.core.model.exception.RateLimitException;
import io.agentscope.core.model.transport.HttpTransportException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

class PipelineFailureClassifierTests {

    private final PipelineFailureClassifier classifier = new PipelineFailureClassifier();

    @Test
    void classifiesTransientFailures() {
        assertFailure(
                new RateLimitException("too many requests", "rate_limit", "{}"),
                PipelineFailureCategory.TRANSIENT_RATE_LIMIT,
                true);
        assertFailure(
                new TimeoutException("model timed out"),
                PipelineFailureCategory.TRANSIENT_TIMEOUT,
                true);
        assertFailure(
                new IOException("connection reset"),
                PipelineFailureCategory.TRANSIENT_NETWORK,
                true);
        assertFailure(
                new HttpTransportException("upstream unavailable", 503, "{}"),
                PipelineFailureCategory.TRANSIENT_PROVIDER,
                true);
    }

    @Test
    void classifiesNonRetryableFailures() {
        assertFailure(
                new BadRequestException("invalid parameter", "invalid_request", "{}"),
                PipelineFailureCategory.NON_RETRYABLE_REQUEST,
                false);
        assertFailure(
                new AuthenticationException("invalid key", "invalid_api_key", "{}"),
                PipelineFailureCategory.NON_RETRYABLE_AUTH,
                false);
        assertFailure(
                new BusinessException("episode number is invalid"),
                PipelineFailureCategory.BUSINESS_ERROR,
                false);
        assertFailure(
                new IllegalStateException("unexpected state"),
                PipelineFailureCategory.UNKNOWN,
                false);
    }

    @Test
    void treatsAuthorizationFailureWrappedInHttp429AsNonRetryable() {
        HttpTransportException error = new HttpTransportException(
                "HTTP request failed with status 429",
                429,
                "{\"error\":{\"message\":\"authorization failed\",\"code\":11210}}");

        assertFailure(error, PipelineFailureCategory.NON_RETRYABLE_AUTH, false);
    }

    @Test
    void preservesDeepestUsefulCauseAndSanitizesSecrets() {
        RuntimeException error = new RuntimeException(
                "Retries exhausted",
                new IOException("Authorization: Bearer sk-secret connection reset"));

        PipelineFailure failure = classifier.classify(error);

        assertThat(failure.category()).isEqualTo(PipelineFailureCategory.TRANSIENT_NETWORK);
        assertThat(failure.message()).contains("connection reset").doesNotContain("sk-secret");
    }

    private void assertFailure(Throwable error, PipelineFailureCategory category, boolean retryable) {
        PipelineFailure failure = classifier.classify(error);
        assertThat(failure.category()).isEqualTo(category);
        assertThat(failure.retryable()).isEqualTo(retryable);
    }
}
