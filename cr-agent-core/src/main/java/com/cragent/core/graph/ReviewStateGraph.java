package com.cragent.core.graph;

import com.cragent.core.agent.SubAgent;
import com.cragent.core.filter.FixGuidedVerificationFilter;
import com.cragent.core.graph.nodes.*;
import com.cragent.core.mcp.McpClientManager;
import com.cragent.core.model.DiffChunk;
import com.cragent.core.model.ReviewFinding;
import com.cragent.core.model.ReviewResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 多 Agent 代码审查 StateGraph。
 *
 * 流水线: fetch_diff → parse_diff → deterministic_scan → [三 Agent 并行审查]
 *         → aggregate → verification_filter → format_output
 *
 * 状态传递：所有节点共享 ReviewState POJO，节点通过 setter 写入产出。
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
        this.securityReviewNode = new SpecializedReviewNode(securitySubAgent, agentRuns);
        this.bugReviewNode = new SpecializedReviewNode(bugSubAgent, agentRuns);
        this.performanceReviewNode = new SpecializedReviewNode(performanceSubAgent, agentRuns);
        this.aggregateNode = new AggregateResultsNode();
        this.verificationFilter = verificationFilter;
        this.formatOutputNode = new FormatOutputNode();
        this.reviewTimeoutSeconds = reviewTimeoutSeconds;
        this.reviewExecutor = Executors.newFixedThreadPool(3, r -> {
            Thread t = new Thread(r, "review-agent");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 启动 9 步审查流水线。
     *
     * @return 完整的 ReviewState，调用方通过 getReviewResult() 取最终结果
     */
    public ReviewState execute(String repoPath, String baseRef, String headRef) {
        ReviewState state = new ReviewState(repoPath, baseRef, headRef);

        log.info("╔══════════════════════════════════════════╗");
        log.info("║   多 Agent 代码审查流水线                  ║");
        log.info("╚══════════════════════════════════════════╝");
        log.info("仓库: {} ({} → {})", repoPath, baseRef, headRef);

        // [1/9]
        log.info("[1/9] 正在拉取 Git Diff...");
        fetchDiffNode.execute(state);
        String rawDiff = state.getRawDiff();
        if (rawDiff == null || rawDiff.isBlank()) {
            log.warn("Diff 为空，无需审查。");
            state.setReviewResult(ReviewResult.empty(repoPath, baseRef, headRef));
            return state;
        }

        // [2/9]
        log.info("[2/9] 正在解析 Diff...");
        parseDiffNode.execute(state);

        // [3/9]
        log.info("[3/9] 确定性扫描...");
        deterministicScanNode.execute(state);
        int deterministicCount = state.getDeterministicFindings().size();
        state.setDetCount(deterministicCount);
        log.info("确定性扫描: {} 条发现（100% 置信度，跳过验证）", deterministicCount);

        // 按维度裁切确定性扫描命中行：每个 Agent 只裁自己维度的命中行
        List<ReviewFinding> detFindings = state.getDeterministicFindings();
        List<DiffChunk> chunks = state.getDiffChunks();
        if (chunks != null && !detFindings.isEmpty()) {
            Map<String, String> securityDetLines = new HashMap<>();
            Map<String, String> bugDetLines = new HashMap<>();
            Map<String, String> perfDetLines = new HashMap<>();

            for (ReviewFinding f : detFindings) {
                String dim = f.getDimension();
                Map<String, String> target = switch (dim) {
                    case "SECURITY" -> securityDetLines;
                    case "BUGS" -> bugDetLines;
                    case "PERFORMANCE" -> perfDetLines;
                    default -> null;
                };
                if (target == null) continue;
                for (int line = f.getLineStart(); line <= f.getLineEnd(); line++) {
                    target.put(f.getFile() + ":" + line, f.getCategory().name());
                }
            }

            state.setSecurityDiffChunks(stripDetLines(chunks, securityDetLines));
            state.setBugDiffChunks(stripDetLines(chunks, bugDetLines));
            state.setPerfDiffChunks(stripDetLines(chunks, perfDetLines));
            log.info("确定性扫描 {} 条（SEC {} / BUG {} / PERF {}），按维度裁切完成",
                    detFindings.size(), securityDetLines.size(), bugDetLines.size(), perfDetLines.size());
        } else {
            state.setSecurityDiffChunks(chunks != null ? chunks : List.of());
            state.setBugDiffChunks(chunks != null ? chunks : List.of());
            state.setPerfDiffChunks(chunks != null ? chunks : List.of());
        }

        // [4-6/9] 三 Agent 并行
        log.info("[4-6/9] 并行 LLM 审查: 安全 + Bug + 性能 (超时: {}s)...", reviewTimeoutSeconds);
        long reviewStart = System.currentTimeMillis();

        CompletableFuture<Void> securityFuture = CompletableFuture.runAsync(
                () -> securityReviewNode.execute(state), reviewExecutor);
        CompletableFuture<Void> bugFuture = CompletableFuture.runAsync(
                () -> bugReviewNode.execute(state), reviewExecutor);
        CompletableFuture<Void> perfFuture = CompletableFuture.runAsync(
                () -> performanceReviewNode.execute(state), reviewExecutor);

        try {
            CompletableFuture.allOf(securityFuture, bugFuture, perfFuture)
                    .orTimeout(reviewTimeoutSeconds, TimeUnit.SECONDS)
                    .join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof TimeoutException) {
                log.warn("审查超时 ({}s)，使用已完成的部分结果继续", reviewTimeoutSeconds);
            } else {
                log.error("Agent 审查异常，使用已完成的部分结果继续", e);
            }
        }

        int completed = 0;
        if (mergeIfDone(securityFuture, "SECURITY")) completed++;
        if (mergeIfDone(bugFuture, "BUGS")) completed++;
        if (mergeIfDone(perfFuture, "PERFORMANCE")) completed++;

        log.info("审查阶段完成 ({}ms)，3 个维度中 {} 个成功",
                System.currentTimeMillis() - reviewStart, completed);

        // [7/9]
        log.info("[7/9] 正在合并去重...");
        aggregateNode.execute(state);

        // [8/9]
        log.info("[8/9] 交叉验证中...");
        List<ReviewFinding> allFindings = state.getAllFindings();
        List<ReviewFinding> verifiedFindings = verificationFilter.filter(allFindings, rawDiff);
        state.setFindingsResult(verifiedFindings);
        log.info("验证: {} -> {} 条发现", allFindings.size(), verifiedFindings.size());
        log.info("┌──────────────────────────────────────────┐");
        log.info("│ 确定性扫描:   {} 条（100% 确定）             │", state.getDetCount());
        log.info("│ LLM 聚合:     {} 条                       │", allFindings.size() - state.getDetCount());
        log.info("│ DeepSeek 保留: {} 条                       │", verifiedFindings.size());
        log.info("│ DeepSeek 过滤: {} 条                       │", allFindings.size() - verifiedFindings.size());
        log.info("└──────────────────────────────────────────┘");

        // [9/9]
        log.info("[9/9] 正在格式化输出...");
        formatOutputNode.execute(state);

        log.info("=== 流水线完成 ===");
        return state;
    }

    private boolean mergeIfDone(CompletableFuture<Void> future, String label) {
        if (future.isDone() && !future.isCompletedExceptionally()) {
            try {
                future.join();
                return true;
            } catch (CompletionException e) {
                log.warn("{} 审查失败: {}", label, e.getMessage());
                return false;
            }
        }
        future.cancel(true);
        log.warn("{} 审查未在时限内完成 — 跳过", label);
        return false;
    }

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

    // ── Diff 行裁切（按维度） ────────────────────────────────
    // 每个 Agent 只裁自己维度命中过的行，其余维度可见原代码

    private static final Pattern HUNK_HEADER = Pattern.compile(
            "^@@ -(\\d+)(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@");

    private List<DiffChunk> stripDetLines(
            List<DiffChunk> chunks, Map<String, String> detLines) {
        List<DiffChunk> result = new ArrayList<>();
        for (DiffChunk chunk : chunks) {
            DiffChunk stripped = new DiffChunk();
            stripped.setFilePath(chunk.getFilePath());
            stripped.setRelevantDimensions(new HashSet<>(chunk.getRelevantDimensions()));
            stripped.setContent(stripContent(chunk.getContent(), chunk.getFilePath(), detLines));
            result.add(stripped);
        }
        return result;
    }

    private String stripContent(String content, String filePath, Map<String, String> detLines) {
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
                if (detLines.containsKey(filePath + ":" + currentLine)) {
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
