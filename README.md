# Code Review AI Agent

Multi-Agent AI Code Review System powered by Spring AI Alibaba + MCP Protocol.

> Built for AI Application Engineer portfolio demonstration.

## Architecture

```
User (CLI / REST API)
    |
    v
ReviewOrchestrationService
    |
    v
ReviewStateGraph (8-node pipeline)
    |
    ├── [1] FetchDiff       → MCP Server (JGit)
    ├── [2] ParseDiff       → File-level chunking + dimension routing
    ├── [3] SecurityReview  → SecuritySubAgent (OWASP checks)
    ├── [4] BugReview       → BugSubAgent (correctness)
    ├── [5] PerfReview      → PerformanceSubAgent (efficiency)
    ├── [6] Aggregate       → Merge + deduplicate + sort
    ├── [7] VerifyFilter    → Fix-Guided false positive reduction
    └── [8] FormatOutput    → Structured JSON report
```

## Tech Stack

| Layer | Technology | Version |
|-------|-----------|---------|
| Framework | Spring Boot | 3.5.x |
| AI Engine | Spring AI Alibaba (DashScope) | 1.1.2.2 |
| LLM | 通义千问 (qwen-plus) | - |
| Agent Pattern | Multi-Agent + StateGraph | - |
| Tool Protocol | MCP (Model Context Protocol) | 1.0.0 |
| Git Operations | JGit | 7.2.x |
| API | WebFlux + SSE | - |

## Project Structure

```
code-review-agent/
├── cr-agent-mcp-server/    # Git MCP Server (port 8082)
├── cr-agent-core/           # StateGraph, SubAgents, Skills, Filters
├── cr-agent-api/            # CLI + REST API (port 8080)
└── pom.xml                  # Parent POM
```

## Quick Start

### Prerequisites
- JDK 17+
- Maven 3.6+
- DashScope API Key (阿里云百炼)

### Setup
```bash
# Set API key
export DASHSCOPE_API_KEY=sk-xxxxx

# Build
mvn clean package -DskipTests

# Start MCP Server (terminal 1)
java -jar cr-agent-mcp-server/target/cr-agent-mcp-server-1.0.0-SNAPSHOT.jar

# Start API (terminal 2)
java -jar cr-agent-api/target/cr-agent-api-1.0.0-SNAPSHOT.jar
```

### CLI Usage
```bash
java -jar cr-agent-api/target/cr-agent-api-1.0.0-SNAPSHOT.jar \
  --repo /path/to/java/project \
  --base main \
  --head feature-branch
```

### REST API
```bash
# Synchronous review
curl -X POST http://localhost:8080/api/v1/review \
  -H "Content-Type: application/json" \
  -d '{"repoPath": "/path/to/repo", "baseRef": "main", "headRef": "HEAD"}'

# Streaming review (SSE)
curl -X POST http://localhost:8080/api/v1/review/stream \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream" \
  -d '{"repoPath": "/path/to/repo", "baseRef": "main", "headRef": "HEAD"}'

# Health check
curl http://localhost:8080/api/v1/health
```

## Key Design Decisions

### Why Multi-Agent over Monolithic Prompt?
cubic.dev demonstrated 51% false positive reduction by switching from a single "do-everything" prompt to specialized micro-agents. Each agent maintains focused context, improving precision.

### Why Fix-Guided Verification Filter?
Asking LLM "is this a real bug?" produces high false positives because the model conflates "this COULD be wrong" with "this IS wrong." Instead, we ask the model to propose a concrete, compilable fix. If it produces specific code changes → high confidence. If it produces vague advice ("consider validating input") → low confidence, filtered out.

### Why MCP Protocol?
MCP is the 2026 standard for AI-tool integration. Our Git MCP Server runs as an independent process, exposing 4 tools via Streamable HTTP. This demonstrates protocol-level architecture understanding, not just library usage.

### Why StateGraph over Simple Sequential Calls?
- Conditional edges skip irrelevant review dimensions per file type
- Explicit state model enables debugging and observability
- Extensible: new nodes can be inserted without restructuring

## Review Dimensions

| Dimension | Examples | Severity |
|-----------|----------|----------|
| **Security** | SQL Injection, XSS, Command Injection, Hardcoded Secrets | CRITICAL |
| **Bugs** | NPE, Resource Leaks, Race Conditions, Logic Errors | CRITICAL |
| **Performance** | N+1 Queries, Excessive Allocation, Missing Caching | WARNING |

## Configuration

```yaml
# Key configuration properties
cragent:
  mcp-server:
    url: http://localhost:8082/mcp
  review:
    max-diff-size: 500000       # 500KB max
    chunk-size: 200             # Lines per chunk
    verification:
      enabled: true
      confidence-threshold: 0.6
```

## Known Trade-offs (Honest Assessment)

- **No evaluation framework**: Production needs labeled PR corpus + precision/recall tracking
- **Single model**: Same model for generation and review creates echo chamber risk. Production should use cross-model review
- **Sequential sub-agents**: Can be parallelized with CompletableFuture for 3x speedup
- **No human-in-the-loop**: Production needs HumanNode in StateGraph for critical decisions
- **Diff size limit**: Very large PRs (1000+ files) need sampling strategy

## License

This project is for portfolio/demonstration purposes.
