export interface RefreshedTokens {
  accessToken: string;
  refreshToken: string;
}

let refreshPromise: Promise<RefreshedTokens | null> | null = null;

/** Ensures Axios and SSE share one refresh-token rotation. */
export function refreshTokenOnce(
  request: () => Promise<RefreshedTokens | null>
): Promise<RefreshedTokens | null> {
  if (!refreshPromise) {
    refreshPromise = request().finally(() => {
      refreshPromise = null;
    });
  }
  return refreshPromise;
}
