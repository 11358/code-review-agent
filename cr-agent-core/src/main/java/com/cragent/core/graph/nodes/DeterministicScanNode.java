package com.cragent.core.graph.nodes;

import com.cragent.core.graph.ReviewState;
import com.cragent.core.model.ReviewCategory;
import com.cragent.core.model.ReviewFinding;
import com.cragent.core.model.Severity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM 之前的确定性扫描器。用正则匹配已知 Bug 模式，置信度 100%，
 * 让 LLM Agent 不再浪费 token 去发现"非黑即白"的问题。
 *
 * 本节点的发现直接跳过 DeepSeek 验证（verified=true）。
 */
public class DeterministicScanNode {

    private static final Logger log = LoggerFactory.getLogger(DeterministicScanNode.class);

    /**
     * 8 条正则规则，覆盖三个维度中"非黑即白"的模式。
     * String+= 检测已升级为跨行状态机，在循环作用域内独立匹配。
     * 每条规则包含：正则表达式、分类、维度、严重度、解释模板、修复建议。
     */
    private static final List<Rule> RULES = List.of(

        // ── 安全 ──────────────────────────────────────────────

        new Rule(
            Pattern.compile(
                "(?:password|passwd|pwd|secret|token|api[_-]?key|access[_-]?key)\\s*=\\s*\"[^\"]+\"",
                Pattern.CASE_INSENSITIVE),
            ReviewCategory.SENSITIVE_DATA_EXPOSURE, "SECURITY", Severity.CRITICAL,
            "Hardcoded credential found: %s",
            "Move to environment variable or external config (e.g. System.getenv / @Value)"
        ),

        new Rule(
            Pattern.compile(
                "\"(?:SELECT|INSERT|UPDATE|DELETE)\\s+.*\"\\s*\\+\\s*\\w+",
                Pattern.CASE_INSENSITIVE),
            ReviewCategory.SQL_INJECTION, "SECURITY", Severity.CRITICAL,
            "SQL query built via string concatenation with user input: %s",
            "Use PreparedStatement / jdbcTemplate with parameterized queries"
        ),

        new Rule(
            Pattern.compile("Statement\\s+\\w+\\s*=\\s*\\w+\\.createStatement\\(\\)"),
            ReviewCategory.SQL_INJECTION, "SECURITY", Severity.CRITICAL,
            "Statement (non-parameterized) used instead of PreparedStatement: %s",
            "Replace with PreparedStatement to prevent SQL injection"
        ),

        new Rule(
            Pattern.compile(
                "Runtime\\.getRuntime\\(\\)\\.exec\\s*\\([^)]*\\+",
                Pattern.CASE_INSENSITIVE),
            ReviewCategory.COMMAND_INJECTION, "SECURITY", Severity.CRITICAL,
            "Command execution with concatenated user input: %s",
            "Avoid shell execution; if unavoidable, use ProcessBuilder with argument list (no shell)"
        ),

        // ── Bug ───────────────────────────────────────────────

        new Rule(
            Pattern.compile("catch\\s*\\([^)]*\\)\\s*\\{\\s*\\}"),
            ReviewCategory.SWALLOWED_EXCEPTION, "BUGS", Severity.CRITICAL,
            "Empty catch block silently discards exception: %s",
            "At minimum log the exception; consider rethrow or fallback action"
        ),

        new Rule(
            Pattern.compile("new\\s+(FileInputStream|FileOutputStream|FileReader|FileWriter)\\s*\\([^)]*\\)"),
            ReviewCategory.RESOURCE_LEAK, "BUGS", Severity.WARNING,
            "File I/O opened without try-with-resources: %s",
            "Wrap in try-with-resources to guarantee close: try (InputStream is = ...) { ... }"
        ),

        new Rule(
            Pattern.compile(
                "\\.printStackTrace\\(\\)",
                Pattern.CASE_INSENSITIVE),
            ReviewCategory.SWALLOWED_EXCEPTION, "BUGS", Severity.WARNING,
            "e.printStackTrace() used — no structured logging, lost in production: %s",
            "Replace with log.error(\"msg\", e) using SLF4J or equivalent"
        ),

        // ── 性能 ──────────────────────────────────────────────

        new Rule(
            Pattern.compile("\\.matches\\s*\\(\\s*\""),
            ReviewCategory.MISSING_CACHE, "PERFORMANCE", Severity.WARNING,
            "String.matches() called with regex literal: %s",
            "Compile Pattern once as static final and reuse: Pattern.compile(regex).matcher(input).matches()"
        )
    );

