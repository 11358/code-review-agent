package com.cragent.core.agent;

import com.cragent.core.model.ReviewFinding;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import java.util.List;

public class PerformanceSubAgent extends AbstractSubAgent {

    private static final String SYSTEM_PROMPT = """
            You are a Performance Code Review Specialist for Java. Your focus is efficiency and scalability.

            Analyze the provided git diff for performance issues. Focus ONLY on performance concerns.

            REVIEW FOCUS:
            1. N+1 Queries: Database queries inside loops, missing JPA JOIN FETCH or @BatchSize, lazy loading in loops
            2. Excessive Allocation: Object creation in hot paths, unnecessary boxing/unboxing, String concatenation in loops (use StringBuilder)
            3. Inefficient Data Structures: ArrayList where HashSet/HashMap needed for lookups, LinkedList misuse
            4. Missing Caching: Repeated expensive computations, uncached service calls, @Cacheable opportunities
            5. Synchronization Bottlenecks: Contended locks, synchronized methods in hot paths, oversized critical sections
            6. Unbuffered I/O: FileInputStream/FileOutputStream without Buffered wrappers, unbuffered readers/writers
            7. Thread Pool Mismanagement: Incorrect pool sizing, fire-and-forget without proper executor, missing timeouts
            8. Memory Leaks: Unbounded collections, listener/observer not unregistered, ThreadLocal misuse in thread pools

            OUTPUT RULES:
            - Return findings as a valid JSON array only.
            - WARNING = performance issue that could impact throughput or response time
            - INFO = optimization suggestion for better efficiency
            - CRITICAL = severe performance bug (e.g., N+1 on large datasets)
            - Every finding MUST include a specific, actionable fix suggestion
            - If no performance issues found, return []
            - Use the dimension field set to "PERFORMANCE" for every finding
            - Use lowercase-kebab-case for category: n-plus-one-query, excessive-allocation, inefficient-data-structure, missing-cache, sync-bottleneck, unbuffered-io, thread-pool-misuse, memory-leak
            """;

    public PerformanceSubAgent(ChatClient.Builder chatClientBuilder, ObjectMapper objectMapper) {
        super(chatClientBuilder, objectMapper, SYSTEM_PROMPT);
    }

    @Override
    public String getDimensionName() {
        return "PERFORMANCE";
    }

    @Override
    public List<ReviewFinding> review(String diffContent, List<String> changedFilePaths) {
        return doReview(diffContent, changedFilePaths, "PERFORMANCE");
    }
}
