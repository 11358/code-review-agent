package com.cragent.core.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;

@Component
public class McpClientManager {

    private static final Logger log = LoggerFactory.getLogger(McpClientManager.class);

    private final RestClient restClient;

    /**
     * 初始化 Git 工具服务客户端（RestClient，非 MCP 协议）。
     * @param serverUrl      Git 工具服务地址（默认 http://localhost:8082）
     * @param connectTimeout 连接超时
     * @param readTimeout    读取超时（diff 可能较大）
     */
    public McpClientManager(
            @Value("${cragent.mcp-server.url:http://localhost:8082}") String serverUrl,
            @Value("${cragent.mcp-server.connect-timeout:10s}") Duration connectTimeout,
            @Value("${cragent.mcp-server.read-timeout:30s}") Duration readTimeout) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        this.restClient = RestClient.builder()
                .baseUrl(serverUrl)
                .requestFactory(factory)
                .build();
        log.info("Git 客户端初始化完成: {} (连接超时={}, 读取超时={})",
                serverUrl, connectTimeout, readTimeout);
    }

    /**
     * MCP 语义的工具调用接口。当前用 REST 实现，后续升级 MCP 只需改此方法内部实现。
     * @param toolName  工具名（get_git_diff / list_changed_files / read_file_at_ref / get_diff_stat）
     * @param arguments 参数 Map
     * @return 工具返回的字符串结果
     */
    public String callToolAsString(String toolName, Map<String, Object> arguments) {
        return switch (toolName) {
            case "get_git_diff" -> restClient.post()
                    .uri("/git/diff")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(arguments)
                    .retrieve()
                    .body(String.class);
            case "list_changed_files" -> restClient.post()
                    .uri("/git/files")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(arguments)
                    .retrieve()
                    .body(String.class);
            case "read_file_at_ref" -> restClient.post()
                    .uri("/git/read")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(arguments)
                    .retrieve()
                    .body(String.class);
            case "get_diff_stat" -> restClient.post()
                    .uri("/git/stat")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(arguments)
                    .retrieve()
                    .body(String.class);
            default -> throw new IllegalArgumentException("未知工具: " + toolName);
        };
    }
}