    // Hunk 头正则: @@ -旧起,旧数 +新起,新数 @@
    private static final Pattern HUNK_PATTERN = Pattern.compile(
            "^@@ -(\\d+)(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@");

    // 文件头: diff --git a/path b/path
    private static final Pattern FILE_PATTERN = Pattern.compile(
            "^diff --git a/(.+) b/(.+)$");

    // String+= in loop cross-line detection
    private static final Pattern STRING_DECL_PATTERN = Pattern.compile("String\\s+(\\w+)\\s*=\\s*\"\"");
    private static final Pattern STRING_CONCAT_PATTERN = Pattern.compile("(\\w+)\\s*\\+=");
    private static final Pattern FOR_WHILE_PATTERN = Pattern.compile("\\b(for|while)\\s*\\(");
    private static final Pattern DO_PATTERN = Pattern.compile("\\bdo\\s*\\{");
    private static final Pattern DO_WHILE_PATTERN = Pattern.compile(".*\\}\\s*while\\s*\\(.*");
    // catch (...) { where { has no } on the same line
    private static final Pattern EMPTY_CATCH_OPEN = Pattern.compile(".*catch\\s*\\([^)]*\\)\\s*\\{");

    /**
     * 流水线节点入口。扫描 rawDiff，将发现写入 state.deterministicFindings。
     */
    public void execute(ReviewState state) {
        String rawDiff = state.getRawDiff();
        if (rawDiff == null || rawDiff.isBlank()) {
            state.setDeterministicFindings(List.of());
            return;
        }

        List<ReviewFinding> findings = scan(rawDiff);

        log.info("确定性扫描: {} 条发现（置信度=1.0，已验证）", findings.size());
        for (ReviewFinding f : findings) {
            log.debug("  [{}] {}:{} — {}",
                    f.getDimension(), f.getFile(), f.getLineStart(), f.getCategory());
        }

        state.setDeterministicFindings(findings);
    }

