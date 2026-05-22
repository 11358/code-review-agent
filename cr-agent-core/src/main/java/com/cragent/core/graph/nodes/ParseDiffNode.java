package com.cragent.core.graph.nodes;

import com.cragent.core.model.ChangedFile;
import com.cragent.core.model.ChangeType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ParseDiffNode {

    private static final Logger log = LoggerFactory.getLogger(ParseDiffNode.class);

    private static final Pattern DIFF_FILE_PATTERN = Pattern.compile(
            "^diff --git a/(.+) b/(.+)$", Pattern.MULTILINE);
    private static final Pattern ADD_DEL_PATTERN = Pattern.compile(
            "^@@ -(\\d+),?(\\d*) \\+(\\d+),?(\\d*) @@");

    public Map<String, Object> execute(Map<String, Object> state) {
        String rawDiff = (String) state.getOrDefault("raw_diff", "");

        if (rawDiff == null || rawDiff.isBlank()) {
            log.warn("No diff content to parse");
            return Map.of("changed_files", List.of(), "diff_chunks", List.of());
        }

        List<ChangedFile> changedFiles = parseChangedFiles(rawDiff);
        List<DiffChunk> chunks = chunkByFile(rawDiff, changedFiles);

        log.info("Parsed diff: {} files changed, {} review chunks", changedFiles.size(), chunks.size());

        Map<String, Object> result = new HashMap<>();
        result.put("changed_files", changedFiles);
        result.put("diff_chunks", chunks);
        result.put("agent_decisions", "parse_diff: " + changedFiles.size() + " files, " +
                chunks.size() + " chunks");
        return result;
    }

    private List<ChangedFile> parseChangedFiles(String diff) {
        List<ChangedFile> files = new ArrayList<>();
        Matcher fileMatcher = DIFF_FILE_PATTERN.matcher(diff);
        while (fileMatcher.find()) {
            ChangedFile cf = new ChangedFile();
            cf.setFilePath(fileMatcher.group(2)); // new path
            cf.setChangeType(ChangeType.MODIFIED);

            // Count additions/deletions for this file
            int fileStart = fileMatcher.start();
            int nextFileStart = diff.indexOf("diff --git", fileStart + 1);
            if (nextFileStart == -1) nextFileStart = diff.length();
            String fileDiff = diff.substring(fileStart, nextFileStart);

            int additions = 0, deletions = 0;
            for (String line : fileDiff.split("\n")) {
                if (line.startsWith("+") && !line.startsWith("+++")) additions++;
                else if (line.startsWith("-") && !line.startsWith("---")) deletions++;
            }
            cf.setAdditions(additions);
            cf.setDeletions(deletions);
            files.add(cf);
        }
        return files;
    }

    private List<DiffChunk> chunkByFile(String diff, List<ChangedFile> changedFiles) {
        List<DiffChunk> chunks = new ArrayList<>();
        String[] fileSections = diff.split("(?=^diff --git )");

        for (String section : fileSections) {
            if (section.isBlank()) continue;
            Matcher fileMatcher = DIFF_FILE_PATTERN.matcher(section);
            String filePath = fileMatcher.find() ? fileMatcher.group(2) : "unknown";

            DiffChunk chunk = new DiffChunk();
            chunk.setFilePath(filePath);
            chunk.setContent(section);

            // Determine relevant dimensions based on file type
            chunk.setRelevantDimensions(determineDimensions(filePath));
            chunks.add(chunk);
        }
        return chunks;
    }

    private Set<String> determineDimensions(String filePath) {
        Set<String> dims = new HashSet<>();
        dims.add("BUGS"); // Always review for bugs

        String lower = filePath.toLowerCase();

        // Security-relevant files
        if (lower.contains("controller") || lower.contains("service") ||
                lower.contains("repository") || lower.contains("dao") ||
                lower.contains("filter") || lower.contains("security") ||
                lower.contains("auth")) {
            dims.add("SECURITY");
        }

        // Performance-relevant files
        if (lower.contains("service") || lower.contains("repository") ||
                lower.contains("dao") || lower.contains("mapper")) {
            dims.add("PERFORMANCE");
        }

        // SQL/XML files
        if (lower.endsWith(".xml") && (lower.contains("mapper") || lower.contains("mybatis"))) {
            dims.add("SECURITY");
            dims.add("PERFORMANCE");
        }

        // Config files
        if (lower.endsWith(".properties") || lower.endsWith(".yml") || lower.endsWith(".yaml")) {
            dims.add("SECURITY");
        }

        return dims;
    }

    public static class DiffChunk {
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
}
