import type { FriendInvitationsResponse, LinkGroupOption, FriendInvitationItem } from "../types";

export type FriendInvitationBox = "inbox" | "outbox";

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

export async function retryFriendInvitation(inviteId: string) {
  const response = await fetch(`/apis/api.plugin.halo.run/v1alpha1/astrahub/friend-invitations/${encodeURIComponent(inviteId)}/retry`, {
    method: "POST",
    headers: {
      Accept: "application/json"
    }
  });

  const payload = (await parseJson(response)) as {
    success?: boolean;
    message?: string;
  };

  if (!response.ok || !payload.success) {
    throw new Error(payload.message || `重试友链邀请失败（${response.status}）`);
  }
}

export async function deleteFriendInvitation(inviteId: string) {
  const response = await fetch(`/apis/api.plugin.halo.run/v1alpha1/astrahub/friend-invitations/${encodeURIComponent(inviteId)}/delete`, {
    method: "POST",
    headers: {
      Accept: "application/json"
    }
  });

  const payload = (await parseJson(response)) as {
    success?: boolean;
    message?: string;
  };

  if (!response.ok || !payload.success) {
    throw new Error(payload.message || `删除友链记录失败（${response.status}）`);
  }
}

export interface RemoveFriendRelationResult {
  success: boolean;
  status: number;
  message: string;
  removed: boolean;
  peerSiteId: string;
  peerSiteUrl: string;
  localLinkDeleted: number;
  localLinkMessage: string;
}

/**
 * 主动解除与对端站点的友链关系：
 *   1) Hub 删除 X→Y 边并向对端发邮件；
 *   2) 本插件随后删除本地 Halo Link CR（按 peerUrl 匹配）。
 * peerSiteId 必须是 Hub 注册的对端 siteId（已加友链 / 我已关注（对方接入）卡片才有此字段）。
 * reason 为可选解除原因，会进对方收件邮件正文。
 */
export async function removeFriendRelation(
  peerSiteId: string,
  reason = ""
): Promise<RemoveFriendRelationResult> {
  const trimmedReason = String(reason || "").trim();
  const init: RequestInit = {
    method: "POST",
    headers: {
      Accept: "application/json"
    }
  };
  if (trimmedReason) {
    init.headers = {
      ...(init.headers as Record<string, string>),
      "Content-Type": "application/json"
    };
    init.body = JSON.stringify({ reason: trimmedReason });
  }
  const response = await fetch(
    `/apis/api.plugin.halo.run/v1alpha1/astrahub/friend-relations/${encodeURIComponent(peerSiteId)}/remove`,
    init
  );

  const payload = (await parseJson(response)) as Partial<RemoveFriendRelationResult> & {
    message?: string;
  };

  if (!response.ok || !payload.success) {
    throw new Error(payload.message || `解除友链关系失败（${response.status}）`);
  }

  return {
    success: Boolean(payload.success),
    status: Number(payload.status || response.status),
    message: String(payload.message || "ok"),
    removed: Boolean(payload.removed),
    peerSiteId: String(payload.peerSiteId || peerSiteId),
    peerSiteUrl: String(payload.peerSiteUrl || ""),
    localLinkDeleted: Number(payload.localLinkDeleted || 0),
    localLinkMessage: String(payload.localLinkMessage || "")
  };
}

export async function removeOwnFriendFollow(peerSiteId: string): Promise<RemoveFriendRelationResult> {
  const response = await fetch(
    `/apis/api.plugin.halo.run/v1alpha1/astrahub/friend-follows/${encodeURIComponent(peerSiteId)}/remove`,
    {
      method: "POST",
      headers: {
        Accept: "application/json"
      }
    }
  );

  const payload = (await parseJson(response)) as Partial<RemoveFriendRelationResult> & {
    message?: string;
  };

  if (!response.ok || !payload.success) {
    throw new Error(payload.message || `删除友链失败（${response.status}）`);
  }

  return {
    success: Boolean(payload.success),
    status: Number(payload.status || response.status),
    message: String(payload.message || "ok"),
    removed: Boolean(payload.removed),
    peerSiteId: String(payload.peerSiteId || peerSiteId),
    peerSiteUrl: String(payload.peerSiteUrl || ""),
    localLinkDeleted: Number(payload.localLinkDeleted || 0),
    localLinkMessage: String(payload.localLinkMessage || "")
  };
}

