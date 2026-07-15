# AI Pipeline 通用检查点与断点续跑设计

## 背景

当前 AI Pipeline 由单次 AgentScope `ReActAgent` 调用驱动。模型、网络或服务异常会终止整条任务；已经成功执行的工具调用虽然会写入业务数据库和消息历史，但系统没有逻辑任务、执行尝试和步骤检查点之间的关系，因此无法安全判断应该跳过、验证还是重放某一步。

本设计为所有 AI Pipeline 建立统一的持久化运行框架。剧本全量解析是第一个需要恢复的真实案例，但基础设施、状态机、接口和前端交互必须面向所有已注册 Pipeline 类型。

## 已确认需求

- 模型瞬时故障的重试次数从 2 次提高为 5 次，即首次请求失败后最多再重试 5 次，总尝试次数为 6。
- 只重试限流、可重试 HTTP 错误、网络错误和超时；参数错误、鉴权错误、上下文超限和业务校验错误不重试。
- 模型层 5 次重试仍失败后，逻辑 Pipeline 自动从最近可靠检查点续跑 1 次。
- 自动续跑后的执行仍失败时，任务进入等待人工继续状态，用户可点击“继续执行”。
- 只有瞬时故障触发自动续跑；业务错误直接进入人工处理状态。
- 续跑创建新的 Agent 执行尝试，不恢复旧 Agent 内存，不重复加载完整历史对话。
- 所有 AI Pipeline 共用运行、检查点、错误分类、并发控制和前端交互能力。
- 必须能够基于已持久化的工具历史和业务数据恢复本次已经失败的历史任务。

## 非目标

- 不保证任意未知工具都能自动重放。
- 不通过重放整段 Agent 消息来恢复任务。
- 不对不可逆外部操作进行无条件重复提交。
- 不修改普通多轮 AI 助手的会话语义；本设计只覆盖 Pipeline 类任务。

## 方案选择

采用“通用运行框架 + Pipeline 恢复策略 + 工具检查点策略”的混合架构。

单纯恢复旧 Agent 内存会继续放大上下文，并可能重复执行有副作用的工具；每种 Pipeline 完全独立实现续跑又会产生大量重复代码。混合架构将共性收敛到运行时，由工具和 Pipeline 只声明各自的业务恢复规则。

## 核心组件

### PipelineRuntime

负责逻辑任务的创建、执行尝试、状态流转、瞬时错误判定、自动续跑、人工续跑、取消和单任务并发锁。该组件只处理运行规则，不理解具体剧本、图片或视频业务。

### PipelineCheckpointService

在有副作用的工具执行前后持久化检查点：

- 执行前写入 `RUNNING`。
- 成功后写入 `SUCCEEDED` 和精简输出快照。
- 工具明确返回业务错误或抛出异常时写入 `FAILED`。
- 服务异常退出后遗留的 `RUNNING` 在恢复时转为 `UNKNOWN`，必须先验证业务状态。

只读查询工具不创建恢复检查点。

### PipelineFailureClassifier

沿异常 cause 链提取根因并分类：

- `TRANSIENT_RATE_LIMIT`：429 或 AgentScope `RateLimitException`。
- `TRANSIENT_TIMEOUT`：模型或 HTTP 超时。
- `TRANSIENT_NETWORK`：连接、DNS、I/O 异常。
- `TRANSIENT_PROVIDER`：可重试 5xx 或 `HttpTransportException.isRetryable()`。
- `NON_RETRYABLE_REQUEST`：BadRequest、上下文超限或不支持的参数。
- `NON_RETRYABLE_AUTH`：401、403、密钥或权限错误。
- `BUSINESS_ERROR`：工具参数、版本冲突和领域校验错误。
- `CANCELLED`：用户取消。
- `UNKNOWN`：无法证明为瞬时故障的错误，按不可自动续跑处理。

持久化时保存脱敏后的异常类型、HTTP 状态、供应商错误码和根因消息；禁止保存 API Key、Authorization Header 或完整敏感响应。

### CheckpointAwareTool

所有 Pipeline 使用的有副作用工具必须声明检查点描述：

