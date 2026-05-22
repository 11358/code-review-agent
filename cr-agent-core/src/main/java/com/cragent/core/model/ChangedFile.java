package com.cragent.core.model;

public class ChangedFile {
    private String filePath;
    private ChangeType changeType;
    private int additions;
    private int deletions;

    public ChangedFile() {}

    public ChangedFile(String filePath, ChangeType changeType, int additions, int deletions) {
        this.filePath = filePath;
        this.changeType = changeType;
        this.additions = additions;
        this.deletions = deletions;
    }

    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }

    public ChangeType getChangeType() { return changeType; }
    public void setChangeType(ChangeType changeType) { this.changeType = changeType; }

    public int getAdditions() { return additions; }
    public void setAdditions(int additions) { this.additions = additions; }

    public int getDeletions() { return deletions; }
    public void setDeletions(int deletions) { this.deletions = deletions; }
}
