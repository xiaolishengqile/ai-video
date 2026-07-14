export function listAssetEpisodes(assets) {
  return [...new Set(assets.map(({ episodeNumber }) => episodeNumber).filter(Number.isInteger))]
    .sort((left, right) => left - right);
}

export function filterAssetsByEpisode(assets, activeEpisode) {
  if (activeEpisode === undefined) return assets;
  if (activeEpisode === "unscoped") return assets.filter(({ episodeNumber }) => episodeNumber === null);
  return assets.filter(({ episodeNumber }) => episodeNumber === activeEpisode);
}
