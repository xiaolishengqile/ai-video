# AI Pipeline 统一手动恢复设计

## 目标

任何已创建 `pipelineRunId`、尚未完成且因错误、取消、连接中断或执行卡住而停止推进的 AI Pipeline，用户都能通过同一个“继续”入口恢复。自动恢复策略保持不变。

## 行为边界

- `COMPLETED`：不显示“继续”，后端拒绝恢复。
- `WAITING_MANUAL_RESUME`、`FAILED_NON_RETRYABLE`、`CANCELLED`：从持久化检查点创建新的手动执行尝试。
- `RUNNING` 且活动流仍存在：重新连接当前执行，不创建重复尝试。
- `RUNNING` 且已超过卡住阈值：终止失效尝试，从检查点创建新的手动执行尝试。
- `AUTO_RESUMING`：正常自动恢复期间不额外创建并发尝试；如果前端因连接中断进入错误态，统一入口根据服务端最新状态执行重连或恢复。
- 无 `pipelineRunId` 的旧任务沿用旧行为，不承诺检查点恢复。

## 后端设计

保留现有 `/api/ai/pipeline/{runId}/resume` 作为统一入口，由 `AgentScopePipelineRuntime` 根据 `PipelineRecoveryPolicy` 的结果分派：

- `RESUME`：调用 `PipelineRuntimeService.startManualResume`。
- `RECONNECT`：返回当前执行流。
- `RECOVER_STALLED`：执行现有卡住恢复流程。
- `NONE`：返回明确的 409，避免重复执行已完成任务或在无安全恢复动作时盲目重放。

不新增数据库状态、接口或依赖。

## 前端设计

服务端 `canResume` 仍是首选事实来源。同时增加本地兜底：任务卡已处于 `error` 或 `cancelled`、存在 `pipelineRunId` 且未完成时，始终展示“继续”。

点击“继续”统一调用 resume 接口，不再依赖可能过期的本地 `recoveryAction` 选择不同接口；后端用最新持久化状态决定重连、续跑或卡住恢复。点击后先进入运行态，失败时恢复错误态并保留再次继续能力。

## 错误处理

- 恢复请求失败时保留原任务卡、时间线和 `pipelineRunId`，展示错误并继续允许用户重试。
- 后端仍使用执行锁和活动 conversation 校验，防止重复手动恢复。
- 业务错误、鉴权错误、限流、取消等只影响自动恢复决策，不再影响人工恢复资格。

## 测试

- 后端：验证统一 resume 入口分别处理 `RESUME`、`RECONNECT`、`RECOVER_STALLED`，并拒绝 `NONE`。
- 前端状态函数：验证错误/取消任务在服务端状态缺失或过期时仍可继续，已完成任务不可继续。
- 前端 store：验证恢复请求失败后任务回到错误态且继续按钮仍可用。
- 运行相关后端定向测试和前端 Node 测试，确认现有自动恢复与检查点行为不回归。
