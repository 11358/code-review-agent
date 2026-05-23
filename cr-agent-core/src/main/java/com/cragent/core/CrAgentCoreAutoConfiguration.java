package com.cragent.core;

import com.cragent.core.agent.SecuritySubAgent;
import com.cragent.core.agent.BugSubAgent;
import com.cragent.core.agent.PerformanceSubAgent;
import com.cragent.core.agent.SubAgent;
import com.cragent.core.filter.FixGuidedVerificationFilter;
import com.cragent.core.graph.ReviewStateGraph;
import com.cragent.core.mcp.McpClientManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@ComponentScan(basePackages = "com.cragent.core")
public class CrAgentCoreAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(CrAgentCoreAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public ChatClient.Builder chatClientBuilder(ChatModel chatModel) {
        return ChatClient.builder(chatModel);
    }

    /**
     * DeepSeek ChatClient for cross-model verification.
     * Falls back to Qwen if DEEPSEEK_API_KEY is not set.
     */
    @Bean
    @Qualifier("deepseek")
    public ChatClient.Builder deepseekChatClientBuilder(
            @Value("${DEEPSEEK_API_KEY:}") String apiKey,
            ChatClient.Builder defaultBuilder) {
        if (apiKey.isBlank()) {
            log.warn("DEEPSEEK_API_KEY not set — verification will use Qwen (same-model echo chamber). "
                    + "Set DEEPSEEK_API_KEY for true cross-model verification.");
            return defaultBuilder;
        }
        var openAiApi = OpenAiApi.builder()
                .baseUrl("https://api.deepseek.com")
                .apiKey(apiKey)
                .build();
        var chatModel = OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model("deepseek-chat")
                        .temperature(0.1)
                        .build())
                .build();
        log.info("DeepSeek cross-model verification enabled (model: deepseek-chat)");
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

    @Bean(destroyMethod = "shutdown")
    public ReviewStateGraph reviewStateGraph(McpClientManager mcpClient,
                                              SubAgent securitySubAgent,
                                              SubAgent bugSubAgent,
                                              SubAgent performanceSubAgent,
                                              FixGuidedVerificationFilter verificationFilter,
                                              @Value("${cragent.review.agent-runs:3}") int agentRuns,
                                              @Value("${cragent.review.sub-agent-timeout:120}") long reviewTimeoutSeconds) {
        return new ReviewStateGraph(mcpClient, securitySubAgent, bugSubAgent,
                performanceSubAgent, verificationFilter, agentRuns, reviewTimeoutSeconds);
    }
}
