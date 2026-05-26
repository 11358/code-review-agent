package com.cragent.core.agent;

import com.cragent.core.model.ReviewCategory;
import com.cragent.core.model.ReviewFinding;
import com.cragent.core.model.Severity;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 全能型 Agent：单 Prompt 覆盖安全/Bug/性能三个维度。
 * 主流水线不使用，保留用于独立测试或对比实验。
 */
@Component
public class GeneralSubAgent implements SubAgent {

    private static final Logger log = LoggerFactory.getLogger(GeneralSubAgent.class);

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;

    private static final String SYSTEM_PROMPT = """
            You are a Senior Java Code Reviewer. Analyze the provided git diff for issues across three dimensions:
            SECURITY, BUGS, and PERFORMANCE.

            ## SECURITY
            - SQL Injection: String concatenation in SQL, raw JDBC Statement, MyBatis ${} interpolation
            - XSS: Unescaped user input in web output
            - Path Traversal: File operations using user-controlled paths
            - Command Injection: Runtime.exec() or ProcessBuilder with unsanitized input
            - Sensitive Data: Passwords, tokens, PII in logs or error messages
            - Insecure Deserialization: ObjectInputStream without validation
            - Missing auth checks: No @PreAuthorize on sensitive endpoints

            ## BUGS
            - Null Pointer: Missing null checks, unsafe Optional usage
            - Race Conditions: Shared state without synchronization
            - Resource Leaks: Unclosed streams, connections, ResultSets
            - Off-by-One: Loop boundary errors, index mistakes
            - Error Handling: Swallowed exceptions, empty catch blocks
            - Logic Errors: Incorrect conditionals, wrong operator precedence

            ## PERFORMANCE
            - N+1 Queries: Database calls inside loops, missing JOIN FETCH
            - Excessive Allocation: Object creation in hot paths, String concat in loops
            - Inefficient Collections: ArrayList where HashSet needed
            - Missing Caching: Repeated expensive computations
            - Sync Bottlenecks: Contended locks, large synchronized blocks
            - Unbuffered I/O: FileStream without Buffered wrappers

            OUTPUT RULES:
            - Return findings as a JSON array only. No markdown, no explanation outside the array.
            - Each finding: {"file": "...", "lineStart": N, "lineEnd": N, "severity": "CRITICAL|WARNING|INFO", "category": "sql-injection|npe|n-plus-one|...", "dimension": "SECURITY|BUGS|PERFORMANCE", "explanation": "...", "suggestion": "..."}
            - CRITICAL = exploitable vulnerability or definite runtime error
            - WARNING = potential issue or best practice violation
            - INFO = minor improvement suggestion
            - If no issues found, return []
            """;

    public GeneralSubAgent(ChatClient.Builder chatClientBuilder, ObjectMapper objectMapper) {
        this.chatClient = chatClientBuilder
                .defaultSystem(SYSTEM_PROMPT)
                .build();
        this.objectMapper = objectMapper;
    }

    @Override
    public String getDimensionName() {
        return "GENERAL";
    }

    @Override
    public List<ReviewFinding> review(String diffContent, List<String> changedFilePaths) {
        String fileList = String.join("\n", changedFilePaths);

        try {
            String response = chatClient.prompt()
                    .user(u -> u.text("""
                            Review the following git diff for issues.

                            Changed files:
                            {files}

                            Diff content:
                            {diff}

                            Return findings as a JSON array.
                            """)
                            .param("files", fileList)
                            .param("diff", diffContent))
                    .call()
                    .content();

            return parseFindings(response);
        } catch (Exception e) {
            log.error("审查出错: {}", e.getMessage(), e);
            return List.of();
        }
    }

    /** 将 LLM 响应解析为 ReviewFinding 列表 */
    List<ReviewFinding> parseFindings(String response) {
        if (response == null || response.isBlank()) {
            return List.of();
        }
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
                f.setDimension(getString(item, "dimension"));
                f.setExplanation(getString(item, "explanation"));
                f.setSuggestion(getString(item, "suggestion"));
                findings.add(f);
            }
            return findings;
        } catch (Exception e) {
            log.warn("LLM 响应 JSON 解析失败: {}", e.getMessage());
            log.debug("原始响应: {}", response);
            return List.of();
        }
    }

    /**
     * 从 LLM 响应中提取 JSON 数组——找到第一个 [ 和最后一个 ] 之间的内容。
     * LLM 经常在 JSON 外面包 markdown 代码块或说明文字。
     */
    private String extractJson(String response) {
        String trimmed = response.trim();
        int start = trimmed.indexOf('[');
        int end = trimmed.lastIndexOf(']');
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }
        return trimmed;
    }

    /** 从解析后的 Map 中安全取值，null 时返回空字符串 */
    private String getString(Map<String, Object> map, String key) {
        Object value = map.getOrDefault(key, "");
        return value != null ? value.toString() : "";
    }

    /** 从解析后的 Map 中安全取整数，null/非数字时返回 0 */
    private int getInt(Map<String, Object> map, String key) {
        Object value = map.getOrDefault(key, 0);
        if (value instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
