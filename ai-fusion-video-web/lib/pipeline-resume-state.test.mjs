import test from "node:test";
import assert from "node:assert/strict";

import { applyPipelineRunStatus } from "./pipeline-resume-state.mjs";

const base = {
  status: "running",
  timeline: [{ type: "content", text: "已保存第 1 集" }],
  pipelineRunId: "run-1",
  attemptNumber: 0,
  resumeType: "INITIAL",
  autoResumeCount: 0,
  canResume: false,
};

test("AUTO_RESUMING keeps one card running", () => {
  const next = applyPipelineRunStatus(base, {
    status: "AUTO_RESUMING",
    attemptNumber: 1,
    resumeType: "AUTO",
    autoResumeCount: 1,
  });

  assert.equal(next.status, "running");
  assert.equal(next.pipelineRunId, "run-1");
  assert.equal(next.canResume, false);
  assert.deepEqual(next.timeline.slice(0, 1), base.timeline);
  assert.match(next.timeline.at(-1).text, /自动续跑开始/);
});

test("WAITING_MANUAL_RESUME exposes continue action", () => {
  const next = applyPipelineRunStatus(base, {
    status: "WAITING_MANUAL_RESUME",
    autoResumeCount: 1,
    errorMessage: "read timeout",
  });

  assert.equal(next.status, "error");
  assert.equal(next.canResume, true);
  assert.match(next.timeline.at(-1).text, /等待手动继续/);
});

test("FAILED_NON_RETRYABLE asks user to correct and continue", () => {
  const next = applyPipelineRunStatus(base, {
    status: "FAILED_NON_RETRYABLE",
    errorMessage: "剧集编号无效",
  });

  assert.equal(next.status, "error");
  assert.equal(next.canResume, true);
  assert.match(next.timeline.at(-1).text, /修正后继续/);
});

test("new attempt preserves card identity and existing timeline", () => {
  const next = applyPipelineRunStatus(base, {
    status: "RUNNING",
    attemptNumber: 2,
    resumeType: "MANUAL",
  });

  assert.equal(next.pipelineRunId, base.pipelineRunId);
  assert.equal(next.attemptNumber, 2);
  assert.deepEqual(next.timeline.slice(0, 1), base.timeline);
});
