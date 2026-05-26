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

    /** 千问 ChatClient.Builder：用于三个审查 Agent */
    @Bean
    @ConditionalOnMissingBean
    public ChatClient.Builder chatClientBuilder(ChatModel chatModel) {
        return ChatClient.builder(chatModel);
    }

    /**
     * 跨模型验证用的 DeepSeek ChatClient。
     * 未设置 DEEPSEEK_API_KEY 时自动回退到千问。
     */
    /** DeepSeek ChatClient.Builder：用于交叉验证。无 API Key 时回退到千问。 */
    @Bean
    @Qualifier("deepseek")
    public ChatClient.Builder deepseekChatClientBuilder(
            @Value("${DEEPSEEK_API_KEY:}") String apiKey,
            ChatClient.Builder defaultBuilder) {
        if (apiKey.isBlank()) {
            log.warn("未设置 DEEPSEEK_API_KEY — 验证将回退到千问（同模型回音室风险）。"
                    + "设置 DEEPSEEK_API_KEY 以启用真正的跨模型交叉验证。");
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
        log.info("DeepSeek 跨模型交叉验证已启用 (模型: deepseek-chat)");
        return ChatClient.builder(chatModel);
    }

    /** SecuritySubAgent：OWASP 安全审查，继承 AbstractSubAgent */
    @Bean
    public SubAgent securitySubAgent(ChatClient.Builder chatClientBuilder, ObjectMapper objectMapper) {
        return new SecuritySubAgent(chatClientBuilder, objectMapper);
    }

    /** BugSubAgent：逻辑 Bug 审查（继承 AbstractSubAgent，仅 11 行业务代码） */
    @Bean
    public SubAgent bugSubAgent(ChatClient.Builder chatClientBuilder, ObjectMapper objectMapper) {
        return new BugSubAgent(chatClientBuilder, objectMapper);
    }

    /** PerformanceSubAgent：性能反模式审查（继承 AbstractSubAgent，仅 11 行业务代码） */
    @Bean
    public SubAgent performanceSubAgent(ChatClient.Builder chatClientBuilder, ObjectMapper objectMapper) {
        return new PerformanceSubAgent(chatClientBuilder, objectMapper);
    }

    /** ★ 核心流水线 Bean。destroyMethod="shutdown" 确保应用关闭时线程池被释放。 */
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
