import axios from "axios";
import { refreshTokenOnce } from "./auth-refresh";
import type { CommonResult } from "./types";

// 后端基础地址（可通过环境变量 NEXT_PUBLIC_API_BASE_URL 覆盖）
const API_BASE_URL =
  (process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:18080").replace(/\/$/, "");

const PUBLIC_AUTH_PREFIXES = [
  "/api/auth/login",
  "/api/auth/register",
  "/api/auth/refresh",
  "/api/system/init/",
];

// 创建 axios 实例
const http = axios.create({
  baseURL: API_BASE_URL,
  timeout: 30000,
});

/**
 * 从 localStorage 读取 auth-storage（zustand persist）
 */
function getAuthStorage() {
  if (typeof window === "undefined") return null;
  try {
    const stored = localStorage.getItem("auth-storage");
    if (stored) return JSON.parse(stored);
  } catch {
    // 忽略
  }
  return null;
}

/**
 * 更新 localStorage 中的 token
 */
function updateAuthStorage(accessToken: string, refreshToken: string) {
  if (typeof window === "undefined") return;
  try {
    const stored = localStorage.getItem("auth-storage");
    if (stored) {
      const parsed = JSON.parse(stored);
      parsed.state.token = accessToken;
      parsed.state.refreshToken = refreshToken;
      localStorage.setItem("auth-storage", JSON.stringify(parsed));
    }
  } catch {
    // 忽略
  }
}

/**
 * 清除认证状态并跳转登录页
 */
function handleAuthFailure() {
  if (typeof window === "undefined") return;
  localStorage.removeItem("auth-storage");
  // 清除 auth-token cookie
  document.cookie = "auth-token=; path=/; max-age=0";
  if (window.location.pathname !== "/login") {
    window.location.href = "/login";
  }
}

function getRequestPath(url?: string, baseURL?: string) {
  if (!url) return "";
  try {
    if (url.startsWith("http://") || url.startsWith("https://")) {
      return new URL(url).pathname;
    }
    return new URL(url, baseURL ?? API_BASE_URL).pathname;
  } catch {
    return url.startsWith("/") ? url : `/${url}`;
  }
}

function isPublicAuthRequest(config?: { url?: string; baseURL?: string }) {
  const path = getRequestPath(config?.url, config?.baseURL);
  return PUBLIC_AUTH_PREFIXES.some((prefix) => path.startsWith(prefix));
}

// 请求拦截器：注入 access_token
http.interceptors.request.use((config) => {
  const parsed = getAuthStorage();
  const token = parsed?.state?.token;
  if (token && !isPublicAuthRequest(config)) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

export async function refreshAccessToken(): Promise<string | null> {
  const tokens = await refreshTokenOnce(async () => {
    const refreshToken = getAuthStorage()?.state?.refreshToken;
    if (!refreshToken) return null;

    try {
      const refreshResp = await axios.post(`${API_BASE_URL}/api/auth/refresh`, {
        refreshToken,
      });
      const result = refreshResp.data as CommonResult<{
        accessToken: string;
        refreshToken: string;
      }>;
      if (result.code !== 0 || !result.data) return null;

      const { accessToken, refreshToken: newRefreshToken } = result.data;
      updateAuthStorage(accessToken, newRefreshToken);
      document.cookie = `auth-token=${accessToken}; path=/; max-age=${7 * 24 * 60 * 60}; SameSite=Lax`;
      try {
        const { useAuthStore } = await import("@/lib/store/auth-store");
        useAuthStore.getState().setTokens(accessToken, newRefreshToken);
      } catch {
        // localStorage 已更新
      }
      return { accessToken, refreshToken: newRefreshToken };
    } catch {
      return null;
    }
  });
  return tokens?.accessToken ?? null;
}

// 响应拦截器：统一解包 CommonResult + 自动刷新令牌
http.interceptors.response.use(
  (response) => {
    if (response.config.responseType === "blob") {
      return response.data as never;
    }
    const result = response.data as CommonResult<unknown>;
    if (result.code !== 0) {
      return Promise.reject(new Error(result.msg || "请求失败"));
    }
    return result.data as never;
  },
  async (error) => {
    if (axios.isCancel(error)) {
      return Promise.reject(error);
    }

    const originalRequest = error.config;

    // 如果没有 config（例如网络错误），直接走通用错误处理
    if (!originalRequest || error.response?.status !== 401) {
      const msg =
        error.response?.data?.msg ||
        error.response?.statusText ||
        error.message ||
        "网络异常";
      return Promise.reject(new Error(msg));
    }

    if (isPublicAuthRequest(originalRequest)) {
      const msg =
        error.response?.data?.msg ||
        error.response?.statusText ||
        error.message ||
        "请求失败";
      return Promise.reject(new Error(msg));
    }

    // 已经是重试请求 → 不再重复刷新
    if (originalRequest._retry) {
      handleAuthFailure();
      return Promise.reject(new Error("登录已过期，请重新登录"));
    }

    originalRequest._retry = true;
    const accessToken = await refreshAccessToken();
    if (!accessToken) {
      handleAuthFailure();
      return Promise.reject(new Error("登录已过期，请重新登录"));
    }

    originalRequest.headers = {
      ...originalRequest.headers,
      Authorization: `Bearer ${accessToken}`,
    };
    return http(originalRequest);
  }
);

export { http, API_BASE_URL };

/**
 * 解析媒体资源 URL
 *
 * - 以 http:// 或 https:// 开头的完整 URL（如 OSS 直链）=> 原样返回
 * - 以 / 开头的相对路径（如 /media/images/xxx.png）=> 拼接后端 API_BASE_URL
 * - 空值 => 返回 null
 */
export function resolveMediaUrl(url: string | null | undefined): string | null {
  if (!url) return null;
  if (url.startsWith("data:")) return url;
  if (url.startsWith("http://") || url.startsWith("https://")) return url;
  if (url.startsWith("/")) return `${API_BASE_URL}${url}`;
  return url;
}
