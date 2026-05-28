export interface BoardingSendCodePayload {
  hubBaseUrl: string;
  contactEmail: string;
}

export interface BoardingSendCodeResponse {
  success: boolean;
  status: number;
  message: string;
  expiresAt: string;
}

export interface BoardingRestorePayload {
  hubBaseUrl: string;
  contactEmail: string;
  code: string;
}

export interface BoardingRestoreResponse {
  success: boolean;
  status: number;
  message: string;
  siteId: string;
  apiKey: string;
  siteName: string;
  siteUrl: string;
  contactEmail: string;
  description: string;
  rssUrl: string;
  nodeName: string;
  category: string;
  nodeAvatar: string;
  createdAt: string;
}

const SEND_CODE_API = "/apis/api.plugin.halo.run/v1alpha1/astrahub/boarding/send-code";
const RESTORE_API = "/apis/api.plugin.halo.run/v1alpha1/astrahub/boarding/restore";

async function parseResponse<T extends { success: boolean; status: number; message: string }>(
  response: Response
): Promise<Partial<T>> {
  const text = await response.text();
  if (!text) {
    return {};
  }
  try {
    return JSON.parse(text) as Partial<T>;
  } catch {
    return {};
  }
}

export async function sendBoardingCode(payload: BoardingSendCodePayload): Promise<BoardingSendCodeResponse> {
  const response = await fetch(SEND_CODE_API, {
    method: "POST",
    headers: {
      "Content-Type": "application/json"
    },
    body: JSON.stringify(payload)
  });

  const parsed = await parseResponse<BoardingSendCodeResponse>(response);
  const result: BoardingSendCodeResponse = {
    success: Boolean(parsed.success),
    status: Number(parsed.status || response.status),
    message: parsed.message || (response.ok ? "ok" : `request failed: ${response.status}`),
    expiresAt: parsed.expiresAt || ""
  };

  if (!response.ok || !result.success) {
    throw new Error(result.message || "send boarding code failed");
  }
  return result;
}

export async function restoreByBoardingCode(payload: BoardingRestorePayload): Promise<BoardingRestoreResponse> {
  const response = await fetch(RESTORE_API, {
    method: "POST",
    headers: {
      "Content-Type": "application/json"
    },
    body: JSON.stringify(payload)
  });

  const parsed = await parseResponse<BoardingRestoreResponse>(response);
  const result: BoardingRestoreResponse = {
    success: Boolean(parsed.success),
    status: Number(parsed.status || response.status),
    message: parsed.message || (response.ok ? "ok" : `request failed: ${response.status}`),
    siteId: parsed.siteId || "",
    apiKey: parsed.apiKey || "",
    siteName: parsed.siteName || "",
    siteUrl: parsed.siteUrl || "",
    contactEmail: parsed.contactEmail || "",
    description: parsed.description || "",
    rssUrl: parsed.rssUrl || "",
    nodeName: parsed.nodeName || parsed.category || "",
    category: parsed.category || "",
    nodeAvatar: parsed.nodeAvatar || "",
    createdAt: parsed.createdAt || ""
  };

  if (!response.ok || !result.success) {
    throw new Error(result.message || "restore by boarding code failed");
  }
  return result;
}
