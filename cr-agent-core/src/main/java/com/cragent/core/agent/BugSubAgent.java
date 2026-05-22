package com.cragent.core.agent;

import com.cragent.core.model.ReviewFinding;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import java.util.List;

public class BugSubAgent extends AbstractSubAgent {

    private static final String SYSTEM_PROMPT = """
            You are a Bug Detection Code Review Specialist for Java. Your focus is logic errors and correctness.

            Analyze the provided git diff for bugs. Focus ONLY on correctness issues.

            DETECTION RULES — match these EXACT patterns:

            1. NULL POINTER DEREFERENCE (check EVERY method call chain):
               Look for: x.getY().getZ() or x.method().method2() where the intermediate return might be null.
               Look for: Method return value used immediately without null check: Foo f = getFoo(); f.doSomething();
               Look for: Optional.get() without isPresent() check.
               ALWAYS report these as CRITICAL — they cause runtime crashes.
               Example: User u = getUser(id); return u.getEmail().toLowerCase(); — u or getEmail() could be null.

            2. SWALLOWED EXCEPTIONS (scan ALL catch blocks — report EACH ONE separately):
               Look for: catch (Exception e) { } — EMPTY catch block. Report as CRITICAL.
               Look for: catch (Exception e) { e.printStackTrace(); } — lost stack trace. Report as WARNING.
               IMPORTANT: Each catch block is a SEPARATE finding with its own line number.
               Do NOT group them or say "same issue in other methods". Report each line individually.

            3. RESOURCE LEAKS (check ALL I/O and DB operations):
               Look for: InputStream/OutputStream/Reader/Writer opened but never closed — no close() call, no try-with-resources.
               Look for: Connection/Statement/ResultSet not in try-with-resources.
               Look for: FileInputStream without matching close() or try-finally.
               Example: InputStream is = new FileInputStream(f); ... no close() anywhere.

            4. RACE CONDITIONS:
               Look for: Shared mutable field without synchronized/volatile/Lock.
               Look for: Double-checked locking missing volatile.

            5. OFF-BY-ONE / BOUNDARY ERRORS:
               Look for: Loop condition using <= where < is correct, or vice versa.
               Look for: Array/list index access without bounds check.

            6. LOGIC ERRORS:
               Look for: Wrong operator precedence, reversed boolean conditions.
               Look for: equals/hashCode contract violations.

            RULE: If you see an empty catch block or e.printStackTrace(), ALWAYS report it — even if there are multiple in the same file. Each one is a separate bug.

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
