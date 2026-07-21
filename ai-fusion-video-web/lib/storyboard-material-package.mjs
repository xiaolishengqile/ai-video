function hasIds(raw) {
  if (!raw) return false;
  if (Array.isArray(raw)) return raw.length > 0;
  if (typeof raw !== "string") return true;
  const trimmed = raw.trim();
  if (!trimmed || trimmed === "[]" || trimmed === "null") return false;
  try {
    const parsed = JSON.parse(trimmed);
    return Array.isArray(parsed) ? parsed.length > 0 : Boolean(parsed);
  } catch {
    return true;
  }
}

export function hasLinkedStoryboardAssets(item) {
  return Boolean(
    hasIds(item?.characterIds) ||
      item?.sceneAssetItemId ||
      hasIds(item?.sceneAssetItemIds) ||
      hasIds(item?.propIds)
  );
}

export function getStoryboardMaterialPackageStatus(item, mode) {
  const resolvedMode = mode || item?.videoWorkflowResolvedMode || item?.videoWorkflowMode;
  const isAction = resolvedMode === "action";
  const hasVisual = isAction
    ? Boolean(item?.actionStoryboardImageUrl)
    : Boolean(item?.grid25ImageUrl);
  const hasPrompt = Boolean(item?.videoPrompt);
  const hasAssets = hasLinkedStoryboardAssets(item);

  return {
    mode: isAction ? "action" : "narrative",
    hasAssets,
    hasVisual,
    hasPrompt,
    complete: hasAssets && hasVisual && hasPrompt,
    missing: [
      hasAssets ? null : "asset",
      hasVisual ? null : isAction ? "actionStoryboard" : "grid25",
      hasPrompt ? null : "videoPrompt",
    ].filter(Boolean),
  };
}

export function getMissingMaterialPackageItemIds(items, mode) {
  return (Array.isArray(items) ? items : [])
    .filter((item) => {
      const status = getStoryboardMaterialPackageStatus(item, mode);
      return !status.hasVisual || !status.hasPrompt;
    })
    .map((item) => item.id);
}

export function getMissingVideoPromptItemIds(items) {
  return (Array.isArray(items) ? items : [])
    .filter((item) => !item?.videoPrompt)
    .map((item) => item.id);
}

function getGridPrompt(item) {
  return resolveGridMode(item) === "action"
    ? item?.actionStoryboardPrompt
    : item?.grid25Prompt;
}

function getGridImage(item) {
  return resolveGridMode(item) === "action"
    ? item?.actionStoryboardImageUrl
    : item?.grid25ImageUrl;
}

function hasGridPrompt(item) {
  return Boolean(getGridPrompt(item));
}

function hasGridImage(item) {
  return Boolean(getGridImage(item));
}

function buildGenerationPlan(items, pendingIds, labelPrefix) {
  const selectedIds = (Array.isArray(items) ? items : []).map((item) => item.id);
  const pendingSet = new Set(pendingIds);
  const skippedIds = selectedIds.filter((id) => !pendingSet.has(id));
  const skippedText = skippedIds.length > 0
    ? `，跳过 ${skippedIds.length} 个已完成`
    : "";

  return {
    pendingIds,
    skippedIds,
    label: `${labelPrefix} (${pendingIds.length} 个镜头${skippedText})`,
  };
}

export function buildMaterialPackageGenerationPlan(items, mode) {
  return buildGenerationPlan(
    items,
    getMissingMaterialPackageItemIds(items, mode),
    mode === "action" ? "生成战斗素材包" : "生成剧情素材包"
  );
}

export function buildVideoPromptGenerationPlan(items, labelPrefix = "批量生成视频提示词") {
  return buildGenerationPlan(
    items,
    getMissingVideoPromptItemIds(items),
    labelPrefix
  );
}

function needsWorkflowModeSelection(item) {
  const mode = item?.videoWorkflowMode;
  const resolvedMode = item?.videoWorkflowResolvedMode;
  return !(
    mode === "narrative" ||
    mode === "action" ||
    resolvedMode === "narrative" ||
    resolvedMode === "action"
  );
}

export function buildGridModeRecognitionPlan(items, labelPrefix = "宫格模式识别") {
  const list = Array.isArray(items) ? items : [];
  const pendingIds = list
    .filter(needsWorkflowModeSelection)
    .map((item) => item.id);
  const pendingSet = new Set(pendingIds);
  const skippedIds = list
    .map((item) => item.id)
    .filter((id) => !pendingSet.has(id));
  const skippedText = skippedIds.length > 0
    ? `，跳过 ${skippedIds.length} 个已识别`
    : "";

  return {
    pendingIds,
    skippedIds,
    label: `${labelPrefix} · AI模式识别 (${pendingIds.length} 个镜头${skippedText})`,
  };
}

