/**
 * @template {{ id: number }} T
 * @param {...T[]} assetGroups
 * @returns {T[]}
 */
export function mergeAssetsById(...assetGroups) {
  const unique = new Map();
  assetGroups.flat().forEach((asset) => {
    if (!unique.has(asset.id)) unique.set(asset.id, asset);
  });
  return [...unique.values()];
}
