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

function resolveGridMode(item) {
  return item?.videoWorkflowResolvedMode === "action" || item?.videoWorkflowMode === "action"
    ? "action"
    : "narrative";
}

export function buildStoryboardGridGenerationPlans(items, scopeLabel = "宫格图") {
  const list = Array.isArray(items) ? items : [];
  const narrativeItems = list.filter((item) => resolveGridMode(item) === "narrative");
  const actionItems = list.filter((item) => resolveGridMode(item) === "action");
  const narrative = buildGenerationPlan(
    narrativeItems,
    getMissingMaterialPackageItemIds(narrativeItems, "narrative"),
    `${scopeLabel} · 剧情宫格图`
  );
  const action = buildGenerationPlan(
    actionItems,
    getMissingMaterialPackageItemIds(actionItems, "action"),
    `${scopeLabel} · 战斗宫格图`
  );

  return {
    narrative,
    action,
    totalPending: narrative.pendingIds.length + action.pendingIds.length,
    totalSkipped: narrative.skippedIds.length + action.skippedIds.length,
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