- 稳定的 `checkpointKey`，由工具名和业务主键组成，不能使用每次变化的 tool call ID。
- `replayPolicy`：`SAFE_REPLAY`、`VERIFY_BEFORE_REPLAY` 或 `NEVER_REPLAY`。
- 可选的完成状态验证器，用于确认数据库或外部任务是否已经成功。
- 输出快照裁剪规则，只保存续跑所需的 ID、版本、任务号和状态。

读取工具保持现有 `ToolExecutor` 行为；写入工具通过扩展接口或独立策略注册表提供检查点元数据，避免强迫所有工具实现空方法。

### PipelineResumeStrategy

每种 Pipeline 可注册恢复策略，负责：

- 根据原始请求、已成功检查点和当前业务数据生成 `ResumePlan`。
- 将 `UNKNOWN` 检查点验证为成功、失败或待重跑。
- 生成精简的 `<resume_context>`，明确已完成步骤、待执行步骤和禁止重复执行的步骤。
- 对复杂 Pipeline 定义业务顺序；简单 Pipeline 使用通用的工具检查点策略。

所有当前 Pipeline 的有副作用工具必须完成重放策略分类后才能开启自动续跑。发现未分类工具时安全降级为等待人工继续，禁止盲目自动执行。

## 数据模型

### afv_ai_pipeline_run

一条记录代表用户看到的一次逻辑任务，跨越首次执行、自动续跑和人工续跑。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | bigint | 主键 |
| run_id | varchar(64) | 对外逻辑任务 ID，唯一 |
| user_id | bigint | 用户 ID |
| project_id | bigint | 项目 ID，可空 |
| agent_type | varchar(64) | Pipeline 类型 |
| title | varchar(255) | 展示标题 |
| request_json | mediumtext | 脱敏后的原始请求和上下文 |
| status | varchar(32) | 逻辑任务状态 |
| auto_resume_count | int | 已执行自动续跑次数，默认 0 |
| max_auto_resume | int | 默认 1 |
| active_conversation_id | varchar(64) | 当前执行尝试的会话 ID |
| last_error_category | varchar(64) | 最近错误分类 |
| last_error_code | varchar(128) | HTTP 或供应商错误码 |
| last_error_message | text | 脱敏根因消息 |
| version | int | 乐观锁版本 |
| create_time/update_time | datetime | 时间戳 |

`run_id` 唯一。状态和 `update_time` 建联合索引，便于启动恢复扫描。

### afv_ai_pipeline_checkpoint

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | bigint | 主键 |
| pipeline_run_id | bigint | 逻辑任务主键 |
| checkpoint_key | varchar(255) | 任务内稳定步骤键 |
| tool_name | varchar(100) | 工具名 |
| scope_type | varchar(64) | script、episode、asset、storyboard_item 等 |
| scope_id | varchar(128) | 业务对象 ID |
| replay_policy | varchar(32) | 重放策略 |
| status | varchar(32) | RUNNING/SUCCEEDED/FAILED/UNKNOWN |
| input_json | mediumtext | 脱敏、裁剪后的输入快照 |
| output_json | mediumtext | 续跑所需输出快照 |
| attempt_count | int | 工具执行次数 |
| error_category/code/message | text | 最近错误信息 |
| create_time/update_time | datetime | 时间戳 |

唯一键为 `(pipeline_run_id, checkpoint_key)`，保证同一逻辑步骤只对应一个检查点。

### afv_agent_conversation 扩展

复用现有会话保存每次 Agent 执行的完整时间线，新增：

- `pipeline_run_id`：关联逻辑任务。
- `attempt_number`：0 为首次，1 为首次自动续跑，后续为人工续跑。
- `resume_type`：`INITIAL`、`AUTO` 或 `MANUAL`。

不新增独立 attempt 表，避免和现有会话记录重复建模。

## 状态机

逻辑任务状态：

- `RUNNING`：首次或人工执行中。
- `AUTO_RESUMING`：瞬时错误后自动续跑中。
- `WAITING_MANUAL_RESUME`：不能自动续跑或自动续跑已用完。
- `COMPLETED`：全部步骤完成。
- `FAILED_NON_RETRYABLE`：明确不可重试且需要修正请求或配置。
- `CANCELLED`：用户取消，禁止自动恢复。