    /**
     * 逐行扫描 diff 文本，用 8 条正则 + 跨行状态机匹配已知 Bug 模式。
     * 扫描流程：跟踪文件路径和行号 → 只扫描新增行 → 匹配规则 → 排序 → 相邻合并 → 设置信度 1.0
     *
     * @param diff 完整的 unified diff 文本
     * @return 合并后的 ReviewFinding 列表（全部 confidence=1.0, verified=true）
     */
    private List<ReviewFinding> scan(String diff) {
        List<ReviewFinding> findings = new ArrayList<>();
        String currentFile = null;
        int currentLine = 0;

        // Cross-line state
        Set<String> stringVars = new HashSet<>();
        int braceDepth = 0;
        int loopDepth = -1;
        boolean pendingLoop = false;
        boolean pendingEmptyCatch = false;
        int emptyCatchLine = 0;

        for (String line : diff.split("\n")) {
            Matcher fm = FILE_PATTERN.matcher(line);
            if (fm.find()) {
                currentFile = fm.group(2);
                stringVars.clear();
                braceDepth = 0;
                loopDepth = -1;
                pendingLoop = false;
                pendingEmptyCatch = false;
                continue;
            }

            Matcher hm = HUNK_PATTERN.matcher(line);
            if (hm.find()) {
                currentLine = Integer.parseInt(hm.group(2));
                continue;
            }

            boolean isAdded = line.startsWith("+") && !line.startsWith("+++");
            boolean isRemoved = line.startsWith("-");
            String codeLine = line.isEmpty() ? "" : line.substring(1);
            String trimmed = codeLine.trim();

            // ── State tracking for all lines (context + added) ──

            Matcher declMatcher = STRING_DECL_PATTERN.matcher(codeLine);
            while (declMatcher.find()) {
                stringVars.add(declMatcher.group(1));
            }

            int closeCount = countChar(trimmed, '}');
            for (int i = 0; i < closeCount; i++) {
                if (braceDepth == loopDepth) {
                    loopDepth = -1;
                }
                braceDepth--;
                if (braceDepth < 0) braceDepth = 0;
            }

            // Check pending empty catch from previous line
            if (pendingEmptyCatch) {
                if (trimmed.equals("}")) {
                    findings.add(new ReviewFinding(
                            currentFile, emptyCatchLine, currentLine,
                            Severity.CRITICAL, ReviewCategory.SWALLOWED_EXCEPTION, "BUGS",
                            "Empty catch block silently discards exception: " + truncate(trimmed, 120),
                            "At minimum log the exception; consider rethrow or fallback action"));
                }
                pendingEmptyCatch = false;
            }

            boolean isLoopHeader = false;
            if (!DO_WHILE_PATTERN.matcher(trimmed).matches()) {
                isLoopHeader = FOR_WHILE_PATTERN.matcher(trimmed).find()
                        || DO_PATTERN.matcher(trimmed).find();
            }
            if (isLoopHeader && loopDepth == -1) {
                pendingLoop = true;
            }

            int openCount = countChar(trimmed, '{');
            for (int i = 0; i < openCount; i++) {
                braceDepth++;
                if (pendingLoop) {
                    loopDepth = braceDepth;
                    pendingLoop = false;
                }
            }

            // ── Rule matching only on added lines ──

            if (!isAdded) {
                if (!isRemoved) currentLine++;
                continue;
            }
            if (trimmed.isEmpty()) {
                currentLine++;
                continue;
            }

            // Empty catch spanning two lines: "catch (...) {" on this line, "}" on next
            if (EMPTY_CATCH_OPEN.matcher(trimmed).matches() && !trimmed.endsWith("}")) {
                pendingEmptyCatch = true;
                emptyCatchLine = currentLine;
            }

            // String+= in loop body
            if (loopDepth != -1 && braceDepth >= loopDepth) {
                Matcher concatMatcher = STRING_CONCAT_PATTERN.matcher(codeLine);
                while (concatMatcher.find()) {
                    String varName = concatMatcher.group(1);
                    if (stringVars.contains(varName)) {
                        findings.add(new ReviewFinding(
                                currentFile, currentLine, currentLine,
                                Severity.WARNING, ReviewCategory.EXCESSIVE_ALLOCATION, "PERFORMANCE",
                                String.format("String concatenation via %s += in loop body: %s",
                                        varName, truncate(trimmed, 120)),
                                "Use StringBuilder outside the loop instead of String += in loop"));
                    }
                }
            }

            // Existing deterministic rules
            for (Rule rule : RULES) {
                Matcher m = rule.pattern.matcher(codeLine);
                if (m.find()) {
                    findings.add(new ReviewFinding(
                            currentFile, currentLine, currentLine,
                            rule.severity, rule.category, rule.dimension,
                            String.format(rule.explanation, truncate(trimmed, 120)),
                            rule.suggestion));
                }
            }

            currentLine++;
        }

        findings.sort(Comparator
                .comparing(ReviewFinding::getFile)
                .thenComparing(ReviewFinding::getCategory)
                .thenComparingInt(ReviewFinding::getLineStart));

        List<ReviewFinding> merged = new ArrayList<>();
        for (ReviewFinding f : findings) {
            if (!merged.isEmpty()) {
                ReviewFinding prev = merged.get(merged.size() - 1);
                if (prev.getFile().equals(f.getFile())
                        && prev.getCategory() == f.getCategory()
                        && f.getLineStart() - prev.getLineEnd() <= 3) {
                    prev.setLineEnd(Math.max(prev.getLineEnd(), f.getLineEnd()));
                    continue;
                }
            }
            merged.add(f);
        }

        for (ReviewFinding f : merged) {
            f.setConfidenceScore(1.0);
            f.setVerified(true);
        }
        return merged;
    }

    private static int countChar(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) n++;
        }
        return n;
    }

    /** 截断过长代码片段，防止 finding 的 explanation 字段过长 */
    private String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }

    // ── 规则定义 ────────────────────────────────────────────

    /**
     * 单条扫描规则。
     * @param pattern     正则表达式
     * @param category    命中的 Bug 类别
     * @param dimension   所属维度（SECURITY/BUGS/PERFORMANCE）
     * @param severity    严重度
     * @param explanation 解释模板（%s 会被替换为匹配到的代码片段）
     * @param suggestion  修复建议
     */
    private record Rule(
            Pattern pattern,
            ReviewCategory category,
            String dimension,
            Severity severity,
            String explanation,
            String suggestion) {}
}
