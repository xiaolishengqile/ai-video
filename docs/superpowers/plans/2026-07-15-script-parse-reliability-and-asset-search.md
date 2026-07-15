# 剧本解析可靠性与资产搜索 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复分集资产候选全部为空、低置信或无图资产误绑、假成功、取消后遗留检查点、EOF 后整集重做和 OpenAI 请求体膨胀问题。

**Architecture:** 使用一个共享名称归一化器生成原始名、路径末段名和视觉基础名，候选服务在当前集与当前类型内本地评分并返回结构化决策。Pipeline 继续使用现有检查点，但分集子 Agent 必须返回可验证的结构化完成证明；取消时把运行中检查点转为 UNKNOWN，恢复计划只把已验证成功的分集视为完成。OpenAI Responses 适配层过滤 AgentScope DSML 文本，候选工具支持批量且只返回代表图片，限制上下文增长。

**Tech Stack:** Java 21、Spring Boot、MyBatis-Plus、AgentScope、JUnit 5、Mockito、Next.js/TypeScript。

## 实施状态

- Task 1-5 已完成；检查确认当前 CONTENT 持久化路径原本就只追加一次，因此只新增了 DSML 请求过滤，没有改动该稳定路径。
- 本次相关后端测试、前端目标文件 ESLint、Next.js 生产构建通过。
- 后端全量测试已执行；274 个测试中有 21 个与本次改动无关的既有失败（测试数据库不可达、生成模型测试 fixture 缺少解析器、一个 Mockito 无效 stub），本次涉及的测试全部通过。
- 已确认未调整子 Agent 并发数，未新增场次标题集数校验。

## Global Constraints

- 直接在 `main` 分支实施并提交中文 Commit Message。
- 不调整当前子 Agent 并发数。
- 不新增场次标题集数校验。
- 不新增数据库表或向量库，不新增大模型调用。
- 保留 `search_episode_asset_candidates` 单条请求与 `unique / ambiguous / none` 外部兼容。

---

### Task 1: 共享名称归一化与本地评分召回

**Files:**
- Create: `ai-fusion-video/src/main/java/com/stonewu/fusion/service/asset/AssetNameNormalizer.java`
- Create: `ai-fusion-video/src/main/java/com/stonewu/fusion/service/asset/model/EpisodeAssetSearchResult.java`
- Modify: `ai-fusion-video/src/main/java/com/stonewu/fusion/service/asset/model/EpisodeAssetCandidate.java`
- Modify: `ai-fusion-video/src/main/java/com/stonewu/fusion/service/asset/EpisodeAssetCandidateService.java`
- Modify: `ai-fusion-video/src/main/java/com/stonewu/fusion/service/script/ScriptAssetPrebindingService.java`
- Test: `ai-fusion-video/src/test/java/com/stonewu/fusion/service/asset/AssetNameNormalizerTests.java`
- Test: `ai-fusion-video/src/test/java/com/stonewu/fusion/service/asset/EpisodeAssetCandidateServiceTests.java`
- Test: `ai-fusion-video/src/test/java/com/stonewu/fusion/service/script/ScriptAssetPrebindingServiceTests.java`

**Interfaces:**
- Produces: `AssetNameNormalizer.forms(String)`, `EpisodeAssetCandidateService.search(Long,Integer,String,String)`。
- `EpisodeAssetSearchResult` 返回 `matchStatus`、Top 5 candidates 与 bestScore。

