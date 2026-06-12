import { computed, ref } from "vue";
import type { PlanetLinkItem, PlanetLinksResponse } from "../types";

const PAGE_SIZE = 50;

const ENDPOINT_PAGED = "/apis/api.plugin.halo.run/v1alpha1/astrahub/planet-links";

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
  const pagedItems = ref<PlanetLinkItem[]>([]);
  const nextCursor = ref("");
  const hasMore = ref(false);

  const keyword = ref("");
  const relation = ref("");

  const items = computed<PlanetLinkItem[]>(() => pagedItems.value);
  const visibleItems = items;

  async function fetchPage(cursor: string): Promise<PlanetLinksResponse> {
    const url =
      ENDPOINT_PAGED +
      buildQuery({
        size: PAGE_SIZE,
        cursor,
        keyword: keyword.value,
        relation: relation.value
      });
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

  async function fetchLinks(options?: { silent?: boolean }) {
    const silent = Boolean(options?.silent);
    if (!silent) {
      loading.value = true;
      error.value = "";
    }
    try {
      const payload = await fetchPage("");
      pagedItems.value = Array.isArray(payload.items) ? payload.items : [];
      nextCursor.value = String(payload.nextCursor || "").trim();
      hasMore.value = Boolean(payload.hasMore) && nextCursor.value !== "";
      if (silent) {
        error.value = "";
      }
    } catch (errorValue) {
      if (silent) {
        return;
      }
      error.value = errorValue instanceof Error ? errorValue.message : "读取星球友链失败";
      pagedItems.value = [];
      nextCursor.value = "";
      hasMore.value = false;
    } finally {
      if (!silent) {
        loading.value = false;
      }
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
      // 按 url 去重
      const seen = new Set(pagedItems.value.map((item) => item.url));
      const appended: PlanetLinkItem[] = [];
      for (const item of more) {
        const url = String(item.url || "").trim();
        if (!url || seen.has(url)) continue;
        seen.add(url);
        appended.push(item);
      }
      if (appended.length) {
        pagedItems.value.push(...appended);
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

  function markOutboxActive(targetUrl: string) {
    const url = String(targetUrl || "").trim();
    if (!url) return;
    const target = pagedItems.value.find((entry) => entry.url === url);
    if (target) {
      target.outboxInvitationActive = true;
    }
  }

  function setQuery(next: { keyword?: string; relation?: string }): boolean {
    const nextKeyword = String(next.keyword ?? keyword.value).trim();
    const nextRelation = String(next.relation ?? relation.value).trim();
    const changed = nextKeyword !== keyword.value || nextRelation !== relation.value;
    keyword.value = nextKeyword;
    relation.value = nextRelation;
    return changed;
  }

  return {
    loading,
    loadingMore,
    error,
    items,
    visibleItems,
    hasMore,
    fetchLinks,
    loadMore,
    markOutboxActive,
    setQuery
  };
}
