package com.cragent.core.graph;

import com.cragent.core.model.ChangedFile;
import com.cragent.core.model.DiffChunk;
import com.cragent.core.model.ReviewFinding;
import com.cragent.core.model.ReviewResult;

import java.util.List;

/**
 * 流水线节点间共享的强类型状态对象，替代原先的 Map<String, Object>。
 * 输入参数（repoPath/baseRef/headRef/startTime）由构造函数设置，后续节点通过 setter 写入产出。
 */
public class ReviewState {

    // Git 仓库本地路径（如 D:/projects/my-app），输入参数，构造函数设置
    private final String repoPath;
    // 对比基准分支（如 main），输入参数，构造函数设置
    private final String baseRef;
    // 对比目标分支（如 HEAD），输入参数，构造函数设置
    private final String headRef;
    // 流水线启动时间戳（ms），用于计算端到端耗时
    private final long startTime;

    // FetchDiffNode 产出：git diff 完整 unified diff 文本
    private String rawDiff;
    // ParseDiffNode 产出：变更文件列表（路径 + 增删行数 + 变更类型）
    private List<ChangedFile> changedFiles = List.of();
    // ParseDiffNode 产出：按文件拆分的 diff 片段，含 relevantDimensions 路由标记
    private List<DiffChunk> diffChunks = List.of();
    // 确定性扫描后按维度裁切的 diff 副本，各 Agent 只拿自己维度裁切后的版本
    private List<DiffChunk> securityDiffChunks = List.of();
    private List<DiffChunk> bugDiffChunks = List.of();
    private List<DiffChunk> perfDiffChunks = List.of();

    // DeterministicScanNode 产出：确定性正则扫描的 findings（confidence=1.0，跳过验证）
    private List<ReviewFinding> deterministicFindings = List.of();
    // SpecializedReviewNode(security) 产出：安全维度 LLM 审查结果
    private List<ReviewFinding> securityFindings = List.of();
    // SpecializedReviewNode(bug) 产出：Bug 维度 LLM 审查结果
    private List<ReviewFinding> bugFindings = List.of();
    // SpecializedReviewNode(perf) 产出：性能维度 LLM 审查结果
    private List<ReviewFinding> perfFindings = List.of();

    // AggregateResultsNode 产出：四个来源合并去重后的全部 findings
    private List<ReviewFinding> allFindings = List.of();
    // FixGuidedVerificationFilter 产出：DeepSeek 交叉验证后的最终 findings
    private List<ReviewFinding> findingsResult = List.of();

    // FormatOutputNode 产出：组装完成的最终审查报告
    private ReviewResult reviewResult;

    // 确定性扫描命中数量（StateGraph 内部计数，用于日志分栏显示）
    private int detCount;

    public ReviewState(String repoPath, String baseRef, String headRef) {
        this.repoPath = repoPath;
        this.baseRef = baseRef;
        this.headRef = headRef;
        this.startTime = System.currentTimeMillis();
    }

    // ── Input parameters (getters only) ──

    public String getRepoPath() { return repoPath; }
    public String getBaseRef() { return baseRef; }
    public String getHeadRef() { return headRef; }
    public long getStartTime() { return startTime; }

    // ── Pipeline state ──

    public String getRawDiff() { return rawDiff; }
    public void setRawDiff(String rawDiff) { this.rawDiff = rawDiff; }

    public List<ChangedFile> getChangedFiles() { return changedFiles; }
    public void setChangedFiles(List<ChangedFile> changedFiles) { this.changedFiles = changedFiles; }

    public List<DiffChunk> getDiffChunks() { return diffChunks; }
    public void setDiffChunks(List<DiffChunk> diffChunks) { this.diffChunks = diffChunks; }

    public List<DiffChunk> getSecurityDiffChunks() { return securityDiffChunks; }
    public void setSecurityDiffChunks(List<DiffChunk> chunks) { this.securityDiffChunks = chunks; }
    public List<DiffChunk> getBugDiffChunks() { return bugDiffChunks; }
    public void setBugDiffChunks(List<DiffChunk> chunks) { this.bugDiffChunks = chunks; }
    public List<DiffChunk> getPerfDiffChunks() { return perfDiffChunks; }
    public void setPerfDiffChunks(List<DiffChunk> chunks) { this.perfDiffChunks = chunks; }

    /** SpecializedReviewNode 调用：按维度名取对应裁切后的 diff */
    public List<DiffChunk> getDiffChunksByDimension(String dimension) {
        return switch (dimension.toUpperCase()) {
            case "SECURITY" -> securityDiffChunks;
            case "BUGS" -> bugDiffChunks;
            case "PERFORMANCE" -> perfDiffChunks;
            default -> diffChunks;
        };
    }

    // ── Findings from four sources ──

    public List<ReviewFinding> getDeterministicFindings() { return deterministicFindings; }
    public void setDeterministicFindings(List<ReviewFinding> findings) { this.deterministicFindings = findings; }

    public List<ReviewFinding> getSecurityFindings() { return securityFindings; }
    public void setSecurityFindings(List<ReviewFinding> findings) { this.securityFindings = findings; }

    public List<ReviewFinding> getBugFindings() { return bugFindings; }
    public void setBugFindings(List<ReviewFinding> findings) { this.bugFindings = findings; }

    public List<ReviewFinding> getPerfFindings() { return perfFindings; }
    public void setPerfFindings(List<ReviewFinding> findings) { this.perfFindings = findings; }

    // ── Aggregation ──

    public List<ReviewFinding> getAllFindings() { return allFindings; }
    public void setAllFindings(List<ReviewFinding> findings) { this.allFindings = findings; }

    public List<ReviewFinding> getFindingsResult() { return findingsResult; }
    public void setFindingsResult(List<ReviewFinding> findings) { this.findingsResult = findings; }

    // ── Final output ──

    public ReviewResult getReviewResult() { return reviewResult; }
    public void setReviewResult(ReviewResult reviewResult) { this.reviewResult = reviewResult; }

    // ── Internal counters ──

    public int getDetCount() { return detCount; }
    public void setDetCount(int detCount) { this.detCount = detCount; }

    /**
     * 按维度名将 findings 路由到对应字段，供 SpecializedReviewNode 并行写入。
     */
    public void setFindingsByDimension(String dimension, List<ReviewFinding> findings) {
        if (findings == null) findings = List.of();
        switch (dimension.toUpperCase()) {
            case "SECURITY" -> setSecurityFindings(findings);
            case "BUGS" -> setBugFindings(findings);
            case "PERFORMANCE" -> setPerfFindings(findings);
            default -> throw new IllegalArgumentException("Unknown dimension: " + dimension);
        }
    }
}
