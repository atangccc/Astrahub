import type {
  NewsBrowseResponse,
  NewsDiscoverItem,
  NewsDiscoverResponse,
  NewsItem
} from "../types";

const BASE = "/apis/api.plugin.halo.run/v1alpha1/astrahub/news";

async function getJson<T>(url: string): Promise<T> {
  const response = await fetch(url, {
    method: "GET",
    headers: {
      Accept: "application/json"
    }
  });
  if (!response.ok) {
    const payload = (await response.json().catch(() => ({}))) as { message?: string };
    throw new Error(payload.message || `读取资讯失败（${response.status}）`);
  }
  return (await response.json()) as T;
}

function buildQuery(params: Record<string, string | number | undefined | null>) {
  const usp = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === null) {
      continue;
    }
    const text = String(value).trim();
    if (!text) {
      continue;
    }
    usp.append(key, text);
  }
  const text = usp.toString();
  return text ? `?${text}` : "";
}

export interface BrowseQuery {
  pageSize?: number;
  cursor?: string;
  onlyMyGalaxy?: boolean;
}

export interface SearchQuery {
  q: string;
  page?: number;
  pageSize?: number;
  cursor?: string;
  onlyMyGalaxy?: boolean;
}

export interface DiscoverQuery {
  size?: number;
  cursor?: string;
}

export async function fetchNewsBrowse(query: BrowseQuery = {}): Promise<NewsBrowseResponse> {
  const { onlyMyGalaxy, ...rest } = query;
  return getJson<NewsBrowseResponse>(
    `${BASE}/browse${buildQuery({ ...rest, onlyMyGalaxy: onlyMyGalaxy ? "true" : "" })}`
  );
}

export async function fetchNewsSearch(query: SearchQuery): Promise<NewsBrowseResponse> {
  const { onlyMyGalaxy, ...rest } = query;
  return getJson<NewsBrowseResponse>(
    `${BASE}/search${buildQuery({ ...rest, onlyMyGalaxy: onlyMyGalaxy ? "true" : "" })}`
  );
}

export async function fetchNewsDiscover(query: DiscoverQuery = {}): Promise<NewsDiscoverResponse> {
  return getJson<NewsDiscoverResponse>(`${BASE}/discover${buildQuery({ ...query })}`);
}

export type { NewsItem, NewsDiscoverItem };
