package com.cragent.api.dto;

import com.cragent.core.model.ReviewFinding;
import com.cragent.core.model.ReviewResult;
import com.cragent.core.model.ReviewSummary;

import java.util.List;

public class ReviewResponse {

    private String repoPath;
    private String baseRef;
    private String headRef;
    private int totalFindings;
    private int criticalCount;
    private int warningCount;
    private int infoCount;
    private long durationMs;
    private List<ReviewFinding> findings;
    private ReviewSummary summary;

    public static ReviewResponse from(ReviewResult result) {
        ReviewResponse resp = new ReviewResponse();
        resp.repoPath = result.getRepoPath();
        resp.baseRef = result.getBaseRef();
        resp.headRef = result.getHeadRef();
        resp.durationMs = result.getDurationMs();

        if (result.getSummary() != null) {
            resp.totalFindings = result.getSummary().getTotalFindings();
            resp.summary = result.getSummary();
            if (result.getSummary().getSeverityCounts() != null) {
                resp.criticalCount = result.getSummary().getSeverityCounts()
                        .getOrDefault(com.cragent.core.model.Severity.CRITICAL, 0);
                resp.warningCount = result.getSummary().getSeverityCounts()
                        .getOrDefault(com.cragent.core.model.Severity.WARNING, 0);
                resp.infoCount = result.getSummary().getSeverityCounts()
                        .getOrDefault(com.cragent.core.model.Severity.INFO, 0);
            }
        }

        resp.findings = result.getFindings();
        return resp;
    }

    public String getRepoPath() { return repoPath; }
    public void setRepoPath(String repoPath) { this.repoPath = repoPath; }
    public String getBaseRef() { return baseRef; }
    public void setBaseRef(String baseRef) { this.baseRef = baseRef; }
    public String getHeadRef() { return headRef; }
    public void setHeadRef(String headRef) { this.headRef = headRef; }
    public int getTotalFindings() { return totalFindings; }
    public void setTotalFindings(int totalFindings) { this.totalFindings = totalFindings; }
    public int getCriticalCount() { return criticalCount; }
    public void setCriticalCount(int criticalCount) { this.criticalCount = criticalCount; }
    public int getWarningCount() { return warningCount; }
    public void setWarningCount(int warningCount) { this.warningCount = warningCount; }
    public int getInfoCount() { return infoCount; }
    public void setInfoCount(int infoCount) { this.infoCount = infoCount; }
    public long getDurationMs() { return durationMs; }
    public void setDurationMs(long durationMs) { this.durationMs = durationMs; }
    public List<ReviewFinding> getFindings() { return findings; }
    public void setFindings(List<ReviewFinding> findings) { this.findings = findings; }
    public ReviewSummary getSummary() { return summary; }
    public void setSummary(ReviewSummary summary) { this.summary = summary; }
}
