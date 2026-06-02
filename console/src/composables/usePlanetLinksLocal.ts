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

// 统一流消费：服务端 /v1/planet/links 已按 relationRankOf 把关系卡片
// （互关 → 我已关注 → 对方关注我 → 可发起邀请 → 已发邀请 → 普通）排在流最前，
// 并用单一游标分两阶段（关系阶段 → 普通阶段）连续分页。
// 前端只负责顺着 nextCursor 往下拉、累积渲染，绝不在本地重排或拼接两路数据。
export function usePlanetLinksLocal() {
  const loading = ref(false);
  const loadingMore = ref(false);
  const error = ref("");
  const pagedItems = ref<PlanetLinkItem[]>([]);
  const nextCursor = ref("");
  const hasMore = ref(false);

  // items 即服务端统一流的累积结果，顺序就是服务端给定的顺序。
  const items = computed<PlanetLinkItem[]>(() => pagedItems.value);
  const visibleItems = items;

  async function fetchPage(cursor: string): Promise<PlanetLinksResponse> {
    const url = ENDPOINT_PAGED + buildQuery({ size: PAGE_SIZE, cursor });
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
      // 首屏：游标留空，服务端从关系阶段第一条开始返回（关系卡片已置顶）。
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
      // 按 url 去重，防止游标边界处偶发重复；顺序仍以服务端流为准，前端不重排。
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

  return {
    loading,
    loadingMore,
    error,
    items,
    visibleItems,
    hasMore,
    fetchLinks,
    loadMore,
    markOutboxActive
  };
}
