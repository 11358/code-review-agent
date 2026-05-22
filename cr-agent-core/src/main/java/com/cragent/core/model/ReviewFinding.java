package com.cragent.core.model;

public class ReviewFinding {
    private String file;
    private int lineStart;
    private int lineEnd;
    private Severity severity;
    private ReviewCategory category;
    private String dimension;
    private String explanation;
    private String suggestion;
    private double confidenceScore = 1.0;
    private boolean verified = true;

    public ReviewFinding() {}

    public ReviewFinding(String file, int lineStart, int lineEnd, Severity severity,
                         ReviewCategory category, String dimension, String explanation, String suggestion) {
        this.file = file;
        this.lineStart = lineStart;
        this.lineEnd = lineEnd;
        this.severity = severity;
        this.category = category;
        this.dimension = dimension;
        this.explanation = explanation;
        this.suggestion = suggestion;
    }

    public String getFile() { return file; }
    public void setFile(String file) { this.file = file; }

    public int getLineStart() { return lineStart; }
    public void setLineStart(int lineStart) { this.lineStart = lineStart; }

    public int getLineEnd() { return lineEnd; }
    public void setLineEnd(int lineEnd) { this.lineEnd = lineEnd; }

    public Severity getSeverity() { return severity; }
    public void setSeverity(Severity severity) { this.severity = severity; }

    public ReviewCategory getCategory() { return category; }
    public void setCategory(ReviewCategory category) { this.category = category; }

    public String getDimension() { return dimension; }
    public void setDimension(String dimension) { this.dimension = dimension; }

    public String getExplanation() { return explanation; }
    public void setExplanation(String explanation) { this.explanation = explanation; }

    public String getSuggestion() { return suggestion; }
    public void setSuggestion(String suggestion) { this.suggestion = suggestion; }

    public double getConfidenceScore() { return confidenceScore; }
    public void setConfidenceScore(double confidenceScore) { this.confidenceScore = confidenceScore; }

    public boolean isVerified() { return verified; }
    public void setVerified(boolean verified) { this.verified = verified; }

    public String uniqueKey() {
        return file + ":" + lineStart + ":" + lineEnd + ":" + dimension;
    }
}
