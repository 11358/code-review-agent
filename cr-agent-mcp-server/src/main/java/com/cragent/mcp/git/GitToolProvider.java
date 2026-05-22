package com.cragent.mcp.git;

import com.cragent.mcp.git.GitService.ChangedFileInfo;
import com.cragent.mcp.git.GitService.DiffStat;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/git")
public class GitToolProvider {

    private final GitService gitService;

    public GitToolProvider(GitService gitService) {
        this.gitService = gitService;
    }

    @PostMapping("/diff")
    public String getGitDiff(@RequestBody DiffRequest request) {
        return gitService.diff(request.repoPath, request.baseRef, request.headRef, request.filePath);
    }

    @PostMapping("/files")
    public List<ChangedFileInfo> listChangedFiles(@RequestBody FilesRequest request) {
        return gitService.listChangedFiles(request.repoPath, request.baseRef, request.headRef);
    }

    @PostMapping("/read")
    public String readFileAtRef(@RequestBody ReadRequest request) {
        return gitService.readFile(request.repoPath, request.filePath, request.ref);
    }

    @PostMapping("/stat")
    public DiffStat getDiffStat(@RequestBody FilesRequest request) {
        return gitService.diffStat(request.repoPath, request.baseRef, request.headRef);
    }

    public record DiffRequest(String repoPath, String baseRef, String headRef, String filePath) {}
    public record FilesRequest(String repoPath, String baseRef, String headRef) {}
    public record ReadRequest(String repoPath, String filePath, String ref) {}
}
