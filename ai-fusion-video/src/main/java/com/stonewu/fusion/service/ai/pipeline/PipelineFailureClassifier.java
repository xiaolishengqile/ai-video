package com.stonewu.fusion.service.ai.pipeline;

import com.stonewu.fusion.common.BusinessException;
import io.agentscope.core.model.exception.AuthenticationException;
import io.agentscope.core.model.exception.BadRequestException;
import io.agentscope.core.model.exception.OpenAIException;
import io.agentscope.core.model.exception.PermissionDeniedException;
import io.agentscope.core.model.exception.RateLimitException;
import io.agentscope.core.model.exception.UnprocessableEntityException;
import io.agentscope.core.model.transport.HttpTransportException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

@Component
public class PipelineFailureClassifier {

    private static final int MAX_MESSAGE_LENGTH = 2000;
    private static final Pattern AUTHORIZATION = Pattern.compile(
            "(?i)(authorization\\s*[:=]\\s*bearer\\s+)[^\\s,;]+"
    );
    private static final Pattern API_KEY = Pattern.compile(
            "(?i)((?:api[_-]?key|x-api-key)\\s*[:=]\\s*)[^\\s,;]+"
    );
    private static final Pattern OPENAI_KEY = Pattern.compile("\\bsk-[A-Za-z0-9_-]+\\b");

    public PipelineFailure classify(Throwable error) {
        List<Throwable> causes = causeChain(error);
        Throwable matched = find(causes, RateLimitException.class);
        PipelineFailureCategory category;

        if (matched != null || hasStatus(causes, 429)) {
            category = PipelineFailureCategory.TRANSIENT_RATE_LIMIT;
        } else if ((matched = findTimeout(causes)) != null) {
            category = PipelineFailureCategory.TRANSIENT_TIMEOUT;
        } else if ((matched = findRetryableTransport(causes)) != null) {
            category = PipelineFailureCategory.TRANSIENT_PROVIDER;
        } else if ((matched = find(causes, IOException.class)) != null) {
            category = PipelineFailureCategory.TRANSIENT_NETWORK;
        } else if ((matched = findAuth(causes)) != null) {
            category = PipelineFailureCategory.NON_RETRYABLE_AUTH;
        } else if ((matched = findRequest(causes)) != null) {
            category = PipelineFailureCategory.NON_RETRYABLE_REQUEST;
        } else if ((matched = find(causes, BusinessException.class)) != null) {
            category = PipelineFailureCategory.BUSINESS_ERROR;
        } else if ((matched = find(causes, CancellationException.class)) != null) {
            category = PipelineFailureCategory.CANCELLED;
        } else {
            matched = error;
            category = PipelineFailureCategory.UNKNOWN;
        }

        Throwable root = deepestUsefulCause(causes, matched);
        return new PipelineFailure(category, extractCode(matched), sanitize(root), isTransient(category));
    }

    private List<Throwable> causeChain(Throwable error) {
        List<Throwable> causes = new ArrayList<>();
        Throwable current = error;
        while (current != null && !causes.contains(current)) {
            causes.add(current);
            current = current.getCause();
        }
        return causes;
    }

    private Throwable findTimeout(List<Throwable> causes) {
        for (Throwable cause : causes) {
            if (cause instanceof TimeoutException
                    || cause instanceof SocketTimeoutException
                    || cause instanceof HttpTimeoutException) {
                return cause;
            }
        }
        return null;
    }

    private Throwable findRetryableTransport(List<Throwable> causes) {
        for (Throwable cause : causes) {
            if (cause instanceof HttpTransportException transport && transport.isRetryable()) {
                return cause;
            }
            if (cause instanceof OpenAIException openAIException
                    && openAIException.getStatusCode() != null
                    && openAIException.getStatusCode() >= 500) {
                return cause;
            }
        }
        return null;
    }

    private Throwable findAuth(List<Throwable> causes) {
        for (Throwable cause : causes) {
            if (cause instanceof AuthenticationException || cause instanceof PermissionDeniedException) {
                return cause;
            }
            Integer status = status(cause);
            if (Integer.valueOf(401).equals(status) || Integer.valueOf(403).equals(status)) {
                return cause;
            }
        }
        return null;
    }

    private Throwable findRequest(List<Throwable> causes) {
        for (Throwable cause : causes) {
            if (cause instanceof BadRequestException || cause instanceof UnprocessableEntityException) {
                return cause;
            }
            Integer status = status(cause);
            if (status != null && status >= 400 && status < 500 && status != 429) {
                return cause;
            }
        }
        return null;
    }

    private <T extends Throwable> Throwable find(List<Throwable> causes, Class<T> type) {
        return causes.stream().filter(type::isInstance).findFirst().orElse(null);
    }

    private boolean hasStatus(List<Throwable> causes, int expected) {
        return causes.stream().anyMatch(cause -> Integer.valueOf(expected).equals(status(cause)));
    }

    private Integer status(Throwable error) {
        if (error instanceof OpenAIException openAIException) {
            return openAIException.getStatusCode();
        }
        if (error instanceof HttpTransportException transportException) {
            return transportException.getStatusCode();
        }
        return null;
    }

    private Throwable deepestUsefulCause(List<Throwable> causes, Throwable fallback) {
        for (int index = causes.size() - 1; index >= 0; index--) {
            Throwable cause = causes.get(index);
            if (cause.getMessage() != null && !cause.getMessage().isBlank()) {
                return cause;
            }
        }
        return fallback;
    }

    private String extractCode(Throwable error) {
        if (error instanceof OpenAIException openAIException) {
            if (openAIException.getErrorCode() != null && !openAIException.getErrorCode().isBlank()) {
                return openAIException.getErrorCode();
            }
            return openAIException.getStatusCode() == null ? null : openAIException.getStatusCode().toString();
        }
        if (error instanceof HttpTransportException transportException) {
            return transportException.getStatusCode() == null ? null : transportException.getStatusCode().toString();
        }
        if (error instanceof BusinessException businessException) {
            return businessException.getCode() == null ? null : businessException.getCode().toString();
        }
        return null;
    }

    private String sanitize(Throwable error) {
        String message = error == null || error.getMessage() == null
                ? "Unknown pipeline failure"
                : error.getMessage();
        message = AUTHORIZATION.matcher(message).replaceAll("$1[REDACTED]");
        message = API_KEY.matcher(message).replaceAll("$1[REDACTED]");
        message = OPENAI_KEY.matcher(message).replaceAll("[REDACTED]");
        return message.length() <= MAX_MESSAGE_LENGTH ? message : message.substring(0, MAX_MESSAGE_LENGTH);
    }

    private boolean isTransient(PipelineFailureCategory category) {
        return category == PipelineFailureCategory.TRANSIENT_RATE_LIMIT
                || category == PipelineFailureCategory.TRANSIENT_TIMEOUT
                || category == PipelineFailureCategory.TRANSIENT_NETWORK
                || category == PipelineFailureCategory.TRANSIENT_PROVIDER;
    }
}
