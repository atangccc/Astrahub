import type { AstraHubSiteRelationBatchResponse, AstraHubSiteRelationItem } from "../types";

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

export async function batchResolveSiteRelations(targetUrls: string[]) {
  const urls = (Array.isArray(targetUrls) ? targetUrls : [])
    .map((item) => String(item || "").trim())
    .filter(Boolean);

  const response = await fetch("/apis/api.plugin.halo.run/v1alpha1/astrahub/site-relations/batch", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Accept: "application/json"
    },
    body: JSON.stringify({
      targetUrls: urls
    })
  });

  const payload = (await parseJson(response)) as Partial<AstraHubSiteRelationBatchResponse> & {
    message?: string;
    items?: AstraHubSiteRelationItem[];
  };

  if (!response.ok || !payload.success) {
    throw new Error(payload.message || `读取站点关系失败（${response.status}）`);
  }

  return {
    success: Boolean(payload.success ?? true),
    status: Number(payload.status || response.status),
    message: String(payload.message || "ok"),
    items: Array.isArray(payload.items) ? payload.items : []
  } satisfies AstraHubSiteRelationBatchResponse;
}
