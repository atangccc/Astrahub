import { computed, ref } from "vue";
import type { PlanetLinkItem, PlanetLinksResponse } from "../types";

const PAGE_SIZE = 50;

const ENDPOINT = "/apis/api.plugin.halo.run/v1alpha1/astrahub/planet-links";

function buildQuery(params: Record<string, string | number | undefined>) {
  const usp = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === null) continue;
    const text = String(value).trim();
    if (!text) continue;
    usp.append(key, text);
  }
  const text = usp.toString();
  return text ? `?${text}` : "";
}

export function usePlanetLinksLocal() {
  const loading = ref(false);
  const loadingMore = ref(false);
  const error = ref("");
  const items = ref<PlanetLinkItem[]>([]);
  const nextCursor = ref("");
  const hasMore = ref(false);

  const visibleItems = computed(() => items.value);

  async function fetchPage(cursor: string): Promise<PlanetLinksResponse> {
    const url = ENDPOINT + buildQuery({ size: PAGE_SIZE, cursor });
    const response = await fetch(url, {
      method: "GET",
      headers: { Accept: "application/json" }
    });
    if (!response.ok) {
      const payload = (await response.json().catch(() => ({}))) as { message?: string };
      throw new Error(payload.message || `读取星球友链失败（${response.status}）`);
    }
    return (await response.json()) as PlanetLinksResponse;
  }

  async function fetchLinks() {
    loading.value = true;
    error.value = "";
    try {
      const payload = await fetchPage("");
      items.value = Array.isArray(payload.items) ? payload.items : [];
      nextCursor.value = String(payload.nextCursor || "").trim();
      hasMore.value = Boolean(payload.hasMore);
    } catch (errorValue) {
      error.value = errorValue instanceof Error ? errorValue.message : "读取星球友链失败";
      items.value = [];
      nextCursor.value = "";
      hasMore.value = false;
    } finally {
      loading.value = false;
    }
  }

  async function loadMore() {
    if (loading.value || loadingMore.value || !hasMore.value || !nextCursor.value) {
      return;
    }
    loadingMore.value = true;
    try {
      const payload = await fetchPage(nextCursor.value);
      const more = Array.isArray(payload.items) ? payload.items : [];
      const seen = new Set(items.value.map((item) => item.url));
      for (const item of more) {
        if (item.url && !seen.has(item.url)) {
          seen.add(item.url);
          items.value.push(item);
        }
      }
      nextCursor.value = String(payload.nextCursor || "").trim();
      hasMore.value = Boolean(payload.hasMore) && nextCursor.value !== "";
    } catch (errorValue) {
      error.value = errorValue instanceof Error ? errorValue.message : "加载更多友链失败";
      hasMore.value = false;
    } finally {
      loadingMore.value = false;
    }
  }

  return {
    loading,
    loadingMore,
    error,
    items,
    visibleItems,
    hasMore,
    fetchLinks,
    loadMore
  };
}
