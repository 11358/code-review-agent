# Code Review AI Agent

基于 Spring AI Alibaba + MCP 协议的多 Agent 智能代码审查系统。

> AI 应用工程师作品集展示项目 | **实测 Precision 70% / Recall 93.3% / F1 80.1%**

## 架构

```
User (CLI / REST API)
    │
    ▼
ReviewOrchestrationService
    │
    ▼
ReviewStateGraph（9 节点流水线）
    │
    ├── [1] FetchDiff       → MCP Server（JGit）
    ├── [2] ParseDiff       → 文件级切块 + 维度路由
    ├── [3] DeterministicScan → 9 条 regex 确定性扫描（零 LLM 成本，confidence=1.0）
    ├── [4] SecurityReview  → SecuritySubAgent（千问 · 安全审查）
    ├── [5] BugReview       → BugSubAgent（千问 · Bug 审查）    │ 三路并行
    ├── [6] PerfReview      → PerformanceSubAgent（千问 · 性能审查） │
    ├── [7] Aggregate       → 四来源合并 + 去重 + 跨来源相邻合并
    ├── [8] VerifyFilter    → DeepSeek 跨模型交叉验证（打破回音室）
    └── [9] FormatOutput    → 结构化 JSON 报告
```

## 技术栈

| 层级 | 技术 | 版本 |
|------|------|------|
| 框架 | Spring Boot | 3.5.0 |
| AI 引擎 | Spring AI Alibaba（DashScope） | 1.1.2.3 |
| 主模型 | 通义千问（qwen-plus） | - |
| 验证模型 | DeepSeek（deepseek-chat） | 跨模型交叉验证 |
| Agent 模式 | Multi-Agent + StateGraph | - |
| 工具协议 | REST（MCP 语义接口，可平滑升级） | - |
| Git 操作 | JGit | 7.6.0 |
| API 层 | WebFlux + SSE | - |

## 项目结构

```
code-review-agent/
├── cr-agent-mcp-server/    # Git 工具服务（端口 8082，独立进程）
├── cr-agent-core/          # 核心引擎：StateGraph、SubAgent、Skills、验证过滤器
├── cr-agent-api/           # CLI + REST API（端口 8080）
├── start.sh                # 一键启动脚本
└── pom.xml                 # 父 POM（三模块）
```

## 快速开始

