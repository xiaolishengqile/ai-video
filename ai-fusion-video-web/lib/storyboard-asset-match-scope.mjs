export function getStoryboardAssetMatchScope(sceneGroups) {
  const groups = Array.isArray(sceneGroups) ? sceneGroups : [];

  const seen = new Set();
  const itemIds = [];
  for (const group of groups) {
    for (const item of group?.items || []) {
      if (typeof item?.id !== "number" || seen.has(item.id)) continue;
      seen.add(item.id);
      itemIds.push(item.id);
    }
  }

  return {
    itemIds,
    scopeLabel: "全部镜头",
  };
}
