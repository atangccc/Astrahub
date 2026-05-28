export interface RegisterPayload {
  hubBaseUrl: string;
  registerToken: string;
  siteName: string;
  siteUrl: string;
  siteDescription: string;
  siteRssUrl: string;
  siteAvatarUrl: string;
  contactEmail: string;
  siteNodeName: string;
  siteNodeAvatar: string;
}

export interface RegisterResponse {
  success: boolean;
  status: number;
  message: string;
  siteId: string;
  apiKey: string;
  createdAt: string;
  nodeName: string;
  category: string;
  nodeAvatar: string;
}

const REGISTER_API = "/apis/api.plugin.halo.run/v1alpha1/astrahub/register";

export async function registerSite(payload: RegisterPayload): Promise<RegisterResponse> {
  const response = await fetch(REGISTER_API, {
    method: "POST",
    headers: {
      "Content-Type": "application/json"
    },
    body: JSON.stringify(payload)
  });

  const text = await response.text();
  let parsed: Partial<RegisterResponse> = {};
  try {
    parsed = text ? (JSON.parse(text) as Partial<RegisterResponse>) : {};
  } catch {
    parsed = {};
  }

  const result: RegisterResponse = {
    success: Boolean(parsed.success),
    status: Number(parsed.status || response.status),
    message: parsed.message || (response.ok ? "ok" : `request failed: ${response.status}`),
    siteId: parsed.siteId || "",
    apiKey: parsed.apiKey || "",
    createdAt: parsed.createdAt || "",
    nodeName: parsed.nodeName || parsed.category || "",
    category: parsed.category || "",
    nodeAvatar: parsed.nodeAvatar || ""
  };

  if (!response.ok || !result.success) {
    throw new Error(result.message || "register failed");
  }
  return result;
}
