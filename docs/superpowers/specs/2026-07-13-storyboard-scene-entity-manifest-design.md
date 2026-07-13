# 场次实体清单、自动资产创建与镜头继承设计

日期：2026-07-13

## 1. 目标

解决 AI 分镜中“画面描述出现了群像、场景、载具或机甲，但镜头没有关联资产”的问题。

系统应只依赖剧本文字，在剧本解析后为每个场次建立一份结构化实体清单；自动创建缺失资产并绑定场次；AI 分镜生成时默认继承这些资产；镜头保存时由后端补齐 AI 漏传的关联。

## 2. 范围与非目标

本期范围：

- 仅采用剧本文字实体识别，不接入图片视觉识别、OCR 或用户确认步骤。
- 自动创建重要且可复用的角色、群像、场景、道具资产。
- 将场次默认资产确定性继承到镜头。
- 保持旧分镜、手工创建资产与既有首尾帧/视频流程可用。

非目标：

- 不新建第四个资产主类型“群像”。
- 不把所有背景物件自动建成资产。
- 不从历史场次或历史镜头回填资产；历史数据可另行提供手动重生成入口。

## 3. 术语与分类

资产主类型维持现有 `character`、`scene`、`prop`。

“群像”是 `character` 的语义子类，写入 `properties.entitySubtype = collective`，例如“撤离士兵群”“祭拜人群”“机甲编队”。它可被多个场次和镜头复用，并可生成独立设定图。

机甲分类规则：

- 具有独立行动目标、意志、对白，或作为叙事主体出现：`character`；可附 `entitySubtype = mech`。
- 作为载具、武器、装备、静态残骸或背景机械：`prop`；可附 `entitySubtype = vehicle | mech | wreckage`。
- “机甲残骸群”“无人机群”等大量同类物：`prop`，可附 `entitySubtype = collective`。

重要性规则：

- `core`：决定场景身份、跨镜复用、剧情关键或构图主体。自动创建，并默认继承到镜头。
- `supporting`：在场内清晰出现、会影响镜头连续性。自动创建，但只继承到标明其入画的镜头。
- `atmospheric`：普通背景、一次性装饰、无法独立复用的细节。只留在 `sceneDescription`/镜头 `content`，不创建资产。

每场最多自动创建：1 个场景、3 个角色/群像、3 个道具；超出时按 `core > supporting` 排序，余项降为氛围描述并记录原因。

## 4. 数据设计

在 `afv_script_scene_item` 增加 `entity_manifest JSON NULL`。它是该场实体识别的唯一事实来源；既有 `character_asset_ids`、`scene_asset_id`、`prop_asset_ids` 保持为兼容性和查询性能字段。

示例：

```json
{
  "version": 1,
  "entities": [
    {
      "key": "scene:evacuation-train-platform",
      "name": "撤离列车站台",
      "assetType": "scene",
      "entitySubtype": "station",
      "importance": "core",
      "defaultForShots": true,
      "assetId": 101,
      "assetItemId": 501,
      "source": "auto_created"
    },
    {
      "key": "character:evacuation-soldier-group",
      "name": "撤离士兵群",
      "assetType": "character",
      "entitySubtype": "collective",
      "importance": "core",
      "defaultForShots": true,
      "assetId": 102,
      "assetItemId": 502,
      "source": "auto_created"
    },
    {
      "key": "prop:armored-evacuation-train",
      "name": "装甲撤离列车",
      "assetType": "prop",
      "entitySubtype": "vehicle",
      "importance": "core",
      "defaultForShots": true,
      "assetId": 103,
      "assetItemId": 503,
      "source": "auto_created"
    }
  ]
}
```

`assetItemId` 始终指向初始子资产。资产存在但初始子资产缺失时，自动补建初始子资产；变体选择仍由现有预处理器负责。

## 5. 剧本解析与资产同步

剧本解析 Agent 的场次输出新增 `entity_manifest`。每个实体必须提供名称、主类型、子类、重要性、可视化静态描述、是否默认继承；不可确定的对象必须降为 `atmospheric`，禁止猜测式创建。

新增后端工具 `resolve_scene_entity_manifest`，输入项目 ID 与一批场次实体清单，职责为：

1. 校验主类型、子类、重要性、数量上限和名称非空。
2. 按 `projectId + assetType + name` 复用已有资产；缺失时创建主资产与初始子资产。
3. 回填每个实体的 `assetId` 与 `assetItemId`。
4. 将 `core`/`supporting` 实体转换为场次现有的角色、场景、道具关联字段。
5. 返回已解析的清单，供 `save_script_scene_items` 原样落库。

