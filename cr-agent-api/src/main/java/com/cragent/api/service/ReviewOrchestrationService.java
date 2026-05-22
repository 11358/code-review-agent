package com.cragent.api.service;

import com.cragent.core.graph.ReviewStateGraph;
import com.cragent.core.model.ReviewResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.Map;

@Service
public class ReviewOrchestrationService {

    private static final Logger log = LoggerFactory.getLogger(ReviewOrchestrationService.class);

    private final ReviewStateGraph stateGraph;

    public ReviewOrchestrationService(ReviewStateGraph stateGraph) {
        this.stateGraph = stateGraph;
    }

    public ReviewResult review(String repoPath, String baseRef, String headRef) {
        repoPath = repoPath.replace('\\', '/');
        log.info("Starting review: repo={}, {} -> {}", repoPath, baseRef, headRef);

        Map<String, Object> result = stateGraph.execute(repoPath, baseRef, headRef);

        ReviewResult reviewResult = (ReviewResult) result.get("review_result");
        if (reviewResult == null) {
            log.warn("No review result produced");
            return ReviewResult.empty(repoPath, baseRef, headRef);
        }

        log.info("Review complete: {} findings", reviewResult.getFindings().size());
        return reviewResult;
    }

    /**
     * Streaming version that emits progress events via SSE.
     * Since the current StateGraph is synchronous, we wrap it in a Flux
     * with progress events emitted at key stages.
     */
    public Flux<Map<String, Object>> reviewStream(String repoPath, String baseRef, String headRef) {
        return Flux.create(sink -> {
            try {
                sink.next(Map.of(
                        "event", "review.started",
                        "repoPath", repoPath,
                        "baseRef", baseRef,
                        "headRef", headRef,
                        "timestamp", System.currentTimeMillis()
                ));

                long startTime = System.currentTimeMillis();

                sink.next(Map.of(
                        "event", "node.start",
                        "node", "fetch_diff",
                        "message", "Fetching git diff..."
                ));

                ReviewResult result = review(repoPath, baseRef, headRef);

                sink.next(Map.of(
                        "event", "node.complete",
                        "node", "review",
                        "message", "Review complete",
                        "findings", result.getFindings().size()
                ));

                sink.next(Map.of(
                        "event", "review.complete",
                        "result", result,
                        "durationMs", System.currentTimeMillis() - startTime
                ));

                sink.complete();
            } catch (Exception e) {
                log.error("Streaming review failed", e);
                sink.next(Map.of(
                        "event", "review.error",
                        "error", e.getMessage()
                ));
                sink.error(e);
            }
        });
    }
}