状态流：

1. 新任务创建 `PipelineRun(RUNNING)` 和 attempt 0。
2. 单次模型调用最多执行 6 次：首次 + 5 次重试，保持 2 秒初始退避、2 倍增长、30 秒上限。
3. 瞬时故障耗尽模型重试后：
   - `auto_resume_count < 1`：状态改为 `AUTO_RESUMING`，5 秒后创建新 conversation，计数加 1。
   - 已自动续跑过：状态改为 `WAITING_MANUAL_RESUME`。
4. 业务、请求、鉴权或未知错误不自动续跑，状态改为 `FAILED_NON_RETRYABLE` 并展示根因；用户修正数据或配置后才可人工继续。
5. 用户点击“继续执行”创建新的 MANUAL attempt；同一 `run_id` 同时只允许一个活动 attempt。
6. 用户取消将逻辑任务和当前 attempt 标记为 `CANCELLED`，后端重启也不得恢复。

## 续跑数据流

1. 获取逻辑任务的原始请求和全部检查点。
2. `SUCCEEDED` 检查点默认跳过；需要业务验证的步骤先执行轻量验证。
3. `RUNNING/UNKNOWN` 检查点必须调用完成状态验证器：
   - 已完成：更新为 `SUCCEEDED`。
   - 未完成且允许重放：更新为待执行。
   - 无法验证或禁止重放：进入人工处理。
4. 恢复策略生成 `ResumePlan` 和精简 `<resume_context>`。
5. 创建全新 ReActAgent，只输入原始业务上下文和恢复清单，不加载旧 Agent 内存。
6. Agent 即使再次调用已完成工具，工具适配层也根据检查点短路返回原输出快照，或在验证成功后返回当前业务结果。

示例：剧本全量解析任务恢复上下文只包含：

```text
已完成：剧本元信息、第1-12集、第1集资产预绑定
待完成：第13-20集、第2-20集资产预绑定、第1-20集场次、第1-20集快照
禁止重复：不得重新创建或删除第1-12集
```

## 幂等与外部任务规则

- 数据库更新类：使用业务主键构造检查点键，例如 `save_script_episode:40:12`，允许安全重放或直接返回当前记录。
- 覆盖写入类：以目标对象和内容版本作为键，失败恢复时整批覆盖，不从未知位置追加。
- 图片生成类：以目标资产 ID 和请求摘要作为键；已有有效图片 URL 时视为完成。
- 视频生成类：以分镜项 ID 和请求摘要作为键；已有远端任务号时先轮询，禁止重复提交。
- 创建外部资源类：没有供应商幂等键且无法查询结果时标记为 `NEVER_REPLAY`。
- 子 Agent 工具：以子任务类型和业务对象 ID 建立父级检查点；子 Agent 内部工具继续记录细粒度检查点。

## 并发与服务重启

- 使用 Redis 锁 `fv:ai:pipeline:lock:{runId}` 保证同一逻辑任务单实例执行，并定期续期。
- 数据库 `version` 防止两个节点同时更新任务状态。
- 应用启动时扫描 `RUNNING/AUTO_RESUMING` 任务；成功获取锁后，将遗留 `RUNNING` 检查点置为 `UNKNOWN` 并执行验证。
- 服务中断按瞬时故障处理：未使用自动续跑额度时自动恢复一次，否则进入人工继续状态。
- Redis 仅负责实时事件和锁；MySQL 是任务与检查点的最终事实来源。

## 历史任务恢复

为没有 `PipelineRun` 的历史失败会话提供一次性懒迁移：

1. 用户打开失败任务或点击继续时，根据 conversation 创建逻辑任务。
2. 按 `tool_call_id` 配对历史 TOOL_CALL 和 TOOL_FINISHED 消息。
3. 使用当前工具检查点策略把成功调用转换为检查点。
4. 对转换后的检查点执行业务验证，不完全信任旧消息文本。
5. 无法验证的步骤标记为 `UNKNOWN`。

当前剧本 ID 40 的失败任务应由该机制识别出第 1-12 集已保存、第 1 集预绑定已完成、场次和快照尚未开始，并从缺失步骤继续。

## API 与前端交互

