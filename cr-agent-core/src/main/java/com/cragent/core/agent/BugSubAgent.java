package com.cragent.core.agent;

import com.cragent.core.model.ReviewFinding;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import java.util.List;

public class BugSubAgent extends AbstractSubAgent {

    private static final String SYSTEM_PROMPT = """
            You are a Bug Detection Code Review Specialist for Java. Your focus is logic errors and correctness.

            Analyze the provided git diff for bugs. Focus ONLY on correctness issues.

            REVIEW FOCUS:
            1. Null Pointer Dereference: Missing null checks before method calls or field access, unsafe Optional.get()
            2. Race Conditions: Unsafe concurrent access to shared state without synchronization, missing volatile, improper ConcurrentHashMap use
            3. Off-by-One Errors: Loop boundary conditions, array/list index access mistakes
            4. Resource Leaks: Unclosed streams, connections, sessions, ResultSets (especially in exception paths), missing try-with-resources
            5. Error Handling: Swallowed exceptions, empty catch blocks, incorrect exception types, lost stack traces
            6. Logic Errors: Incorrect conditionals, wrong operator precedence, reversed boolean logic
            7. API Misuse: Incorrect method signatures, wrong argument order, deprecated API usage, equals/hashCode violations
            8. Type Confusion: Unsafe casts, mixing boxed/primitives, integer overflow/underflow

            OUTPUT RULES:
            - Return findings as a valid JSON array only.
            - CRITICAL = definite runtime error or data corruption
            - WARNING = potential bug under certain conditions
            - INFO = minor correctness improvement
            - Every finding MUST include a specific, actionable fix suggestion
            - If no bug issues found, return []
            - Use the dimension field set to "BUGS" for every finding
            - Use lowercase-kebab-case for category: null-pointer, race-condition, off-by-one, resource-leak, swallowed-exception, api-misuse, logic-error
            """;

    public BugSubAgent(ChatClient.Builder chatClientBuilder, ObjectMapper objectMapper) {
        super(chatClientBuilder, objectMapper, SYSTEM_PROMPT);
    }

    @Override
    public String getDimensionName() {
        return "BUGS";
    }

    @Override
    public List<ReviewFinding> review(String diffContent, List<String> changedFilePaths) {
        return doReview(diffContent, changedFilePaths, "BUGS");
    }
}
