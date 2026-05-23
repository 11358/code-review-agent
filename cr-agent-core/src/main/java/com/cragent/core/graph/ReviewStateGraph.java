package com.cragent.core.graph;

import com.cragent.core.agent.SubAgent;
import com.cragent.core.filter.FixGuidedVerificationFilter;
import com.cragent.core.graph.nodes.*;
import com.cragent.core.mcp.McpClientManager;
import com.cragent.core.model.ReviewFinding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Multi-Agent Code Review StateGraph (Phase 3).
 *
 * Pipeline: fetch_diff → parse_diff → [security_review → bug_review → perf_review]
 *           → aggregate → verification_filter → format_output
 */
public class ReviewStateGraph {

    private static final Logger log = LoggerFactory.getLogger(ReviewStateGraph.class);

    private final FetchDiffNode fetchDiffNode;
    private final ParseDiffNode parseDiffNode;
    private final DeterministicScanNode deterministicScanNode;
    private final SpecializedReviewNode securityReviewNode;
    private final SpecializedReviewNode bugReviewNode;
    private final SpecializedReviewNode performanceReviewNode;
    private final AggregateResultsNode aggregateNode;
    private final FixGuidedVerificationFilter verificationFilter;
    private final FormatOutputNode formatOutputNode;
    private final ExecutorService reviewExecutor;
    private final long reviewTimeoutSeconds;

    public ReviewStateGraph(McpClientManager mcpClient,
                            SubAgent securitySubAgent,
                            SubAgent bugSubAgent,
                            SubAgent performanceSubAgent,
                            FixGuidedVerificationFilter verificationFilter,
                            int agentRuns,
                            long reviewTimeoutSeconds) {
        this.fetchDiffNode = new FetchDiffNode(mcpClient);
        this.parseDiffNode = new ParseDiffNode();
        this.deterministicScanNode = new DeterministicScanNode();
        this.securityReviewNode = new SpecializedReviewNode(securitySubAgent, "security_findings", agentRuns);
        this.bugReviewNode = new SpecializedReviewNode(bugSubAgent, "bug_findings", agentRuns);
        this.performanceReviewNode = new SpecializedReviewNode(performanceSubAgent, "perf_findings", agentRuns);
        this.aggregateNode = new AggregateResultsNode(List.of(
                "deterministic_findings", "security_findings", "bug_findings", "perf_findings"));
        this.verificationFilter = verificationFilter;
        this.formatOutputNode = new FormatOutputNode();
        this.reviewTimeoutSeconds = reviewTimeoutSeconds;
        this.reviewExecutor = Executors.newFixedThreadPool(3, r -> {
            Thread t = new Thread(r, "review-agent");
            t.setDaemon(true);
            return t;
        });
    }

