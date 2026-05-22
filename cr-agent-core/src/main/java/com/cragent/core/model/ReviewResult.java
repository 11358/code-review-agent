package com.cragent.core.model;

import java.util.ArrayList;
import java.util.List;

public class ReviewResult {
    private String repoPath;
    private String baseRef;
    private String headRef;
    private List<ChangedFile> changedFiles = new ArrayList<>();
    private List<ReviewFinding> findings = new ArrayList<>();
    private ReviewSummary summary;
    private long durationMs;
    private List<String> agentDecisionTrace = new ArrayList<>();

    public String getRepoPath() { return repoPath; }
    public void setRepoPath(String repoPath) { this.repoPath = repoPath; }

    public String getBaseRef() { return baseRef; }
    public void setBaseRef(String baseRef) { this.baseRef = baseRef; }

    public String getHeadRef() { return headRef; }
    public void setHeadRef(String headRef) { this.headRef = headRef; }

    public List<ChangedFile> getChangedFiles() { return changedFiles; }
    public void setChangedFiles(List<ChangedFile> changedFiles) { this.changedFiles = changedFiles; }

    public List<ReviewFinding> getFindings() { return findings; }
    public void setFindings(List<ReviewFinding> findings) { this.findings = findings; }

    public ReviewSummary getSummary() { return summary; }
    public void setSummary(ReviewSummary summary) { this.summary = summary; }

    public long getDurationMs() { return durationMs; }
    public void setDurationMs(long durationMs) { this.durationMs = durationMs; }

    public List<String> getAgentDecisionTrace() { return agentDecisionTrace; }
    public void setAgentDecisionTrace(List<String> agentDecisionTrace) { this.agentDecisionTrace = agentDecisionTrace; }

    public void addTrace(String message) {
        this.agentDecisionTrace.add(message);
    }

    public static ReviewResult empty(String repoPath, String baseRef, String headRef) {
        ReviewResult result = new ReviewResult();
        result.repoPath = repoPath;
        result.baseRef = baseRef;
        result.headRef = headRef;
        result.summary = new ReviewSummary();
        return result;
    }
}
