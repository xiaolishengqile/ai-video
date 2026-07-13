#!/usr/bin/env node

/**
 * 测试 /v1/models 接口，列出当前可用的模型（OpenAI 格式）
 *
 * 用法:
 *   API_BASE_URL=https://your-api-host API_KEY=your-token node scripts/list-models.js
 *   node scripts/list-models.js https://your-api-host your-token
 */

function usage() {
  console.error(`
用法:
  API_BASE_URL=<服务根地址> API_KEY=<Token> node scripts/list-models.js
  node scripts/list-models.js <服务根地址> <Token>

示例:
  API_BASE_URL=https://api.example.com API_KEY=2f68dbbf-xxxx node scripts/list-models.js
  node scripts/list-models.js https://api.example.com 2f68dbbf-xxxx
`);
}

function normalizeBaseUrl(baseUrl) {
  return baseUrl.replace(/\/+$/, "");
}

function buildModelsUrl(baseUrl) {
  const root = normalizeBaseUrl(baseUrl);
  if (root.endsWith("/v1")) {
    return `${root}/models`;
  }
  return `${root}/v1/models`;
}

function extractModels(payload) {
  if (Array.isArray(payload)) {
    return payload;
  }
  if (Array.isArray(payload?.data)) {
    return payload.data;
  }
  if (Array.isArray(payload?.models)) {
    return payload.models;
  }
  return [];
}

async function main() {
  const baseUrl = process.env.API_BASE_URL || process.argv[2];
  const apiKey = process.env.API_KEY || process.argv[3];

  if (!baseUrl || !apiKey) {
    usage();
    process.exit(1);
  }

  const url = buildModelsUrl(baseUrl);
  console.log(`请求: GET ${url}\n`);

  const response = await fetch(url, {
    method: "GET",
    headers: {
      Authorization: `Bearer ${apiKey}`,
      Accept: "application/json",
    },
  });

  const raw = await response.text();
  let payload;
  try {
    payload = JSON.parse(raw);
  } catch {
    console.error(`响应不是合法 JSON (HTTP ${response.status}):`);
    console.error(raw);
    process.exit(1);
  }

  if (!response.ok) {
    console.error(`请求失败: HTTP ${response.status}`);
    console.error(JSON.stringify(payload, null, 2));
    process.exit(1);
  }

  const models = extractModels(payload);
  if (models.length === 0) {
    console.log("未解析到模型列表，原始响应如下:");
    console.log(JSON.stringify(payload, null, 2));
    process.exit(0);
  }

  console.log(`共 ${models.length} 个可用模型:\n`);
  for (const model of models) {
    console.log(model.id || model.name || model.model || "(unknown)");
  }
}

main().catch((error) => {
  console.error("请求异常:", error.message);
  process.exit(1);
});
