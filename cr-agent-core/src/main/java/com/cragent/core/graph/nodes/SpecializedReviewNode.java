package com.cragent.core.graph.nodes;

import com.cragent.core.agent.SubAgent;
import com.cragent.core.model.ReviewFinding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

public class SpecializedReviewNode {

    private static final Logger log = LoggerFactory.getLogger(SpecializedReviewNode.class);

    private final SubAgent subAgent;
    private final String stateKey;

    public SpecializedReviewNode(SubAgent subAgent, String stateKey) {
        this.subAgent = subAgent;
        this.stateKey = stateKey;
    }

    public String getStateKey() {
        return stateKey;
    }

    public String getDimensionName() {
        return subAgent.getDimensionName();
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> execute(Map<String, Object> state) {
        String rawDiff = (String) state.getOrDefault("raw_diff", "");
        List<ParseDiffNode.DiffChunk> chunks = (List<ParseDiffNode.DiffChunk>) state.get("diff_chunks");

        // Filter chunks relevant to this dimension
        String dimension = subAgent.getDimensionName();
        StringBuilder relevantDiff = new StringBuilder();

        if (chunks != null) {
            for (ParseDiffNode.DiffChunk chunk : chunks) {
                if (chunk.getRelevantDimensions().contains(dimension)) {
                    relevantDiff.append("=== ").append(chunk.getFilePath()).append(" ===\n");
                    relevantDiff.append(chunk.getContent()).append("\n\n");
                }
            }
        }

        // If no chunks matched, fall back to full diff
        String diffToReview = !relevantDiff.isEmpty() ? relevantDiff.toString() : rawDiff;
        if (diffToReview == null || diffToReview.isBlank()) {
            log.info("No diff content for dimension: {}", dimension);
            return Map.of(stateKey, List.of());
        }

        // Truncate if too large
        if (diffToReview.length() > 500000) {
            diffToReview = diffToReview.substring(0, 500000) + "\n... [diff truncated] ...";
        }

        log.info("Starting {} review ({} chars)...", dimension, diffToReview.length());
        List<ReviewFinding> findings = subAgent.review(diffToReview, List.of());
        log.info("{} review complete: {} findings", dimension, findings.size());

        return Map.of(stateKey, findings,
                "agent_decisions", dimension.toLowerCase() + "_review: found " + findings.size() + " issues");
    }
}
