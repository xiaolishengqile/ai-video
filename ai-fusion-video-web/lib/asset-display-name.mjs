/**
 * @param {string | null | undefined} name
 * @returns {string}
 */
export function getAssetDisplayName(name) {
  const text = typeof name === "string" ? name.trim() : "";
  if (!text) return "";
  const parts = text.split("/").map((part) => part.trim()).filter(Boolean);
  return parts.at(-1) || text;
}