export async function cancelFriendInvitation(inviteId: string) {
  const response = await fetch(`/apis/api.plugin.halo.run/v1alpha1/astrahub/friend-invitations/${encodeURIComponent(inviteId)}/cancel`, {
    method: "POST",
    headers: {
      Accept: "application/json"
    }
  });

  const payload = (await parseJson(response)) as {
    success?: boolean;
    invitation?: FriendInvitationItem;
    message?: string;
  };

  if (!response.ok || !payload.success) {
    throw new Error(payload.message || `撤回友链邀请失败（${response.status}）`);
  }

  return payload.invitation || null;
}

export async function fetchFriendInvitations(box: FriendInvitationBox, status = "") {
  return fetchFriendInvitationsBySource(box, status, "local");
}

export async function fetchRemoteFriendInvitations(box: FriendInvitationBox, status = "") {
  return fetchFriendInvitationsBySource(box, status, "remote");
}

async function fetchFriendInvitationsBySource(
  box: FriendInvitationBox,
  status = "",
  source: "local" | "remote" = "local"
) {
  const params = new URLSearchParams();
  params.set("box", box);
  params.set("source", source);
  if (String(status || "").trim()) {
    params.set("status", String(status || "").trim());
  }

  const response = await fetch(`/apis/api.plugin.halo.run/v1alpha1/astrahub/friend-invitations?${params.toString()}`, {
    method: "GET",
    headers: {
      Accept: "application/json"
    }
  });

  const payload = (await parseJson(response)) as Partial<FriendInvitationsResponse> & {
    message?: string;
  };

  if (!response.ok) {
    throw new Error(payload.message || `读取友链邀请失败（${response.status}）`);
  }

  return {
    success: Boolean(payload.success ?? true),
    generatedAt: String(payload.generatedAt || ""),
    total: Number(payload.total || 0),
    items: Array.isArray(payload.items) ? payload.items : []
  } satisfies FriendInvitationsResponse;
}

export async function syncFriendInvitationInbox() {
  const response = await fetch("/apis/api.plugin.halo.run/v1alpha1/astrahub/friend-invitations/inbox/sync", {
    method: "POST",
    headers: {
      Accept: "application/json"
    }
  });

  const payload = (await parseJson(response)) as {
    success?: boolean;
    message?: string;
  };

  if (!response.ok || !payload.success) {
    throw new Error(payload.message || `同步收件箱失败（${response.status}）`);
  }
}

export async function syncFriendInvitationOutbox() {
  const response = await fetch("/apis/api.plugin.halo.run/v1alpha1/astrahub/friend-invitations/outbox/sync", {
    method: "POST",
    headers: {
      Accept: "application/json"
    }
  });

  const payload = (await parseJson(response)) as {
    success?: boolean;
    message?: string;
  };

  if (!response.ok || !payload.success) {
    throw new Error(payload.message || `同步发件箱失败（${response.status}）`);
  }
}

export async function createFriendInvitation(toSiteId: string, message = "", linkGroupName = "") {
  const response = await fetch("/apis/api.plugin.halo.run/v1alpha1/astrahub/friend-invitations", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Accept: "application/json"
    },
    body: JSON.stringify({
      toSiteId,
      message,
      linkGroupName
    })
  });

  const payload = (await parseJson(response)) as {
    success?: boolean;
    invitation?: FriendInvitationItem;
    message?: string;
  };

  if (!response.ok || !payload.success) {
    throw new Error(payload.message || `发起邀请失败（${response.status}）`);
  }

  return payload.invitation || null;
}

