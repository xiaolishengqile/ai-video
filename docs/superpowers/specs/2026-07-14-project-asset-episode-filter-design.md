# Project Asset Episode Filter Design

## Goal

在项目资产库的类型标签下方提供分集按钮，让用户按剧集查看角色、场景和道具资产。

## Scope

- 不新增接口、不修改后端或数据库。
- 复用资产列表响应的 `episodeNumber` 字段。
- 分集按钮只显示当前项目中已有资产的集数，按升序排列。

## Interaction

1. 页面默认选择类型“全部”和分集“全部”，显示项目中的全部资产。
2. 类型标签下方显示第二行筛选按钮：`全部`、`第 N 集`（按数字升序）、`未分集`。
3. 点击类型和分集时，两项条件同时生效；搜索条件继续叠加生效。
4. 资产的 `episodeNumber` 为 `null` 时，仅在分集“全部”和“未分集”下显示。
5. 分集按钮始终基于项目全部资产生成，因此切换角色、场景、道具不会改变可选集数；当组合条件没有结果时，沿用现有空状态。
6. 导入、创建或删除资产后，列表和分集按钮一同刷新；切换分集会清空批量选择，但不会关闭正在查看的资产详情。

## Frontend Design

`ai-fusion-video-web/app/(dashboard)/projects/[id]/assets/page.tsx` 维护独立的分集筛选状态：

- `activeEpisode`：`undefined` 表示全部，数字表示对应集，`"unscoped"` 表示未分集。
- `availableEpisodes`：从当前项目的完整资产列表中提取非空 `episodeNumber`，去重后升序排列。
- 资产请求仍由现有 `assetApi.list(projectId, type, keyword)` 完成；另一次无类型、无搜索条件的列表请求仅用于生成完整的分集按钮，避免类型切换时遗漏其他集数。
- 渲染资产前根据 `activeEpisode` 作前端过滤；标题计数、批量选择和空状态都使用过滤后的资产集合。

## Error Handling

- 完整资产列表请求失败时，不显示分集按钮，资产列表仍保留既有加载和错误行为。
- 没有任何分集资产时，只显示“全部”和“未分集”。

## Verification

- 对筛选逻辑添加单元测试：全部、指定集、未分集以及类型切换后可选集数不变。
- 运行前端 ESLint、TypeScript 检查和生产构建。
