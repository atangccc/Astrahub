import { onBeforeUnmount, watch } from "vue";
import type { Ref } from "vue";
import type { AstraHubSettings } from "../types";
import { buildHubWsUrl, issueAstraHubRealtimeToken } from "./useAstraHubRealtimeToken";

const RECONNECT_DELAY_MS = 3000;

export interface SiteRelationRealtimeEventData {
  sourceSiteId?: string;
  sourceSiteUrl?: string;
  sourceSiteName?: string;
  trigger?: string;
  snapshotAt?: string;
}

export interface SiteRelationRealtimeEvent {
  id?: string;
  type: string;
  timestamp?: string;
  data?: SiteRelationRealtimeEventData;
}

export function useSiteRelationRealtime(
  settings: Ref<AstraHubSettings>,
  onRelevantEvent: (event: SiteRelationRealtimeEvent) => void
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
    const currentSiteId = String(settings.value.credentials.siteId || "").trim();
    const hubBaseUrl = String(settings.value.connection.hubBaseUrl || "").trim();
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
        const event = JSON.parse(String(messageEvent.data)) as SiteRelationRealtimeEvent;
        if (String(event.type || "").trim() === "site_relation_updated") {
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
