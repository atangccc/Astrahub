export interface ConnectionSettings {
  hubBaseUrl: string;
  registerToken: string;
  siteName: string;
  siteUrl: string;
  siteDescription: string;
  contactEmail: string;
  siteNodeName: string;
  siteNodeAvatar: string;
  siteRssUrl: string;
}

export interface CredentialSettings {
  siteId: string;
  apiKey: string;
  createdAt: string;
}

export interface WidgetSettings {
  enabled: boolean;
}

export interface RealtimeBroadcastSettings {
  enabled: boolean;
}

export interface InvitationSettings {
  allowIncomingInvitations: boolean;
  allowOutgoingInvitations: boolean;
}

export interface FavoritesSettings {
  pinnedSiteUrls: string[];
}

export interface ReadLaterItem {
  url: string;
  title: string;
  summary: string;
  blogTitle: string;
  blogLogo: string;
  publishedAt: string;
  savedAt: string;
}

export interface ReadLaterSettings {
  items: ReadLaterItem[];
}

export interface AstraHubSettings {
  connection: ConnectionSettings;
  credentials: CredentialSettings;
  invitation: InvitationSettings;
  widget: WidgetSettings;
  realtimeBroadcast: RealtimeBroadcastSettings;
  favorites: FavoritesSettings;
  readLater: ReadLaterSettings;
}

export interface CollectedGroup {
  externalId: string;
  name: string;
  priority: number;
  linkNames: string[];
}

export interface CollectedLink {
  externalId: string;
  title: string;
  url: string;
  description: string;
  logo: string;
  priority: number;
  groupExternalIds: string[];
  createdAt: string;
}

export interface GraphSourcePreview {
  platform: string;
  plugin: string;
  pluginVersion: string;
  siteId: string;
  siteName: string;
  siteUrl: string;
  siteRssUrl?: string;
}

export interface GraphGroupPreview {
  externalId: string;
  name: string;
  priority: number;
  meta: Record<string, unknown>;
}

export interface GraphContentPreview {
  externalId: string;
  canonicalUrl: string;
  title: string;
  summary: string;
  author: string;
  publishedAt: string;
  updatedAt: string;
  topics: string[];
  groupExternalIds: string[];
  meta: Record<string, unknown>;
}

export interface PlanetLinkSourceSite {
  name: string;
  url: string;
}

export interface PlanetLinkItem {
  url: string;
  title: string;
  description: string;
  logo?: string;
  tags?: string[];
  updatedAt: string;
  sourceSites: PlanetLinkSourceSite[];
}

export interface PlanetLinksResponse {
  generatedAt: string;
  total: number;
  size?: number;
  hasMore?: boolean;
  nextCursor?: string;
  items: PlanetLinkItem[];
}

export interface PlanetFeedSourceSite {
  name: string;
  url: string;
}

export interface PlanetFeedItem {
  url: string;
  title: string;
  description: string;
  logo?: string;
  tags?: string[];
  mentionCount: number;
  updatedAt: string;
  sourceSites: PlanetFeedSourceSite[];
}

export interface PlanetFeedResponse {
  page: number;
  size: number;
  total: number;
  generatedAt: string;
  items: PlanetFeedItem[];
}

export interface PlanetClusterSample {
  siteName: string;
  siteURL: string;
  title?: string;
  description?: string;
  logo?: string;
}

export interface PlanetClusterItem {
  nodeName: string;
  siteCount: number;
  mentionCount: number;
  uniqueLinkCount: number;
  updatedAt: string;
  sample: PlanetClusterSample;
  tags?: string[];
}

export interface PlanetClustersResponse {
  page: number;
  size: number;
  total: number;
  generatedAt: string;
  items: PlanetClusterItem[];
}

export interface FriendInvitationSiteInfo {
  siteId: string;
  siteName: string;
  siteUrl: string;
  description?: string;
  avatarUrl?: string;
  rssUrl?: string;
}

export interface FriendInvitationItem {
  inviteId: string;
  fromSite: FriendInvitationSiteInfo;
  toSite: FriendInvitationSiteInfo;
  message?: string;
  status: string;
  deliveryStatus: string;
  reviewReason?: string;
  linkGroupName?: string;
  createdAt: string;
  reviewedAt?: string;
  ackedAt?: string;
  lastError?: string;
  retryCount: number;
  updatedAt: string;
}

export interface FriendInvitationsResponse {
  success: boolean;
  generatedAt: string;
  total: number;
  items: FriendInvitationItem[];
}

export interface LinkGroupOption {
  name: string;
  displayName: string;
}

export interface AstraHubSiteLookupResult {
  success: boolean;
  status: number;
  message: string;
  registered: boolean;
  registeredByPlugin?: boolean;
  credentialReady?: boolean;
  siteId?: string;
  siteName?: string;
  siteUrl?: string;
  avatarUrl?: string;
  supportsInvitation?: boolean;
  invitationState?: string;
  invitationMessage?: string;
}

export interface AstraHubSiteRelationIdentity {
  queryUrl?: string;
  normalizedUrl?: string;
  normalizedRootUrl?: string;
  registered: boolean;
  registeredByPlugin?: boolean;
  credentialReady?: boolean;
  siteId?: string;
  siteName?: string;
  siteUrl?: string;
  description?: string;
  rssUrl?: string;
  avatarUrl?: string;
  contactEmail?: string;
  nodeName?: string;
  category?: string;
  nodeAvatar?: string;
  siteStatus?: string;
  supportsInvitation?: boolean;
  invitationState?: string;
  invitationMessage?: string;
  matchedBy?: string;
  lookupError?: string;
}

export interface AstraHubSiteRelationSide {
  known: boolean;
  added: boolean;
  snapshotAt?: string;
  updatedAt?: string;
  reason?: string;
}

