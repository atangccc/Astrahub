import type { AstraHubRealtimeTokenResult } from "../types";

async function parseJson(response: Response) {
  const text = await response.text();
  if (!text) {
    return {};
  }
  try {
    return JSON.parse(text) as Record<string, unknown>;
  } catch {
    return {};
  }
}

export function buildHubWsUrl(rawBaseUrl: string, token: string) {
  const value = String(rawBaseUrl || "").trim().replace(/\/+$/, "");
  const accessToken = String(token || "").trim();
  if (!value || !accessToken) {
    return "";
  }
  try {
    const url = new URL(value);
    url.protocol = url.protocol === "https:" ? "wss:" : "ws:";
    url.pathname = "/v1/ws";
    url.search = "";
    url.hash = "";
    url.searchParams.set("access_token", accessToken);
    return url.toString();
  } catch {
    return "";
  }
}

export async function issueAstraHubRealtimeToken() {
  const response = await fetch("/apis/api.plugin.halo.run/v1alpha1/astrahub/ws-token", {
    method: "POST",
    headers: {
      Accept: "application/json"
    }
  });

  const payload = (await parseJson(response)) as Partial<AstraHubRealtimeTokenResult> & {
    message?: string;
  };

  if (!response.ok || !payload.success || !String(payload.token || "").trim()) {
    throw new Error(payload.message || `获取实时连接令牌失败（${response.status}）`);
  }

  return {
    success: Boolean(payload.success ?? true),
    status: Number(payload.status || response.status),
    message: String(payload.message || "ok"),
    token: String(payload.token || ""),
    expiresAt: String(payload.expiresAt || "")
  } satisfies AstraHubRealtimeTokenResult;
}
