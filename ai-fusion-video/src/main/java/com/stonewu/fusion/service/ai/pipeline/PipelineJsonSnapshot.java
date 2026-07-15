package com.stonewu.fusion.service.ai.pipeline;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

@Component
public class PipelineJsonSnapshot {

    public static final int MAX_LENGTH = 64 * 1024;
    private static final Pattern JSON_CREDENTIAL = Pattern.compile(
            "(?i)(\\\"(?:authorization|api[_-]?key|x-api-key)\\\"\\s*:\\s*\\\")[^\\\"]*");
    private static final Pattern TEXT_CREDENTIAL = Pattern.compile(
            "(?i)((?:authorization|api[_-]?key|x-api-key)\\s*[:=]\\s*(?:bearer\\s+)?)[^\\s,;\\\"]+");

    private final ObjectMapper objectMapper;

    public PipelineJsonSnapshot(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String serialize(Object value) {
        try {
            return trim(objectMapper.writeValueAsString(value));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Pipeline 请求无法序列化", e);
        }
    }

    public <T> T deserialize(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Pipeline 请求无法恢复", e);
        }
    }

    public String trim(String json) {
        if (json == null) {
            return null;
        }
        String sanitized = sanitize(json);
        if (sanitized.length() <= MAX_LENGTH) {
            return sanitized;
        }

        int previewLength = Math.min(sanitized.length(), MAX_LENGTH / 2);
        while (previewLength > 0) {
            Map<String, Object> truncated = new LinkedHashMap<>();
            truncated.put("truncated", true);
            truncated.put("originalLength", sanitized.length());
            truncated.put("preview", sanitized.substring(0, previewLength));
            try {
                String result = objectMapper.writeValueAsString(truncated);
                if (result.length() <= MAX_LENGTH) {
                    return result;
                }
            } catch (JsonProcessingException e) {
                throw new IllegalArgumentException("Pipeline 快照无法裁剪", e);
            }
            previewLength /= 2;
        }
        return "{\"truncated\":true}";
    }

    private String sanitize(String value) {
        String sanitized = JSON_CREDENTIAL.matcher(value).replaceAll("$1[REDACTED]");
        return TEXT_CREDENTIAL.matcher(sanitized).replaceAll("$1[REDACTED]");
    }
}
