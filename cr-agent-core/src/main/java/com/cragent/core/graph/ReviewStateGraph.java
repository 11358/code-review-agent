package com.cragent.core.graph;

import com.cragent.core.agent.SubAgent;
import com.cragent.core.filter.FixGuidedVerificationFilter;
import com.cragent.core.graph.nodes.*;
import com.cragent.core.mcp.McpClientManager;
import com.cragent.core.model.ReviewFinding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    private final SpecializedReviewNode securityReviewNode;
    private final SpecializedReviewNode bugReviewNode;
    private final SpecializedReviewNode performanceReviewNode;
    private final AggregateResultsNode aggregateNode;
    private final FixGuidedVerificationFilter verificationFilter;
    private final FormatOutputNode formatOutputNode;

    public ReviewStateGraph(McpClientManager mcpClient,
                            SubAgent securitySubAgent,
                            SubAgent bugSubAgent,
                            SubAgent performanceSubAgent,
                            FixGuidedVerificationFilter verificationFilter,
                            int agentRuns) {
        this.fetchDiffNode = new FetchDiffNode(mcpClient);
        this.parseDiffNode = new ParseDiffNode();
        this.securityReviewNode = new SpecializedReviewNode(securitySubAgent, "security_findings", agentRuns);
        this.bugReviewNode = new SpecializedReviewNode(bugSubAgent, "bug_findings", agentRuns);
        this.performanceReviewNode = new SpecializedReviewNode(performanceSubAgent, "perf_findings", agentRuns);
        this.aggregateNode = new AggregateResultsNode(List.of("security_findings", "bug_findings", "perf_findings"));
        this.verificationFilter = verificationFilter;
        this.formatOutputNode = new FormatOutputNode();
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
        log.info("[2/8] Parsing diff...");
        mergeState(state, parseDiffNode.execute(state));

        // Step 3-5: Multi-agent reviews
        log.info("[3/8] Security review...");
        mergeState(state, securityReviewNode.execute(state));

        log.info("[4/8] Bug review...");
        mergeState(state, bugReviewNode.execute(state));

        log.info("[5/8] Performance review...");
        mergeState(state, performanceReviewNode.execute(state));

        // Step 6: Aggregate findings
        log.info("[6/8] Aggregating findings...");
        mergeState(state, aggregateNode.execute(state));

        // Step 7: Verification filter
        log.info("[7/8] Verifying findings...");
        @SuppressWarnings("unchecked")
        List<ReviewFinding> allFindings = (List<ReviewFinding>) state.get("all_findings");
        List<ReviewFinding> verifiedFindings = verificationFilter.filter(allFindings, rawDiff);
        state.put("findings_result", verifiedFindings);
        log.info("Verification: {} -> {} findings", allFindings.size(), verifiedFindings.size());

        // Step 8: Format output
        log.info("[8/8] Formatting output...");
        mergeState(state, formatOutputNode.execute(state));

        log.info("=== Pipeline Complete ===");
        return state;
    }

    private void mergeState(Map<String, Object> state, Map<String, Object> nodeOutput) {
        if (nodeOutput != null) {
            state.putAll(nodeOutput);
        }
    }
}
