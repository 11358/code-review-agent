package com.cragent.mcp.git;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.eclipse.jgit.util.io.DisabledOutputStream;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class GitService {

    /**
     * 计算两个 ref 之间的 unified diff。
     *
     * 本质是 JGit 版的 {@code git diff baseRef..headRef}。
     * 返回的不是完整文件内容，而是标准 unified diff 格式的改动片段——
     * 只包含新增行（+）、删除行（-）和前后几行上下文（空格开头），
     * 这样 Agent 只审查改过的代码，不浪费 token 看没改的部分。
     *
     * 流程：
     *   1. openRepo → 打开本地 .git 目录
     *   2. resolveTree ×2 → 把两个分支名解析为目录树快照
     *   3. 单文件模式：DiffFormatter.format(oldTree, newTree)
     *      全量模式：先 git.diff().call() 列出变更文件，再逐文件 format
     *   4. DiffFormatter 将差异写入 ByteArrayOutputStream → 转字符串返回
     *
     * @param repoPath Git 仓库本地路径（如 D:/projects/my-app）
     * @param baseRef  基准分支（旧版本，如 main）
     * @param headRef  目标分支（新版本，如 HEAD）
     * @param filePath 可选，指定单文件路径；null 或空字符串则返回全部变更文件的 diff
     * @return 标准 unified diff 格式文本（以 "diff --git a/path b/path" 开头）
     */
    public String diff(String repoPath, String baseRef, String headRef, String filePath) {
        try (Repository repo = openRepo(repoPath);
             Git git = new Git(repo)) {

            AbstractTreeIterator oldTree = resolveTree(repo, baseRef);
            AbstractTreeIterator newTree = resolveTree(repo, headRef);

            try (ByteArrayOutputStream out = new ByteArrayOutputStream();
                 DiffFormatter formatter = new DiffFormatter(out)) {
                formatter.setRepository(repo);

                if (filePath != null && !filePath.isBlank()) {
                    formatter.setPathFilter(PathFilter.create(filePath));
                    formatter.format(oldTree, newTree);
                } else {
                    List<DiffEntry> entries = git.diff()
                            .setOldTree(oldTree)
                            .setNewTree(newTree)
                            .call();
                    for (DiffEntry entry : entries) {
                        formatter.format(entry);
                    }
                }
                formatter.flush();
                return out.toString(StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            throw new RuntimeException("Diff 计算失败: " + e.getMessage(), e);
        }
    }

    /** 列出两个 ref 之间的变更文件列表（含每个文件增删行数统计）。复用 diff() 避免逐文件创建 DiffFormatter。 */
    public List<ChangedFileInfo> listChangedFiles(String repoPath, String baseRef, String headRef) {
        String fullDiff = diff(repoPath, baseRef, headRef, null);
        List<ChangedFileInfo> result = new ArrayList<>();
        if (fullDiff == null || fullDiff.isBlank()) return result;

        for (String section : fullDiff.split("(?m)^(?=diff --git )")) {
            if (section.isBlank()) continue;
            ChangedFileInfo info = new ChangedFileInfo();
            info.setFilePath(extractFilePath(section));
            info.setChangeType(extractChangeType(section));
            int[] counts = countChanges(section);
            info.setAdditions(counts[0]);
            info.setDeletions(counts[1]);
            result.add(info);
        }
        return result;
    }

    /** 读取指定 ref 下的某个文件内容 */
    public String readFile(String repoPath, String filePath, String ref) {
        try (Repository repo = openRepo(repoPath);
             RevWalk walk = new RevWalk(repo)) {

            ObjectId commitId = repo.resolve(ref);
            if (commitId == null) {
                return "错误: ref '" + ref + "' 未找到";
            }
            RevCommit commit = walk.parseCommit(commitId);
            RevTree tree = commit.getTree();

            try (org.eclipse.jgit.treewalk.TreeWalk treeWalk =
                         org.eclipse.jgit.treewalk.TreeWalk.forPath(repo, filePath, tree)) {
                if (treeWalk == null) {
                    return "错误: 文件 '" + filePath + "' 在 ref '" + ref + "' 处未找到";
                }
                ObjectId blobId = treeWalk.getObjectId(0);
                try (org.eclipse.jgit.lib.ObjectReader reader = repo.newObjectReader()) {
                    byte[] bytes = reader.open(blobId).getBytes();
                    return new String(bytes, StandardCharsets.UTF_8);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("读取文件失败: " + e.getMessage(), e);
        }
    }

    /** 统计两个 ref 之间的变更总览（文件数 + 总增删行数 + 每个文件明细）。复用 diff() 避免逐文件创建 DiffFormatter。 */
    public DiffStat diffStat(String repoPath, String baseRef, String headRef) {
        String fullDiff = diff(repoPath, baseRef, headRef, null);
        DiffStat stat = new DiffStat();
        stat.setFiles(new ArrayList<>());
        if (fullDiff == null || fullDiff.isBlank()) return stat;

        int totalAdd = 0, totalDel = 0;
        for (String section : fullDiff.split("(?m)^(?=diff --git )")) {
            if (section.isBlank()) continue;
            DiffStat.FileStat fs = new DiffStat.FileStat();
            fs.setFilePath(extractFilePath(section));
            fs.setChangeType(extractChangeType(section));
            int[] counts = countChanges(section);
            fs.setAdditions(counts[0]);
            fs.setDeletions(counts[1]);
            totalAdd += counts[0];
            totalDel += counts[1];
            stat.getFiles().add(fs);
        }
        stat.setTotalFiles(stat.getFiles().size());
        stat.setTotalAdditions(totalAdd);
        stat.setTotalDeletions(totalDel);
        return stat;
    }

    /** 打开本地 Git 仓库，创建 JGit Repository 对象。校验路径合法性防止目录遍历。 */
    private Repository openRepo(String repoPath) throws IOException {
        Path path = Path.of(repoPath).toRealPath();
        String normalized = path.toString().replace('\\', '/');
        if (normalized.startsWith("/etc/") || normalized.startsWith("/proc/")
                || normalized.startsWith("/sys/") || normalized.equals("/")
                || normalized.startsWith("C:/Windows") || normalized.startsWith("C:/WINDOWS")) {
            throw new IllegalArgumentException("不允许访问系统目录: " + repoPath);
        }
        File repoDir = path.toFile();
        return new FileRepositoryBuilder()
                .setGitDir(new File(repoDir, ".git"))
                .readEnvironment()
                .findGitDir()
                .build();
    }

    /** 将分支名/commit hash 解析为目录树对象，供 DiffCommand 使用 */
    private AbstractTreeIterator resolveTree(Repository repo, String ref) throws IOException {
        ObjectId commitId = repo.resolve(ref);
        if (commitId == null) {
            throw new IllegalArgumentException("无法解析 ref: " + ref);
        }
        try (RevWalk walk = new RevWalk(repo)) {
            RevCommit commit = walk.parseCommit(commitId);
            CanonicalTreeParser parser = new CanonicalTreeParser();
            try (ObjectReader reader = repo.newObjectReader()) {
                parser.reset(reader, commit.getTree().getId());
            }
            return parser;
        }
    }

    /** 从 diff 片段头 extract 文件路径（diff --git a/xxx b/yyy → b 路径） */
    private static final Pattern DIFF_FILE = Pattern.compile("^diff --git a/(.+) b/(.+)$", Pattern.MULTILINE);

    private String extractFilePath(String diffSection) {
        Matcher m = DIFF_FILE.matcher(diffSection);
        return m.find() ? m.group(2) : "unknown";
    }

    /** 从 diff 片段推断变更类型 */
    private String extractChangeType(String diffSection) {
        if (diffSection.contains("\nrename from ")) return "RENAMED";
        if (diffSection.contains("\ncopy from ")) return "COPIED";
        if (diffSection.contains("\nnew file mode")) return "ADDED";
        if (diffSection.contains("\ndeleted file mode")) return "DELETED";
        return "MODIFIED";
    }

    /** 统计一段 diff 文本中的新增行数和删除行数 */
    private int[] countChanges(String diffText) {
        int additions = 0;
        int deletions = 0;
        for (String line : diffText.split("\n")) {
            if (line.startsWith("+") && !line.startsWith("+++")) {
                additions++;
            } else if (line.startsWith("-") && !line.startsWith("---")) {
                deletions++;
            }
        }
        return new int[]{additions, deletions};
    }

    // 内部 DTO

    public static class ChangedFileInfo {
        private String filePath;
        private String changeType;
        private int additions;
        private int deletions;

        public String getFilePath() { return filePath; }
        public void setFilePath(String filePath) { this.filePath = filePath; }
        public String getChangeType() { return changeType; }
        public void setChangeType(String changeType) { this.changeType = changeType; }
        public int getAdditions() { return additions; }
        public void setAdditions(int additions) { this.additions = additions; }
        public int getDeletions() { return deletions; }
        public void setDeletions(int deletions) { this.deletions = deletions; }
    }

    public static class DiffStat {
        private int totalFiles;
        private int totalAdditions;
        private int totalDeletions;
        private List<FileStat> files;

        public int getTotalFiles() { return totalFiles; }
        public void setTotalFiles(int totalFiles) { this.totalFiles = totalFiles; }
        public int getTotalAdditions() { return totalAdditions; }
        public void setTotalAdditions(int totalAdditions) { this.totalAdditions = totalAdditions; }
        public int getTotalDeletions() { return totalDeletions; }
        public void setTotalDeletions(int totalDeletions) { this.totalDeletions = totalDeletions; }
        public List<FileStat> getFiles() { return files; }
        public void setFiles(List<FileStat> files) { this.files = files; }

        public static class FileStat {
            private String filePath;
            private String changeType;
            private int additions;
            private int deletions;

            public String getFilePath() { return filePath; }
            public void setFilePath(String filePath) { this.filePath = filePath; }
            public String getChangeType() { return changeType; }
            public void setChangeType(String changeType) { this.changeType = changeType; }
            public int getAdditions() { return additions; }
            public void setAdditions(int additions) { this.additions = additions; }
            public int getDeletions() { return deletions; }
            public void setDeletions(int deletions) { this.deletions = deletions; }
        }
    }
}
