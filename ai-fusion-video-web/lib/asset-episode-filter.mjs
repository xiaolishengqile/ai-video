/**
 * @template {{ episodeNumber: number | null }} T
 * @param {T[]} assets
 * @returns {number[]}
 */
export function listAssetEpisodes(assets) {
  return [...new Set(assets.map(({ episodeNumber }) => episodeNumber).filter(Number.isInteger))]
    .sort((left, right) => left - right);
}

/**
 * @template {{ episodeNumber: number | null }} T
 * @param {T[]} assets
 * @param {number | "unscoped" | "other" | undefined} activeEpisode
 * @param {number | null | undefined} [currentEpisodeNumber]
 * @returns {T[]}
 */
export function filterAssetsByEpisode(assets, activeEpisode, currentEpisodeNumber = undefined) {
  if (activeEpisode === undefined) return assets;
  if (activeEpisode === "unscoped") return assets.filter(({ episodeNumber }) => episodeNumber === null);
  if (activeEpisode === "other") {
    if (!Number.isInteger(currentEpisodeNumber)) return assets;
    return assets.filter(({ episodeNumber }) =>
      Number.isInteger(episodeNumber) && episodeNumber !== currentEpisodeNumber
    );
  }
  return assets.filter(({ episodeNumber }) => episodeNumber === activeEpisode);
}
