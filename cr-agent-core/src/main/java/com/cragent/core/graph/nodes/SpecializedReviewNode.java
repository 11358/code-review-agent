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

    /**
     * @param subAgent  注入的审查 Agent（Security/Bug/Performance）
     * @param stateKey  输出到 state Map 时用的 key（如 "security_findings"）
     * @param runs      多轮并集轮数（默认 3）
     */
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

    /**
     * 通用审查节点执行逻辑：过滤 chunk → 截断 → 多轮并集 → 返回结果。
     *
     * 每个 Agent 只审查 relevantDimensions 中包含自己维度的 chunk，
     * 然后跑 N 轮取 UNION（有一轮报了就要），最大化召回。
     * 误报留给后续 DeepSeek 交叉验证过滤。
     */
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
            log.info("维度 {} 无相关 diff 内容", dimension);
            return Map.of(stateKey, List.of());
        }

        if (diffToReview.length() > 500_000) {
            diffToReview = diffToReview.substring(0, 500_000) + "\n... [diff 已截断] ...";
        }

        // 多轮并集：跑 N 轮，有一轮报了就要（最大化召回）
        // 误报由后续 DeepSeek 交叉验证过滤
        log.info("开始 {} 审查 ({} 字符, {} 轮 — 并集模式)...", dimension, diffToReview.length(), runs);

        Map<String, ReviewFinding> union = new LinkedHashMap<>();
        int totalRaw = 0;

        for (int run = 1; run <= runs; run++) {
            List<ReviewFinding> findings = subAgent.review(diffToReview, List.of());
            totalRaw += findings.size();
            log.info("{} 审查 第{}/{}轮: {} 条发现", dimension, run, runs, findings.size());

            for (ReviewFinding f : findings) {
                union.merge(f.uniqueKey(), f, (existing, incoming) ->
                        incoming.getExplanation().length() > existing.getExplanation().length() ? incoming : existing);
            }
        }

        List<ReviewFinding> result = new ArrayList<>(union.values());

        log.info("{} 审查并集: {} 条原始 × {} 轮 → {} 条唯一发现",
                dimension, totalRaw, runs, result.size());

        return Map.of(stateKey, result,
                "agent_decisions", dimension.toLowerCase() + "_review: " + result.size() + " 条唯一发现 (" + runs + " 轮并集)");
    }
}
