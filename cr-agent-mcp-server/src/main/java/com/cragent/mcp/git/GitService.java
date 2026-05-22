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

@Service
public class GitService {

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
            throw new RuntimeException("Failed to compute diff: " + e.getMessage(), e);
        }
    }

    public List<ChangedFileInfo> listChangedFiles(String repoPath, String baseRef, String headRef) {
        try (Repository repo = openRepo(repoPath);
             Git git = new Git(repo)) {

            AbstractTreeIterator oldTree = resolveTree(repo, baseRef);
            AbstractTreeIterator newTree = resolveTree(repo, headRef);

            List<DiffEntry> entries = git.diff()
                    .setOldTree(oldTree)
                    .setNewTree(newTree)
                    .call();

            List<ChangedFileInfo> result = new ArrayList<>();
            for (DiffEntry entry : entries) {
                ChangedFileInfo info = new ChangedFileInfo();
                info.setFilePath(entry.getNewPath());
                info.setChangeType(mapChangeType(entry.getChangeType()));

                // Count additions/deletions by parsing the diff
                try (ByteArrayOutputStream out = new ByteArrayOutputStream();
                     DiffFormatter formatter = new DiffFormatter(out)) {
                    formatter.setRepository(repo);
                    formatter.format(entry);
                    formatter.flush();
                    String diffText = out.toString(StandardCharsets.UTF_8);
                    int[] counts = countChanges(diffText);
                    info.setAdditions(counts[0]);
                    info.setDeletions(counts[1]);
                }

                result.add(info);
            }
            return result;
        } catch (Exception e) {
            throw new RuntimeException("Failed to list changed files: " + e.getMessage(), e);
        }
    }

    public String readFile(String repoPath, String filePath, String ref) {
        try (Repository repo = openRepo(repoPath);
             RevWalk walk = new RevWalk(repo)) {

            ObjectId commitId = repo.resolve(ref);
            if (commitId == null) {
                return "Error: ref '" + ref + "' not found";
            }
            RevCommit commit = walk.parseCommit(commitId);
            RevTree tree = commit.getTree();

            try (org.eclipse.jgit.treewalk.TreeWalk treeWalk =
                         org.eclipse.jgit.treewalk.TreeWalk.forPath(repo, filePath, tree)) {
                if (treeWalk == null) {
                    return "Error: file '" + filePath + "' not found at ref '" + ref + "'";
                }
                ObjectId blobId = treeWalk.getObjectId(0);
                try (org.eclipse.jgit.lib.ObjectReader reader = repo.newObjectReader()) {
                    byte[] bytes = reader.open(blobId).getBytes();
                    return new String(bytes, StandardCharsets.UTF_8);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to read file: " + e.getMessage(), e);
        }
    }

    public DiffStat diffStat(String repoPath, String baseRef, String headRef) {
        try (Repository repo = openRepo(repoPath);
             Git git = new Git(repo)) {

            AbstractTreeIterator oldTree = resolveTree(repo, baseRef);
            AbstractTreeIterator newTree = resolveTree(repo, headRef);

            List<DiffEntry> entries = git.diff()
                    .setOldTree(oldTree)
                    .setNewTree(newTree)
                    .call();

            DiffStat stat = new DiffStat();
            stat.setTotalFiles(entries.size());
            stat.setTotalAdditions(0);
            stat.setTotalDeletions(0);
            stat.setFiles(new ArrayList<>());

            for (DiffEntry entry : entries) {
                DiffStat.FileStat fs = new DiffStat.FileStat();
                fs.setFilePath(entry.getNewPath());
                fs.setChangeType(mapChangeType(entry.getChangeType()));

                try (ByteArrayOutputStream out = new ByteArrayOutputStream();
                     DiffFormatter formatter = new DiffFormatter(out)) {
                    formatter.setRepository(repo);
                    formatter.format(entry);
                    formatter.flush();
                    String diffText = out.toString(StandardCharsets.UTF_8);
                    int[] counts = countChanges(diffText);
                    fs.setAdditions(counts[0]);
                    fs.setDeletions(counts[1]);
                    stat.setTotalAdditions(stat.getTotalAdditions() + counts[0]);
                    stat.setTotalDeletions(stat.getTotalDeletions() + counts[1]);
                }

                stat.getFiles().add(fs);
            }
            return stat;
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute diff stat: " + e.getMessage(), e);
        }
    }

    private Repository openRepo(String repoPath) throws IOException {
        File repoDir = new File(repoPath);
        if (!repoDir.exists()) {
            throw new IllegalArgumentException("Repository path does not exist: " + repoPath);
        }
        return new FileRepositoryBuilder()
                .setGitDir(new File(repoDir, ".git"))
                .readEnvironment()
                .findGitDir()
                .build();
    }

    private AbstractTreeIterator resolveTree(Repository repo, String ref) throws IOException {
        ObjectId commitId = repo.resolve(ref);
        if (commitId == null) {
            throw new IllegalArgumentException("Cannot resolve ref: " + ref);
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

    private String mapChangeType(DiffEntry.ChangeType type) {
        return switch (type) {
            case ADD -> "ADDED";
            case MODIFY -> "MODIFIED";
            case DELETE -> "DELETED";
            case RENAME -> "RENAMED";
            case COPY -> "COPIED";
        };
    }

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

    // Inner DTOs

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