export function buildStoryboardGridPromptGenerationPlan(items, scopeLabel = "宫格提示词") {
  const list = Array.isArray(items) ? items : [];
  const needsModeResolutionIds = list
    .filter(needsGridModeResolution)
    .map((item) => item.id);
  const resolvedItems = list.filter((item) => !needsGridModeResolution(item));
  const pendingIds = resolvedItems
    .filter((item) => !hasGridPrompt(item))
    .map((item) => item.id);
  const pendingSet = new Set(pendingIds);
  const skippedIds = resolvedItems
    .map((item) => item.id)
    .filter((id) => !pendingSet.has(id));
  const skippedText = skippedIds.length > 0
    ? `，跳过 ${skippedIds.length} 个已完成`
    : "";
  const missingAssetIds = resolvedItems
    .filter((item) => pendingSet.has(item.id))
    .filter((item) => !hasLinkedStoryboardAssets(item))
    .map((item) => item.id);

  return {
    pendingIds,
    skippedIds,
    needsModeResolutionIds,
    missingAssetIds,
    label: `${scopeLabel} · AI生成宫格提示词 (${pendingIds.length} 个镜头${skippedText})`,
  };
}

export function buildStoryboardGridImageGenerationPlan(items, scopeLabel = "宫格图") {
  const list = Array.isArray(items) ? items : [];
  const needsModeResolutionIds = list
    .filter(needsGridModeResolution)
    .map((item) => item.id);
  const resolvedItems = list.filter((item) => !needsGridModeResolution(item));
  const missingImageItems = resolvedItems.filter((item) => !hasGridImage(item));
  const missingPromptIds = missingImageItems
    .filter((item) => !hasGridPrompt(item))
    .map((item) => item.id);
  const missingPromptSet = new Set(missingPromptIds);
  const pendingIds = missingImageItems
    .filter((item) => !missingPromptSet.has(item.id))
    .map((item) => item.id);
  const missingImageSet = new Set(missingImageItems.map((item) => item.id));
  const skippedIds = resolvedItems
    .map((item) => item.id)
    .filter((id) => !missingImageSet.has(id));
  const skippedText = skippedIds.length > 0
    ? `，跳过 ${skippedIds.length} 个已生成`
    : "";
  const missingAssetIds = resolvedItems
    .filter((item) => pendingIds.includes(item.id))
    .filter((item) => !hasLinkedStoryboardAssets(item))
    .map((item) => item.id);

  return {
    pendingIds,
    skippedIds,
    needsModeResolutionIds,
    missingPromptIds,
    missingAssetIds,
    label: `${scopeLabel} · 生成宫格图 (${pendingIds.length} 个镜头${skippedText})`,
  };
}

function resolveGridMode(item) {
  return item?.videoWorkflowResolvedMode === "action" || item?.videoWorkflowMode === "action"
    ? "action"
    : "narrative";
}

function needsGridModeResolution(item) {
  return needsWorkflowModeSelection(item);
}

function buildGridModePlan(items, mode, labelPrefix, blockedIds = []) {
  const pendingRaw = getMissingMaterialPackageItemIds(items, mode);
  const blockedSet = new Set(blockedIds);
  const pendingIds = pendingRaw.filter((id) => !blockedSet.has(id));
  const pendingRawSet = new Set(pendingRaw);
  const skippedIds = items
    .map((item) => item.id)
    .filter((id) => !pendingRawSet.has(id));
  const skippedText = skippedIds.length > 0
    ? `，跳过 ${skippedIds.length} 个已完成`
    : "";

  return {
    pendingIds,
    skippedIds,
    label: `${labelPrefix} (${pendingIds.length} 个镜头${skippedText})`,
  };
}

export function buildStoryboardGridGenerationPlans(items, scopeLabel = "宫格图") {
  const list = Array.isArray(items) ? items : [];
  const needsModeResolutionIds = list
    .filter(needsGridModeResolution)
    .map((item) => item.id);
  const resolvedItems = list.filter((item) => !needsGridModeResolution(item));
  const narrativeItems = resolvedItems.filter((item) => resolveGridMode(item) === "narrative");
  const actionItems = resolvedItems.filter((item) => resolveGridMode(item) === "action");
  const blockedDurationIds = [];
  const narrative = buildGridModePlan(
    narrativeItems,
    "narrative",
    `${scopeLabel} · 剧情宫格图`,
    blockedDurationIds
  );
  const action = buildGridModePlan(
    actionItems,
    "action",
    `${scopeLabel} · 战斗宫格图`
  );
  const pendingSet = new Set([...narrative.pendingIds, ...action.pendingIds]);
  const missingAssetIds = resolvedItems
    .filter((item) => pendingSet.has(item.id))
    .filter((item) => !hasLinkedStoryboardAssets(item))
    .map((item) => item.id);

  return {
    narrative,
    action,
    needsModeResolutionIds,
    blockedDurationIds,
    missingAssetIds,
    totalPending: narrative.pendingIds.length + action.pendingIds.length,
    totalSkipped: narrative.skippedIds.length + action.skippedIds.length,
    totalBlocked: blockedDurationIds.length,
  };
}

export function summarizeMaterialPackages(items, mode) {
  const statuses = (Array.isArray(items) ? items : []).map((item) =>
    getStoryboardMaterialPackageStatus(item, mode)
  );

  return {
    total: statuses.length,
    complete: statuses.filter((status) => status.complete).length,
    missingAssets: statuses.filter((status) => !status.hasAssets).length,
    missingVisual: statuses.filter((status) => !status.hasVisual).length,
    missingPrompt: statuses.filter((status) => !status.hasPrompt).length,
  };
}
