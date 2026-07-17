export function getStoryboardAssetMatchScope(sceneGroups, selection) {
  const type = selection?.type || "all";
  const groups = Array.isArray(sceneGroups) ? sceneGroups : [];
  const scopedGroups = groups.filter((group) => {
    if (type === "scene") {
      return group?.scene?.id === selection?.sceneId;
    }
    if (type === "episode") {
      return group?.scene?.episodeId === selection?.episodeId;
    }
    return true;
  });

  const seen = new Set();
  const itemIds = [];
  for (const group of scopedGroups) {
    for (const item of group?.items || []) {
      if (typeof item?.id !== "number" || seen.has(item.id)) continue;
      seen.add(item.id);
      itemIds.push(item.id);
    }
  }

  return {
    itemIds,
    scopeLabel: type === "scene" ? "当前场次" : type === "episode" ? "当前集" : "当前分镜表",
  };
}
