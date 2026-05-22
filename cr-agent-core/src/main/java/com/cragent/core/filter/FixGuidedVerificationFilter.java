package com.cragent.core.filter;

import com.cragent.core.model.ReviewFinding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fix-Guided Verification Filter — cross-model edition.
 *
 * Uses DeepSeek (or configured model) as a second-pass verifier to
 * break the echo chamber of single-model review. Falls back to Qwen
 * if DEEPSEEK_API_KEY is not set (logs a warning).
 *
 * Based on: arXiv:2603.00539 - Fix-guided Verification Filter
 */
@Component
public class FixGuidedVerificationFilter {

    private static final Logger log = LoggerFactory.getLogger(FixGuidedVerificationFilter.class);

    private final ChatClient chatClient;
    private final boolean enabled;
    private final int runs;

    private static final double DEFAULT_THRESHOLD = 0.5;
    private static final int DEFAULT_RUNS = 3;

    private static final Pattern CONFIDENCE_PATTERN = Pattern.compile(
            "confidence[:\\s]*([0-9]?(?:\\.\\d+)?)", Pattern.CASE_INSENSITIVE);

    private static final Pattern CODE_PATTERN = Pattern.compile(
            "StringBuilder|Buffered|JOIN\\s+FETCH|IN\\s*\\(|batch|Pattern\\.compile|try-with-resources|PreparedStatement",
            Pattern.CASE_INSENSITIVE);

    public FixGuidedVerificationFilter(
            @Qualifier("deepseek") ChatClient.Builder chatClientBuilder,
            @Value("${cragent.review.verification.enabled:true}") boolean enabled,
            @Value("${cragent.review.verification.runs:3}") int runs) {
        this.chatClient = chatClientBuilder.build();
        this.enabled = enabled;
        this.runs = Math.max(1, runs);
        log.info("Verification filter initialized (runs={})", this.runs);
    }

    /**
     * Filter findings using cross-model verification.
     * DeepSeek acts as an independent second-pass verifier,
     * breaking the echo chamber of Qwen reviewing its own output.
     */
    public List<ReviewFinding> filter(List<ReviewFinding> findings, String diffContext) {
        if (!enabled || findings.isEmpty()) {
            return findings;
        }

        log.info("Verification filter: {} findings (threshold={}, {} runs)", findings.size(), DEFAULT_THRESHOLD, runs);
        List<ReviewFinding> verified = new ArrayList<>();
        int filtered = 0;

        for (ReviewFinding finding : findings) {
            if (finding.getSeverity() == null) {
                verified.add(finding);
                continue;
            }

            if ("INFO".equals(finding.getSeverity().name())) {
                finding.setConfidenceScore(0.8);
                finding.setVerified(true);
                verified.add(finding);
                continue;
            }

            // Multi-run verification: run N times, average the confidence
            double totalConfidence = 0.0;
            int realCount = 0;
            for (int run = 1; run <= runs; run++) {
                double c = assessConfidence(finding, diffContext);
                totalConfidence += c;
                if (c >= DEFAULT_THRESHOLD) realCount++;
            }
            double avgConfidence = totalConfidence / runs;

            finding.setConfidenceScore(avgConfidence);
            finding.setVerified(avgConfidence >= DEFAULT_THRESHOLD);

            if (avgConfidence >= DEFAULT_THRESHOLD) {
                verified.add(finding);
            } else {
                filtered++;
                log.debug("Filtered (avgConf={}, {}/{} runs real): {} @ {}:{}",
                        avgConfidence, realCount, runs, finding.getCategory(), finding.getFile(), finding.getLineStart());
            }
        }

        log.info("Verification: {} kept, {} filtered", verified.size(), filtered);
        return verified;
    }

    private double assessConfidence(ReviewFinding finding, String diffContext) {
        try {
            String response = chatClient.prompt()
                    .user(u -> u.text("""
                            Given a potential code issue found by a code review tool, independently assess whether it is a REAL problem.

                            Issue:
                            - File: {file}
                            - Lines: {lineStart}-{lineEnd}
                            - Severity: {severity}
                            - Category: {category}
                            - Problem: {explanation}

                            Relevant code context:
                            {diff}

                            TASK:
                            1. If this is a REAL issue, write a specific, compilable code fix.
                            2. If the existing code is actually fine, say "No fix needed - false positive" and explain.
                            3. Rate your confidence from 0.0 to 1.0.

                            Output format:
                            Fix: [specific code fix OR "No fix needed - false positive"]
                            Confidence: [0.0-1.0]
                            """)
                            .param("file", finding.getFile())
                            .param("lineStart", String.valueOf(finding.getLineStart()))
                            .param("lineEnd", String.valueOf(finding.getLineEnd()))
                            .param("severity", finding.getSeverity().name())
                            .param("category", finding.getCategory().name())
                            .param("explanation", finding.getExplanation())
                            .param("diff", truncate(diffContext, 2000)))
                    .call()
                    .content();

            return extractConfidence(response);
        } catch (Exception e) {
            log.warn("Verification call failed, keeping finding: {}", e.getMessage());
            return DEFAULT_THRESHOLD;
        }
    }

    private double extractConfidence(String response) {
        if (response == null || response.isBlank()) return DEFAULT_THRESHOLD;

        String lower = response.toLowerCase();

        if (lower.contains("false positive") || lower.contains("no fix needed") ||
                lower.contains("not a real issue") || lower.contains("code is correct")) {
            return 0.0;
        }

        boolean hasSpecificFix = lower.contains("```") ||
                lower.contains("replace") ||
                lower.contains("change line") ||
                lower.contains("modify to") ||
                lower.contains("rewrite as") ||
                CODE_PATTERN.matcher(response).find();

        Matcher m = CONFIDENCE_PATTERN.matcher(response);
        if (m.find()) {
            try {
                double conf = Double.parseDouble(m.group(1));
                if (!hasSpecificFix && conf > 0.5) {
                    conf = 0.4;
                }
                return Math.min(1.0, Math.max(0.0, conf));
            } catch (NumberFormatException ignored) {}
        }

        if (hasSpecificFix) return 0.8;
        if (lower.contains("could") || lower.contains("might") || lower.contains("possibly")) return 0.35;
        return 0.5;
    }

    private String truncate(String s, int maxLen) {
        if (s == null || s.length() <= maxLen) return s != null ? s : "";
        return s.substring(0, maxLen) + "\n... [truncated] ...";
    }
}
