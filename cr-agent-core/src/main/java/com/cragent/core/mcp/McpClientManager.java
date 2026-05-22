package com.cragent.core.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Component
public class McpClientManager {

    private static final Logger log = LoggerFactory.getLogger(McpClientManager.class);

    private final RestClient restClient;

    public McpClientManager(
            @Value("${cragent.mcp-server.url:http://localhost:8082}") String serverUrl) {
        this.restClient = RestClient.builder()
                .baseUrl(serverUrl)
                .requestFactory(new SimpleClientHttpRequestFactory())
                .build();
        log.info("Git client initialized: {}", serverUrl);
    }

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
            default -> throw new IllegalArgumentException("Unknown tool: " + toolName);
        };
    }
}
