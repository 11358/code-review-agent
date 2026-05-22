package com.cragent.core.graph.nodes;

import com.cragent.core.agent.SubAgent;
import com.cragent.core.model.ReviewFinding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class SpecializedReviewNode {

    private static final Logger log = LoggerFactory.getLogger(SpecializedReviewNode.class);

    private final SubAgent subAgent;
    private final String stateKey;
    private final int runs;

    public SpecializedReviewNode(SubAgent subAgent, String stateKey) {
        this(subAgent, stateKey, 3);
    }

    public SpecializedReviewNode(SubAgent subAgent, String stateKey, int runs) {
        this.subAgent = subAgent;
        this.stateKey = stateKey;
        this.runs = Math.max(1, runs);
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

        String diffToReview = !relevantDiff.isEmpty() ? relevantDiff.toString() : rawDiff;
        if (diffToReview == null || diffToReview.isBlank()) {
            log.info("No diff content for dimension: {}", dimension);
            return Map.of(stateKey, List.of());
        }

        if (diffToReview.length() > 500_000) {
            diffToReview = diffToReview.substring(0, 500_000) + "\n... [diff truncated] ...";
        }

        // Multi-run UNION: run N times, keep ALL unique findings (maximize recall)
        // False positives get cleaned up later by DeepSeek cross-model verification
        log.info("Starting {} review ({} chars, {} runs — union mode)...", dimension, diffToReview.length(), runs);

        Map<String, ReviewFinding> union = new LinkedHashMap<>();
        Map<String, Integer> occurrenceCount = new HashMap<>();
        int totalRaw = 0;

        for (int run = 1; run <= runs; run++) {
            List<ReviewFinding> findings = subAgent.review(diffToReview, List.of());
            totalRaw += findings.size();
            log.info("{} review run {}/{}: {} findings", dimension, run, runs, findings.size());

            for (ReviewFinding f : findings) {
                String key = f.uniqueKey();
                occurrenceCount.merge(key, 1, Integer::sum);
                // Keep the most detailed version
                union.merge(key, f, (existing, incoming) ->
                        incoming.getExplanation().length() > existing.getExplanation().length() ? incoming : existing);
            }
        }

        List<ReviewFinding> result = new ArrayList<>(union.values());

        log.info("{} review union: {} raw across {} runs → {} unique findings (votes: {})",
                dimension, totalRaw, runs, result.size(), occurrenceCount);

        return Map.of(stateKey, result,
                "agent_decisions", dimension.toLowerCase() + "_review: " + result.size() + " unique findings (" + runs + " runs, union)");
    }
}
