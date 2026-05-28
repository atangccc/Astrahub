import type { AstraHubSiteLookupResult } from "../types";

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

export async function lookupAstraHubSiteByUrl(url: string) {
  const params = new URLSearchParams();
  params.set("url", String(url || "").trim());

  const response = await fetch(`/apis/api.plugin.halo.run/v1alpha1/astrahub/sites/lookup?${params.toString()}`, {
    method: "GET",
    headers: {
      Accept: "application/json"
    }
  });

  const payload = (await parseJson(response)) as Partial<AstraHubSiteLookupResult> & {
    message?: string;
  };

  if (!response.ok || !payload.success) {
    throw new Error(payload.message || `查询 AstraHub 站点失败（${response.status}）`);
  }

  return {
    success: Boolean(payload.success ?? true),
    status: Number(payload.status || response.status),
    message: String(payload.message || "ok"),
    registered: Boolean(payload.registered),
    registeredByPlugin: Boolean(payload.registeredByPlugin ?? payload.registered),
    credentialReady: Boolean(payload.credentialReady),
    siteId: String(payload.siteId || ""),
    siteName: String(payload.siteName || ""),
    siteUrl: String(payload.siteUrl || ""),
    avatarUrl: String(payload.avatarUrl || ""),
    supportsInvitation: Boolean(payload.supportsInvitation),
    invitationState: String(payload.invitationState || ""),
    invitationMessage: String(payload.invitationMessage || "")
  } satisfies AstraHubSiteLookupResult;
}
