package com.cragent.core.graph.nodes;

import com.cragent.core.agent.SubAgent;
import com.cragent.core.graph.ReviewState;
import com.cragent.core.model.DiffChunk;
import com.cragent.core.model.ReviewFinding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class SpecializedReviewNode {

    private static final Logger log = LoggerFactory.getLogger(SpecializedReviewNode.class);

    private final SubAgent subAgent;
    private final int runs;

    /**
     * @param subAgent 注入的审查 Agent（Security/Bug/Performance）
     * @param runs     多轮并集轮数（默认 3）
     */
    public SpecializedReviewNode(SubAgent subAgent, int runs) {
        this.subAgent = subAgent;
        this.runs = Math.max(1, runs);
    }

    public String getDimensionName() {
        return subAgent.getDimensionName();
    }

    /**
     * 过滤 chunk → 截断 → 多轮并集 → 通过 state.setFindingsByDimension 写入对应字段。
     */
    public void execute(ReviewState state) {
        String rawDiff = state.getRawDiff();
        List<DiffChunk> chunks = state.getDiffChunks();

        String dimension = subAgent.getDimensionName();
        StringBuilder relevantDiff = new StringBuilder();

        if (chunks != null) {
            for (DiffChunk chunk : chunks) {
                if (chunk.getRelevantDimensions().contains(dimension)) {
                    relevantDiff.append("=== ").append(chunk.getFilePath()).append(" ===\n");
                    relevantDiff.append(chunk.getContent()).append("\n\n");
                }
            }
        }

        // 有 chunk 路由信息时，若无匹配维度则跳过 LLM 调用（省 token）
        // 无 chunk 时（parse 失败等）fallback 到 rawDiff
        String diffToReview;
        if (chunks != null && !chunks.isEmpty() && relevantDiff.isEmpty()) {
            log.info("维度 {} 无匹配 chunk，跳过 LLM 审查", dimension);
            state.setFindingsByDimension(dimension, List.of());
            return;
        }
        diffToReview = !relevantDiff.isEmpty() ? relevantDiff.toString() : rawDiff;
        if (diffToReview == null || diffToReview.isBlank()) {
            log.info("维度 {} 无相关 diff 内容", dimension);
            state.setFindingsByDimension(dimension, List.of());
            return;
        }

        if (diffToReview.length() > 500_000) {
            diffToReview = diffToReview.substring(0, 500_000) + "\n... [diff 已截断] ...";
        }

        log.info("{} chunks={} relevantEmpty={} rawLen={}", dimension,
                chunks != null ? chunks.size() : -1, relevantDiff.isEmpty(), rawDiff != null ? rawDiff.length() : -1);
        log.info("开始 {} 审查 ({} 字符, {} 轮 — 并集模式)...", dimension, diffToReview.length(), runs);
        log.info("{} diff[0..300]: {}", dimension,
                diffToReview.length() > 300 ? diffToReview.substring(0, 300) : diffToReview);

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

        state.setFindingsByDimension(dimension, result);
    }
}
