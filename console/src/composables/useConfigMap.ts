import { ref } from "vue";
import { consoleApiClient } from "@halo-dev/api-client";
import { Toast } from "@halo-dev/components";
import type { AstraHubSettings, ReadLaterItem } from "../types";

const PLUGIN_NAME = "PluginAstraHub";

export interface SaveSettingsOptions {
  silentSuccess?: boolean;
}

function createDefaultSettings(): AstraHubSettings {
  return {
    connection: {
      hubBaseUrl: "https://astra.aobp.cn",
      registerToken: "",
      siteName: "",
      siteUrl: "",
      siteDescription: "",
      contactEmail: "",
      siteNodeName: "",
      siteNodeAvatar: "",
      siteRssUrl: ""
    },
    credentials: {
      siteId: "",
      apiKey: "",
      createdAt: ""
    },
    invitation: {
      allowIncomingInvitations: true,
      allowOutgoingInvitations: true
    },
    widget: {
      enabled: true
    },
    realtimeBroadcast: {
      enabled: true
    },
    favorites: {
      pinnedSiteUrls: []
    },
    readLater: {
      items: []
    }
  };
}

export function useConfigMap() {
  const loading = ref(false);
  const saving = ref(false);
  const configMapData = ref<Record<string, unknown>>({});
  const settings = ref<AstraHubSettings>(createDefaultSettings());

  const fetchSettings = async () => {
    loading.value = true;
    try {
      const { data } = await consoleApiClient.plugin.plugin.fetchPluginJsonConfig({ name: PLUGIN_NAME });
      const dataRecord = (data || {}) as Record<string, unknown>;
      configMapData.value = dataRecord;

      const defaults = createDefaultSettings();
      const rawConnection = (dataRecord.connection || {}) as Record<string, unknown>;
      const siteNodeName = String(rawConnection.siteNodeName || "").trim();
      const siteNodeAvatar = String(rawConnection.siteNodeAvatar || "").trim();
      const siteRssUrl = String(rawConnection.siteRssUrl || "").trim();
      const siteDescription = String(rawConnection.siteDescription || "").trim();
      const hubBaseUrl = String(rawConnection.hubBaseUrl || "").trim() || defaults.connection.hubBaseUrl;
      const rawInvitation = (dataRecord.invitation || {}) as Record<string, unknown>;
      const rawRealtimeBroadcast = (dataRecord.realtimeBroadcast || {}) as Record<string, unknown>;
      const rawCredentials = (dataRecord.credentials || {}) as Record<string, unknown>;
      const rawWidget = (dataRecord.widget || {}) as Record<string, unknown>;
      const rawFavorites = (dataRecord.favorites || {}) as Record<string, unknown>;
      const rawReadLater = (dataRecord.readLater || {}) as Record<string, unknown>;

      settings.value = {
        connection: {
          ...defaults.connection,
          ...rawConnection,
          hubBaseUrl,
          siteDescription,
          siteNodeName,
          siteNodeAvatar,
          siteRssUrl
        },
        credentials: { ...defaults.credentials, ...rawCredentials },
        invitation: {
          ...defaults.invitation,
          allowIncomingInvitations: Boolean(rawInvitation.allowIncomingInvitations ?? defaults.invitation.allowIncomingInvitations),
          allowOutgoingInvitations: Boolean(rawInvitation.allowOutgoingInvitations ?? defaults.invitation.allowOutgoingInvitations)
        },
        widget: { ...defaults.widget, ...rawWidget },
        realtimeBroadcast: {
          ...defaults.realtimeBroadcast,
          enabled: Boolean(rawRealtimeBroadcast.enabled ?? defaults.realtimeBroadcast.enabled)
        },
        favorites: {
          pinnedSiteUrls: Array.isArray(rawFavorites.pinnedSiteUrls)
            ? (rawFavorites.pinnedSiteUrls as string[])
            : []
        },
        readLater: {
          items: Array.isArray(rawReadLater.items)
            ? (rawReadLater.items as ReadLaterItem[])
            : []
        }
      };
    } catch (error) {
      console.error("[AstraHub] Failed to fetch settings:", error);
      Toast.error("读取配置失败");
    } finally {
      loading.value = false;
    }
  };

  const saveSettings = async (options: SaveSettingsOptions = {}): Promise<boolean> => {
    saving.value = true;
    try {
      const connection = {
        ...settings.value.connection,
        siteDescription: String(settings.value.connection.siteDescription || "").trim(),
        siteNodeAvatar: String(settings.value.connection.siteNodeAvatar || "").trim(),
        siteRssUrl: String(settings.value.connection.siteRssUrl || "").trim()
      };
      const invitation = {
        allowIncomingInvitations: Boolean(settings.value.invitation.allowIncomingInvitations),
        allowOutgoingInvitations: Boolean(settings.value.invitation.allowOutgoingInvitations)
      };
      const realtimeBroadcast = {
        enabled: Boolean(settings.value.realtimeBroadcast.enabled)
      };
      const bodyBase = { ...configMapData.value };
      delete bodyBase.security;
      delete bodyBase.sync;
      delete bodyBase.consent;
      const body = {
        ...bodyBase,
        connection,
        credentials: settings.value.credentials,
        invitation,
        widget: settings.value.widget,
        realtimeBroadcast,
        favorites: settings.value.favorites,
        readLater: settings.value.readLater
      };
      await consoleApiClient.plugin.plugin.updatePluginJsonConfig({ name: PLUGIN_NAME, body });
      configMapData.value = body;
      if (!options.silentSuccess) {
        Toast.success("保存成功");
      }
      return true;
    } catch (error) {
      console.error("[AstraHub] Failed to save settings:", error);
      Toast.error("保存失败");
      return false;
    } finally {
      saving.value = false;
    }
  };

  return {
    loading,
    saving,
    settings,
    fetchSettings,
    saveSettings
  };
}
