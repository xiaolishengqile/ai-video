import { http } from "./client";

// ========== 类型定义 ==========

export interface Script {
  id: number;
  projectId: number;
  title: string;
  content: string | null;
  rawContent: string | null;
  totalEpisodes: number;
  storySynopsis: string | null;
  charactersJson: string | null;
  sourceType: number;
  parsingStatus: number;
  parsingProgress: string | null;
  summary: string | null;
  genre: string | null;
  targetAudience: string | null;
  durationEstimate: number | null;
  aiGenerated: boolean;
  status: number;
  createTime: string;
  updateTime: string;
}

export interface ScriptEpisode {
  id: number;
  scriptId: number;
  episodeNumber: number;
  title: string;
  synopsis: string | null;
  rawContent: string | null;
  durationEstimate: number | null;
  totalScenes: number;
  sourceType: number;
  sortOrder: number;
  parsingStatus: number;
  status: number;
  version: number;
  createTime: string;
  updateTime: string;
}

/** 对白/动作元素 */
export interface DialogueElement {
  type: number; // 1=对白, 2=动作, 3=旁白, 4=镜头指令, 5=环境描写
  character_name?: string;
  character_asset_id?: number;
  content: string;
  parenthetical?: string;
  sortOrder?: number;
}

export type SceneEntityAssetType = "character" | "scene" | "prop";
export type SceneEntityImportance = "core" | "supporting" | "atmospheric";
export type SceneEntitySource = "auto_created" | "reused" | "matched" | "auto_created_episode_catalog" | "unmatched_episode_catalog" | "atmospheric" | "filtered_limit";

/** 场次中识别并解析为资产的可复用实体。 */
export interface SceneEntity {
  key: string;
  name: string;
  assetType: SceneEntityAssetType;
  entitySubtype: string;
  importance: SceneEntityImportance;
  defaultForShots: boolean;
  assetId: number | null;
  assetItemId: number | null;
  source: SceneEntitySource;
}

/** 场次实体清单，由剧本解析阶段保存。 */
export interface SceneEntityManifest {
  version: number;
  entities: SceneEntity[];
}

export type SceneEntityManifestValue = SceneEntityManifest | string | null;

const sceneEntityAssetTypes = new Set<SceneEntityAssetType>([
  "character",
  "scene",
  "prop",
]);
const sceneEntityImportances = new Set<SceneEntityImportance>([
  "core",
  "supporting",
  "atmospheric",
]);
const sceneEntitySources = new Set<SceneEntitySource>([
  "auto_created",
  "reused",
  "matched",
  "auto_created_episode_catalog",
  "unmatched_episode_catalog",
  "atmospheric",
  "filtered_limit",
]);

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function isAssetId(value: unknown): value is number | null {
  return value === null || (typeof value === "number" && Number.isSafeInteger(value));
}

function isSceneEntity(value: unknown): value is SceneEntity {
  if (!isRecord(value)) return false;
  return typeof value.key === "string" &&
    typeof value.name === "string" &&
    sceneEntityAssetTypes.has(value.assetType as SceneEntityAssetType) &&
    typeof value.entitySubtype === "string" &&
    sceneEntityImportances.has(value.importance as SceneEntityImportance) &&
    typeof value.defaultForShots === "boolean" &&
    isAssetId(value.assetId) &&
    isAssetId(value.assetItemId) &&
    sceneEntitySources.has(value.source as SceneEntitySource);
}

/**
 * Normalizes the JSONB scene manifest returned by the API. The backend can expose
 * it as either a JSON string or an already-decoded object; malformed values are ignored.
 */
export function parseSceneEntityManifest(raw: unknown): SceneEntityManifest | null {
  let value = raw;
  if (typeof value === "string") {
    try {
      value = JSON.parse(value);
    } catch {
      return null;
    }
  }
  if (!isRecord(value) || typeof value.version !== "number" || !Number.isSafeInteger(value.version)
    || !Array.isArray(value.entities)) {
    return null;
  }

  const entities: SceneEntity[] = [];
  for (const entity of value.entities) {
    if (!isSceneEntity(entity)) return null;
    entities.push(entity);
  }
  return { version: value.version, entities };
}

