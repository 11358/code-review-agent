package com.cragent.core.graph.nodes;

import com.cragent.core.model.ReviewFinding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

public class AggregateResultsNode {

    private static final Logger log = LoggerFactory.getLogger(AggregateResultsNode.class);

    private final List<String> stateKeys;

    /**
     * @param stateKeys 需要合并的 state key 列表（如 "deterministic_findings", "security_findings" 等）
     */
    public AggregateResultsNode(List<String> stateKeys) {
        this.stateKeys = stateKeys;
    }

    /**
     * 从 state 取出多个来源的 finding 列表，执行两轮去重后输出 "all_findings"。
     *
     * 第一轮：uniqueKey（文件:行号）去重，冲突时合并维度名、保留更高严重度。
     * 第二轮：跨来源合并，同文件+同类别+行距≤5 → 合并（解决确定性扫描和千问重复报同一问题）。
     */
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

        // 按 uniqueKey 去重，冲突时合并维度名
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

        // 排序：严重度（CRITICAL 在前）→ 文件 → 行号
        List<ReviewFinding> sorted = new ArrayList<>(deduped.values());
        sorted.sort(Comparator
                .comparing(ReviewFinding::getSeverity)
                .thenComparing(ReviewFinding::getFile)
                .thenComparingInt(ReviewFinding::getLineStart));

        // 跨来源合并：同文件 + 同类别 + 行距 ≤5 → 合并为一条
        List<ReviewFinding> merged = new ArrayList<>();
        for (ReviewFinding f : sorted) {
            if (!merged.isEmpty()) {
                ReviewFinding prev = merged.get(merged.size() - 1);
                if (prev.getFile().equals(f.getFile())
                        && prev.getCategory() == f.getCategory()
                        && f.getLineStart() - prev.getLineEnd() <= 5) {
                    prev.setLineEnd(Math.max(prev.getLineEnd(), f.getLineEnd()));
                    prev.mergeDimensions(f);
                    continue;
                }
            }
            merged.add(f);
        }

        log.info("发现汇总: {} 条原始 → {} 去重 → {} 合并 → {} 最终",
                allFindings.size(), deduped.size(), sorted.size(), merged.size());

        Map<String, Object> result = new HashMap<>();
        result.put("all_findings", merged);
        result.put("agent_decisions", "aggregate: " + allFindings.size() +
                " 条原始 → " + sorted.size() + " 条去重后");
        return result;
    }
}