保留现有接口兼容性，并让流事件携带 `pipelineRunId`：

- `POST /api/ai/pipeline/run`：创建逻辑任务并启动首次 attempt。
- `POST /api/ai/pipeline/{runId}/resume`：人工继续。
- `POST /api/ai/pipeline/{runId}/cancel`：取消逻辑任务及当前 attempt。
- `GET /api/ai/pipeline/{runId}/status`：查询逻辑状态、当前 attempt 和错误。
- `GET /api/ai/pipeline/{runId}/reconnect`：回放逻辑任务的合并时间线并连接当前 attempt。

任务中心以一个卡片展示同一逻辑任务的多次尝试，不创建多个用户可见任务。时间线增加分隔事件：

- `模型请求重试 3/5`
- `模型重试已耗尽，5 秒后从检查点自动续跑 1/1`
- `自动续跑开始`
- `自动续跑仍失败，等待手动继续`

失败卡片显示脱敏根因和“继续执行”按钮。业务错误应显示“修正后继续”，避免暗示重复点击能够解决参数或权限问题。

## 模型重试配置

- AgentScope Chat Completions 模型在 `GenerateOptions.executionConfig` 注入 `maxAttempts=6`。
- 保持 AgentScope 默认可重试异常谓词，避免重试 BadRequest。
- OpenAI Responses 自定义模型的客户端 `maxRetries` 从 2 改为 5。
- 模型实例按模型 ID 缓存，因此配置变更时必须沿用现有模型缓存清理机制；部署后重启也会重建模型。
- 模型重试和 Pipeline 自动续跑是两层独立机制：前者重试同一次推理请求，后者重新规划剩余业务步骤。

## 测试策略

### 单元测试

- 错误分类：429、超时、I/O、可重试 5xx、BadRequest、401、业务异常和未知异常。
- 状态机：首次成功、自动续跑成功、自动续跑耗尽、人工继续、取消。
- 检查点唯一键、状态更新、输出短路和 UNKNOWN 验证。
- 模型配置验证总尝试次数为 6，Responses 客户端重试次数为 5。
- 恢复计划只包含缺失步骤，不包含已成功步骤。

### 集成测试

- 在第 N 个写入工具后注入瞬时异常，验证自动续跑不重复前 N 个副作用。
- 在外部生成任务提交后、结果落库前模拟进程中断，验证恢复时先轮询原任务。
- 模拟业务参数错误，验证不会自动续跑。
- 模拟服务重启，验证遗留运行步骤先转 UNKNOWN 再校验。
- 验证历史会话能够重建检查点并继续当前剧本任务。

### 前端测试

- 同一卡片合并首次、自动和人工 attempt 的事件。
- 自动续跑阶段不可重复点击继续。
- `WAITING_MANUAL_RESUME` 显示“继续执行”；`FAILED_NON_RETRYABLE` 显示“修正后继续”；`CANCELLED` 不显示继续按钮。
- 页面刷新后按 `pipelineRunId` 恢复状态和时间线。

## 实施边界

实现必须分阶段提交，但上线时只有在以下条件全部满足后才对所有 Pipeline 开启自动续跑：

1. 通用运行、检查点、错误分类和锁机制完成。
2. 所有已注册 Pipeline 使用的有副作用工具已完成重放策略分类。
3. 剧本、资产图片、分镜和视频四类代表性流程通过故障注入测试。
4. 未分类工具能够安全降级，且不会被自动执行。

不要求一次重构现有超大服务；新增逻辑应拆分为独立运行时、持久化、分类器和恢复策略组件，AgentScopeAssistantService 只负责接入。

## 验收标准

- 瞬时模型错误最多重试 5 次，错误信息显示真实脱敏根因。
- 模型重试耗尽后自动从检查点续跑一次，而不是从头执行。
- 自动续跑仍失败时能够人工继续。
- 业务错误不自动续跑。
- 已成功数据库写入和外部生成任务不会重复创建。
- 服务重启后可恢复未完成 Pipeline。
- 当前剧本 ID 40 的历史失败任务能够重建进度并继续完成。
- 所有 AI Pipeline 使用同一套逻辑任务、attempt、检查点和前端状态模型。
