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

/**
 * Agent 基类，封装 LLM 调用和 JSON 解析的通用逻辑。
 * BugSubAgent、PerformanceSubAgent、SecuritySubAgent 均继承此类。
 */
public abstract class AbstractSubAgent implements SubAgent {

    protected final Logger log = LoggerFactory.getLogger(getClass());
    protected final ChatClient chatClient;   // Spring AI ChatClient（千问）
    protected final ObjectMapper objectMapper; // Jackson JSON 解析器

    /**
     * @param chatClientBuilder ChatClient.Builder（自动注入）
     * @param objectMapper      Jackson ObjectMapper
     * @param systemPrompt      该 Agent 的 System Prompt
     */
    protected AbstractSubAgent(ChatClient.Builder chatClientBuilder, ObjectMapper objectMapper, String systemPrompt) {
        this.chatClient = chatClientBuilder.defaultSystem(systemPrompt).build();
        this.objectMapper = objectMapper;
    }

    /**
     * 子类 review() 方法直接委托到这里：构造 Prompt → 调千问 → 解析 JSON。
     * @param diffContent      待审查 diff
     * @param changedFilePaths 变更文件列表
     * @param dimension        维度名（仅用于日志）
     * @return 解析后的 ReviewFinding 列表，出错时返回空列表
     */
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
            log.error("{} 审查出错: {}", dimension, e.getMessage(), e);
            return List.of();
        }
    }

    /** 将千问返回的 JSON 字符串解析为 ReviewFinding 列表，包含容错处理。 */
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
                log.debug("解析类别 '{}' -> {}", rawCategory, f.getCategory());
                f.setDimension(dimension);
                f.setExplanation(getString(item, "explanation"));
                f.setSuggestion(getString(item, "suggestion"));
                // 过滤 LLM 产生的空壳 finding（缺文件或行号）
                if (!f.getFile().isBlank() && f.getLineStart() > 0) {
                    findings.add(f);
                }
            }
            return findings;
        } catch (Exception e) {
            log.warn("{} 审查 JSON 解析失败: {}", dimension, e.getMessage());
            log.debug("原始响应: {}", response);
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
