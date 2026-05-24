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

            DETECTION RULES — match these EXACT patterns:

            1. HARDCODED CREDENTIALS (priority #1 — check EVERY line):
               Look for: static final String containing "password", "secret", "key", "token", "apiKey", "api_key".
               Look for: DriverManager.getConnection(url, "user", "password") with literal strings.
               Look for: Any string literal that looks like a credential ("admin123", "root", "changeme", etc.).
               Severity: CRITICAL. These will be leaked if code is ever shared.

            2. SQL INJECTION — report EVERY occurrence separately, even if the same pattern repeats:
               SCAN EVERY LINE for: String sql = "..." + variable — string concatenation building SQL.
               SCAN EVERY LINE for: Statement stmt (raw JDBC, not PreparedStatement).
               SCAN EVERY LINE for: executeQuery(sql) or executeUpdate(sql) where sql involves concatenation.
               IMPORTANT: If the same SQL injection pattern appears in multiple methods, report EACH ONE as a separate finding.
               Do NOT group them together or say "also in methods X, Y, Z". Each line is its own finding.
               Severity: CRITICAL. Attackers can hijack database queries.

            3. COMMAND INJECTION (check ALL exec calls):
               Look for: Runtime.getRuntime().exec(variable) or Runtime.exec(str + variable).
               Look for: ProcessBuilder with user-controlled parameters.
               Look for: Any shell command string that includes concatenated variables.
               Severity: CRITICAL. Attackers can execute arbitrary OS commands.

            4. PATH TRAVERSAL:
               Look for: new FileInputStream/FileReader/File(pathVariable) where pathVariable comes from user input.
               Look for: File operations without path normalization or whitelist.

            5. XSS:
               Look for: Unescaped user input written to HTTP response, @ResponseBody with unsanitized strings.

            6. SENSITIVE DATA IN LOGS:
               Look for: log.info/debug/error with user passwords, tokens, or PII in the message.
               Look for: e.printStackTrace() that might leak connection strings or credentials.

            7. INSECURE DESERIALIZATION:
               Look for: ObjectInputStream.readObject() without input validation.

            8. MISSING AUTH:
               Look for: @RequestMapping methods without @PreAuthorize or auth checks.

            RULE: If you see ANY of these patterns, ALWAYS report it. Do not skip even obvious ones.

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
            log.error("安全检查出错: {}", e.getMessage(), e);
            return List.of();
        }
    }

    /** ⚠ 自行实现 JSON 解析（与 AbstractSubAgent.parseFindings 重复）。待重构。 */
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
                log.debug("安全审查解析类别 '{}' -> {}", getString(item, "category"), f.getCategory());
                f.setDimension("SECURITY");
                f.setExplanation(getString(item, "explanation"));
                f.setSuggestion(getString(item, "suggestion"));
                findings.add(f);
            }
            return findings;
        } catch (Exception e) {
            log.warn("安全审查 JSON 解析失败: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 从 LLM 响应中提取 JSON 数组。
     * LLM 经常在 JSON 外面包 markdown 代码块或说明文字，这个方法找到第一个 [ 和最后一个 ] 之间的内容。
     */
    private String extractJson(String response) {
        String trimmed = response.trim();
        int start = trimmed.indexOf('[');
        int end = trimmed.lastIndexOf(']');
        if (start >= 0 && end > start) return trimmed.substring(start, end + 1);
        return trimmed;
    }

    /** 从解析后的 Map 中安全取值，null 时返回空字符串 */
    private String getString(Map<String, Object> map, String key) {
        Object v = map.getOrDefault(key, "");
        return v != null ? v.toString() : "";
    }

    /** 从解析后的 Map 中安全取整数，null/非数字时返回 0 */
    private int getInt(Map<String, Object> map, String key) {
        Object v = map.getOrDefault(key, 0);
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(v.toString()); } catch (NumberFormatException e) { return 0; }
    }
}
