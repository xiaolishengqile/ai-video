const STORYBOARD_ITEM_ID_PATTERN = /storyboardItemId\s*[:：]\s*(\d+)/;

export function getStoryboardItemProgressLabel(argumentsJson) {
  if (!argumentsJson) return null;
  try {
    const parsed = JSON.parse(argumentsJson);
    if (parsed?.storyboardItemId != null) {
      return `镜头 #${parsed.storyboardItemId}`;
    }
    if (typeof parsed?.message === "string") {
      const match = parsed.message.match(STORYBOARD_ITEM_ID_PATTERN);
      return match ? `镜头 #${match[1]}` : null;
    }
  } catch {
    const match = String(argumentsJson).match(STORYBOARD_ITEM_ID_PATTERN);
    return match ? `镜头 #${match[1]}` : null;
  }
  return null;
}
