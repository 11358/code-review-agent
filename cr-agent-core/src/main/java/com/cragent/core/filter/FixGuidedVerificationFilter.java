package com.cragent.core.filter;

import com.cragent.core.model.ReviewFinding;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fix-Guided 验证过滤器 — 跨模型版本。
 *
 * 用 DeepSeek 作为第二裁判进行独立验证，打破单模型自审自判的回音室。
 * 未设置 DEEPSEEK_API_KEY 时自动回退到千问（记录警告）。
 *
 * 理论来源: arXiv:2603.00539 - Fix-guided Verification Filter
 */
@Component
public class FixGuidedVerificationFilter {

    private static final Logger log = LoggerFactory.getLogger(FixGuidedVerificationFilter.class);

    private final ChatClient chatClient;
    private final boolean enabled;
    private final int runs;
    private final long timeoutSeconds;
    private final ExecutorService verificationExecutor;
    private final ExecutorService runExecutor;

    // ── 常量 ────────────────────────────────────────────────

    /** DeepSeek 置信度阈值：≥0.5 视为真问题保留 */
    private static final double DEFAULT_THRESHOLD = 0.5;
    /** 每条 finding 验证轮数 */
    private static final int DEFAULT_RUNS = 3;

    /** 从 DeepSeek 回复中提取 "confidence: 0.X" 数值 */
    private static final Pattern CONFIDENCE_PATTERN = Pattern.compile(
            "confidence[:\\s]*([0-9]?(?:\\.\\d+)?)", Pattern.CASE_INSENSITIVE);

    /** 关键词匹配：如果回复中包含这些具体修复关键词，说明 DeepSeek 写出了具体方案 */
    private static final Pattern CODE_PATTERN = Pattern.compile(
            "StringBuilder|Buffered|JOIN\\s+FETCH|IN\\s*\\(|batch|Pattern\\.compile|try-with-resources|PreparedStatement",
            Pattern.CASE_INSENSITIVE);

    /**
     * @param chatClientBuilder DeepSeek 的 ChatClient.Builder（@Qualifier("deepseek")）
     * @param enabled           是否启用验证（配置文件: cragent.review.verification.enabled）
     * @param runs              每条 finding 的验证轮数（默认 3）
     * @param timeoutSeconds    单条 finding 验证超时秒数（默认 60s）
     */
    public FixGuidedVerificationFilter(
            @Qualifier("deepseek") ChatClient.Builder chatClientBuilder,
            @Value("${cragent.review.verification.enabled:true}") boolean enabled,
            @Value("${cragent.review.verification.runs:3}") int runs,
            @Value("${cragent.review.verification.timeout-seconds:60}") long timeoutSeconds) {
        this.chatClient = chatClientBuilder.build();
        this.enabled = enabled;
        this.runs = Math.max(1, runs);
        this.timeoutSeconds = timeoutSeconds;
        // 外层线程池：并行处理多条 finding 的验证
        this.verificationExecutor = Executors.newFixedThreadPool(8, r -> {
            Thread t = new Thread(r, "verify-filter");
            t.setDaemon(true);
            return t;
        });
        // 内层线程池：每条 finding 的 3 轮 DeepSeek 调用并行
        // 独立池避免与 verificationExecutor 共享导致线程饥饿死锁
        this.runExecutor = Executors.newFixedThreadPool(Math.max(8, runs * 4), r -> {
            Thread t = new Thread(r, "verify-run");
            t.setDaemon(true);
            return t;
        });
        log.info("验证过滤器初始化完成 (轮数={}, 外层 {} 线程 + 内层 {} 线程)", this.runs, 8, Math.max(8, runs * 4));
    }

