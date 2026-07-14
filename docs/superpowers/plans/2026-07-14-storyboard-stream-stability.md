# 分镜流式任务稳定性 Implementation Plan

**Goal:** 限制高频流事件与回放规模，并按服务端真实状态收敛 SSE 断线结果。

**Architecture:** 后端在推理块入口丢弃仅用于展示的逐 token 思维链，并将 Redis Replay List 限制为最近 2,000 条事件。前端发生 SSE 异常后查询 pipeline 状态，只有任务仍在运行时才重连。

**Tech Stack:** Java 17、Spring Data Redis、JUnit 5 / Mockito、Next.js、TypeScript。

## 任务

- [ ] 为 Replay List 长度上限编写失败测试，验证 `rightPush` 后会裁剪最近 2,000 条。
- [ ] 实现 Replay List 常量和裁剪调用；运行后端聚焦测试。
- [ ] 停止将 `ThinkingBlock` 转换为 `REASONING` SSE 事件。
- [ ] 在 pipeline store 中添加状态确认与单次重连逻辑，避免完成任务被网络异常覆盖。
- [ ] 运行后端聚焦测试、前端 lint 和 build，并审查 diff。
