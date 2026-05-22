package com.cragent.core.model;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ReviewSummary {
    private int totalFindings;
    private int verifiedFindings;
    private Map<Severity, Integer> severityCounts = new HashMap<>();
    private Map<ReviewCategory, Integer> categoryCounts = new HashMap<>();
    private Map<String, Integer> findingsPerFile = new HashMap<>();
    private long durationMs;

    public int getTotalFindings() { return totalFindings; }
    public void setTotalFindings(int totalFindings) { this.totalFindings = totalFindings; }

    public int getVerifiedFindings() { return verifiedFindings; }
    public void setVerifiedFindings(int verifiedFindings) { this.verifiedFindings = verifiedFindings; }

    public Map<Severity, Integer> getSeverityCounts() { return severityCounts; }
    public void setSeverityCounts(Map<Severity, Integer> severityCounts) { this.severityCounts = severityCounts; }

    public Map<ReviewCategory, Integer> getCategoryCounts() { return categoryCounts; }
    public void setCategoryCounts(Map<ReviewCategory, Integer> categoryCounts) { this.categoryCounts = categoryCounts; }

    public Map<String, Integer> getFindingsPerFile() { return findingsPerFile; }
    public void setFindingsPerFile(Map<String, Integer> findingsPerFile) { this.findingsPerFile = findingsPerFile; }

    public long getDurationMs() { return durationMs; }
    public void setDurationMs(long durationMs) { this.durationMs = durationMs; }

    public static ReviewSummary from(List<ReviewFinding> findings) {
        ReviewSummary summary = new ReviewSummary();
        summary.totalFindings = findings.size();

        int verified = 0;
        for (ReviewFinding f : findings) {
            if (f.isVerified()) verified++;

            summary.severityCounts.merge(f.getSeverity(), 1, Integer::sum);
            summary.categoryCounts.merge(f.getCategory(), 1, Integer::sum);
            summary.findingsPerFile.merge(f.getFile(), 1, Integer::sum);
        }
        summary.verifiedFindings = verified;
        return summary;
    }
}
