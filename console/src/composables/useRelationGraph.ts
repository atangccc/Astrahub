import { computed, ref, shallowRef } from "vue";
import type {
  GraphNodeDetailResponse,
  GraphNodeFriendLink,
  GraphSiteDetailResponse,
} from "../types";

const ENDPOINT_MY_SITE = "/apis/api.plugin.halo.run/v1alpha1/astrahub/graph/my-site";
const ENDPOINT_NODE = "/apis/api.plugin.halo.run/v1alpha1/astrahub/graph/nodes";

const FRIEND_LINK_PAGE_SIZE = 100;
const BFS_MAX_NODES = 1000;
const BFS_MAX_CONCURRENCY = 4;

export interface GraphCanvasNode {
  id: string;
  kind: "self" | "registered" | "unregistered";
  nodeId?: string;
  siteId?: string;
  title: string;
  galaxyName?: string;
  subtitle?: string;
  url?: string;
  rssUrl?: string;
  description?: string;
  avatar?: string;
  raw?: GraphNodeFriendLink;
}

export interface GraphCanvasEdge {
  id: string;
  source: string;
  target: string;
}

export interface GraphLoadProgress {
  expanded: number;
  pending: number;
  inflight: number;
  total: number;
  capped: boolean;
}

export function useRelationGraph() {
  const loading = ref(false);
  const error = ref("");
  const mySite = shallowRef<GraphSiteDetailResponse | null>(null);
  const nodeCache = new Map<string, GraphNodeDetailResponse>();
  const nodes = ref(new Map<string, GraphCanvasNode>());
  const edges = ref(new Map<string, GraphCanvasEdge>());
  const focusedId = ref<string>("");
  const progress = ref<GraphLoadProgress>({
    expanded: 0,
    pending: 0,
    inflight: 0,
    total: 0,
    capped: false,
  });

  const focusedNode = computed(() => nodes.value.get(focusedId.value) ?? null);

  async function bootstrap() {
    loading.value = true;
    error.value = "";
    progress.value = { expanded: 0, pending: 0, inflight: 0, total: 0, capped: false };
    try {
      const site = await fetchMySite();
      mySite.value = site;
      const summary = site.summary;
      if (!summary.nodeId) {
        throw new Error("当前站点尚未建立主星节点，请先在主星完成首次同步");
      }
      const selfId = summary.nodeId;
      const selfCanvasNode: GraphCanvasNode = {
        id: selfId,
        kind: "self",
        nodeId: selfId,
        siteId: summary.siteId,
        title: summary.name,
        galaxyName: summary.nodeName || summary.name,
        subtitle: summary.url,
        url: summary.url,
        avatar: summary.avatar,
      };
      nodes.value.set(selfId, selfCanvasNode);
      focusedId.value = selfId;
      bumpProgress();
      await crawlAll(selfId);
    } catch (e) {
      error.value = e instanceof Error ? e.message : String(e);
    } finally {
      loading.value = false;
    }
  }

  function focusOn(canvasNodeId: string) {
    const node = nodes.value.get(canvasNodeId);
    if (!node) {
      return;
    }
    focusedId.value = canvasNodeId;
  }

  async function reset() {
    nodeCache.clear();
    nodes.value = new Map();
    edges.value = new Map();
    focusedId.value = "";
    mySite.value = null;
    progress.value = { expanded: 0, pending: 0, inflight: 0, total: 0, capped: false };
    await bootstrap();
  }

  async function fetchMySite(): Promise<GraphSiteDetailResponse> {
    const response = await fetch(ENDPOINT_MY_SITE, {
      method: "GET",
      credentials: "include",
      headers: { Accept: "application/json" },
    });
    const payload = await parseJsonOrThrow(response);
    return payload as GraphSiteDetailResponse;
  }

  async function fetchNode(nodeId: string): Promise<GraphNodeDetailResponse> {
    const url = `${ENDPOINT_NODE}/${encodeURIComponent(nodeId)}?size=${FRIEND_LINK_PAGE_SIZE}`;
    const response = await fetch(url, {
      method: "GET",
      credentials: "include",
      headers: { Accept: "application/json" },
    });
    const payload = await parseJsonOrThrow(response);
    return payload as GraphNodeDetailResponse;
  }

  async function parseJsonOrThrow(response: Response): Promise<unknown> {
    const text = await response.text();
    let payload: { message?: string; error?: { message?: string } } | unknown = {};
    try {
      payload = text ? JSON.parse(text) : {};
    } catch {
      payload = {};
    }
    if (!response.ok) {
      const obj = payload as { message?: string; error?: { message?: string } };
      const message = obj?.message || obj?.error?.message || `加载失败（${response.status}）`;
      throw new Error(message);
    }
    return payload;
  }

  async function crawlAll(seedNodeId: string) {
    const queue: string[] = [seedNodeId];
    const queued = new Set<string>([seedNodeId]);
    let inflight = 0;
    progress.value = {
      ...progress.value,
      pending: queue.length,
      inflight,
    };

    return new Promise<void>((resolve) => {
      const tick = () => {
        if (queue.length === 0 && inflight === 0) {
          progress.value = { ...progress.value, pending: 0, inflight: 0 };
          resolve();
          return;
        }
        if (nodes.value.size >= BFS_MAX_NODES) {
          progress.value = { ...progress.value, capped: true, pending: 0 };
          if (inflight === 0) {
            resolve();
          }
          return;
        }
        while (inflight < BFS_MAX_CONCURRENCY && queue.length > 0) {
          const next = queue.shift();
          if (!next) break;
          inflight += 1;
          progress.value = {
            ...progress.value,
            pending: queue.length,
            inflight,
          };
          expandFriendLinks(next)
            .then((newNeighbors) => {
              for (const id of newNeighbors) {
                if (queued.has(id)) continue;
                if (nodes.value.size >= BFS_MAX_NODES) break;
                queued.add(id);
                queue.push(id);
              }
              progress.value = {
                ...progress.value,
                pending: queue.length,
                expanded: progress.value.expanded + 1,
                total: nodes.value.size,
              };
            })
            .catch((err) => {
              console.warn("[RelationGraph] expand failed", next, err);
            })
            .finally(() => {
              inflight -= 1;
              progress.value = { ...progress.value, inflight };
              tick();
            });
        }
      };
      tick();
    });
  }

  async function expandFriendLinks(canvasNodeId: string): Promise<string[]> {
    const canvasNode = nodes.value.get(canvasNodeId);
    if (!canvasNode || !canvasNode.nodeId) {
      return [];
    }
    if (nodeCache.has(canvasNode.nodeId)) {
      return [];
    }
    const detail = await fetchNode(canvasNode.nodeId);
    nodeCache.set(canvasNode.nodeId, detail);
    const nextNodes = new Map(nodes.value);
    const nextEdges = new Map(edges.value);
    const newRegistered: string[] = [];
    for (const link of detail.friendLinks ?? []) {
      const friendNode = friendLinkToCanvasNode(link);
      const existing = nextNodes.get(friendNode.id);
      if (!existing) {
        nextNodes.set(friendNode.id, friendNode);
        if (friendNode.kind === "registered") {
          newRegistered.push(friendNode.id);
        }
      } else if (existing.kind === "unregistered" && friendNode.kind === "registered") {
        nextNodes.set(friendNode.id, friendNode);
        newRegistered.push(friendNode.id);
      }
      const edgeKey = friendEdgeKey(canvasNodeId, friendNode.id);
      if (!nextEdges.has(edgeKey)) {
        nextEdges.set(edgeKey, {
          id: edgeKey,
          source: canvasNodeId,
          target: friendNode.id,
        });
      }
    }
    nodes.value = nextNodes;
    edges.value = nextEdges;
    return newRegistered;
  }

  function bumpProgress() {
    progress.value = {
      ...progress.value,
      total: nodes.value.size,
    };
  }

  return {
    loading,
    error,
    nodes,
    edges,
    focusedId,
    focusedNode,
    mySite,
    progress,
    bootstrap,
    focusOn,
    reset,
  };
}

function friendLinkToCanvasNode(link: GraphNodeFriendLink): GraphCanvasNode {
  if (link.targetRegistered && link.targetNodeId) {
    return {
      id: link.targetNodeId,
      kind: "registered",
      nodeId: link.targetNodeId,
      siteId: link.targetSiteId,
      title: link.title || link.targetNodeName || link.url,
      galaxyName: link.targetNodeName,
      subtitle: link.url,
      url: link.url,
      rssUrl: link.rssUrl,
      description: link.description,
      avatar: link.targetAvatar || link.logo,
      raw: link,
    };
  }
  return {
    id: `url:${normalizeUrl(link.url)}`,
    kind: "unregistered",
    title: link.title || link.url,
    subtitle: link.url,
    url: link.url,
    rssUrl: link.rssUrl,
    description: link.description,
    avatar: link.logo,
    raw: link,
  };
}

function friendEdgeKey(a: string, b: string): string {
  const [x, y] = a < b ? [a, b] : [b, a];
  return `friend|${x}|${y}`;
}

function normalizeUrl(raw: string): string {
  return String(raw || "").trim().toLowerCase().replace(/\/+$/, "");
}
