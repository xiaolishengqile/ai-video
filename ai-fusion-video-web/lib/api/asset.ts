import { http } from "./client";

// ========== 类型定义 ==========

/** 资产（角色/场景/道具等） */
export interface Asset {
  id: number;
  userId: number;
  projectId: number;
  /** 所属剧集；历史未归集资产为 null。 */
  episodeNumber: number | null;
  type: string;
  name: string;
  description: string | null;
  coverUrl: string | null;
  properties: Record<string, unknown> | null;
  tags: string[] | null;
  sourceType: number;
  aiPrompt: string | null;
  ownerType: number;
  ownerId: number;
  status: number;
  createTime: string;
  updateTime: string;
}

/** 子资产（图片/变体） */
export interface AssetItem {
  id: number;
  assetId: number;
  itemType: string | null;
  name: string | null;
  imageUrl: string | null;
  thumbnailUrl: string | null;
  properties: Record<string, unknown> | null;
  sortOrder: number;
  sourceType: number;
  aiPrompt: string | null;
  createTime: string;
  updateTime: string;
}

/** 创建资产请求 */
export interface AssetCreateReq {
  projectId: number;
  episodeNumber: number;
  type: string;
  name: string;
  description?: string;
  coverUrl?: string;
  properties?: string;
  tags?: string[];
}

/** 更新资产请求 */
export interface AssetUpdateReq {
  id: number;
  episodeNumber?: number;
  name?: string;
  description?: string;
  coverUrl?: string;
  properties?: string;
  tags?: string[];
  status?: number;
}

/** 创建子资产请求 */
export interface AssetItemCreateReq {
  assetId: number;
  itemType?: string;
  name?: string;
  imageUrl?: string;
  thumbnailUrl?: string;
  properties?: string;
  sortOrder?: number;
}

/** 更新子资产请求 */
export interface AssetItemUpdateReq {
  id: number;
  itemType?: string;
  name?: string;
  imageUrl?: string;
  thumbnailUrl?: string;
  properties?: string;
  sortOrder?: number;
}

/** 字段选项 */
export interface FieldOption {
  value: string;
  label: string;
}

/** 字段定义 */
export interface FieldDef {
  key: string;
  label: string;
  type: string; // text | select
  required: boolean;
  options?: FieldOption[] | null;
  description?: string | null;
}

/** 元数据响应 */
export interface AssetMetadataResp {
  assetType: string;
  fields: FieldDef[];
}

/** 资产分页响应 */
export interface AssetPageResp {
  records: Asset[];
  total: number;
  page: number;
  size: number;
  typeCounts: Record<string, number>;
}

/** 带子资产的资产信息 */
export interface AssetWithItems extends Asset {
  items: AssetItem[];
}

export interface AssetFolderImportPreviewItem {
  relativePath: string;
  originalName: string;
  assetName: string;
  variantName: string | null;
  itemType: string;
  kind: "root" | "variant" | "variant_candidate";
  episodeNumber: number | null;
  reason: string | null;
}

export interface AssetFolderImportResult {
  results: Array<{
    relativePath: string;
    originalName: string;
    status: "success" | "skipped" | "failed";
    assetName: string;
    variantName: string | null;
    episodeNumber: number | null;
    reason: string | null;
  }>;
}

// ========== API ==========

export const assetApi = {
  // ========== 资产 ==========

  /** 获取资产详情 */
  get: (id: number) => http.get<never, Asset>(`/api/asset/${id}`),

  /** 按项目+类型查询资产列表 */
  list: (projectId: number, type?: string, keyword?: string) => {
    let url = `/api/asset/list?projectId=${projectId}`;
    if (type) url += `&type=${encodeURIComponent(type)}`;
    if (keyword) url += `&keyword=${encodeURIComponent(keyword)}`;
    return http.get<never, Asset[]>(url);
  },

  /** 按项目查询资产及其所有子资产 */
  listWithItems: (projectId: number) => 
    http.get<never, AssetWithItems[]>(`/api/asset/list-with-items?projectId=${projectId}`),

  /** 分页查询当前用户的资产（跨项目），含类型统计 */
  listAll: (params?: {
    projectId?: number;
    type?: string;
    keyword?: string;
    page?: number;
    size?: number;
  }) => {
    const searchParams = new URLSearchParams();
    if (params?.projectId) searchParams.set("projectId", String(params.projectId));
    if (params?.type) searchParams.set("type", params.type);
    if (params?.keyword) searchParams.set("keyword", params.keyword);
    searchParams.set("page", String(params?.page ?? 1));
    searchParams.set("size", String(params?.size ?? 20));
    const qs = searchParams.toString();
    return http.get<never, AssetPageResp>(`/api/asset/all?${qs}`);
  },

  /** 创建资产 */
  create: (data: AssetCreateReq) =>
    http.post<never, Asset>("/api/asset", data),

  /** 更新资产 */
  update: (data: AssetUpdateReq) =>
    http.put<never, Asset>("/api/asset", data),

  /** 删除资产 */
  delete: (id: number) => http.delete<never, boolean>(`/api/asset/${id}`),

  /** 批量删除已选择的资产 */
  deleteBatch: (ids: number[]) =>
    http.delete<never, boolean>("/api/asset/batch", { data: { ids } }),

  /** 预览文件夹导入后的资产归类 */
  previewFolderImport: (data: {
    projectId: number;
    type: string;
    files: Array<{ relativePath: string; originalName: string }>;
  }) => http.post<never, { items: AssetFolderImportPreviewItem[] }>("/api/asset/folder-import/preview", data),

  /** 上传一个不超过 100MB 的文件夹导入分块 */
  importFolderChunk: (data: {
    projectId: number;
    type: string;
    files: File[];
    relativePaths: string[];
    onProgress?: (progress: number) => void;
  }) => {
    const form = new FormData();
    form.append("projectId", String(data.projectId));
    form.append("type", data.type);
    data.files.forEach((file) => form.append("files", file));
    data.relativePaths.forEach((path) => form.append("relativePaths", path));
    return http.post<never, AssetFolderImportResult>("/api/asset/folder-import", form, {
      onUploadProgress: (event) => data.onProgress?.(event.progress ?? 0),
    });
  },

  // ========== 元数据 ==========

  /** 获取资产类型的属性字段定义 */
  getMetadata: (assetType: string) =>
    http.get<never, AssetMetadataResp>(`/api/asset/metadata/${assetType}`),

  // ========== 子资产 ==========

  /** 获取单个子资产详情 */
  getItem: (id: number) =>
    http.get<never, AssetItem>(`/api/asset/item/${id}`),

  /** 获取子资产列表 */
  listItems: (assetId: number) =>
    http.get<never, AssetItem[]>(`/api/asset/${assetId}/items`),

  /** 创建子资产 */
  createItem: (data: AssetItemCreateReq) =>
    http.post<never, AssetItem>("/api/asset/item", data),

  /** 更新子资产 */
  updateItem: (data: AssetItemUpdateReq) =>
    http.put<never, AssetItem>("/api/asset/item", data),

  /** 删除子资产 */
  deleteItem: (id: number) =>
    http.delete<never, boolean>(`/api/asset/item/${id}`),
};
