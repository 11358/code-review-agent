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
            log.info("No --repo argument provided. Starting in server mode.");
            log.info("Use --repo <path> [--base <ref>] [--head <ref>] to run a review from CLI.");
            return;
        }

        // Normalize Windows backslash paths for JSON serialization
        repoPath = repoPath.replace('\\', '/');

        log.info("");
        log.info("╔══════════════════════════════════════════════╗");
        log.info("║       Code Review AI Agent - Phase 1         ║");
        log.info("╚══════════════════════════════════════════════╝");
        log.info("");
        log.info("Repository: {}", repoPath);
        log.info("Base ref:   {}", baseRef);
        log.info("Head ref:   {}", headRef);
        log.info("");

        ReviewResult result = service.review(repoPath, baseRef, headRef);

        log.info("");
        log.info("══════════════════ Review Report ═══════════════");
        log.info("Files changed: {}", result.getChangedFiles().size());
        log.info("Total findings: {}", result.getSummary().getTotalFindings());
        log.info("  CRITICAL: {}", result.getSummary().getSeverityCounts());
        log.info("  Duration: {}ms", result.getDurationMs());
        log.info("");

        List<ReviewFinding> findings = result.getFindings();
        if (findings.isEmpty()) {
            log.info("No issues found. Code looks good!");
        } else {
            for (int i = 0; i < findings.size(); i++) {
                ReviewFinding f = findings.get(i);
                log.info("--- Finding #{} ---", i + 1);
                log.info("  File:     {}:{} - {}", f.getFile(), f.getLineStart(), f.getLineEnd());
                log.info("  Severity: {}", f.getSeverity());
                log.info("  Category: {}", f.getCategory());
                log.info("  Dimension: {}", f.getDimension());
                log.info("  Why:      {}", f.getExplanation());
                log.info("  Fix:      {}", f.getSuggestion());
                log.info("");
            }
        }

        // Also output as JSON for scripting use
        System.out.println(objectMapper.writeValueAsString(result));
    }

    private void printUsage() {
        System.out.println("""
                Code Review AI Agent - Usage:
                  --repo <path>    Path to git repository (required for CLI mode)
                  --base <ref>     Base reference (default: main)
                  --head <ref>     Head reference (default: HEAD)
                  --help           Show this help

                Without --repo, starts in web server mode.
                """);
    }
}
