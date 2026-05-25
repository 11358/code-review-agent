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
 * 多 Agent 代码审查 StateGraph（第 3 阶段）。
 *
 * 流水线: fetch_diff → parse_diff → deterministic_scan → [三 Agent 并行审查]
 *         → aggregate → verification_filter → format_output
 *
 * 状态传递：所有节点通过共享的 Map<String, Object> 交换数据，
 *           每个节点的输出通过 mergeState() 合并入共享 state。
 */
public class ReviewStateGraph {

    private static final Logger log = LoggerFactory.getLogger(ReviewStateGraph.class);

    // 9 个流水线节点，在构造器中一次性创建
    private final FetchDiffNode fetchDiffNode;              // [1/9] 拉取 git diff
    private final ParseDiffNode parseDiffNode;              // [2/9] 解析 diff 结构
    private final DeterministicScanNode deterministicScanNode; // [3/9] 确定性 regex 扫描
    private final SpecializedReviewNode securityReviewNode;    // [4/9] 安全审查（并行）
    private final SpecializedReviewNode bugReviewNode;         // [5/9] Bug 审查（并行）
    private final SpecializedReviewNode performanceReviewNode; // [6/9] 性能审查（并行）
    private final AggregateResultsNode aggregateNode;          // [7/9] 合并去重
    private final FixGuidedVerificationFilter verificationFilter; // [8/9] DeepSeek 交叉验证
    private final FormatOutputNode formatOutputNode;           // [9/9] 格式化输出报告
    private final ExecutorService reviewExecutor;              // 固定 3 线程池，Agent 三路并行
    private final long reviewTimeoutSeconds;                   // Agent 阶段总超时秒数

    /**
     * 创建 StateGraph 及其内部的 9 个节点。
     *
     * @param mcpClient             Git 工具服务客户端
     * @param securitySubAgent      安全审查 Agent（注入 Bean 名: securitySubAgent）
     * @param bugSubAgent           Bug 审查 Agent（注入 Bean 名: bugSubAgent）
     * @param performanceSubAgent   性能审查 Agent（注入 Bean 名: performanceSubAgent）
     * @param verificationFilter    DeepSeek 交叉验证过滤器
     * @param agentRuns             每个 Agent 跑几轮（默认 3，多轮并集最大化召回）
     * @param reviewTimeoutSeconds  三路并行总超时秒数（默认 120s）
     */
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

    /**
     * 启动 9 步审查流水线，返回包含 review_result 的完整 state Map。
     *
     * 调用链: Controller → OrchestrationService → 此方法 → 9 个 Node 依次执行
     *
     * @param repoPath Git 仓库本地路径（如 D:/projects/my-app）
     * @param baseRef  基准分支（如 main，对比的旧版本）
     * @param headRef  目标分支（如 HEAD，对比的新版本）
     * @return state Map，其中 "review_result" key 存放最终的 ReviewResult 对象
     */
    public Map<String, Object> execute(String repoPath, String baseRef, String headRef) {
        long startTime = System.currentTimeMillis();

        Map<String, Object> state = new HashMap<>();
        state.put("repo_path", repoPath);
        state.put("base_ref", baseRef);
        state.put("head_ref", headRef);
        state.put("_start_time", startTime);

        log.info("╔══════════════════════════════════════════╗");
        log.info("║   多 Agent 代码审查流水线                  ║");
        log.info("╚══════════════════════════════════════════╝");
        log.info("仓库: {} ({} → {})", repoPath, baseRef, headRef);

        // [1/9] 获取 diff
        log.info("[1/9] 正在拉取 Git Diff...");
        mergeState(state, fetchDiffNode.execute(state));
        String rawDiff = (String) state.get("raw_diff");
        if (rawDiff == null || rawDiff.isBlank()) {
            log.warn("Diff 为空，无需审查。");
            state.put("review_result", com.cragent.core.model.ReviewResult.empty(repoPath, baseRef, headRef));
            return state;
        }

        // [2/9] 解析 diff 为文件级 chunk
        log.info("[2/9] 正在解析 Diff...");
        mergeState(state, parseDiffNode.execute(state));

        // [3/9] 确定性 regex 扫描（LLM 之前，confidence=1.0）
        log.info("[3/9] 确定性扫描...");
        mergeState(state, deterministicScanNode.execute(state));
        @SuppressWarnings("unchecked")
        List<ReviewFinding> detFindings = (List<ReviewFinding>) state.get("deterministic_findings");
        int deterministicCount = detFindings.size();
        state.put("_det_count", deterministicCount);
        log.info("确定性扫描: {} 条发现（100% 置信度，跳过验证）", deterministicCount);

        // 裁切确定性命中行，让 LLM 不再重复报
        // 将 finding 的行号区间展开为逐行 key（file:line），供 stripContent O(1) 查找
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
            log.info("已从 diff chunk 中裁切 {} 行确定性命中，LLM 不再看到", detLineSet.size());
        }

