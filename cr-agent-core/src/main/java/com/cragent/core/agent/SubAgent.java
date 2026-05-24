package com.cragent.core.agent;

import com.cragent.core.model.ReviewFinding;

import java.util.List;

/**
 * Agent 接口。所有审查 Agent 实现此接口，统一调用方式。
 * 三个实现：SecuritySubAgent、BugSubAgent、PerformanceSubAgent。
 * 还有一个 GeneralSubAgent（单 Agent 全维度审查，主流水线未使用）。
 */
public interface SubAgent {

    /** @return 维度名（SECURITY / BUGS / PERFORMANCE），用于 chunk 路由和日志 */
    String getDimensionName();

    /** @param diffContent      待审查的 diff 文本
     *  @param changedFilePaths 变更文件路径列表
     *  @return 审查发现列表 */
    List<ReviewFinding> review(String diffContent, List<String> changedFilePaths);
}