export interface SceneItem {
  id: number;
  episodeId: number;
  scriptId: number;
  sceneNumber: string;
  sceneHeading: string;
  location: string | null;
  timeOfDay: string | null;
  intExt: string | null;
  characters: string[] | null;
  characterAssetIds: number[] | null;
  sceneAssetId: number | null;
  propAssetIds: number[] | null;
  entityManifest: SceneEntityManifestValue;
  sceneDescription: string | null;
  dialogues: DialogueElement[] | null;
  sortOrder: number;
  status: number;
  version: number;
  createTime: string;
  updateTime: string;
}

export interface ScriptCreateReq {
  projectId: number;
  title: string;
  rawContent?: string;
  storySynopsis?: string;
}

export interface ScriptUpdateReq {
  id: number;
  title?: string;
  content?: string;
  rawContent?: string;
  storySynopsis?: string;
  genre?: string;
  targetAudience?: string;
  durationEstimate?: number;
}

export interface EpisodeCreateReq {
  scriptId: number;
  episodeNumber?: number;
  title?: string;
  synopsis?: string;
  rawContent?: string;
  durationEstimate?: number;
  sortOrder?: number;
}

export interface EpisodeUpdateReq {
  id: number;
  title?: string;
  synopsis?: string;
  rawContent?: string;
  durationEstimate?: number;
  sortOrder?: number;
  version?: number;
}

export interface SceneCreateReq {
  episodeId: number;
  scriptId?: number;
  sceneNumber?: string;
  sceneHeading?: string;
  location?: string;
  timeOfDay?: string;
  intExt?: string;
  sceneDescription?: string;
  sortOrder?: number;
}

export interface SceneUpdateReq {
  id: number;
  episodeId?: number;
  scriptId?: number;
  sceneNumber?: string;
  sceneHeading?: string;
  location?: string;
  timeOfDay?: string;
  intExt?: string;
  characters?: string;
  characterAssetIds?: string;
  sceneAssetId?: number;
  propAssetIds?: string;
  sceneDescription?: string;
  dialogues?: string;
  sortOrder?: number;
  version?: number;
}

// ========== API ==========

export const scriptApi = {
  /** 按项目查询剧本列表 */
  list: (projectId: number) =>
    http.get<never, Script[]>(`/api/script/list?projectId=${projectId}`),

  /** 获取剧本详情 */
  get: (id: number) => http.get<never, Script>(`/api/script/${id}`),

  /** 创建剧本 */
  create: (data: ScriptCreateReq) => http.post<never, Script>("/api/script", data),

  /** 更新剧本 */
  update: (data: ScriptUpdateReq) => http.put<never, Script>("/api/script", data),

  /** 删除剧本 */
  delete: (id: number) => http.delete<never, boolean>(`/api/script/${id}`),

  // ========== 分集 ==========

  /** 获取分集列表 */
  listEpisodes: (scriptId: number) =>
    http.get<never, ScriptEpisode[]>(`/api/script/${scriptId}/episodes`),

  /** 获取分集详情 */
  getEpisode: (id: number) =>
    http.get<never, ScriptEpisode>(`/api/script/episode/${id}`),

  /** 创建分集 */
  createEpisode: (data: EpisodeCreateReq) =>
    http.post<never, ScriptEpisode>("/api/script/episode", data),

  /** 更新分集 */
  updateEpisode: (data: EpisodeUpdateReq) =>
    http.put<never, ScriptEpisode>("/api/script/episode", data),

  /** 删除分集 */
  deleteEpisode: (id: number) =>
    http.delete<never, boolean>(`/api/script/episode/${id}`),

  // ========== 场次 ==========

  /** 获取场次列表（按分集） */
  listScenes: (episodeId: number) =>
    http.get<never, SceneItem[]>(`/api/script/episode/${episodeId}/scenes`),

  /** 获取场次详情 */
  getScene: (id: number) => http.get<never, SceneItem>(`/api/script/scene/${id}`),

  /** 创建场次 */
  createScene: (data: SceneCreateReq) =>
    http.post<never, SceneItem>("/api/script/scene", data),

  /** 更新场次 */
  updateScene: (data: SceneUpdateReq) =>
    http.put<never, SceneItem>("/api/script/scene", data),

  /** 删除场次 */
  deleteScene: (id: number) =>
    http.delete<never, boolean>(`/api/script/scene/${id}`),
};
