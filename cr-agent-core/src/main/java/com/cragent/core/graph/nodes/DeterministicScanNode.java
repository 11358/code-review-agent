package com.cragent.core.graph.nodes;

import com.cragent.core.model.ReviewCategory;
import com.cragent.core.model.ReviewFinding;
import com.cragent.core.model.Severity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pre-LLM deterministic scanner. Regex-based rules detect known patterns
 * with 100% confidence, so the LLM agents don't waste tokens on what
 * a simple pattern match can find.
 *
 * Findings from this node skip DeepSeek verification (already verified=true).
 */
public class DeterministicScanNode {

    private static final Logger log = LoggerFactory.getLogger(DeterministicScanNode.class);

    private static final List<Rule> RULES = List.of(

        // ── SECURITY ──────────────────────────────────────────

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

        // ── BUGS ──────────────────────────────────────────────

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

        // ── PERFORMANCE ───────────────────────────────────────

        new Rule(
            Pattern.compile("String\\s+\\w+\\s*=\\s*\"\";.*\\+="),
            ReviewCategory.EXCESSIVE_ALLOCATION, "PERFORMANCE", Severity.WARNING,
            "String concatenation via += detected: %s",
            "Use StringBuilder instead of String += in loops / repeated concat"
        ),

        new Rule(
            Pattern.compile("\\.matches\\s*\\(\\s*\""),
            ReviewCategory.MISSING_CACHE, "PERFORMANCE", Severity.WARNING,
            "String.matches() called with regex literal: %s",
            "Compile Pattern once as static final and reuse: Pattern.compile(regex).matcher(input).matches()"
        )
    );

    // Hunk header pattern: @@ -oldStart,oldCount +newStart,newCount @@
    private static final Pattern HUNK_PATTERN = Pattern.compile(
            "^@@ -(\\d+)(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@");

    // File header: diff --git a/path b/path
    private static final Pattern FILE_PATTERN = Pattern.compile(
            "^diff --git a/(.+) b/(.+)$");

    public Map<String, Object> execute(Map<String, Object> state) {
        String rawDiff = (String) state.getOrDefault("raw_diff", "");
        if (rawDiff == null || rawDiff.isBlank()) {
            return Map.of("deterministic_findings", List.of());
        }

        List<ReviewFinding> findings = scan(rawDiff);

        log.info("Deterministic scan: {} findings (confidence=1.0, pre-verified)", findings.size());
        for (ReviewFinding f : findings) {
            log.debug("  [{}] {}:{} — {}",
                    f.getDimension(), f.getFile(), f.getLineStart(), f.getCategory());
        }

        Map<String, Object> result = new HashMap<>();
        result.put("deterministic_findings", findings);
        return result;
    }

    private List<ReviewFinding> scan(String diff) {
        List<ReviewFinding> findings = new ArrayList<>();
        String currentFile = null;
        int currentLine = 0;

        for (String line : diff.split("\n")) {
            // Track file
            Matcher fm = FILE_PATTERN.matcher(line);
            if (fm.find()) {
                currentFile = fm.group(2);
                continue;
            }

            // Track hunk line number
            Matcher hm = HUNK_PATTERN.matcher(line);
            if (hm.find()) {
                currentLine = Integer.parseInt(hm.group(2));
                continue;
            }

            // Only scan added lines
            if (!line.startsWith("+") || line.startsWith("+++")) {
                if (!line.startsWith("-")) currentLine++;
                continue;
            }

            String code = line.substring(1).trim();
            if (code.isEmpty()) {
                currentLine++;
                continue;
            }

            for (Rule rule : RULES) {
                Matcher m = rule.pattern.matcher(line.substring(1)); // match on raw added line
                if (m.find()) {
                    findings.add(new ReviewFinding(
                            currentFile, currentLine, currentLine,
                            rule.severity, rule.category, rule.dimension,
                            String.format(rule.explanation, truncate(code, 120)),
                            rule.suggestion));
                }
            }

            currentLine++;
        }

        // Sort by file → category → line
        findings.sort(Comparator
                .comparing(ReviewFinding::getFile)
                .thenComparing(ReviewFinding::getCategory)
                .thenComparingInt(ReviewFinding::getLineStart));

        // Merge adjacent same-category findings within 3 lines
        List<ReviewFinding> merged = new ArrayList<>();
        for (ReviewFinding f : findings) {
            if (!merged.isEmpty()) {
                ReviewFinding prev = merged.get(merged.size() - 1);
                if (prev.getFile().equals(f.getFile())
                        && prev.getCategory() == f.getCategory()
                        && f.getLineStart() - prev.getLineEnd() <= 3) {
                    // Extend previous finding to cover this one too
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

    private String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }

    // ── Rule definition ──────────────────────────────────────

    private record Rule(
            Pattern pattern,
            ReviewCategory category,
            String dimension,
            Severity severity,
            String explanation,
            String suggestion) {}
}
