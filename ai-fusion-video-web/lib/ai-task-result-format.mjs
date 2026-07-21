const fieldLabels = {
  status: "状态",
  message: "信息",
  total: "总数",
  id: "ID",
  name: "名称",
  type: "类型",
  count: "数量",
  title: "标题",
  remark: "备注",
  projectId: "项目ID",
  scriptId: "剧本ID",
  storyboardId: "分镜ID",
  storyboardItemId: "镜头ID",
  storyboardEpisodeId: "分镜分集ID",
  storyboardSceneId: "分镜场次ID",
  sceneId: "场次ID",
  scriptEpisodeId: "剧本分集ID",
  episodeId: "集ID",
  sceneName: "场次名称",
  sceneNumber: "场次号",
  shotCount: "镜头数",
  sceneCount: "场次数",
  totalItems: "总项数",
  totalEpisodes: "总集数",
  matched: "已匹配资产",
  suggested: "建议匹配",
  ambiguous: "待人工判断",
  unmatched: "未匹配剧本实体",
  uploadedUnused: "未使用上传资产",
  bindings: "预匹配记录",
  episodeNumber: "集数",
  matchStatus: "匹配状态",
  candidates: "候选资产",
  entityManifest: "实体清单",
  matchedCount: "已匹配实体",
  unmatchedCount: "未匹配实体",
  ambiguousCount: "歧义实体",
  autoCreatedCount: "自动创建",
  filteredCount: "超限过滤",
  selectedCount: "AI已选择",
  assetResolutionFeedback: "资产解析反馈",
  shotNumber: "镜号",
  autoShotNumber: "自动镜号",
  shotType: "景别",
  duration: "时长",
  cameraMovement: "运镜",
  sceneExpectation: "画面预期",
  genre: "类型",
  description: "描述",
  content: "内容",
  url: "链接",
  imageUrl: "图片",
  generatedImageUrl: "生成图片",
  referenceImageUrl: "参考图",
  videoUrl: "视频",
  generatedVideoUrl: "生成视频",
  videoWorkflowMode: "视频工作流模式",
  videoWorkflowResolvedMode: "实际采用模式",
  videoWorkflowReason: "模式判断原因",
  videoPromptMode: "视频提示词模式",
  grid25ImageUrl: "25宫格图",
  grid25Prompt: "25宫格提示词",
  grid25ReferenceImageUrls: "25宫格参考图",
  hasGrid25: "已有25宫格图",
  hasGrid25References: "已有25宫格参考图",
  actionStoryboardImageUrl: "战斗宫格图",
  actionStoryboardPrompt: "战斗宫格提示词",
  hasActionStoryboard: "已有战斗宫格图",
  motionPlan: "身位调度",
  keyFrameImageUrls: "关键帧",
  hasKeyFrames: "已有关键帧",
  qualityCheckStatus: "质检状态",
  qualityCheckResult: "质检结果",
  sceneRef: "场景参考",
  characterRefs: "角色参考",
  propRefs: "道具参考",
  isCurrentTarget: "当前目标",
  createdAt: "创建时间",
  updatedAt: "更新时间",
  storyboards: "分镜列表",
  items: "子项",
  episodes: "集列表",
  assets: "资产列表",
  scripts: "剧本列表",
  projects: "项目列表",
};

const valueLabels = {
  auto: "自动",
  narrative: "剧情",
  action: "战斗",
  success: "成功",
  ok: "成功",
  error: "失败",
  failed: "失败",
};

export function getAiTaskResultFieldLabel(key) {
  return fieldLabels[key] || key;
}

export function formatAiTaskResultValue(value) {
  if (value === null || value === undefined) return "—";
  if (typeof value === "boolean") return value ? "是" : "否";
  if (typeof value === "number") return String(value);
  if (typeof value === "string") {
    const label = valueLabels[value];
    const text = label || value;
    return text.length > 150 ? `${text.slice(0, 150)}…` : text;
  }
  if (Array.isArray(value)) return `[${value.length} 项]`;
  if (typeof value === "object") return `{${Object.keys(value).length} 个字段}`;
  return String(value);
}
