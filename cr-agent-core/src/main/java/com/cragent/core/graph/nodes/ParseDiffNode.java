package com.cragent.core.graph.nodes;

import com.cragent.core.graph.ReviewState;
import com.cragent.core.model.ChangedFile;
import com.cragent.core.model.ChangeType;
import com.cragent.core.model.DiffChunk;
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

    /**
     * 解析 rawDiff 为结构化数据，写入 state.changedFiles 和 state.diffChunks。
     */
    public void execute(ReviewState state) {
        String rawDiff = state.getRawDiff();

        if (rawDiff == null || rawDiff.isBlank()) {
            log.warn("Diff 内容为空，无需解析");
            state.setChangedFiles(List.of());
            state.setDiffChunks(List.of());
            return;
        }

        List<ChangedFile> changedFiles = parseChangedFiles(rawDiff);
        List<DiffChunk> chunks = chunkByFile(rawDiff, changedFiles);

        log.info("Diff 解析完成: {} 个文件变更, {} 个审查 chunk", changedFiles.size(), chunks.size());

        state.setChangedFiles(changedFiles);
        state.setDiffChunks(chunks);
    }

    private List<ChangedFile> parseChangedFiles(String diff) {
        List<ChangedFile> files = new ArrayList<>();
        Matcher fileMatcher = DIFF_FILE_PATTERN.matcher(diff);
        while (fileMatcher.find()) {
            ChangedFile cf = new ChangedFile();
            cf.setFilePath(fileMatcher.group(2));
            cf.setChangeType(ChangeType.MODIFIED);

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
        String[] fileSections = diff.split("(?m)^(?=diff --git )");

        for (String section : fileSections) {
            if (section.isBlank()) continue;
            Matcher fileMatcher = DIFF_FILE_PATTERN.matcher(section);
            String filePath = fileMatcher.find() ? fileMatcher.group(2) : "unknown";

            DiffChunk chunk = new DiffChunk();
            chunk.setFilePath(filePath);
            chunk.setContent(section);
            chunk.setRelevantDimensions(determineDimensions(filePath));
            chunks.add(chunk);
        }
        return chunks;
    }

    private Set<String> determineDimensions(String filePath) {
        Set<String> dims = new HashSet<>();
        dims.add("BUGS");

        String lower = filePath.toLowerCase();

        if (lower.contains("controller") || lower.contains("service") ||
                lower.contains("repository") || lower.contains("dao") ||
                lower.contains("filter") || lower.contains("security") ||
                lower.contains("auth")) {
            dims.add("SECURITY");
        }

        if (lower.contains("service") || lower.contains("repository") ||
                lower.contains("dao") || lower.contains("mapper")) {
            dims.add("PERFORMANCE");
        }

        if (lower.endsWith(".xml") && (lower.contains("mapper") || lower.contains("mybatis"))) {
            dims.add("SECURITY");
            dims.add("PERFORMANCE");
        }

        if (lower.endsWith(".properties") || lower.endsWith(".yml") || lower.endsWith(".yaml")) {
            dims.add("SECURITY");
        }

        return dims;
    }
}
