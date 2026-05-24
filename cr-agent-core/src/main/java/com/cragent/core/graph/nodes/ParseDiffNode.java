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

    // 匹配 diff 文件头: "diff --git a/旧路径 b/新路径"
    private static final Pattern DIFF_FILE_PATTERN = Pattern.compile(
            "^diff --git a/(.+) b/(.+)$", Pattern.MULTILINE);
    // 匹配 hunk 头: "@@ -旧起始,旧行数 +新起始,新行数 @@"（目前未使用，保留备用）
    private static final Pattern ADD_DEL_PATTERN = Pattern.compile(
            "^@@ -(\\d+),?(\\d*) \\+(\\d+),?(\\d*) @@");

    /**
     * 解析 raw_diff 为结构化数据。
     * 产出两个 key：
     *   - "changed_files": List<ChangedFile>（文件路径 + 增删行数）
     *   - "diff_chunks": List<DiffChunk>（每个文件一段，含 relevantDimensions 路由标记）
     *
     * relevantDimensions 路由规则根据文件路径后缀和关键词判断：
     *   Controller/Service/Repository/DAO → 三个维度全审
     *   Mapper XML → Security + Performance
     *   配置文件 → Security
     *   其他 → 至少 BUGS
     */
    public Map<String, Object> execute(Map<String, Object> state) {
        String rawDiff = (String) state.getOrDefault("raw_diff", "");

        if (rawDiff == null || rawDiff.isBlank()) {
            log.warn("Diff 内容为空，无需解析");
            return Map.of("changed_files", List.of(), "diff_chunks", List.of());
        }

        List<ChangedFile> changedFiles = parseChangedFiles(rawDiff);
        List<DiffChunk> chunks = chunkByFile(rawDiff, changedFiles);

        log.info("Diff 解析完成: {} 个文件变更, {} 个审查 chunk", changedFiles.size(), chunks.size());

        Map<String, Object> result = new HashMap<>();
        result.put("changed_files", changedFiles);
        result.put("diff_chunks", chunks);
        result.put("agent_decisions", "parse_diff: " + changedFiles.size() + " 文件, " +
                chunks.size() + " chunks");
        return result;
    }

    /**
     * 从 unified diff 文本中提取变更文件列表，统计每个文件的增删行数。
     *
     * 逻辑：用正则匹配所有 "diff --git a/xxx b/xxx" 头，
     *       截取相邻两个文件头之间的文本作为单文件 diff，
     *       统计其中以 + 开头（新增）和 - 开头（删除）的行数。
     *
     * @param diff 完整 unified diff 文本
     * @return 变更文件列表（文件路径 + 增删行数 + 变更类型）
     */
    private List<ChangedFile> parseChangedFiles(String diff) {
        List<ChangedFile> files = new ArrayList<>();
        Matcher fileMatcher = DIFF_FILE_PATTERN.matcher(diff);
        while (fileMatcher.find()) {
            ChangedFile cf = new ChangedFile();
            cf.setFilePath(fileMatcher.group(2)); // 新文件路径
            cf.setChangeType(ChangeType.MODIFIED);

            // 统计该文件的增删行数
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

    /**
     * 按文件边界切割 diff 文本，每个文件一段，包装为 DiffChunk 并标注审查维度。
     *
     * 切割方式：用正则前瞻 "(?=^diff --git )" 按文件头边界分裂。
     * 每个 chunk 调用 determineDimensions() 根据文件路径决定该文件需要哪些维度审查。
     *
     * @param diff         完整 unified diff 文本
     * @param changedFiles 前面 parseChangedFiles 产出的文件列表（仅用于日志，未直接使用）
     * @return DiffChunk 列表（每个元素 = 文件路径 + diff 片段 + 审查维度集合）
     */
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

            // 根据文件路径决定需要哪些维度审查
            chunk.setRelevantDimensions(determineDimensions(filePath));
            chunks.add(chunk);
        }
        return chunks;
    }

    /**
     * 根据文件路径后缀和关键词，判断该文件需要哪些审查维度。
     *
     * 路由规则：
     *   Controller/Service/Repository/DAO/Filter/Security/Auth → SECURITY + BUGS + PERF
     *   Mapper XML 文件 → SECURITY + PERFORMANCE
     *   .properties/.yml/.yaml 配置文件 → SECURITY
     *   所有文件 → 至少 BUGS（兜底，Bug 维度永远不跳过）
     *
     * @param filePath 文件路径（如 "src/main/java/com/example/UserController.java"）
     * @return 需要审查的维度集合（如 {"SECURITY", "BUGS", "PERFORMANCE"}）
     */
    private Set<String> determineDimensions(String filePath) {
        Set<String> dims = new HashSet<>();
        dims.add("BUGS"); // 所有文件都至少审查 Bug 维度

        String lower = filePath.toLowerCase();

        // 安全相关文件
        if (lower.contains("controller") || lower.contains("service") ||
                lower.contains("repository") || lower.contains("dao") ||
                lower.contains("filter") || lower.contains("security") ||
                lower.contains("auth")) {
            dims.add("SECURITY");
        }

        // 性能相关文件
        if (lower.contains("service") || lower.contains("repository") ||
                lower.contains("dao") || lower.contains("mapper")) {
            dims.add("PERFORMANCE");
        }

        // SQL/XML 文件
        if (lower.endsWith(".xml") && (lower.contains("mapper") || lower.contains("mybatis"))) {
            dims.add("SECURITY");
            dims.add("PERFORMANCE");
        }

        // 配置文件
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
