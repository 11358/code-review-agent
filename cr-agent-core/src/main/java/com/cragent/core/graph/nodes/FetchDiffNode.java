package com.cragent.core.graph.nodes;

import com.cragent.core.graph.ReviewState;
import com.cragent.core.mcp.McpClientManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

public class FetchDiffNode {

    private static final Logger log = LoggerFactory.getLogger(FetchDiffNode.class);

    private final McpClientManager mcpClient;

    public FetchDiffNode(McpClientManager mcpClient) {
        this.mcpClient = mcpClient;
    }

    /**
     * 通过 McpClientManager 向 Git 工具服务请求 diff，写入 state.rawDiff。
     */
    public void execute(ReviewState state) {
        log.info("正在拉取 Diff: repo={}, base={}, head={}",
                state.getRepoPath(), state.getBaseRef(), state.getHeadRef());

        String rawDiff = mcpClient.callToolAsString("get_git_diff", Map.of(
                "repoPath", state.getRepoPath(),
                "baseRef", state.getBaseRef(),
                "headRef", state.getHeadRef()));

        state.setRawDiff(rawDiff != null ? rawDiff : "");

        log.info("Diff 获取完成: {} 字符", rawDiff != null ? rawDiff.length() : 0);
    }
}
