package com.cragent.core;

import com.cragent.core.agent.SecuritySubAgent;
import com.cragent.core.agent.BugSubAgent;
import com.cragent.core.agent.PerformanceSubAgent;
import com.cragent.core.agent.SubAgent;
import com.cragent.core.filter.FixGuidedVerificationFilter;
import com.cragent.core.graph.ReviewStateGraph;
import com.cragent.core.mcp.McpClientManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@ComponentScan(basePackages = "com.cragent.core")
public class CrAgentCoreAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ChatClient.Builder chatClientBuilder(ChatModel chatModel) {
        return ChatClient.builder(chatModel);
    }

    @Bean
    public SubAgent securitySubAgent(ChatClient.Builder chatClientBuilder, ObjectMapper objectMapper) {
        return new SecuritySubAgent(chatClientBuilder, objectMapper);
    }

    @Bean
    public SubAgent bugSubAgent(ChatClient.Builder chatClientBuilder, ObjectMapper objectMapper) {
        return new BugSubAgent(chatClientBuilder, objectMapper);
    }

    @Bean
    public SubAgent performanceSubAgent(ChatClient.Builder chatClientBuilder, ObjectMapper objectMapper) {
        return new PerformanceSubAgent(chatClientBuilder, objectMapper);
    }

    @Bean
    public ReviewStateGraph reviewStateGraph(McpClientManager mcpClient,
                                              SubAgent securitySubAgent,
                                              SubAgent bugSubAgent,
                                              SubAgent performanceSubAgent,
                                              FixGuidedVerificationFilter verificationFilter) {
        return new ReviewStateGraph(mcpClient, securitySubAgent, bugSubAgent,
                performanceSubAgent, verificationFilter);
    }
}
