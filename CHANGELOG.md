# 2026-05-22 开发日志：从 7 条到 23 条的调优历程

## 背景

初始版本审查 D:\projects\cr-test-repo 的 UserService.java（15 个已知问题），最初只查出 7 条发现，分类全是 OTHER。

## 心路历程

### 第一轮：跑通全链路
- **问题**：jar 没有主清单属性 → spring-boot-maven-plugin 缺少 `<executions><goal>repackage</goal>`
- **问题**：MCP 服务 500 → Windows 反斜杠路径 JSON 序列化后 Git 服务不认
- **问题**：API Key 401 → 之前撤销了旧 Key，忘设新的
- **结果**：首次成功运行，7 条发现，全 CRITICAL，跑通全链路 ✅

### 第二轮：修复去重与分类
- **去重不生效**：uniqueKey 含 dimension，同一行不同 Agent 视为不同 key → 去掉 dimension，冲突时合并维度名
- **分类全 OTHER**：LLM 返回 `sql-injection`，toUpperCase 后变 `SQL-INJECTION`，对不上枚举 `SQL_INJECTION` → fromString 统一把 `-` 和空格换 `_`
- **Performance Agent 越界**：报了 SQL 注入 → 提示词加 `DO NOT report security/bug issues`
- **changedFiles 为空**：FormatOutputNode 没从 state 取 ParseDiffNode 输出 → 补一行读取
- **结果**：7→8，NPE 找回，分类 SQL_INJECTION/NULL_POINTER 正确 ✅

### 第三轮：发现验证过滤器是杀手
- 日志：`Aggregated findings: 13 raw -> 13 deduped -> Verification: 13 -> 7 findings`
- Performance Agent 的 3 条发现全被验证过滤器杀了
- 性能问题主观性强（"取决于规模"），同一模型自审白判，倾向于判为不严重
- **实验**：关掉验证 → 13 条发现，准确率 85%
- **结论**：同一模型既做审查又做质检 = 回音室效应

### 第四轮：引入 DeepSeek 交叉验证
- 添加 spring-ai-openai 依赖，用 OpenAiApi 直连 DeepSeek
- 验证过滤器注入 `@Qualifier("deepseek")` ChatClient
- 千问负责"宁可错杀不可放过"，DeepSeek 负责"拨乱反正清误报"
- UNBUFFERED_IO 误报被 DeepSeek 正确干掉
- 没设 DEEPSEEK_API_KEY 时自动回退到千问，不炸

### 第五轮：多轮并集 + 投票提升召回率
- **原先逻辑错误**：投票是交集（≥2 票才要），反而降低召回
- **修正**：5 轮并集（全留着，最大化召回）→ DeepSeek 3 轮平均置信度（保证准确率）
- 唤异常 1/3 → 3/3，硬编码密码 1/2 → 2/2，命令注入 0/1 → 3/3
- N+1 查询首次检出，从 0 到 2

### 第六轮：提示词精修
- Security Agent：SQL 注入检测规则从泛泛而谈改为 "report EVERY occurrence separately"
- Bug Agent：吞异常每个 catch 块单独报
- 温度从 0.3 降到 0.1 减少随机波动

## 最终数据

| 指标 | 初始版本 | 最终版本 |
|------|---------|---------|
| 总发现数 | 7 | **23** |
| SQL 注入 | 4/4 | 6/4（多了 2，LLM 过剩） |
| 命令注入 | 0/1 | **3/3** ✅ |
| 硬编码密码 | 1/2 | **2/2** ✅ |
| 吞异常 | 1/3 | **3/3** ✅ |
| N+1 查询 | 0/1 | **2/2** ✅ |
| 空指针 | 0/1 | 3/1（多了 2） |
| 资源泄漏 | 1/1 | 2/1（多了 1） |
| UNBUFFERED_IO | 0 | 2/0（误报） |
| 准确率 | ~60% | ~60% |
| 召回率 | ~47% | **~73%** |

## 架构演进

```
初始：千问单模型 → 3 Agent 串行 → 同模型自验证 → 7 条

最终：千问 3 Agent × 5 轮并集 → DeepSeek × 3 轮平均置信度 → 23 条
       ↑ 最大化召回                    ↑ 跨模型保证准确率
```

## 剩余问题

1. **LLM 天性不稳定**：同一段代码每次运行结果不同，SQL 注入从 4 到 1 之间波动
2. **性能问题漏报**：String+=（EXCESSIVE_ALLOCATION）和 Pattern 缓存（MISSING_CACHE）千问始终抓不到——模型能力天花板
3. **同类重复报**：同一模式在不同方法中出现，LLM 有时多报（如 SQL 注入 6/4）
4. **UNBUFFERED_IO 误报**：FileInputStream 不包 BufferedInputStream 在千问看来算未缓冲 I/O，实际上对于 1024 字节小文件无所谓
5. **成本 ×15**：3 Agent × 5 轮 + 每条发现 DeepSeek × 3 轮 = 大量 API 调用
6. **耗时 3-5 分钟**：实战中需要优化并行度
7. **没有标准测试集**：15 个已知问题是手工标注的，缺乏可复现的评估基准

## 面试话术

> "这个项目最有价值的部分不是调 Prompt 把召回率从 47% 做到 73%，而是中间踩过的坑和架构决策。比如同模型自验证的回音室问题——我关了验证过滤器，换成 DeepSeek 做跨模型质检；比如并集策略——每个 Agent 跑 5 轮取并集最大化召回，再用另一个模型保证准确率。这些都是 AI 工程化落地真正会遇到的问题。"