    /**
     * 对 finding 列表进行跨模型交叉验证，返回通过验证的子集。
     *
     * 流程：外层 verificationExecutor 并行调度 → verifyOneFinding 逐条验证
     *      → 确定性 finding 跳过 → 其余由 runExecutor 并行调 DeepSeek 3 轮取均值
     *
     * @param findings    待验证的 finding 列表（来自 Aggregate）
     * @param diffContext 原始 diff 文本，供 DeepSeek 审查上下文
     * @return 验证通过的 finding 子列表
     */
    public List<ReviewFinding> filter(List<ReviewFinding> findings, String diffContext) {
        if (!enabled || findings.isEmpty()) {
            return findings;
        }

        log.info("验证过滤器: {} 条发现 (阈值={}, {} 轮, {}s 超时, 并行模式)",
                findings.size(), DEFAULT_THRESHOLD, runs, timeoutSeconds);

        // 外层并行：每条 finding 独立验证
        @SuppressWarnings("unchecked")
        CompletableFuture<ReviewFinding>[] futures = findings.stream()
                .map(f -> CompletableFuture.supplyAsync(() -> verifyOneFinding(f, diffContext), verificationExecutor)
                        .orTimeout(timeoutSeconds, TimeUnit.SECONDS)
                        .exceptionally(ex -> {
                            log.debug("验证超时 {} @ {} — 保留原结果",
                                    f.getFile(), f.getLineStart());
                            return f;
                        }))
                .toArray(CompletableFuture[]::new);

        CompletableFuture.allOf(futures)
                .orTimeout(timeoutSeconds + 30, TimeUnit.SECONDS)
                .join();

        List<ReviewFinding> verified = new ArrayList<>();
        int filtered = 0;
        for (int i = 0; i < futures.length; i++) {
            ReviewFinding f = futures[i].join();
            if (f.isVerified()) {
                verified.add(f);
            } else {
                filtered++;
            }
        }

        log.info("验证结果: {} 条保留, {} 条过滤", verified.size(), filtered);
        return verified;
    }

    /**
     * 单条 finding 验证。三步决策：
     * 1. severity 为空 → 原样返回
     * 2. confidenceScore >= 1.0 → 确定性扫描产出，直接放行（零 LLM 成本）
     * 3. severity == INFO → 低风险，直接放行（confidence=0.8）
     * 4. 其他 → 调用 DeepSeek 多轮评估，取平均置信度，≥0.5 保留
     *
     * @param finding     单条审查发现
     * @param diffContext 原始 diff 文本
     * @return 设置了 confidenceScore 和 verified 后的同一条 finding
     */
    private ReviewFinding verifyOneFinding(ReviewFinding finding, String diffContext) {
        if (finding.getSeverity() == null) {
            return finding;
        }

        // 确定性发现：100% 确定，无需 LLM 验证
        if (finding.getConfidenceScore() >= 1.0) {
            return finding;
        }

        // INFO 级别：低风险，直接放行
        if ("INFO".equals(finding.getSeverity().name())) {
            finding.setConfidenceScore(0.8);
            finding.setVerified(true);
            return finding;
        }

        @SuppressWarnings("unchecked")
        CompletableFuture<Double>[] runFutures = new CompletableFuture[runs];
        for (int i = 0; i < runs; i++) {
            runFutures[i] = CompletableFuture.supplyAsync(
                    () -> assessConfidence(finding, diffContext), runExecutor);
        }

        CompletableFuture.allOf(runFutures).join();

        double totalConfidence = 0.0;
        int realCount = 0;
        for (CompletableFuture<Double> f : runFutures) {
            double c = f.join();
            totalConfidence += c;
            if (c >= DEFAULT_THRESHOLD) realCount++;
        }
        double avgConfidence = totalConfidence / runs;

        finding.setConfidenceScore(avgConfidence);
        finding.setVerified(avgConfidence >= DEFAULT_THRESHOLD);

        if (!finding.isVerified()) {
            log.debug("已过滤 (平均置信度={}, {}/{} 轮判真): {} @ {}:{}",
                    avgConfidence, realCount, runs, finding.getCategory(), finding.getFile(), finding.getLineStart());
        }
        return finding;
    }

