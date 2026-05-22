package com.cragent.core.agent;

import com.cragent.core.model.ReviewFinding;
import com.cragent.core.model.Severity;
import com.cragent.core.model.ReviewCategory;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public abstract class AbstractSubAgent implements SubAgent {

    protected final Logger log = LoggerFactory.getLogger(getClass());
    protected final ChatClient chatClient;
    protected final ObjectMapper objectMapper;

    protected AbstractSubAgent(ChatClient.Builder chatClientBuilder, ObjectMapper objectMapper, String systemPrompt) {
        this.chatClient = chatClientBuilder.defaultSystem(systemPrompt).build();
        this.objectMapper = objectMapper;
    }

    protected List<ReviewFinding> doReview(String diffContent, List<String> changedFilePaths, String dimension) {
        String fileList = String.join("\n", changedFilePaths);
        try {
            String response = chatClient.prompt()
                    .user(u -> u.text("""
                            Review the following git diff for {dimension} issues only.

                            Changed files:
                            {files}

                            Diff content:
                            {diff}

                            Return your findings as a JSON array.
                            """)
                            .param("dimension", dimension)
                            .param("files", fileList)
                            .param("diff", diffContent))
                    .call()
                    .content();

            return parseFindings(response, dimension);
        } catch (Exception e) {
            log.error("Error during {} review: {}", dimension, e.getMessage(), e);
            return List.of();
        }
    }

    List<ReviewFinding> parseFindings(String response, String dimension) {
        if (response == null || response.isBlank()) return List.of();
        try {
            String json = extractJson(response);
            List<Map<String, Object>> raw = objectMapper.readValue(json,
                    new TypeReference<List<Map<String, Object>>>() {});
            List<ReviewFinding> findings = new ArrayList<>();
            for (Map<String, Object> item : raw) {
                ReviewFinding f = new ReviewFinding();
                f.setFile(getString(item, "file"));
                f.setLineStart(getInt(item, "lineStart"));
                f.setLineEnd(getInt(item, "lineEnd"));
                f.setSeverity(Severity.fromString(getString(item, "severity")));
                String rawCategory = getString(item, "category");
                f.setCategory(ReviewCategory.fromString(rawCategory));
                log.debug("Parsed category '{}' -> {}", rawCategory, f.getCategory());
                f.setDimension(dimension);
                f.setExplanation(getString(item, "explanation"));
                f.setSuggestion(getString(item, "suggestion"));
                findings.add(f);
            }
            return findings;
        } catch (Exception e) {
            log.warn("Failed to parse {} review JSON: {}", dimension, e.getMessage());
            log.debug("Raw response: {}", response);
            return List.of();
        }
    }

    private String extractJson(String response) {
        String trimmed = response.trim();
        int start = trimmed.indexOf('[');
        int end = trimmed.lastIndexOf(']');
        if (start >= 0 && end > start) return trimmed.substring(start, end + 1);
        return trimmed;
    }

    private String getString(Map<String, Object> map, String key) {
        Object v = map.getOrDefault(key, "");
        return v != null ? v.toString() : "";
    }

    private int getInt(Map<String, Object> map, String key) {
        Object v = map.getOrDefault(key, 0);
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(v.toString()); } catch (NumberFormatException e) { return 0; }
    }
}
