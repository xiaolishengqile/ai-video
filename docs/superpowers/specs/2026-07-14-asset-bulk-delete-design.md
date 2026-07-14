# Asset Bulk Delete Design

## Goal

Let users explicitly choose multiple assets from the global asset list and delete the selected set with one confirmed action.

## Chosen interaction

- The global `/assets` page gets a `选择资产` mode.
- In that mode, every currently loaded asset card or row has a checkbox, with `全选当前列表` and a selected-count indicator.
- `删除已选` is disabled with no selection and opens a destructive confirmation dialog showing the selected count.
- Selection is cleared when the filter changes, after a successful delete, or when selection mode is exited.
- “全选” intentionally means the loaded list, not every result across unloaded pagination pages. This leaves the final choice with the user and avoids a hidden large deletion.

## API and security

- Add `DELETE /api/asset/batch` with JSON body `{ "ids": [1, 2] }`.
- The server reads every asset first and validates current-user access to all of them before deleting any. An inaccessible ID rejects the full request.
- Single-item deletion routes through the same access-checked service method.
- Existing logical deletion remains unchanged.

## Validation

- Backend tests cover successful multi-delete and all-or-nothing rejection for an inaccessible asset.
- Frontend type-check and production build must pass.
