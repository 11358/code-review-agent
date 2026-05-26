package com.cragent.core.graph.nodes;

import com.cragent.core.graph.ReviewState;
import com.cragent.core.model.ReviewFinding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class AggregateResultsNode {

    private static final Logger log = LoggerFactory.getLogger(AggregateResultsNode.class);

    /**
     * 将四个来源的 finding 合并去重，写入 state.allFindings。
     *
     * 第一轮：uniqueKey 去重，冲突时保留更高严重度、合并维度名。
     * 第二轮：跨来源合并（同文件+同类别+行距≤5）。
     */
    public void execute(ReviewState state) {
        List<ReviewFinding> allFindings = new ArrayList<>();
        allFindings.addAll(state.getDeterministicFindings());
        allFindings.addAll(state.getSecurityFindings());
        allFindings.addAll(state.getBugFindings());
        allFindings.addAll(state.getPerfFindings());

        // 按 uniqueKey 去重
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

        // 合并前：文件 → 类别 → 行号
        List<ReviewFinding> sorted = new ArrayList<>(deduped.values());
        sorted.sort(Comparator
                .comparing(ReviewFinding::getFile)
                .thenComparing(ReviewFinding::getCategory)
                .thenComparingInt(ReviewFinding::getLineStart));

        // 跨来源合并：同文件+同类别+行距≤5
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

        // 合并后按严重度排序输出
        merged.sort(Comparator
                .comparing(ReviewFinding::getSeverity)
                .thenComparing(ReviewFinding::getFile)
                .thenComparingInt(ReviewFinding::getLineStart));

        log.info("发现汇总: {} 条原始 → {} 去重 → {} 合并 → {} 最终",
                allFindings.size(), deduped.size(), sorted.size(), merged.size());

        state.setAllFindings(merged);
    }
}
