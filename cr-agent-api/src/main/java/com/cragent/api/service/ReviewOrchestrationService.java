package com.cragent.api.service;

import com.cragent.core.graph.ReviewState;
import com.cragent.core.graph.ReviewStateGraph;
import com.cragent.core.model.ReviewResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.Map;

@Service
public class ReviewOrchestrationService {

    private static final Logger log = LoggerFactory.getLogger(ReviewOrchestrationService.class);

    private final ReviewStateGraph stateGraph;

    public ReviewOrchestrationService(ReviewStateGraph stateGraph) {
        this.stateGraph = stateGraph;
    }

    /**
     * 同步审查入口：触达 StateGraph 跑完 9 步流水线，从返回的 Map 中取出最终 ReviewResult。
     * 本身无业务逻辑，只做路径规范化 + 空结果兜底，core 模块的内部变更只影响这一层。
     */
    public ReviewResult review(String repoPath, String baseRef, String headRef) {
        repoPath = repoPath.replace('\\', '/');
        log.info("开始审查: repo={}, {} -> {}", repoPath, baseRef, headRef);

        ReviewState state = stateGraph.execute(repoPath, baseRef, headRef);

        ReviewResult reviewResult = state.getReviewResult();
        if (reviewResult == null) {
            log.warn("未生成审查结果");
            return ReviewResult.empty(repoPath, baseRef, headRef);
        }

        log.info("审查完成: {} 条发现", reviewResult.getFindings().size());
        return reviewResult;
    }

    /**
     * SSE 流式版本，推送审查进度事件。
     * 当前 StateGraph 为同步调用，在 Flux 中手动发送开始/完成事件。
     */
    public Flux<Map<String, Object>> reviewStream(String repoPath, String baseRef, String headRef) {
        return Flux.create(sink -> {
            try {
                sink.next(Map.of(
                        "event", "review.started",
                        "repoPath", repoPath,
                        "baseRef", baseRef,
                        "headRef", headRef,
                        "timestamp", System.currentTimeMillis()
                ));

                long startTime = System.currentTimeMillis();

                sink.next(Map.of(
                        "event", "node.start",
                        "node", "fetch_diff",
                        "message", "正在拉取 Git Diff..."
                ));

                ReviewResult result = review(repoPath, baseRef, headRef);

                sink.next(Map.of(
                        "event", "node.complete",
                        "node", "review",
                        "message", "审查完成",
                        "findings", result.getFindings().size()
                ));

                sink.next(Map.of(
                        "event", "review.complete",
                        "result", result,
                        "durationMs", System.currentTimeMillis() - startTime
                ));

                sink.complete();
            } catch (Exception e) {
                log.error("流式审查失败", e);
                sink.next(Map.of(
                        "event", "review.error",
                        "error", e.getMessage()
                ));
                sink.error(e);
            }
        });
    }
}