- [ ] 先写失败测试：目录末段精确匹配、视觉后缀匹配、灰脊核心模糊召回、多个凌烬状态保持歧义、无图候选排除、补给站场景返回 none。
- [ ] 运行定向测试，确认因新类型和新行为缺失而失败。
- [ ] 实现最小归一化：仅剥离 `/` 与 `\` 路径，统一空格/引号/连接符，保留原始名，并重复去除已知视觉后缀。
- [ ] 实现评分：路径末段/清洗后精确 100、视觉基础名相同 90、包含 80、字符 LCS 相似度 60–79；只有最高分不低于 90 且领先第二名至少 15 才为 unique。
- [ ] 只保留有 `imageUrl` 的代表子项；低分单候选仍为 ambiguous。
- [ ] 让预匹配复用共享归一化结果，删除重复的清洗实现。
- [ ] 运行三个测试类并确认通过。

### Task 2: 批量、精简且可解释的候选工具与 UI

**Files:**
- Modify: `ai-fusion-video/src/main/java/com/stonewu/fusion/service/ai/tool/SearchEpisodeAssetCandidatesToolExecutor.java`
- Modify: `ai-fusion-video/src/test/java/com/stonewu/fusion/service/ai/tool/SearchEpisodeAssetCandidatesToolExecutorTests.java`
- Modify: `ai-fusion-video/src/main/resources/prompts/agents/episode-scene-writer.system.md`
- Modify: `ai-fusion-video-web/components/dashboard/agent-pipeline/results.tsx`

**Interfaces:**
- 单条输入继续支持 `projectId/scriptEpisodeId/assetType/name`。
- 批量输入增加 `queries:[{assetType,name}]`，输出 `results`；每个结果包含 queryName、assetType、matchStatus、bestScore、candidates。

- [ ] 写失败测试：单条兼容、批量返回、多候选包含 score/matchMode/matchedName/evidence 和单个代表图片字段。
- [ ] 运行工具测试确认失败。
- [ ] 改造工具执行器，复用候选服务决策，不再为每个候选返回全部子项。
- [ ] 更新子 Agent 提示词，要求同一集未匹配实体一次批量搜索，AI 对 ambiguous 可明确选择不匹配。
- [ ] 更新 UI，展示查询名称、类型、最高分、匹配方式和未命中原因；兼容旧单条结果。
- [ ] 运行后端测试与前端 lint。

### Task 3: 实体绑定安全与分集完成证明

**Files:**
- Modify: `ai-fusion-video/src/main/java/com/stonewu/fusion/service/script/SceneEntityManifestService.java`
- Modify: `ai-fusion-video/src/test/java/com/stonewu/fusion/service/script/SceneEntityManifestServiceTests.java`
- Modify: `ai-fusion-video/src/main/java/com/stonewu/fusion/service/ai/pipeline/PipelineToolCheckpointService.java`
- Modify: `ai-fusion-video/src/test/java/com/stonewu/fusion/service/ai/pipeline/PipelineToolCheckpointServiceTests.java`
- Modify: `ai-fusion-video/src/main/java/com/stonewu/fusion/service/ai/agentscope/AgentScopeSubAgentToolAdapter.java`
- Modify: `ai-fusion-video/src/test/java/com/stonewu/fusion/service/ai/agentscope/AgentScopeSubAgentToolAdapterTests.java`
- Modify: `ai-fusion-video/src/main/resources/prompts/agents/episode-scene-writer.system.md`

**Interfaces:**
- 分集子 Agent 最终返回 `{"status":"success","scriptEpisodeId":N,"expectedSceneCount":N,"savedSceneCount":N,"message":"..."}`。
- Pipeline 在标记 `episode_scene_writer` 成功前用数据库实际场次数校验三个数字一致且大于 0。

- [ ] 写失败测试：低分唯一候选不自动绑定、无图 selectedAssetId 保持未绑定、0 场/数量不一致/非结构化总结不能标记成功。
- [ ] 运行测试确认失败。
- [ ] 让实体解析使用候选服务的 matchStatus，而不是 `candidates.size()==1`；移除无图 initial 子项回退。
- [ ] 让检查点服务校验分集完成证明，校验失败时返回结构化 error 并标记 FAILED。
- [ ] 更新子 Agent 适配器使用校验后的结果，更新提示词输出结构化证明。
- [ ] 运行定向测试确认通过。

### Task 4: EOF 续跑与取消后的检查点收敛

**Files:**
- Modify: `ai-fusion-video/src/main/java/com/stonewu/fusion/service/ai/pipeline/ScriptFullParseResumeStrategy.java`
- Modify: `ai-fusion-video/src/test/java/com/stonewu/fusion/service/ai/pipeline/ScriptFullParseResumeStrategyTests.java`
- Modify: `ai-fusion-video/src/main/java/com/stonewu/fusion/service/ai/pipeline/PipelineRuntimeService.java`
- Modify: `ai-fusion-video/src/test/java/com/stonewu/fusion/service/ai/pipeline/PipelineRuntimeServiceTests.java`
- Modify: `ai-fusion-video/src/main/resources/prompts/agents/episode-scene-writer.system.md`

**Interfaces:**
- 只有 `episode_scene_writer=SUCCEEDED` 且数据库场次非空才进入恢复计划 completed。
- Pipeline 取消时调用现有 `PipelineCheckpointRepository.markRunningUnknown(runId)`。

- [ ] 写失败测试：部分场次但 writer FAILED 仍属于 pending；取消后所有 RUNNING 检查点转 UNKNOWN。
- [ ] 运行测试确认失败。
- [ ] 修改恢复策略，只认可已验证成功的 writer 检查点；提示子 Agent 重试时保留已有场次并只补缺失批次。
- [ ] 修改 Pipeline 取消流程收敛运行中检查点。
- [ ] 运行定向测试确认通过。

### Task 5: 过滤 DSML 与修复重复内容持久化

**Files:**
- Modify: `ai-fusion-video/src/main/java/com/stonewu/fusion/service/ai/provider/OpenAiResponsesAgentScopeModel.java`
- Create: `ai-fusion-video/src/test/java/com/stonewu/fusion/service/ai/provider/OpenAiResponsesAgentScopeModelTests.java`
- Modify: `ai-fusion-video/src/main/java/com/stonewu/fusion/service/ai/agentscope/AgentScopeAssistantService.java`
- Modify: `ai-fusion-video/src/test/java/com/stonewu/fusion/service/ai/agentscope/AgentScopeAssistantServiceTests.java`

**Interfaces:**
- OpenAI Responses 请求不再把 `<｜DSML｜...>` TextBlock 作为普通 assistant 文本重复发送。
- 每个 CONTENT 事件只持久化一次。

- [ ] 写失败测试：DSML TextBlock 不进入请求 input；普通文本与 ToolUseBlock 仍保留；CONTENT 不重复累积。
- [ ] 运行测试确认失败。
- [ ] 在模型映射层跳过 DSML TextBlock，并删除 assistant 累积器的重复 append。
- [ ] 运行定向测试确认通过。

### Task 6: 全量验证与提交

**Files:**
- Modify: `docs/superpowers/plans/2026-07-15-script-parse-reliability-and-asset-search.md`

- [ ] 运行后端相关测试集合。
- [ ] 运行 `mvn test`。
- [ ] 运行前端 `pnpm lint` 与 `pnpm build`。
- [ ] 检查 `git diff --check`、确认未修改并发配置和场次标题校验。
- [ ] 使用中文 Commit Message 提交完整改动。
