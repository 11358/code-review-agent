package com.cragent.core.filter;

import com.cragent.core.model.ReviewFinding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fix-Guided Verification Filter.
 *
 * Instead of asking the LLM "is this a real bug?" (which has a high false positive rate),
 * we ask the LLM to propose a SPECIFIC, COMPILABLE fix. If the model can produce
 * concrete code changes, the finding is likely valid. If it produces vague advice
 * ("consider validating input"), it is likely a false positive.
 *
 * Based on: arXiv:2603.00539 - Fix-guided Verification Filter
 */
@Component
public class FixGuidedVerificationFilter {

    private static final Logger log = LoggerFactory.getLogger(FixGuidedVerificationFilter.class);

    private final ChatClient chatClient;
    private final double confidenceThreshold;
    private final boolean enabled;

    private static final Pattern CONFIDENCE_PATTERN = Pattern.compile(
            "confidence[:\\s]*([0-9]?(?:\\.\\d+)?)", Pattern.CASE_INSENSITIVE);

    public FixGuidedVerificationFilter(
            ChatClient.Builder chatClientBuilder,
            @Value("${cragent.review.verification.enabled:true}") boolean enabled,
            @Value("${cragent.review.verification.confidence-threshold:0.6}") double confidenceThreshold) {
        this.chatClient = chatClientBuilder.build();
        this.enabled = enabled;
        this.confidenceThreshold = confidenceThreshold;
    }

    /**
     * Filter findings to reduce false positives.
     */
    public List<ReviewFinding> filter(List<ReviewFinding> findings, String diffContext) {
        if (!enabled || findings.isEmpty()) {
            return findings;
        }

        log.info("Verification filter: {} findings to verify (threshold={})", findings.size(), confidenceThreshold);
        List<ReviewFinding> verified = new ArrayList<>();
        int filtered = 0;

        for (ReviewFinding finding : findings) {
            if (finding.getSeverity() == null) {
                verified.add(finding);
                continue;
            }

            // Only verify CRITICAL and WARNING findings (INFO is low-stakes)
            if (finding.getSeverity().name().equals("INFO")) {
                finding.setConfidenceScore(0.8);
                finding.setVerified(true);
                verified.add(finding);
                continue;
            }

            double confidence = assessConfidence(finding, diffContext);
            finding.setConfidenceScore(confidence);
            finding.setVerified(confidence >= confidenceThreshold);

            if (confidence >= confidenceThreshold) {
                verified.add(finding);
            } else {
                filtered++;
                log.debug("Filtered (confidence={}): {} @ {}:{}",
                        confidence, finding.getCategory(), finding.getFile(), finding.getLineStart());
            }
        }

        log.info("Verification complete: {} kept, {} filtered", verified.size(), filtered);
        return verified;
    }

    private double assessConfidence(ReviewFinding finding, String diffContext) {
        try {
            String response = chatClient.prompt()
                    .user(u -> u.text("""
                            Given a potential code issue, assess if it is a REAL problem by trying to write a specific fix.

                            Issue:
                            - File: {file}
                            - Lines: {lineStart}-{lineEnd}
                            - Severity: {severity}
                            - Category: {category}
                            - Problem: {explanation}

                            Diff context (partial):
                            {diff}

                            TASK:
                            1. If this is a REAL issue, write the specific code fix (what to change, line by line).
                            2. If this is a FALSE POSITIVE (the existing code is actually fine), explain why.
                            3. Rate your confidence from 0.0 to 1.0 on whether this is a real issue.

                            Output format:
                            Fix: [your specific fix OR "No fix needed - false positive"]
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
            log.warn("Verification failed for finding, keeping by default: {}", e.getMessage());
            return 0.8; // Default to keep if verification fails
        }
    }

    private double extractConfidence(String response) {
        if (response == null || response.isBlank()) return 0.5;

        // Check if the response indicates a false positive
        String lower = response.toLowerCase();
        if (lower.contains("false positive") || lower.contains("no fix needed") ||
                lower.contains("not a real issue") || lower.contains("code is correct")) {
            return 0.0;
        }

        // Check if a specific fix is provided
        boolean hasSpecificFix = lower.contains("```") ||
                lower.contains("replace") ||
                lower.contains("change line") ||
                lower.contains("modify to") ||
                lower.contains("rewrite as");

        // Try to extract numeric confidence
        Matcher m = CONFIDENCE_PATTERN.matcher(response);
        if (m.find()) {
            try {
                double conf = Double.parseDouble(m.group(1));
                // If no specific fix but high confidence, reduce it
                if (!hasSpecificFix && conf > 0.5) {
                    conf = 0.4;
                }
                return Math.min(1.0, Math.max(0.0, conf));
            } catch (NumberFormatException ignored) {}
        }

        // Heuristic scoring
        if (hasSpecificFix) return 0.8;
        if (lower.contains("could be") || lower.contains("might be") || lower.contains("possibly")) return 0.3;
        return 0.5;
    }

    private String truncate(String s, int maxLen) {
        if (s == null || s.length() <= maxLen) return s != null ? s : "";
        return s.substring(0, maxLen) + "\n... [truncated] ...";
    }
}
