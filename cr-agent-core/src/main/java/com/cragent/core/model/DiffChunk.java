package com.cragent.core.model;

import java.util.HashSet;
import java.util.Set;

public class DiffChunk {
    private String filePath;
    private String content;
    private Set<String> relevantDimensions = new HashSet<>();

    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public Set<String> getRelevantDimensions() { return relevantDimensions; }
    public void setRelevantDimensions(Set<String> relevantDimensions) { this.relevantDimensions = relevantDimensions; }
}
