package com.cragent.api.cli;

import com.cragent.api.service.ReviewOrchestrationService;
import com.cragent.core.model.ReviewFinding;
import com.cragent.core.model.ReviewResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ReviewCliRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(ReviewCliRunner.class);

    private final ReviewOrchestrationService service;
    private final ObjectMapper objectMapper;

    public ReviewCliRunner(ReviewOrchestrationService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    /**
     * Spring Boot 启动后自动回调入口。
     * 有 --repo → CLI 模式（审查 → 打印结果 → 退出）
     * 无 --repo → Server 模式（返回，容器持续运行等待 HTTP 请求）
     */
    @Override
    public void run(String... args) throws Exception {
        String repoPath = null;
        String baseRef = "main";
        String headRef = "HEAD";

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--repo" -> repoPath = (++i < args.length) ? args[i] : null;
                case "--base" -> baseRef = (++i < args.length) ? args[i] : "main";
                case "--head" -> headRef = (++i < args.length) ? args[i] : "HEAD";
                case "--help" -> {
                    printUsage();
                    return;
                }
            }
        }

        if (repoPath == null) {
            log.info("未提供 --repo 参数，以 Server 模式启动。");
            log.info("使用 --repo <path> [--base <ref>] [--head <ref>] 从命令行运行审查。");
            return;
        }

        // 规范化 Windows 反斜杠路径
        repoPath = repoPath.replace('\\', '/');

        log.info("");
        log.info("╔══════════════════════════════════════════════╗");
        log.info("║       Code Review AI Agent - Phase 1         ║");
        log.info("╚══════════════════════════════════════════════╝");
        log.info("");
        log.info("仓库: {}", repoPath);
        log.info("基准 ref:   {}", baseRef);
        log.info("目标 ref:   {}", headRef);
        log.info("");

        ReviewResult result = service.review(repoPath, baseRef, headRef);

        log.info("");
        log.info("══════════════════ 审查报告 ═══════════════");
        log.info("变更文件: {}", result.getChangedFiles().size());
        log.info("发现总数: {}", result.getSummary().getTotalFindings());
        log.info("  CRITICAL: {}", result.getSummary().getSeverityCounts());
        log.info("  耗时: {}ms", result.getDurationMs());
        log.info("");

        List<ReviewFinding> findings = result.getFindings();
        if (findings.isEmpty()) {
            log.info("未发现任何问题，代码质量良好！");
        } else {
            for (int i = 0; i < findings.size(); i++) {
                ReviewFinding f = findings.get(i);
                log.info("--- 第 {} 条 ---", i + 1);
                log.info("  文件:     {}:{} - {}", f.getFile(), f.getLineStart(), f.getLineEnd());
                log.info("  严重度: {}", f.getSeverity());
                log.info("  类别: {}", f.getCategory());
                log.info("  维度: {}", f.getDimension());
                log.info("  原因:      {}", f.getExplanation());
                log.info("  修复:      {}", f.getSuggestion());
                log.info("");
            }
        }

        // 同时输出 JSON 供脚本使用
        System.out.println(objectMapper.writeValueAsString(result));
    }

    private void printUsage() {
        System.out.println("""
                Code Review AI Agent - 用法:
                  --repo <path>    Git 仓库路径（CLI 模式必填）
                  --base <ref>     基准 ref（默认: main）
                  --head <ref>     目标 ref（默认: HEAD）
                  --help           显示帮助

                不传 --repo 时以 Web Server 模式启动。
                """);
    }
}