export async function fetchFriendInvitationLinkGroups() {
  const response = await fetch("/apis/api.plugin.halo.run/v1alpha1/astrahub/friend-invitations/link-groups", {
    method: "GET",
    headers: {
      Accept: "application/json"
    }
  });

  const payload = (await parseJson(response)) as {
    success?: boolean;
    items?: LinkGroupOption[];
    message?: string;
  };

  if (!response.ok) {
    throw new Error(payload.message || `读取友链分组失败（${response.status}）`);
  }

  return Array.isArray(payload.items) ? payload.items : [];
}

export async function reviewFriendInvitation(
  inviteId: string,
  approved: boolean,
  reason = "",
  linkGroupName = ""
) {
  const response = await fetch(`/apis/api.plugin.halo.run/v1alpha1/astrahub/friend-invitations/${encodeURIComponent(inviteId)}/review`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Accept: "application/json"
    },
    body: JSON.stringify({
      approved,
      reason,
      linkGroupName
    })
  });

  const payload = (await parseJson(response)) as {
    success?: boolean;
    invitation?: FriendInvitationItem;
    message?: string;
  };

  if (!response.ok || !payload.success) {
    throw new Error(payload.message || `审核友链邀请失败（${response.status}）`);
  }

  return payload.invitation || null;
}

export async function reconcileFriendInvitation(invitation: FriendInvitationItem, currentSiteId: string) {
  const response = await fetch(`/apis/api.plugin.halo.run/v1alpha1/astrahub/friend-invitations/${encodeURIComponent(invitation.inviteId)}/reconcile`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Accept: "application/json"
    },
    body: JSON.stringify({
      currentSiteId,
      fromSiteId: invitation.fromSite.siteId,
      fromSiteName: invitation.fromSite.siteName,
      fromSiteUrl: invitation.fromSite.siteUrl,
      fromDescription: invitation.fromSite.description || "",
      fromAvatarUrl: invitation.fromSite.avatarUrl || "",
      fromRssUrl: invitation.fromSite.rssUrl || "",
      fromContactEmail: "",
      toSiteId: invitation.toSite.siteId,
      toSiteName: invitation.toSite.siteName,
      toSiteUrl: invitation.toSite.siteUrl,
      toDescription: invitation.toSite.description || "",
      toAvatarUrl: invitation.toSite.avatarUrl || "",
      toRssUrl: invitation.toSite.rssUrl || "",
      toContactEmail: "",
      message: invitation.message || "",
      status: invitation.status,
      deliveryStatus: invitation.deliveryStatus,
      reviewReason: invitation.reviewReason || "",
      linkGroupName: invitation.linkGroupName || "",
      createdAt: invitation.createdAt,
      reviewedAt: invitation.reviewedAt || "",
      ackedAt: invitation.ackedAt || "",
      lastError: invitation.lastError || "",
      retryCount: invitation.retryCount || 0,
      updatedAt: invitation.updatedAt
    })
  });

  const payload = (await parseJson(response)) as {
    success?: boolean;
    created?: boolean;
    duplicate?: boolean;
    message?: string;
  };

  if (!response.ok || !payload.success) {
    throw new Error(payload.message || `本地建链失败（${response.status}）`);
  }

  return {
    created: Boolean(payload.created),
    duplicate: Boolean(payload.duplicate),
    message: String(payload.message || "ok")
  };
}

export async function ackFriendInvitation(inviteId: string, lastError = "") {
  const response = await fetch(`/apis/api.plugin.halo.run/v1alpha1/astrahub/friend-invitations/${encodeURIComponent(inviteId)}/ack`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Accept: "application/json"
    },
    body: JSON.stringify({ lastError })
  });

  const payload = (await parseJson(response)) as {
    success?: boolean;
    message?: string;
  };

  if (!response.ok || !payload.success) {
    throw new Error(payload.message || `回执 Hub 失败（${response.status}）`);
  }
}
