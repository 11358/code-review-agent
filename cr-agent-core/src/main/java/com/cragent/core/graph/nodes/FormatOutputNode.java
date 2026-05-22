package com.cragent.core.graph.nodes;

import com.cragent.core.model.ReviewFinding;
import com.cragent.core.model.ReviewResult;
import com.cragent.core.model.ReviewSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FormatOutputNode {

    private static final Logger log = LoggerFactory.getLogger(FormatOutputNode.class);

    @SuppressWarnings("unchecked")
    public Map<String, Object> execute(Map<String, Object> state) {
        String repoPath = (String) state.getOrDefault("repo_path", "");
        String baseRef = (String) state.getOrDefault("base_ref", "");
        String headRef = (String) state.getOrDefault("head_ref", "");

        List<ReviewFinding> findings = (List<ReviewFinding>) state.getOrDefault("findings_result", List.of());

        ReviewResult result = ReviewResult.empty(repoPath, baseRef, headRef);
        result.setFindings(findings);
        result.setSummary(ReviewSummary.from(findings));

        long startTime = state.containsKey("_start_time") ? (long) state.get("_start_time") : System.currentTimeMillis();
        result.setDurationMs(System.currentTimeMillis() - startTime);

        log.info("Review complete: {} total findings, {} CRITICAL, {} WARNING, {} INFO",
                result.getSummary().getTotalFindings(),
                result.getSummary().getSeverityCounts(),
                result.getSummary().getCategoryCounts());

        Map<String, Object> output = new HashMap<>();
        output.put("review_result", result);
        output.put("agent_decisions", "format: final report generated");
        return output;
    }
}
