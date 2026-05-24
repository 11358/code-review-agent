package com.cragent.core.graph.nodes;

import com.cragent.core.agent.SubAgent;
import com.cragent.core.model.ReviewFinding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ReviewNode {

    private static final Logger log = LoggerFactory.getLogger(ReviewNode.class);

    private final SubAgent subAgent;

    public ReviewNode(SubAgent subAgent) {
        this.subAgent = subAgent;
    }

    /**
     * ⚠ 旧版审查节点：单 Agent 单轮审查，已被 SpecializedReviewNode 替代。
     * 保留此文件仅作为历史参考，主流水线中不再使用。
     */
    public Map<String, Object> execute(Map<String, Object> state) {
        String rawDiff = (String) state.getOrDefault("raw_diff", "");

        if (rawDiff == null || rawDiff.isBlank()) {
            log.warn("无 diff 内容可供审查");
            Map<String, Object> result = new HashMap<>();
            result.put("findings_result", List.of());
            return result;
        }

        // diff 超限时截断，防止超出 LLM context window
        String diffToReview = rawDiff;
        int maxSize = 500000;
        if (diffToReview.length() > maxSize) {
            log.warn("Diff 过大 ({} 字符)，截断至 {}", rawDiff.length(), maxSize);
            diffToReview = rawDiff.substring(0, maxSize) + "\n... [diff truncated] ...";
        }

        log.info("开始审查，Agent: {}", subAgent.getDimensionName());
        List<ReviewFinding> findings = subAgent.review(diffToReview, List.of());
        log.info("审查完成: {} 条发现", findings.size());

        Map<String, Object> result = new HashMap<>();
        result.put("findings_result", findings);
        result.put("agent_decisions", "review: " + subAgent.getDimensionName() +
                " 发现 " + findings.size() + " 个问题");
        return result;
    }
}
