package com.cragent.core.graph.nodes;

import com.cragent.core.model.ReviewFinding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

public class AggregateResultsNode {

    private static final Logger log = LoggerFactory.getLogger(AggregateResultsNode.class);

    private final List<String> stateKeys;

    public AggregateResultsNode(List<String> stateKeys) {
        this.stateKeys = stateKeys;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> execute(Map<String, Object> state) {
        List<ReviewFinding> allFindings = new ArrayList<>();

        for (String key : stateKeys) {
            Object value = state.get(key);
            if (value instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof ReviewFinding f) {
                        allFindings.add(f);
                    }
                }
            }
        }

        // Deduplicate by unique key, merge dimensions on collision
        Map<String, ReviewFinding> deduped = new LinkedHashMap<>();
        for (ReviewFinding f : allFindings) {
            deduped.merge(f.uniqueKey(), f, (existing, incoming) -> {
                existing.mergeDimensions(incoming);
                if (incoming.getSeverity().ordinal() < existing.getSeverity().ordinal()) {
                    incoming.mergeDimensions(existing);
                    return incoming;
                }
                return existing;
            });
        }

        // Sort by severity (CRITICAL first), then by file
        List<ReviewFinding> sorted = new ArrayList<>(deduped.values());
        sorted.sort(Comparator
                .comparing(ReviewFinding::getSeverity)
                .thenComparing(ReviewFinding::getFile)
                .thenComparingInt(ReviewFinding::getLineStart));

        log.info("Aggregated findings: {} raw -> {} deduped -> {} sorted",
                allFindings.size(), deduped.size(), sorted.size());

        Map<String, Object> result = new HashMap<>();
        result.put("all_findings", sorted);
        result.put("agent_decisions", "aggregate: " + allFindings.size() +
                " raw -> " + sorted.size() + " deduped findings");
        return result;
    }
}