        // [4-6/9] 三 Agent 并行 LLM 审查
        log.info("[4-6/9] 并行 LLM 审查: 安全 + Bug + 性能 (超时: {}s)...", reviewTimeoutSeconds);
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
            log.warn("审查超时 ({}s)，使用已完成的部分结果继续", reviewTimeoutSeconds);
        }

        int completed = 0;
        if (mergeIfDone(state, securityFuture, "SECURITY")) completed++;
        if (mergeIfDone(state, bugFuture, "BUGS")) completed++;
        if (mergeIfDone(state, perfFuture, "PERFORMANCE")) completed++;

        log.info("审查阶段完成 ({}ms)，3 个维度中 {} 个成功",
                System.currentTimeMillis() - reviewStart, completed);

        // [7/9] 合并去重
        log.info("[7/9] 正在合并去重...");
        mergeState(state, aggregateNode.execute(state));

        // [8/9] DeepSeek 交叉验证
        log.info("[8/9] 交叉验证中...");
        @SuppressWarnings("unchecked")
        List<ReviewFinding> allFindings = (List<ReviewFinding>) state.get("all_findings");
        List<ReviewFinding> verifiedFindings = verificationFilter.filter(allFindings, rawDiff);
        state.put("findings_result", verifiedFindings);
        log.info("验证: {} -> {} 条发现", allFindings.size(), verifiedFindings.size());
        int detCount = state.get("_det_count") instanceof Integer i ? i : 0;
        log.info("┌──────────────────────────────────────────┐");
        log.info("│ 确定性扫描:   {} 条（100% 确定）             │", detCount);
        log.info("│ LLM 聚合:     {} 条                       │", allFindings.size() - detCount);
        log.info("│ DeepSeek 保留: {} 条                       │", verifiedFindings.size());
        log.info("│ DeepSeek 过滤: {} 条                       │", allFindings.size() - verifiedFindings.size());
        log.info("└──────────────────────────────────────────┘");

        // [9/9] 格式化输出
        log.info("[9/9] 正在格式化输出...");
        mergeState(state, formatOutputNode.execute(state));

        log.info("=== 流水线完成 ===");
        return state;
    }

    /**
     * 检查单个 Agent Future 是否正常完成，完成则合并结果，失败/超时则取消并记警告。
     * 设计意图：一个 Agent 挂了不影响其余 Agent 的结果。
     *
     * @param state  共享状态 Map
     * @param future Agent 的异步结果
     * @param label  维度名（SECURITY/BUGS/PERFORMANCE），仅用于日志
     * @return true=成功合并，false=失败/超时
     */
    private boolean mergeIfDone(Map<String, Object> state, CompletableFuture<Map<String, Object>> future, String label) {
        if (future.isDone() && !future.isCompletedExceptionally()) {
            try {
                mergeState(state, future.join());
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

    /**
     * 关闭 Agent 并行线程池。由 Spring 的 destroyMethod 回调。
     * 先等 30 秒让正在执行的 Agent 优雅完成，超时则强制中断。
     */
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

    /**
     * 将节点的输出 Map 合并到共享 state。
     * 各节点通过隐式 key 约定通信（如 "raw_diff"、"security_findings" 等）。
     */
    private void mergeState(Map<String, Object> state, Map<String, Object> nodeOutput) {
        if (nodeOutput != null) {
            state.putAll(nodeOutput);
        }
    }

    // ── Diff 行裁切 ──────────────────────────────────────────
    // 目的：确定性扫描已经抓到的行，从 diff 中替换为注释，
    //       让 LLM 不再看到这些代码，从源头消除同类重复报。

    // 解析 unified diff 的 hunk 头：@@ -旧起始,旧行数 +新起始,新行数 @@
    private static final Pattern HUNK_HEADER = Pattern.compile(
            "^@@ -(\\d+)(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@");

    /**
     * 遍历所有 chunk，深拷贝后裁切其中被确定性命中的行。
     * 深拷贝是为了不污染原始 chunk（三个 Agent 并行时共享引用）。
     *
     * @param chunks   ParseDiff 产出的 diff 切片列表
     * @param detLines "文件路径:行号" 集合，来自确定性扫描的命中
     * @return 裁切后的新 chunk 列表（LLM 只看到未命中部分）
     */
    @SuppressWarnings("unchecked")
    private List<ParseDiffNode.DiffChunk> stripDetLines(
            List<ParseDiffNode.DiffChunk> chunks, Set<String> detLines) {
        List<ParseDiffNode.DiffChunk> result = new ArrayList<>();
        for (ParseDiffNode.DiffChunk chunk : chunks) {
            // 深拷贝：新的 DiffChunk 对象，共享 filePath 和 dimensions 的副本
            ParseDiffNode.DiffChunk stripped = new ParseDiffNode.DiffChunk();
            stripped.setFilePath(chunk.getFilePath());
            stripped.setRelevantDimensions(new HashSet<>(chunk.getRelevantDimensions()));
            // 核心：替换 content 中的命中行为注释
            stripped.setContent(stripContent(chunk.getContent(), chunk.getFilePath(), detLines));
            result.add(stripped);
        }
        return result;
    }

    /**
     * 逐行处理单个 diff chunk 的文本。
     * 跟踪 hunk header 中的行号，当行号命中 detLines 时替换为注释。
     *
     * @param content  diff 文本（如 "@@ -1,5 +10,7 @@\n+new line\n unchanged"）
     * @param filePath 当前文件路径（用于拼接查找 key）
     * @param detLines "文件:行号" 的命中集合
     * @return 裁切后的 diff 文本
     */
    private String stripContent(String content, String filePath, Set<String> detLines) {
        StringBuilder sb = new StringBuilder(content.length() + 256); // 预分配，注释行可能更长
        int currentLine = 0; // 当前在**新文件**中的行号

        for (String line : content.split("\n", -1)) {
            // 1. hunk 头 → 重置行号计数器
            Matcher hm = HUNK_HEADER.matcher(line);
            if (hm.find()) {
                currentLine = Integer.parseInt(hm.group(2)); // group(2) = 新文件起始行号
                sb.append(line).append("\n");
                continue;
            }

            // 2. 新增行（以 + 开头但不是 +++ 文件头）
            if (line.startsWith("+") && !line.startsWith("+++")) {
                if (detLines.contains(filePath + ":" + currentLine)) {
                    // 命中 → 替换为注释，千问看到的是一行无害的注释而非真实代码
                    sb.append("+// [skipped: deterministic]\n");
                } else {
                    // 未命中 → 保留原样，让千问审查
                    sb.append(line).append("\n");
                }
                currentLine++; // 新增行使新文件行号 +1
            } else {
                // 3. 上下文行（空格开头）或删除行（- 开头）
                //    删除行不增加新文件行号（它是旧文件的）
                if (!line.startsWith("-")) currentLine++;
                sb.append(line).append("\n");
            }
        }
        return sb.toString();
    }
}