### 环境要求
- JDK 17+
- Maven 3.6+
- 阿里云百炼 API Key（[申请地址](https://bailian.console.aliyun.com/)）
- DeepSeek API Key（可选，用于跨模型交叉验证，[申请地址](https://platform.deepseek.com/)）

### 启动

```bash
# 1. 设置 API Key
export DASHSCOPE_API_KEY=sk-你的key
export DEEPSEEK_API_KEY=sk-你的key    # 可选，未设置时自动回退千问

# 2. 编译
mvn clean package -DskipTests

# 3. 一键启动（推荐）
bash start.sh

# 或手动分别启动：
# 终端 1 — Git 工具服务
java -jar cr-agent-mcp-server/target/cr-agent-mcp-server-1.0.0-SNAPSHOT.jar

# 终端 2 — Agent 审查服务
java -jar cr-agent-api/target/cr-agent-api-1.0.0-SNAPSHOT.jar
```

### CLI 模式

```bash
java -jar cr-agent-api/target/cr-agent-api-1.0.0-SNAPSHOT.jar \
  --repo /path/to/java/project \
  --base main \
  --head HEAD
```

### REST API

```bash
# 同步审查
curl -X POST http://localhost:8080/api/v1/review \
  -H "Content-Type: application/json" \
  -d '{"repoPath": "/path/to/repo", "baseRef": "main", "headRef": "HEAD"}'

# 流式审查（SSE）
curl -X POST http://localhost:8080/api/v1/review/stream \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream" \
  -d '{"repoPath": "/path/to/repo", "baseRef": "main", "headRef": "HEAD"}'

# 健康检查
curl http://localhost:8080/api/v1/health
```

## 审查维度

| 维度 | Agent | 检查项示例 | 严重度 |
|------|-------|-----------|--------|
| **安全** | SecuritySubAgent | SQL 注入、XSS、命令注入、硬编码凭据、路径遍历、不安全反序列化 | CRITICAL |
| **Bug** | BugSubAgent | 空指针、资源泄漏、竞态条件、异常吞没、逻辑错误、API 误用 | CRITICAL |
| **性能** | PerformanceSubAgent | N+1 查询、String+= 低效拼接、缓存缺失、过度分配、线程池误用 | WARNING |

## 核心设计决策

### 为什么是 Multi-Agent 而不是单一 Prompt？

cubic.dev 的实践表明，将"全能 Prompt"拆分为多个专业 Agent 可以减少 51% 的误报。每个 Agent 只关注一个维度，上下文更聚焦，判断更准。本质上，这是软件工程中的"单一职责原则"在 AI 领域的应用。

### 为什么需要确定性扫描层（Deterministic Scan）？

在 LLM 之前用 9 条 regex 检出"非黑即白"的模式——硬编码密码、空 catch、SQL 拼接等。这些不需要 AI 判断，regex 零成本、零波动、100% 确定。检出后把命中行从 diff 中裁切掉，让 LLM 不再看到，从源头消除同类重复报。

### 为什么需要 DeepSeek 跨模型交叉验证？

实测发现：千问审查 → 千问验证 = 回音室效应。同一模型既当运动员又当裁判，倾向于"信任自己"。引入 DeepSeek 作为独立第二裁判，打破这个循环。验证时不让 DeepSeek 判断"这是 bug 吗"，而是让它写具体的可编译修复代码——能写出具体改动的说明真看懂了，只能写模糊建议的说明没把握。

### 为什么是多轮并集（Union）而不是投票？

LLM 即使在低温度（0.1）下输出也不稳定——同一段代码，不同轮次可能报也可能不报。投票（≥2 票才保留）会丢 bug，并集（有一轮报了就要）最大化召回率。误报交给 DeepSeek 去过滤。

### 为什么 Git 工具服务用 REST 而不是 MCP 协议？

MVP 阶段先跑通全链路，REST 最快。但接口设计（`callToolAsString(toolName, args)`）完全模仿 MCP 的工具调用语义。升级到 MCP Streamable HTTP 只需改传输层的两个文件，Agent 层代码无需变动。

### 为什么用 StateGraph 编排？

- 条件路由：按文件类型跳过无关审查维度，省 token
- 状态显式化：共享 Map 传递上下文，便于调试和观测
- 可扩展：新增节点不影响现有结构

## 配置

```yaml
cragent:
  mcp-server:
    url: http://localhost:8082          # Git 工具服务地址
  review:
    agent-runs: 3                       # 每个 Agent 跑几轮（并集策略）
    review-timeout-seconds: 120         # 三路并行总超时
    max-diff-size: 500000               # diff 大小上限（500KB）
    chunk-size: 200                     # 每块行数
    verification:
      enabled: true                     # 是否启用 DeepSeek 交叉验证
      runs: 3                           # 每条 finding 验证轮数
      timeout-seconds: 60               # 单条 finding 验证超时
```

## 实测效果

在 15 个已知问题手工标注的测试集上：

| 指标 | 数值 |
|------|:---:|
| **Precision（精确率）** | **70.0%** |
| **Recall（召回率）** | **93.3%** |
| **F1 Score** | **80.1%** |
| 误报（False Positives） | 6 |
| 漏报（False Negatives） | 1 |
| 确定性扫描产出 | 15 条（零 LLM 成本） |
| 总耗时 | ~75s |

各检出类别表现：

| 类别 | 检出 | 实际 | 评价 |
|------|:---:|:---:|------|
| SQL 注入 | 4 | 4 | 完美 |
| 异常吞没 | 4 | 4 | 完美 |
| 敏感信息泄露 | 1 | 1 | 完美 |
| 空指针 | 1 | 1 | 正确 |
| 缓存缺失 | 1 | 1 | 正确 |
| N+1 查询 | 2 | 1 | 1 误报 |
| 资源泄漏 | 2 | 1 | 1 误报 |
| 命令注入 | 3 | 1 | 2 误报 |
| 无缓冲 I/O | 1 | 0 | 误报 |
| String+= 拼接 | 0 | 1 | 漏报 |

模型选型结论：**qwen-plus 是当前最优选择**——Precision 70% vs qwen-max 的 45-61%，且稳定性远超 qwen-max（后者两次运行结果可差 55%）。

## 已知局限

- **评估体系缺失**：15 个问题手工标注，每次评估靠人眼对比。生产需要标注 PR 语料 + 自动化指标追踪
- **单模型审查**：三个 Agent 共用千问，审查阶段仍有回音室。理想方案是 Security 用千问、Bug 用 DeepSeek
- **String+= 跨行漏报**：确定性扫描的正则要求声明和 += 在同一行，跨行拼接无法匹配
- **SSE 流式实现不完整**：只推送 start/complete 事件，未做节点级进度推送
- **SecuritySubAgent 有重复代码**：未继承 AbstractSubAgent，~80 行解析逻辑与基类重复

## 扩展方向

- [ ] MCP 协议升级：替换传输层为 MCP Streamable HTTP
- [ ] 跨模型交叉审查：审查阶段就用不同模型，而非仅在验证阶段
- [ ] SecuritySubAgent 重构：继承 AbstractSubAgent 消除重复代码
- [ ] 真正的 SSE 流式推送：节点级进度事件
- [ ] 标准化评估体系：标注 PR 语料 + 自动化 Precision/Recall 追踪
- [ ] CI/CD 集成：GitHub App Webhook，PR 创建时自动审查

## 许可证

本项目用于作品集展示目的。
