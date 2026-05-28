export interface RequestInvitationPayload {
  hubBaseUrl: string;
  contactEmail: string;
  siteUrl: string;
}

export interface RequestInvitationResponse {
  success: boolean;
  status: number;
  message: string;
  expiresAt: string;
  cooldownUntil?: string;
}

export interface InvitationRegisterPayload {
  hubBaseUrl: string;
  invitationCode: string;
  siteName: string;
  siteUrl: string;
  siteDescription: string;
  siteRssUrl: string;
  siteAvatarUrl: string;
  contactEmail: string;
  siteNodeName: string;
  siteNodeAvatar: string;
}

export interface InvitationRegisterResponse {
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

const REQUEST_INVITATION_API =
  "/apis/api.plugin.halo.run/v1alpha1/astrahub/invitation/request";
const INVITATION_REGISTER_API =
  "/apis/api.plugin.halo.run/v1alpha1/astrahub/invitation/register";

async function parseJsonResponse(response: Response): Promise<Record<string, unknown>> {
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

function stringField(
  source: Record<string, unknown>,
  key: string,
  fallback = ""
): string {
  const value = source[key];
  return typeof value === "string" ? value : fallback;
}

export async function requestInvitationCode(
  payload: RequestInvitationPayload
): Promise<RequestInvitationResponse> {
  const response = await fetch(REQUEST_INVITATION_API, {
    method: "POST",
    headers: {
      "Content-Type": "application/json"
    },
    body: JSON.stringify(payload)
  });

  const parsed = await parseJsonResponse(response);
  const result: RequestInvitationResponse = {
    success: Boolean(parsed.success),
    status: Number(parsed.status ?? response.status),
    message:
      stringField(parsed, "message") ||
      (response.ok ? "ok" : `request failed: ${response.status}`),
    expiresAt: stringField(parsed, "expiresAt"),
    cooldownUntil: stringField(parsed, "cooldownUntil") || undefined
  };

  if (!response.ok || !result.success) {
    throw new Error(result.message || "request invitation failed");
  }
  return result;
}

export async function registerWithInvitation(
  payload: InvitationRegisterPayload
): Promise<InvitationRegisterResponse> {
  const response = await fetch(INVITATION_REGISTER_API, {
    method: "POST",
    headers: {
      "Content-Type": "application/json"
    },
    body: JSON.stringify(payload)
  });

  const parsed = await parseJsonResponse(response);
  const result: InvitationRegisterResponse = {
    success: Boolean(parsed.success),
    status: Number(parsed.status ?? response.status),
    message:
      stringField(parsed, "message") ||
      (response.ok ? "ok" : `request failed: ${response.status}`),
    siteId: stringField(parsed, "siteId"),
    apiKey: stringField(parsed, "apiKey"),
    createdAt: stringField(parsed, "createdAt"),
    nodeName:
      stringField(parsed, "nodeName") || stringField(parsed, "category"),
    category: stringField(parsed, "category"),
    nodeAvatar: stringField(parsed, "nodeAvatar")
  };

  if (!response.ok || !result.success) {
    throw new Error(result.message || "register with invitation failed");
  }
  return result;
}
