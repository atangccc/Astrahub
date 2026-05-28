import { onBeforeUnmount, watch } from "vue";
import type { Ref } from "vue";
import type { AstraHubSettings, FriendInvitationItem } from "../types";
import { buildHubWsUrl, issueAstraHubRealtimeToken } from "./useAstraHubRealtimeToken";

const RECONNECT_DELAY_MS = 3000;

type FriendInvitationRealtimeEventType =
  | "friend_invitation_created"
  | "friend_invitation_reviewed"
  | "friend_invitation_acked"
  | "friend_invitation_cancelled"
  | "friend_invitation_deleted";

export interface HubRealtimeEvent<T = unknown> {
  id?: string;
  type: string;
  timestamp?: string;
  data?: T;
}

const FRIEND_INVITATION_EVENT_TYPES = new Set<FriendInvitationRealtimeEventType>([
  "friend_invitation_created",
  "friend_invitation_reviewed",
  "friend_invitation_acked",
  "friend_invitation_cancelled",
  "friend_invitation_deleted"
]);

function isRelevantInvitationEvent(
  event: HubRealtimeEvent<FriendInvitationItem>,
  currentSiteId: string
) {
  if (!FRIEND_INVITATION_EVENT_TYPES.has(event.type as FriendInvitationRealtimeEventType)) {
    return false;
  }
  const invitation = event.data;
  if (!invitation) {
    return false;
  }
  const siteId = String(currentSiteId || "").trim();
  if (!siteId) {
    return false;
  }
  return (
    String(invitation.fromSite?.siteId || "").trim() === siteId ||
    String(invitation.toSite?.siteId || "").trim() === siteId
  );
}

export function useFriendInvitationRealtime(
  settings: Ref<AstraHubSettings>,
  onRelevantEvent: (event: HubRealtimeEvent<FriendInvitationItem>) => void
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
        const event = JSON.parse(String(messageEvent.data)) as HubRealtimeEvent<FriendInvitationItem>;
        if (isRelevantInvitationEvent(event, currentSiteId)) {
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
