import assert from "node:assert/strict";
import test from "node:test";

import { openDirectoryPicker } from "./directory-input.mjs";

test("enables directory selection before opening the picker", () => {
  let directoryEnabled = false;
  const input = {
    setAttribute(name, value) {
      directoryEnabled = name === "webkitdirectory" && value === "";
    },
    click() {
      assert.equal(directoryEnabled, true);
    },
  };

  openDirectoryPicker(input);
});