解析 Agent 不再自行分散调用 `batch_create_assets` 后凭名称猜测 ID；它先生成清单，再调用该工具获取确定映射。这仍复用现有资产去重规则，避免重复资产。

`save_script_scene_items` 接受并保存已解析的 `entity_manifest`。若清单中的 ID 与传统关联字段不一致，工具返回参数错误，不静默覆盖。

## 6. AI 分镜与镜头继承

`get_script_scene` 返回 `entityManifest`，以及已解析的默认 `assetItemId` 集合。

分镜 Agent 设计镜头时：

- 默认带入场次的 `core` 场景、角色/群像、道具。
- `supporting` 实体仅在镜头 `content` 明确入画时带入。
- 镜头可用 `excludedDefaultEntityKeys` 明确排除默认实体；排除必须给出 `offscreen`、`close_up` 或 `not_yet_appeared` 原因。
- 镜头独有的新实体不在本期自动创建，保留在内容描述，避免分镜阶段资产膨胀。

`save_storyboard_scene_shots` 新增 `scriptSceneItemId` 和可选的排除列表。后端按以下确定性规则生成最终关联：

1. 读取该场的 `entity_manifest`。
2. 合并默认实体与 AI 提供的 `characterIds`、`sceneAssetItemId`、`propIds`。
3. 应用合法排除项；不能排除该场唯一的核心场景。
4. 对角色和道具去重；场景优先使用 AI 显式指定的镜头专属场景，否则使用默认场景。
5. 写入最终子资产 ID；保存结果返回“继承/显式/排除”的来源信息。

这一步消除“模型写了画面、却漏传关联 ID”导致的 `null` 数据。前端无需推测或补建关联，只展示最终保存结果。

## 7. Agent 提示词与接口边界

剧本解析提示词新增：

- 先提取场次实体清单，再调用解析工具；不得跳过核心场景、载具、关键道具或可复用群像。
- 群像、机甲、残骸按第 3 节规则分类。
- 严格执行重要性阈值，氛围物件不建资产。

分镜提示词新增：

- 以场次实体清单为默认资产，不得因为镜头描述未重复名称而遗漏核心资产。
- 对默认资产的排除必须显式声明理由。
- 不在分镜阶段创建新主资产。

后端工具负责 ID 解析、去重、继承和校验；提示词只负责语义判断。业务正确性不依赖模型是否准确填写每一个 ID。

## 8. 前端与可观测性

本期不增加确认弹窗。剧本场次详情与分镜镜头详情增加只读信息：

- 场次实体清单：名称、类型、子类、重要性、创建/复用状态。
- 镜头关联来源：继承、AI 显式添加、AI 显式排除。

任务面板应输出每集统计：识别实体数、自动创建数、复用数、按类型统计、被重要性规则过滤数。系统日志记录清单校验失败、资产解析失败与镜头继承结果，不记录完整剧本文本。

## 9. 错误处理与兼容性

- 主资产创建或初始子资产创建失败：该集剧本解析失败，不生成分镜，避免半关联数据。
- 同名同类型资产：复用，不覆盖用户已有描述、图片或子资产。
- 历史场次 `entity_manifest` 为空：维持旧逻辑，不自动改写；重新解析该集后才启用新链路。
- AI 提供了不存在或类型不符的子资产 ID：保存失败并返回镜头序号和字段名。
- 场次不存在、与 `storyboardEpisodeId` 不匹配或未授权：保存失败。

## 10. 验收与测试

单元测试：

- 群像、载具、主动机甲、机甲残骸的分类与重要性阈值。
- 同名资产复用、初始子资产补建与数量上限。
- 清单与传统场次关联字段的一致性校验。
- 默认继承、显式追加、合法排除、非法排除、去重与类型校验。

集成测试：

1. 输入“撤离站台、装甲列车、撤离士兵群”的剧本场次后，自动得到 1 个场景、1 个道具、1 个 `collective` 角色，并关联到场次和默认镜头。
2. 输入“旧战场、主动巨型机甲、机甲残骸群”后，机甲归角色、残骸群归道具，镜头可继承战场和机甲。
3. 特写镜头以 `close_up` 排除群像/场景时，最终关联符合排除规则；没有排除的镜头不再出现关键场景或道具为空。
4. 重新生成已有剧本集时，不创建重复资产；历史未重生成数据不变化。

## 11. 交付顺序

1. 数据迁移、实体清单 DTO/校验与资产解析工具。
2. 剧本解析 Agent 接入清单生成与场次保存。
3. 分镜查询、保存工具与后端继承规则。
4. Agent 提示词调整与任务统计。
5. 场次/镜头只读展示与自动化测试。
