import { onBeforeUnmount, watch } from "vue";
import type { Ref } from "vue";
import type { AstraHubSettings, FriendInvitationItem } from "../types";
import { buildHubWsUrl, issueAstraHubRealtimeToken } from "./useAstraHubRealtimeToken";

const RECONNECT_DELAY_MS = 3000;

type HubInvitationRealtimeEventType =
  | "friend_invitation_created"
  | "friend_invitation_reviewed"
  | "friend_invitation_acked"
  | "friend_invitation_cancelled"
  | "friend_invitation_deleted"
  | "friend_relation_removed"
  | "site_relation_updated";

export interface HubRealtimeEvent<T = unknown> {
  id?: string;
  type: string;
  timestamp?: string;
  data?: T;
}

/**
 * site_relation_updated 事件的 payload 结构，由 Hub 在审核通过时广播。
 * impactedSiteIds 同时包含邀请方与审批方双方的 siteId。
 */
export interface HubSiteRelationUpdatedPayload {
  sourceSiteId?: string;
  sourceSiteUrl?: string;
  sourceSiteName?: string;
  impactedSiteIds?: string[];
  trigger?: string;
  inviteId?: string;
}

const HUB_INVITATION_REALTIME_EVENT_TYPES = new Set<HubInvitationRealtimeEventType>([
  "friend_invitation_created",
  "friend_invitation_reviewed",
  "friend_invitation_acked",
  "friend_invitation_cancelled",
  "friend_invitation_deleted",
  "friend_relation_removed",
  "site_relation_updated"
]);

function isRelevantHubEvent(
  event: HubRealtimeEvent<unknown>,
  currentSiteId: string
) {
  const type = event.type as HubInvitationRealtimeEventType;
  if (!HUB_INVITATION_REALTIME_EVENT_TYPES.has(type)) {
    return false;
  }
  const siteId = String(currentSiteId || "").trim();
  if (!siteId) {
    return false;
  }
  if (type === "site_relation_updated") {
    const data = (event.data || {}) as HubSiteRelationUpdatedPayload;
    if (String(data.sourceSiteId || "").trim() === siteId) {
      return true;
    }
    const impacted = Array.isArray(data.impactedSiteIds) ? data.impactedSiteIds : [];
    return impacted.some((id) => String(id || "").trim() === siteId);
  }
  if (type === "friend_relation_removed") {
    // payload: { actorSiteId, peerSiteId, ... }，路由给主动方与被动方双方。
    const data = (event.data || {}) as { actorSiteId?: string; peerSiteId?: string };
    return (
      String(data.actorSiteId || "").trim() === siteId ||
      String(data.peerSiteId || "").trim() === siteId
    );
  }
  // 友链邀请事件：data 是 FriendInvitationItem，按 fromSite/toSite 路由。
  const invitation = event.data as FriendInvitationItem | undefined;
  if (!invitation) {
    return false;
  }
  return (
    String(invitation.fromSite?.siteId || "").trim() === siteId ||
    String(invitation.toSite?.siteId || "").trim() === siteId
  );
}

export function useFriendInvitationRealtime(
  settings: Ref<AstraHubSettings>,
  onRelevantEvent: (event: HubRealtimeEvent<unknown>) => void
) {
  let socket: WebSocket | null = null;
  let reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  let stopped = false;

  const clearReconnectTimer = () => {
    if (!reconnectTimer) {
      return;
    }
    clearTimeout(reconnectTimer);
    reconnectTimer = null;
  };

  const closeSocket = () => {
    if (!socket) {
      return;
    }
    socket.onopen = null;
    socket.onclose = null;
    socket.onerror = null;
    socket.onmessage = null;
    socket.close();
    socket = null;
  };

  const scheduleReconnect = () => {
    clearReconnectTimer();
    reconnectTimer = setTimeout(() => {
      connect();
    }, RECONNECT_DELAY_MS);
  };

  const connect = async () => {
    if (stopped || socket) {
      return;
    }
    const hubBaseUrl = String(settings.value.connection.hubBaseUrl || "").trim();
    const currentSiteId = String(settings.value.credentials.siteId || "").trim();
    if (!hubBaseUrl || !currentSiteId) {
      return;
    }
    let ticket;
    try {
      ticket = await issueAstraHubRealtimeToken();
    } catch {
      if (!stopped) {
        scheduleReconnect();
      }
      return;
    }
    if (stopped || socket) {
      return;
    }
    const wsUrl = buildHubWsUrl(hubBaseUrl, String(ticket.token || "").trim());
    if (!wsUrl) {
      scheduleReconnect();
      return;
    }

    const ws = new WebSocket(wsUrl);
    socket = ws;

    ws.onmessage = (messageEvent) => {
      try {
        const event = JSON.parse(String(messageEvent.data)) as HubRealtimeEvent<unknown>;
        if (isRelevantHubEvent(event, currentSiteId)) {
          onRelevantEvent(event);
        }
      } catch {
        return;
      }
    };

    ws.onclose = () => {
      socket = null;
      if (!stopped) {
        scheduleReconnect();
      }
    };

    ws.onerror = () => {
      closeSocket();
    };
  };

  const reconnect = () => {
    stopped = false;
    clearReconnectTimer();
    closeSocket();
    connect();
  };

  const stop = () => {
    stopped = true;
    clearReconnectTimer();
    closeSocket();
  };

  watch(
    () => [settings.value.connection.hubBaseUrl, settings.value.credentials.siteId].join("|"),
    () => {
      reconnect();
    },
    { immediate: true }
  );

  onBeforeUnmount(stop);

  return {
    reconnect,
    stop
  };
}