    /**
     * 调 DeepSeek 评估一条 finding 的可信度。
     * 包含重试机制（2 次，间隔 2s backoff）。
     *
     * @return 0.0~1.0 的置信度分数
     */
    private double assessConfidence(ReviewFinding finding, String diffContext) {
        Exception lastException = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                if (attempt > 0) {
                    Thread.sleep(2000); // 重试前退避
                }
                String response = chatClient.prompt()
                        .user(u -> u.text("""
                                Given a potential code issue found by a code review tool, independently assess whether it is a REAL problem.

                                Issue:
                                - File: {file}
                                - Lines: {lineStart}-{lineEnd}
                                - Severity: {severity}
                                - Category: {category}
                                - Problem: {explanation}

                                Relevant code context:
                                {diff}

                                TASK:
                                1. If this is a REAL issue, write a specific, compilable code fix.
                                2. If the existing code is actually fine, say "No fix needed - false positive" and explain.
                                3. Rate your confidence from 0.0 to 1.0.

                                Output format:
                                Fix: [specific code fix OR "No fix needed - false positive"]
                                Confidence: [0.0-1.0]
                                """)
                                .param("file", finding.getFile())
                                .param("lineStart", String.valueOf(finding.getLineStart()))
                                .param("lineEnd", String.valueOf(finding.getLineEnd()))
                                .param("severity", finding.getSeverity().name())
                                .param("category", finding.getCategory().name())
                                .param("explanation", finding.getExplanation())
                                .param("diff", truncate(diffContext, 2000)))
                        .call()
                        .content();

                return extractConfidence(response);
            } catch (Exception e) {
                lastException = e;
                if (attempt < 1) {
                    log.debug("验证调用失败 (第{}次), 重试: {}", attempt + 1, e.getMessage());
                }
            }
        }
        log.warn("验证调用 2 次均失败，保留 finding (最后错误: {})",
                lastException != null ? lastException.getMessage() : "unknown");
        return DEFAULT_THRESHOLD;
    }

    /**
     * 从 DeepSeek 的自然语言回复中提取置信度分数。
     *
     * 提取策略（按优先级）：
     * 1. 含 "false positive" / "no fix needed" → 直接 0.0
     * 2. 有具体修复代码 + 明确 "confidence: 0.X" → 返回数值
     * 3. 有具体修复代码但无数值 → 默认 0.8
     * 4. 含模糊词（could/might/possibly）→ 0.35
     * 5. 兜底 → 0.5（不轻易丢弃 finding）
     */
    private double extractConfidence(String response) {
        if (response == null || response.isBlank()) return DEFAULT_THRESHOLD;

        String lower = response.toLowerCase();

        if (lower.contains("false positive") || lower.contains("no fix needed") ||
                lower.contains("not a real issue") || lower.contains("code is correct")) {
            return 0.0;
        }

        boolean hasSpecificFix = lower.contains("```") ||
                lower.contains("replace") ||
                lower.contains("change line") ||
                lower.contains("modify to") ||
                lower.contains("rewrite as") ||
                CODE_PATTERN.matcher(response).find();

        Matcher m = CONFIDENCE_PATTERN.matcher(response);
        if (m.find()) {
            try {
                double conf = Double.parseDouble(m.group(1));
                if (!hasSpecificFix && conf > 0.5) {
                    conf = 0.4;
                }
                return Math.min(1.0, Math.max(0.0, conf));
            } catch (NumberFormatException ignored) {}
        }

        if (hasSpecificFix) return 0.8;
        if (lower.contains("could") || lower.contains("might") || lower.contains("possibly")) return 0.35;
        return 0.5;
    }

    @PreDestroy
    public void shutdown() {
        shutdownExecutor(verificationExecutor);
        shutdownExecutor(runExecutor);
    }

    private void shutdownExecutor(ExecutorService executor) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private String truncate(String s, int maxLen) {
        if (s == null || s.length() <= maxLen) return s != null ? s : "";
        return s.substring(0, maxLen) + "\n... [truncated] ...";
    }
}
