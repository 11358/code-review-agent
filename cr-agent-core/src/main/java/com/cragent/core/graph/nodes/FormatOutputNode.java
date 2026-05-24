package com.cragent.core.graph.nodes;

import com.cragent.core.model.ChangedFile;
import com.cragent.core.model.ReviewFinding;
import com.cragent.core.model.ReviewResult;
import com.cragent.core.model.ReviewSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FormatOutputNode {

    private static final Logger log = LoggerFactory.getLogger(FormatOutputNode.class);

    /**
     * 将 findings_result + changed_files 组装为 ReviewResult，
     * 调用 ReviewSummary.from() 统计严重度/类别/文件分布，计算端到端耗时。
     * 产出 "review_result" key。
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> execute(Map<String, Object> state) {
        String repoPath = (String) state.getOrDefault("repo_path", "");
        String baseRef = (String) state.getOrDefault("base_ref", "");
        String headRef = (String) state.getOrDefault("head_ref", "");

        List<ReviewFinding> findings = (List<ReviewFinding>) state.getOrDefault("findings_result", List.of());
        List<ChangedFile> changedFiles = (List<ChangedFile>) state.getOrDefault("changed_files", List.of());

        ReviewResult result = ReviewResult.empty(repoPath, baseRef, headRef);
        result.setChangedFiles(changedFiles);
        result.setFindings(findings);
        result.setSummary(ReviewSummary.from(findings));

        long startTime = state.containsKey("_start_time") ? (long) state.get("_start_time") : System.currentTimeMillis();
        result.setDurationMs(System.currentTimeMillis() - startTime);

        log.info("审查完成: {} 条发现, 严重度 {}, 类别 {}",
                result.getSummary().getTotalFindings(),
                result.getSummary().getSeverityCounts(),
                result.getSummary().getCategoryCounts());

        Map<String, Object> output = new HashMap<>();
        output.put("review_result", result);
        output.put("agent_decisions", "format: 最终报告已生成");
        return output;
    }
}
