import assert from "node:assert/strict";
import test from "node:test";

import { removeFileFromPreview } from "./asset-folder-import-preview.mjs";

test("removes only the selected file while retaining the remaining preview rows", () => {
  const files = [{ relativePath: "第 10 集/有效.png" }, { relativePath: "临时/无效.png" }];
  const preview = [{ relativePath: "第 10 集/有效.png" }, { relativePath: "临时/无效.png" }];

  const next = removeFileFromPreview(files, preview, "临时/无效.png");

  assert.deepEqual(next.files, [{ relativePath: "第 10 集/有效.png" }]);
  assert.deepEqual(next.preview, [{ relativePath: "第 10 集/有效.png" }]);
});