    public Map<String, Object> execute(String repoPath, String baseRef, String headRef) {
        long startTime = System.currentTimeMillis();

        Map<String, Object> state = new HashMap<>();
        state.put("repo_path", repoPath);
        state.put("base_ref", baseRef);
        state.put("head_ref", headRef);
        state.put("_start_time", startTime);

        log.info("╔══════════════════════════════════════════╗");
        log.info("║   Multi-Agent Code Review Pipeline       ║");
        log.info("╚══════════════════════════════════════════╝");
        log.info("Repository: {} ({} → {})", repoPath, baseRef, headRef);

        // Step 1: Fetch diff via MCP
        log.info("[1/8] Fetching diff...");
        mergeState(state, fetchDiffNode.execute(state));
        String rawDiff = (String) state.get("raw_diff");
        if (rawDiff == null || rawDiff.isBlank()) {
            log.warn("Empty diff. No review needed.");
            state.put("review_result", com.cragent.core.model.ReviewResult.empty(repoPath, baseRef, headRef));
            return state;
        }

        // Step 2: Parse diff into file-level chunks
        log.info("[2/9] Parsing diff...");
        mergeState(state, parseDiffNode.execute(state));

        // Step 3: Deterministic regex scan (pre-LLM, confidence=1.0)
        log.info("[3/9] Deterministic scan...");
        mergeState(state, deterministicScanNode.execute(state));
        @SuppressWarnings("unchecked")
        List<ReviewFinding> detFindings = (List<ReviewFinding>) state.get("deterministic_findings");
        int deterministicCount = detFindings.size();
        state.put("_det_count", deterministicCount);
        log.info("Deterministic: {} findings (100% confidence, skip verification)", deterministicCount);

        // Strip deterministic-flagged lines from diff chunks so LLM doesn't re-report them
        Set<String> detLineSet = new HashSet<>();
        for (ReviewFinding f : detFindings) {
            for (int line = f.getLineStart(); line <= f.getLineEnd(); line++) {
                detLineSet.add(f.getFile() + ":" + line);
            }
        }
        @SuppressWarnings("unchecked")
        List<ParseDiffNode.DiffChunk> chunks = (List<ParseDiffNode.DiffChunk>) state.get("diff_chunks");
        if (chunks != null && !detLineSet.isEmpty()) {
            state.put("diff_chunks", stripDetLines(chunks, detLineSet));
            log.info("Stripped {} deterministic lines from diff chunks for LLM review", detLineSet.size());
        }

        // Step 4-6: Multi-agent LLM reviews (parallel with timeout)
        log.info("[4-6/9] Parallel LLM review: security + bugs + performance (timeout: {}s)...", reviewTimeoutSeconds);
        long reviewStart = System.currentTimeMillis();

        CompletableFuture<Map<String, Object>> securityFuture = CompletableFuture.supplyAsync(
                () -> securityReviewNode.execute(state), reviewExecutor);
        CompletableFuture<Map<String, Object>> bugFuture = CompletableFuture.supplyAsync(
                () -> bugReviewNode.execute(state), reviewExecutor);
        CompletableFuture<Map<String, Object>> perfFuture = CompletableFuture.supplyAsync(
                () -> performanceReviewNode.execute(state), reviewExecutor);

        try {
            CompletableFuture.allOf(securityFuture, bugFuture, perfFuture)
                    .orTimeout(reviewTimeoutSeconds, TimeUnit.SECONDS)
                    .join();
        } catch (CompletionException e) {
            log.warn("Review phase timed out after {}s — proceeding with partial results", reviewTimeoutSeconds);
        }

        int completed = 0;
        if (mergeIfDone(state, securityFuture, "SECURITY")) completed++;
        if (mergeIfDone(state, bugFuture, "BUGS")) completed++;
        if (mergeIfDone(state, perfFuture, "PERFORMANCE")) completed++;

        log.info("Review phase done in {}ms ({} of 3 dimensions completed)",
                System.currentTimeMillis() - reviewStart, completed);

        // Step 7: Aggregate findings
        log.info("[7/9] Aggregating findings...");
        mergeState(state, aggregateNode.execute(state));

        // Step 8: Verification filter
        log.info("[8/9] Verifying findings...");
        @SuppressWarnings("unchecked")
        List<ReviewFinding> allFindings = (List<ReviewFinding>) state.get("all_findings");
        List<ReviewFinding> verifiedFindings = verificationFilter.filter(allFindings, rawDiff);
        state.put("findings_result", verifiedFindings);
        log.info("Verification: {} -> {} findings", allFindings.size(), verifiedFindings.size());
        int detCount = state.get("_det_count") instanceof Integer i ? i : 0;
        log.info("┌──────────────────────────────────────────┐");
        log.info("│ Deterministic:   {} findings (100% sure)   │", detCount);
        log.info("│ LLM aggregated:  {} findings              │", allFindings.size() - detCount);
        log.info("│ DeepSeek kept:   {} findings              │", verifiedFindings.size());
        log.info("│ DeepSeek filtered: {} findings             │", allFindings.size() - verifiedFindings.size());
        log.info("└──────────────────────────────────────────┘");

        // Step 9: Format output
        log.info("[9/9] Formatting output...");
        mergeState(state, formatOutputNode.execute(state));

        log.info("=== Pipeline Complete ===");
        return state;
    }

    private boolean mergeIfDone(Map<String, Object> state, CompletableFuture<Map<String, Object>> future, String label) {
        if (future.isDone() && !future.isCompletedExceptionally()) {
            try {
                mergeState(state, future.join());
                return true;
            } catch (CompletionException e) {
                log.warn("{} review failed: {}", label, e.getMessage());
                return false;
            }
        }
        future.cancel(true);
        log.warn("{} review did not complete in time -- skipping", label);
        return false;
    }

    /** Shutdown the internal executor. Call on application shutdown. */
    public void shutdown() {
        reviewExecutor.shutdown();
        try {
            if (!reviewExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                reviewExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            reviewExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private void mergeState(Map<String, Object> state, Map<String, Object> nodeOutput) {
        if (nodeOutput != null) {
            state.putAll(nodeOutput);
        }
    }

    // ── Diff line stripping ──────────────────────────────────

    private static final Pattern HUNK_HEADER = Pattern.compile(
            "^@@ -(\\d+)(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@");

    /** Strip deterministic-flagged lines from all chunks so LLM doesn't re-report them. */
    @SuppressWarnings("unchecked")
    private List<ParseDiffNode.DiffChunk> stripDetLines(
            List<ParseDiffNode.DiffChunk> chunks, Set<String> detLines) {
        List<ParseDiffNode.DiffChunk> result = new ArrayList<>();
        for (ParseDiffNode.DiffChunk chunk : chunks) {
            ParseDiffNode.DiffChunk stripped = new ParseDiffNode.DiffChunk();
            stripped.setFilePath(chunk.getFilePath());
            stripped.setRelevantDimensions(new HashSet<>(chunk.getRelevantDimensions()));
            stripped.setContent(stripContent(chunk.getContent(), chunk.getFilePath(), detLines));
            result.add(stripped);
        }
        return result;
    }

    private String stripContent(String content, String filePath, Set<String> detLines) {
        StringBuilder sb = new StringBuilder(content.length() + 256);
        int currentLine = 0;

        for (String line : content.split("\n", -1)) {
            Matcher hm = HUNK_HEADER.matcher(line);
            if (hm.find()) {
                currentLine = Integer.parseInt(hm.group(2));
                sb.append(line).append("\n");
                continue;
            }

            if (line.startsWith("+") && !line.startsWith("+++")) {
                if (detLines.contains(filePath + ":" + currentLine)) {
                    sb.append("+// [skipped: deterministic]\n");
                } else {
                    sb.append(line).append("\n");
                }
                currentLine++;
            } else {
                if (!line.startsWith("-")) currentLine++;
                sb.append(line).append("\n");
            }
        }
        return sb.toString();
    }
}
