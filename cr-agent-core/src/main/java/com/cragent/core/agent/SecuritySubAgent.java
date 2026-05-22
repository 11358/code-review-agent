package com.cragent.core.agent;

import com.cragent.core.model.ReviewCategory;
import com.cragent.core.model.ReviewFinding;
import com.cragent.core.model.Severity;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class SecuritySubAgent implements SubAgent {

    private static final Logger log = LoggerFactory.getLogger(SecuritySubAgent.class);

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;

    private static final String SYSTEM_PROMPT = """
            You are a Security Code Review Specialist for Java code. Your expertise is OWASP Top 10 and Java security.

            Analyze the provided git diff for security vulnerabilities. Focus ONLY on security.

            REVIEW FOCUS:
            1. SQL Injection: String concatenation in SQL, raw JDBC Statement, MyBatis ${} interpolation, unsafe JPA @Query with concatenated parameters
            2. XSS: Unescaped user input in HTML/template output, disabling auto-escaping
            3. Path Traversal: File operations using user-controlled paths without sanitization
            4. Command Injection: Runtime.exec(), ProcessBuilder with unsanitized input
            5. Sensitive Data Exposure: Passwords, tokens, PII, secrets in logs, error messages, or serialized output
            6. Authentication/Authorization: Missing @PreAuthorize, insecure direct object references
            7. Insecure Deserialization: ObjectInputStream.readObject(), unsafe deserialization of untrusted data
            8. XXE: XML parsing without XXE protection

            OUTPUT RULES:
            - Return findings as a valid JSON array only. No markdown, no explanation outside the array.
            - CRITICAL = exploitable vulnerability with clear business impact
            - WARNING = potential security concern or defense-in-depth gap
            - INFO = security hardening suggestion
            - Every finding MUST include a specific, actionable fix suggestion
            - If no security issues found, return []
            - Use the dimension field set to "SECURITY" for every finding
            - Use lowercase-kebab-case for category: sql-injection, xss, path-traversal, command-injection, sensitive-data-exposure, insecure-deserialization, auth-bypass
            """;

    public SecuritySubAgent(ChatClient.Builder chatClientBuilder, ObjectMapper objectMapper) {
        this.chatClient = chatClientBuilder
                .defaultSystem(SYSTEM_PROMPT)
                .build();
        this.objectMapper = objectMapper;
    }

    @Override
    public String getDimensionName() {
        return "SECURITY";
    }

    @Override
    public List<ReviewFinding> review(String diffContent, List<String> changedFilePaths) {
        String fileList = String.join("\n", changedFilePaths);

        try {
            String response = chatClient.prompt()
                    .user(u -> u.text("""
                            Review the following git diff for SECURITY vulnerabilities only.

                            Changed files:
                            {files}

                            Diff content:
                            {diff}

                            Return your findings as a JSON array.
                            """)
                            .param("files", fileList)
                            .param("diff", diffContent))
                    .call()
                    .content();

            return parseFindings(response);
        } catch (Exception e) {
            log.error("Error during security review: {}", e.getMessage(), e);
            return List.of();
        }
    }

    List<ReviewFinding> parseFindings(String response) {
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
                f.setCategory(ReviewCategory.fromString(getString(item, "category")));
                f.setDimension("SECURITY");
                f.setExplanation(getString(item, "explanation"));
                f.setSuggestion(getString(item, "suggestion"));
                findings.add(f);
            }
            return findings;
        } catch (Exception e) {
            log.warn("Failed to parse security review JSON: {}", e.getMessage());
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
