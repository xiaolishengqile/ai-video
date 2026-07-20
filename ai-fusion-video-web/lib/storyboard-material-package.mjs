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
