export interface PushGraphResult {
  success: boolean;
  status: number;
  message: string;
  responseBody: string;
  pushedAt: string;
}

export interface ReportStatusResult {
  sequence: number;
  phase: string;
  trigger: string;
  running: boolean;
  success: boolean;
  status: number;
  message: string;
  pushedAt: string;
  nextRunAt: string;
  updatedAt: string;
  linked: boolean;
  lastSuccessfulPushAt: string;
}

const PUSH_API = "/apis/api.plugin.halo.run/v1alpha1/astrahub/push-graph";
const PUSH_LINK_EDGES_API = "/apis/api.plugin.halo.run/v1alpha1/astrahub/push-link-edges";
const REPORT_STATUS_API = "/apis/api.plugin.halo.run/v1alpha1/astrahub/report-status";

export async function pushGraphNow(reason?: string): Promise<PushGraphResult> {
  const endpoint = reason
    ? `${PUSH_API}?reason=${encodeURIComponent(reason)}`
    : PUSH_API;
  return pushByEndpoint(endpoint);
}

export async function pushLinkEdgesNow(): Promise<PushGraphResult> {
  return pushByEndpoint(PUSH_LINK_EDGES_API);
}

async function pushByEndpoint(endpoint: string): Promise<PushGraphResult> {
  const response = await fetch(endpoint, {
    method: "POST",
    headers: {
      Accept: "application/json"
    }
  });

  const text = await response.text();
  let parsed: Partial<PushGraphResult> = {};
  try {
    parsed = text ? (JSON.parse(text) as Partial<PushGraphResult>) : {};
  } catch {
    parsed = {};
  }

  const result: PushGraphResult = {
    success: Boolean(parsed.success),
    status: Number(parsed.status || response.status),
    message: parsed.message || (response.ok ? "ok" : `push failed: ${response.status}`),
    responseBody: parsed.responseBody || "",
    pushedAt: parsed.pushedAt || ""
  };

  if (!response.ok || !result.success) {
    throw new Error(result.message || "push failed");
  }
  return result;
}

export async function fetchReportStatus(): Promise<ReportStatusResult | null> {
  const response = await fetch(REPORT_STATUS_API, {
    method: "GET",
    headers: {
      Accept: "application/json"
    }
  });

  const text = await response.text();
  let parsed: { success?: boolean; status?: Partial<ReportStatusResult> } = {};
  try {
    parsed = text ? (JSON.parse(text) as { success?: boolean; status?: Partial<ReportStatusResult> }) : {};
  } catch {
    parsed = {};
  }

  if (!response.ok || parsed.success === false || !parsed.status) {
    throw new Error(`读取同步状态失败（${response.status}）`);
  }

  return {
    sequence: Number(parsed.status.sequence || 0),
    phase: String(parsed.status.phase || ""),
    trigger: String(parsed.status.trigger || ""),
    running: Boolean(parsed.status.running),
    success: Boolean(parsed.status.success),
    status: Number(parsed.status.status || 0),
    message: String(parsed.status.message || ""),
    pushedAt: String(parsed.status.pushedAt || ""),
    nextRunAt: String(parsed.status.nextRunAt || ""),
    updatedAt: String(parsed.status.updatedAt || ""),
    linked: Boolean(parsed.status.linked),
    lastSuccessfulPushAt: String(parsed.status.lastSuccessfulPushAt || "")
  };
}
