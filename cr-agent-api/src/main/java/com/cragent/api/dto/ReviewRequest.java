package com.cragent.api.dto;

import jakarta.validation.constraints.NotBlank;

public class ReviewRequest {

    @NotBlank(message = "repoPath is required")
    private String repoPath;

    @NotBlank(message = "baseRef is required")
    private String baseRef = "main";

    @NotBlank(message = "headRef is required")
    private String headRef = "HEAD";

    public ReviewRequest() {}

    public ReviewRequest(String repoPath, String baseRef, String headRef) {
        this.repoPath = repoPath;
        this.baseRef = baseRef;
        this.headRef = headRef;
    }

    public String getRepoPath() { return repoPath; }
    public void setRepoPath(String repoPath) { this.repoPath = repoPath; }

    public String getBaseRef() { return baseRef; }
    public void setBaseRef(String baseRef) { this.baseRef = baseRef; }

    public String getHeadRef() { return headRef; }
    public void setHeadRef(String headRef) { this.headRef = headRef; }
}
