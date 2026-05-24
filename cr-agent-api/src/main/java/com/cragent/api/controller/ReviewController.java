package com.cragent.api.controller;

import com.cragent.api.dto.ReviewRequest;
import com.cragent.api.dto.ReviewResponse;
import com.cragent.api.service.ReviewOrchestrationService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class ReviewController {

    private static final Logger log = LoggerFactory.getLogger(ReviewController.class);

    private final ReviewOrchestrationService service;

    /** 构造器注入 ReviewOrchestrationService */
    public ReviewController(ReviewOrchestrationService service) {
        this.service = service;
    }

    @PostMapping("/review")
    public Mono<ReviewResponse> review(@Valid @RequestBody ReviewRequest request) {
        log.info("收到 REST 审查请求: repo={}, {} -> {}",
                request.getRepoPath(), request.getBaseRef(), request.getHeadRef());

        return Mono.fromCallable(() ->
                service.review(request.getRepoPath(), request.getBaseRef(), request.getHeadRef()))
                .subscribeOn(Schedulers.boundedElastic())
                .map(ReviewResponse::from);
    }

    @PostMapping(value = "/review/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<Map<String, Object>> reviewStream(@Valid @RequestBody ReviewRequest request) {
        log.info("SSE 审查请求: repo={}, {} -> {}",
                request.getRepoPath(), request.getBaseRef(), request.getHeadRef());

        return service.reviewStream(
                request.getRepoPath(), request.getBaseRef(), request.getHeadRef());
    }

    @GetMapping("/health")
    public Mono<Map<String, String>> health() {
        return Mono.just(Map.of(
                "status", "UP",
                "service", "cr-agent-api",
                "version", "1.0.0"
        ));
    }
}
