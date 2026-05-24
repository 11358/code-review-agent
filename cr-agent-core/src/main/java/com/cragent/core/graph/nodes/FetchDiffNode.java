package com.cragent.core.graph.nodes;

import com.cragent.core.mcp.McpClientManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

public class FetchDiffNode {

    private static final Logger log = LoggerFactory.getLogger(FetchDiffNode.class);

    private final McpClientManager mcpClient;

    public FetchDiffNode(McpClientManager mcpClient) {
        this.mcpClient = mcpClient;
    }

    /**
     * 通过 McpClientManager 向 Git 工具服务请求 diff。
     * 产出 "raw_diff": 完整的 unified diff 字符串。
     */
    public Map<String, Object> execute(Map<String, Object> state) {
        String repoPath = (String) state.get("repo_path");
        String baseRef = (String) state.get("base_ref");
        String headRef = (String) state.get("head_ref");

        log.info("正在拉取 Diff: repo={}, base={}, head={}", repoPath, baseRef, headRef);

        Map<String, Object> args = new HashMap<>();
        args.put("repoPath", repoPath);
        args.put("baseRef", baseRef);
        args.put("headRef", headRef);

        String rawDiff = mcpClient.callToolAsString("get_git_diff", args);

        Map<String, Object> result = new HashMap<>();
        result.put("raw_diff", rawDiff != null ? rawDiff : "");
        result.put("changed_files", ""); // will be parsed later
        result.put("agent_decisions", "fetch_diff: 获取 diff (" +
                (rawDiff != null ? rawDiff.length() : 0) + " 字符)");

        log.info("Diff 获取完成: {} 字符", rawDiff != null ? rawDiff.length() : 0);
        return result;
    }
}