export interface AstraHubSiteRelationItem {
  observerSiteId: string;
  observerSiteUrl?: string;
  targetUrl: string;
  targetIdentity: AstraHubSiteRelationIdentity;
  mine: AstraHubSiteRelationSide;
  theirs: AstraHubSiteRelationSide;
  relationKind: string;
}

export interface AstraHubSiteRelationBatchResponse {
  success: boolean;
  status: number;
  message: string;
  items: AstraHubSiteRelationItem[];
}

export interface AstraHubRealtimeTokenResult {
  success: boolean;
  status: number;
  message: string;
  token?: string;
  expiresAt?: string;
}

export interface NewsItem {
  id: string;
  sourceId: string;
  title: string;
  summary: string;
  url: string;
  publishedAt: string;
  blogTitle: string;
  blogUrl: string;
  blogLogo: string;
  nodeName: string;
  tags: string[];
  mentionCount: number;
  sourceSiteCount: number;
}

export interface NewsBrowseResponse {
  generatedAt: string;
  refreshedAt: string;
  indexedBlogs: number;
  indexedFeeds: number;
  indexedItems: number;
  total: number;
  cursor: string;
  nextCursor: string;
  hasMore: boolean;
  refreshing: boolean;
  items: NewsItem[];
  page: number;
  size: number;
}

export interface NewsDiscoverItem {
  sourceId: string;
  blogTitle: string;
  blogUrl: string;
  blogLogo: string;
  nodeName: string;
  feedUrl: string;
  itemCount: number;
  latestPublishedAt: string;
  latestTitle: string;
}

export interface NewsDiscoverResponse {
  generatedAt: string;
  refreshedAt: string;
  indexedBlogs: number;
  indexedItems: number;
  total: number;
  cursor: string;
  nextCursor: string;
  hasMore: boolean;
  refreshing: boolean;
  items: NewsDiscoverItem[];
}

export interface GraphNodeFriendLink {
  id: string;
  sourceSiteId?: string;
  sourceSiteName?: string;
  sourceSiteUrl?: string;
  title: string;
  url: string;
  description?: string;
  logo?: string;
  rssUrl?: string;
  targetSiteId?: string;
  targetRegistered: boolean;
  targetNodeId?: string;
  targetNodeName?: string;
  targetAvatar?: string;
  firstSeenAt?: string;
  lastSeenAt?: string;
}

export interface GraphSiteSummary {
  siteId: string;
  name: string;
  url: string;
  nodeId?: string;
  nodeName?: string;
  avatar?: string;
  status: string;
  trustLevel?: string;
  joinedAt?: string;
  lastActiveAt?: string;
  lastSyncAt?: string;
  latestVersion?: string;
  pushSuccessCount: number;
}

export interface GraphNodeSummary {
  nodeId: string;
  name: string;
  avatar?: string;
  status: string;
  joinedAt?: string;
  lastActiveAt?: string;
  lastSyncAt?: string;
  latestVersion?: string;
  primarySite?: { siteId?: string; name?: string; url?: string; avatar?: string };
}

export interface GraphSiteMini {
  siteId?: string;
  name: string;
  url?: string;
  nodeId?: string;
  nodeName?: string;
  avatar?: string;
  status?: string;
}

export interface GraphNodeMini {
  nodeId: string;
  name: string;
  avatar?: string;
  status?: string;
}

export interface GraphRelationItem {
  relationType: string;
  matchTypes?: string[];
  direction?: string;
  strength?: string;
  weight: number;
  recommendationScore?: number;
  reasonCodes?: string[];
  evidenceCount?: number;
  lastMatchedAt?: string;
  matchedGroups?: string[];
  matchedTopics?: string[];
  sourceSite: GraphSiteMini;
  sourceNode?: GraphNodeMini;
  targetSite: GraphSiteMini;
  targetNode?: GraphNodeMini;
  sharedLinkCount?: number;
  sharedGroupCount?: number;
  sharedTopicCount?: number;
}

export interface GraphSiteDetailResponse {
  generatedAt: string;
  summary: GraphSiteSummary;
  relations?: GraphRelationItem[];
}

export interface GraphNodeDetailResponse {
  generatedAt: string;
  summary: GraphNodeSummary;
  relations?: GraphRelationItem[];
  relatedNodes?: GraphNodeMini[];
  friendLinks: GraphNodeFriendLink[];
}

export const HubPhase = {
  INIT: "INIT",
  STARTING: "STARTING",
  IDLE: "IDLE",
  RUNNING: "RUNNING",
  SUCCESS: "SUCCESS",
  ERROR: "ERROR",
  BUSY: "BUSY",
  STOPPED: "STOPPED",
  UNKNOWN: "UNKNOWN",
} as const;

export type HubPhase = (typeof HubPhase)[keyof typeof HubPhase];

export const HUB_PHASE_TEXT: Readonly<Record<HubPhase, string>> = {
  INIT: "初始化",
  STARTING: "启动中",
  IDLE: "空闲",
  RUNNING: "推送中",
  SUCCESS: "推送成功",
  ERROR: "推送失败",
  BUSY: "任务繁忙",
  STOPPED: "已停止",
  UNKNOWN: "未知状态",
};

export const HUB_PHASE_COLOR: Readonly<Record<HubPhase, string>> = {
  INIT: "#64748b",
  STARTING: "#64748b",
  IDLE: "#0ea5e9",
  RUNNING: "#3b82f6",
  SUCCESS: "#16a34a",
  ERROR: "#dc2626",
  BUSY: "#f97316",
  STOPPED: "#64748b",
  UNKNOWN: "#94a3b8",
};
