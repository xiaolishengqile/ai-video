package com.stonewu.fusion.service.ai.pipeline;

public enum PipelineFailureCategory {
    TRANSIENT_RATE_LIMIT,
    TRANSIENT_TIMEOUT,
    TRANSIENT_NETWORK,
    TRANSIENT_PROVIDER,
    NON_RETRYABLE_REQUEST,
    NON_RETRYABLE_AUTH,
    BUSINESS_ERROR,
    CANCELLED,
    UNKNOWN
}
