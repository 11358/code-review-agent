package com.cragent.core.graph.nodes;

import com.cragent.core.agent.SubAgent;
import com.cragent.core.model.ReviewFinding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ReviewNode {

    private static final Logger log = LoggerFactory.getLogger(ReviewNode.class);

    private final SubAgent subAgent;

    public ReviewNode(SubAgent subAgent) {
        this.subAgent = subAgent;
    }

    public Map<String, Object> execute(Map<String, Object> state) {
        String rawDiff = (String) state.getOrDefault("raw_diff", "");

        if (rawDiff == null || rawDiff.isBlank()) {
            log.warn("No diff content to review");
            Map<String, Object> result = new HashMap<>();
            result.put("findings_result", List.of());
            return result;
        }

        // Truncate diff if it exceeds max size
        String diffToReview = rawDiff;
        int maxSize = 500000;
        if (diffToReview.length() > maxSize) {
            log.warn("Diff too large ({} chars), truncating to {}", rawDiff.length(), maxSize);
            diffToReview = rawDiff.substring(0, maxSize) + "\n... [diff truncated] ...";
        }

        log.info("Starting review with agent: {}", subAgent.getDimensionName());
        List<ReviewFinding> findings = subAgent.review(diffToReview, List.of());
        log.info("Review complete: {} findings", findings.size());

        Map<String, Object> result = new HashMap<>();
        result.put("findings_result", findings);
        result.put("agent_decisions", "review: " + subAgent.getDimensionName() +
                " found " + findings.size() + " issues");
        return result;
    }
}
