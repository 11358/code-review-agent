package com.cragent.core.graph.nodes;

import com.cragent.core.graph.ReviewState;
import com.cragent.core.model.ReviewFinding;
import com.cragent.core.model.ReviewResult;
import com.cragent.core.model.ReviewSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class FormatOutputNode {

    private static final Logger log = LoggerFactory.getLogger(FormatOutputNode.class);

    private static final Map<String, String> EN2CN = new LinkedHashMap<>();
    static {
        EN2CN.put("String concatenation via", "循环内字符串拼接：");
        EN2CN.put("String.matches() called with regex literal", "String.matches() 每次编译正则：");
        EN2CN.put("File I/O opened without try-with-resources", "文件 I/O 未用 try-with-resources：");
        EN2CN.put("Hardcoded credential found", "发现硬编码凭据：");
        EN2CN.put("SQL query built via string concatenation with user input", "SQL 查询通过字符串拼接用户输入：");
        EN2CN.put("Command execution with concatenated user input", "命令执行拼接了用户输入：");
        EN2CN.put("Empty catch block silently discards exception", "空 catch 块静默吞掉异常：");
        EN2CN.put("e.printStackTrace() used", "使用了 e.printStackTrace()：");
        EN2CN.put("Use StringBuilder outside the loop instead of String += in loop", "循环外使用 StringBuilder 替代 String +=");
        EN2CN.put("Compile Pattern once as static final and reuse", "将 Pattern 编译为 static final 常量复用");
        EN2CN.put("Use PreparedStatement / jdbcTemplate with parameterized queries", "使用 PreparedStatement 参数化查询");
        EN2CN.put("Move to environment variable or external config", "改用环境变量或外部配置");
        EN2CN.put("Avoid shell execution; if unavoidable, use ProcessBuilder", "避免 Shell 执行，用 ProcessBuilder 传参数列表");
        EN2CN.put("Replace with log.error", "改用 log.error");
        EN2CN.put("no structured logging, lost in production", "无结构化日志，生产环境不可见");
        EN2CN.put("Wrap in try-with-resources to guarantee close", "用 try-with-resources 确保关闭");
        EN2CN.put("At minimum log the exception; consider rethrow", "至少记录日志，考虑重新抛出");
    }

    public void execute(ReviewState state) {
        List<ReviewFinding> findings = state.getFindingsResult();
        for (ReviewFinding f : findings) {
            f.setExplanation(tr(f.getExplanation()));
            f.setSuggestion(tr(f.getSuggestion()));
        }

        ReviewResult result = ReviewResult.empty(state.getRepoPath(), state.getBaseRef(), state.getHeadRef());
        result.setChangedFiles(state.getChangedFiles());
        result.setFindings(findings);
        result.setSummary(ReviewSummary.from(findings));
        result.setDurationMs(System.currentTimeMillis() - state.getStartTime());

        log.info("审查完成: {} 条发现, 严重度 {}, 类别 {}",
                result.getSummary().getTotalFindings(),
                result.getSummary().getSeverityCounts(),
                result.getSummary().getCategoryCounts());

        state.setReviewResult(result);
    }

    private String tr(String text) {
        if (text == null || text.isBlank()) return text;
        String r = text;
        for (var e : EN2CN.entrySet()) {
            r = r.replace(e.getKey(), e.getValue());
        }
        return r;
    }
}
