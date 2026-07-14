# Storyboard Linked Assets Filter Design

## Goal

The storyboard linked-assets dialog should match the asset library's grouping model: assets can be filtered by type and by episode. When a shot has an owning episode, the dialog opens on that episode by default.

## Requirements

- The linked-assets dialog opens with the current shot's episode selected by default when the episode number is known.
- Users can switch episode scope to `全部`, `其他集`, or `未分集`.
- Users can switch asset type between `全部`, `角色`, `场景`, and `道具`.
- Existing linked assets remain selected even when filters hide them.
- Saving keeps the current data contract: `characterIds`, `sceneAssetItemId`, `sceneAssetItemIds`, and `propIds`.
- No backend changes or new dependencies.

## Approach

- Reuse `ai-fusion-video-web/lib/asset-episode-filter.mjs`.
- Extend the helper with `other` episode filtering relative to the current shot episode.
- Load storyboard episodes once on the storyboard page and derive `storyboardEpisodeId -> episodeNumber`.
- Pass the current shot episode number into `EditItemAssetsDialog`.
- Add compact type and episode segmented controls at the top of the dialog.

## Edge Cases

- If the shot's episode number is unknown, the dialog opens on `全部`.
- `其他集` with unknown current episode behaves like `全部` because there is no current episode to exclude.
- `未分集` only shows assets whose `episodeNumber` is `null`.
- Empty filtered lists keep the existing empty-state messages.
