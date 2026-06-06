<script lang="ts" setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { Toast, VLoading } from "@halo-dev/components";
import EmptyState from "../common/EmptyState.vue";
import type { AstraHubSettings, PlanetLinkItem } from "../../types";
import type { SaveSettingsOptions } from "../../composables/useConfigMap";
import { usePlanetLinksLocal } from "../../composables/usePlanetLinksLocal";
import { HERO_MASCOT_DATA_URI } from "../../data/heroMascot";
import {
  createFriendInvitation,
  fetchFriendInvitationLinkGroups,
  removeFriendRelation
} from "../../composables/useFriendInvitations";
import { lookupAstraHubSiteByUrl } from "../../composables/useAstraHubSiteLookup";
import type {
  HubRealtimeEvent,
  HubSiteRelationUpdatedPayload
} from "../../composables/useFriendInvitationRealtime";
import type { FriendInvitationItem, LinkGroupOption } from "../../types";

const props = defineProps<{
  settings: AstraHubSettings;
  activeFilter?: "all" | "mutual" | "following" | "pendingBack" | "favorites";
  searchQuery?: string;
  persistSettings?: (options?: SaveSettingsOptions) => Promise<boolean>;
  // 父组件 AstraHubView 持有 WS 单例，把事件通过 prop 透传过来。
  // 本组件按事件类型就地翻面卡片状态，避免重复连 WS，也不依赖 Hub 物化表 5s tick 的延迟。
  realtimeEvent?: HubRealtimeEvent<unknown> | null;
}>();

// 浮层最多渲染的条目数，超出用"还有 N 个…"省略，避免站点/标签上千条时全量渲染卡顿。
const OVERFLOW_POPOVER_MAX = 20;

const DEFAULT_AVATAR_DATA_URI = `data:image/svg+xml;charset=UTF-8,${encodeURIComponent(`<svg viewBox="0 0 1024 1024" xmlns="http://www.w3.org/2000/svg"><path d="M512 512m-512 0a512 512 0 1 0 1024 0 512 512 0 1 0-1024 0Z" fill="#1A4066"/><path d="M675.623007 719.427534H348.369612a169.86709 169.86709 0 0 0-169.859709 169.867089v11.026784a511.431685 511.431685 0 0 0 666.965432 0v-11.026784a169.86709 169.86709 0 0 0-169.852328-169.867089zM786.783912 461.892345a273.602998 273.602998 0 0 1-74.323771 187.912931H311.539859a274.776532 274.776532 0 1 1 475.244053-187.912931z" fill="#CBD5D8"/><path d="M727.738215 477.731354a215.125616 215.125616 0 0 1-48.631512 136.484128H344.9302A215.716073 215.716073 0 1 1 727.738215 477.731354z" fill="#0E243A"/><path d="M755.342079 684.612714a34.726251 34.726251 0 0 1-10.332997 24.629437 35.737408 35.737408 0 0 1-24.97633 10.185383H303.959867a35.110048 35.110048 0 0 1-35.375753-34.81482 34.549113 34.549113 0 0 1 10.406804-24.629436 35.641459 35.641459 0 0 1 24.97633-10.178002h416.072885a35.043621 35.043621 0 0 1 35.301946 34.807438z" fill="#AD382B"/><path d="M398.624881 487.761741l-51.664985-0.915208a218.779069 218.779069 0 0 1 1.476143-22.28237l51.288568 6.192418a188.222921 188.222921 0 0 0-1.099726 17.00516zM403.437105 451.699582L353.536111 438.244544a149.090385 149.090385 0 0 1 102.673086-106.666052l11.978896 50.26265-5.993138-25.131325 6.214559 25.094421a97.587776 97.587776 0 0 0-64.972409 69.895344z" fill="#CBD5D8"/><path d="M383.58299 780.554591m15.02713 0l226.77238 0q15.02713 0 15.02713 15.02713l0 119.973476q0 15.02713-15.02713 15.02713l-226.77238 0q-15.02713 0-15.02713-15.02713l0-119.973476q0-15.02713 15.02713-15.02713Z" fill="#F7F7F7"/><path d="M449.92083 855.572149m-36.822372 0a36.822373 36.822373 0 1 0 73.644745 0 36.822373 36.822373 0 1 0-73.644745 0Z" fill="#D8D8D8"/><path d="M449.92083 855.572149m-22.511172 0a22.511172 22.511172 0 1 0 45.022344 0 22.511172 22.511172 0 1 0-45.022344 0Z" fill="#C6817B"/></svg>`)}`;

const {
  loading,
  loadingMore,
  error,
  items,
  visibleItems,
  hasMore,
  fetchLinks,
  loadMore,
  markOutboxActive
} = usePlanetLinksLocal();

let favSaveTimer: ReturnType<typeof setTimeout> | null = null;

function isFavorite(item: PlanetLinkItem): boolean {
  return props.settings.favorites.pinnedSiteUrls.includes(item.url);
}

function toggleFavorite(item: PlanetLinkItem) {
  const urls = props.settings.favorites.pinnedSiteUrls;
  const idx = urls.indexOf(item.url);
  if (idx >= 0) {
    urls.splice(idx, 1);
  } else {
    urls.push(item.url);
  }
  debounceSaveFavorites();
}

function debounceSaveFavorites() {
  if (favSaveTimer) clearTimeout(favSaveTimer);
  favSaveTimer = setTimeout(() => {
    props.persistSettings?.({ silentSuccess: true });
  }, 800);
}

const orderedItems = computed(() => {
  // 顺序完全由服务端统一流（relationRankOf 关系阶段置顶 + 游标分页）决定，
  // 前端不再本地重排，只把"收藏"作为唯一的展示置顶偏好叠加在服务端顺序之上。
  // 这样关系卡片首屏即在最前，无需等全部加载完，从根上消除"滑到底才排到前面"。
  return items.value
    .map((item, index) => ({
      item,
      index,
      favRank: isFavorite(item) ? 0 : 1
    }))
    .sort((left, right) => {
      if (left.favRank !== right.favRank) {
        return left.favRank - right.favRank;
      }
      return left.index - right.index;
    })
    .map(({ item }) => item);
});

type RelationFilter = "all" | "mutual" | "following" | "pendingBack" | "favorites";

const relationFilter = computed<RelationFilter>(() => props.activeFilter || "all");

function relationKindOf(item: PlanetLinkItem) {
  return String(item.relationKind || "").trim().toLowerCase();
}

// relationStatusOf 读取服务端权威关系状态；旧数据缺失时退回 relationKind 推导，
// 保证筛选与标签口径一致（都以 relationStatus 为准）。
function relationStatusOf(item: PlanetLinkItem) {
  const status = String(item.relationStatus || "").trim();
  if (status) return status;
  const kind = relationKindOf(item);
  if (kind === "mutual") return "mutual";
  if (kind === "one_way_out") return "following";
  if (kind === "one_way_in") return "follower";
  if (item.outboxInvitationActive) return "invite_sent";
  return "none";
}

function isInvitablePending(item: PlanetLinkItem) {
  return canInvite(item) && !item.outboxInvitationActive;
}

const filteredItems = computed(() => {
  const keyword = String(props.searchQuery || "").trim().toLowerCase();
  return orderedItems.value.filter((item) => {
    if (isSelfLink(item)) {
      return relationFilter.value === "all";
    }
    if (relationFilter.value === "favorites") {
      if (!isFavorite(item)) return false;
    } else if (relationFilter.value !== "all") {
      const status = relationStatusOf(item);
      if (relationFilter.value === "mutual" && status !== "mutual") {
        return false;
      }
      if (relationFilter.value === "following" && status !== "following") {
        return false;
      }
      if (relationFilter.value === "pendingBack" && !isInvitablePending(item)) {
        return false;
      }
    }
    if (keyword) {
      const haystack = [
        item.title,
        item.description,
        item.url,
        displayHost(item.url)
      ]
        .map((value) => String(value || "").toLowerCase())
        .join(" ");
      if (!haystack.includes(keyword)) {
        return false;
      }
    }
    return true;
  });
});

const renderItems = computed(() => filteredItems.value.slice(0, visibleItems.value.length));

const invitingTargets = ref<string[]>([]);
const inviteDialogVisible = ref(false);
const inviteTarget = ref<PlanetLinkItem | null>(null);
const inviteMessage = ref("");
const inviteLinkGroupName = ref("");
const inviteLinkGroups = ref<LinkGroupOption[]>([]);
const inviteGroupDropdownOpen = ref(false);

// 解除友链关系（"删除"按钮）相关状态。
// removingTargets 按 targetSiteId 去重避免重复点击；removeDialog* 控制确认弹窗。
const removingTargets = ref<string[]>([]);
const removeDialogVisible = ref(false);
const removeTarget = ref<PlanetLinkItem | null>(null);
const removeReason = ref("");

const SIMPLE_STATUS_MAP = {
  relation: {
    self: "本站",
    mutual: "互相关注",
    oneWayOut: "我已关注",
    oneWayIn: "他已关注",
    invitable: "可发起邀请",
    sentInvite: "已邀",
    none: "没有关系",
    unknown: "暂未接入"
  }
} as const;

async function reload(options?: { silent?: boolean }) {
  await fetchLinks(options);
}

function primarySourceSite(item: PlanetLinkItem) {
  const names = sourceSiteNames(item);
  return names[0] || "-";
}

function sourceSiteNames(item: PlanetLinkItem) {
  return (item.sourceSites || [])
    .map((site) => String(site.name || "").trim())
    .filter(Boolean);
}

function visibleSourceSiteNames(item: PlanetLinkItem) {
  return sourceSiteNames(item).slice(0, 1);
}

function sourceSiteOverflow(item: PlanetLinkItem) {
  const count = sourceSiteNames(item).length;
  return count > 1 ? count - 1 : 0;
}

function sourceSiteTooltip(item: PlanetLinkItem) {
  return sourceSiteNames(item).join("、");
}

function displayHost(rawUrl: string) {
  const value = String(rawUrl || "").trim();
  if (!value) {
    return "-";
  }
  try {
    return new URL(value).host || value;
  } catch {
    return value;
  }
}

function formatUpdatedAt(value: string) {
  const raw = String(value || "").trim();
  if (!raw) {
    return "-";
  }
  const date = new Date(raw);
  if (Number.isNaN(date.getTime())) {
    return raw;
  }
  return date.toLocaleString("zh-CN", {
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit"
  });
}

function visibleTags(item: PlanetLinkItem) {
  return (item.tags || []).slice(0, 1);
}

function tagOverflow(item: PlanetLinkItem) {
  return Math.max((item.tags || []).length - 1, 0);
}

function tagTooltip(item: PlanetLinkItem) {
  return (item.tags || []).join("、");
}

function currentSiteId() {
  return String(props.settings.credentials?.siteId || "").trim();
}

function comparableSiteRoot(rawUrl: string) {
  const value = String(rawUrl || "").trim();
  if (!value) {
    return "";
  }
  try {
    const parsed = new URL(value);
    return `${parsed.protocol}//${parsed.host}`.replace(/\/+$/, "").toLowerCase();
  } catch {
    return normalizeComparableUrl(value);
  }
}

function sameComparableSiteUrl(leftRawUrl: string, rightRawUrl: string) {
  const left = normalizeComparableUrl(leftRawUrl);
  const right = normalizeComparableUrl(rightRawUrl);
  if (left && right && left === right) {
    return true;
  }
  const leftRoot = comparableSiteRoot(leftRawUrl);
  const rightRoot = comparableSiteRoot(rightRawUrl);
  return Boolean(leftRoot) && Boolean(rightRoot) && leftRoot === rightRoot;
}

function isSelfLink(item: PlanetLinkItem) {
  const selfSiteId = currentSiteId();
  const targetSiteId = String(item.targetSiteId || "").trim();
  if (selfSiteId && targetSiteId && selfSiteId === targetSiteId) {
    return true;
  }
  return sameComparableSiteUrl(props.settings.connection.siteUrl, item.url);
}

function normalizeComparableUrl(rawUrl: string) {
  return String(rawUrl || "").trim().replace(/\/+$/, "").toLowerCase();
}

function hasLocalLink(item: PlanetLinkItem) {
  const kind = relationKindOf(item);
  return kind === "mutual" || kind === "one_way_out";
}

function invitationStateText(invitationState: string) {
  switch (String(invitationState || "").trim()) {
    case "self_site":
      return "本站链接";
    case "site_not_found":
      return "尚未注册";
    case "site_id_missing":
      return "缺少编号";
    case "credential_missing":
      return "凭据缺失";
    case "site_inactive":
      return "尚未激活";
    case "contact_email_missing":
      return "缺少邮箱";
    case "invalid_site_url":
      return "地址无效";
    default:
      return "不可邀请";
  }
}

function invitationStateTone(invitationState: string) {
  switch (String(invitationState || "").trim()) {
    case "self_site":
      return "self";
    case "site_not_found":
    case "site_id_missing":
      return "unregistered";
    case "credential_missing":
      return "credential";
    case "site_inactive":
      return "inactive";
    case "contact_email_missing":
      return "warning";
    case "invalid_site_url":
      return "warning";
    default:
      return "muted";
  }
}

function relationSummary(item: PlanetLinkItem) {
  // 权威关系状态完全由服务端 relationStatus 给定，前端只做「枚举 → 文案/色调」映射，
  // 绝不再用 targetRegistered / relationKind 自行推断（历史 bug：未接入插件的已关注
  // 站点被错标成"暂未接入"）。relationStatus 与服务端排序档位同源，标签与位置永远一致。
  const status = String(item.relationStatus || "").trim();
  switch (status) {
    case "self":
      return { text: SIMPLE_STATUS_MAP.relation.self, tone: "self" };
    case "mutual":
      return { text: SIMPLE_STATUS_MAP.relation.mutual, tone: "mutual" };
    case "following":
      return { text: SIMPLE_STATUS_MAP.relation.oneWayOut, tone: "one-way-out" };
    case "follower":
      return { text: SIMPLE_STATUS_MAP.relation.oneWayIn, tone: "one-way-in" };
    case "invite_sent":
      return { text: SIMPLE_STATUS_MAP.relation.sentInvite, tone: "pending" };
    case "invitable":
      return { text: SIMPLE_STATUS_MAP.relation.invitable, tone: "muted" };
    case "none":
      // 服务端明确判定无关系：已接入的显示"没有关系"，未接入的显示"暂未接入"。
      return item.targetRegistered
        ? { text: SIMPLE_STATUS_MAP.relation.none, tone: "muted" }
        : { text: SIMPLE_STATUS_MAP.relation.unknown, tone: "muted" };
  }

  // 兼容兜底：服务端未返回 relationStatus（旧版本）时，退回基于 relationKind 的判定，
  // 但已修正优先级——先认关系，再认是否接入，避免已关注站点被误标"暂未接入"。
  const relationKind = relationKindOf(item);
  if (relationKind === "mutual") {
    return { text: SIMPLE_STATUS_MAP.relation.mutual, tone: "mutual" };
  }
  if (relationKind === "one_way_out") {
    return { text: SIMPLE_STATUS_MAP.relation.oneWayOut, tone: "one-way-out" };
  }
  if (relationKind === "one_way_in") {
    return { text: SIMPLE_STATUS_MAP.relation.oneWayIn, tone: "one-way-in" };
  }
  if (item.outboxInvitationActive) {
    return { text: SIMPLE_STATUS_MAP.relation.sentInvite, tone: "pending" };
  }
  if (!item.targetRegistered) {
    return { text: SIMPLE_STATUS_MAP.relation.unknown, tone: "muted" };
  }
  return { text: SIMPLE_STATUS_MAP.relation.none, tone: "muted" };
}

function hasActiveOutboxInvitation(item: PlanetLinkItem) {
  return Boolean(item.outboxInvitationActive);
}

function canInvite(item: PlanetLinkItem) {
  return (
    !isSelfLink(item) &&
    Boolean(item.targetRegistered) &&
    Boolean(item.targetSupportsInvitation) &&
    !hasLocalLink(item) &&
    !hasActiveOutboxInvitation(item) &&
    Boolean(String(item.url || "").trim())
  );
}

function isInviting(item: PlanetLinkItem) {
  const targetUrl = normalizeComparableUrl(item.url);
  return invitingTargets.value.includes(targetUrl);
}

function inviteButtonText(item: PlanetLinkItem) {
  if (isSelfLink(item)) {
    return "本站链接";
  }
  const invitationState = String(item.targetInvitationState || "").trim();
  if (!item.targetRegistered) {
    return invitationStateText(invitationState);
  }
  if (!item.targetSupportsInvitation) {
    return invitationStateText(invitationState);
  }
  if (isInviting(item)) {
    return "正在邀请";
  }
  if (hasLocalLink(item)) {
    return "已加";
  }
  if (hasActiveOutboxInvitation(item)) {
    return "已邀";
  }
  if (!canInvite(item)) {
    return invitationStateText(invitationState);
  }
  return "邀请";
}

function inviteButtonTone(item: PlanetLinkItem) {
  if (isSelfLink(item)) {
    return "self";
  }
  const invitationState = String(item.targetInvitationState || "").trim();
  if (!item.targetRegistered) {
    return invitationStateTone(invitationState);
  }
  if (!item.targetSupportsInvitation) {
    return invitationStateTone(invitationState);
  }
  if (isInviting(item)) {
    return "loading";
  }
  if (hasLocalLink(item)) {
    return "linked";
  }
  if (hasActiveOutboxInvitation(item)) {
    return "pending";
  }
  if (!canInvite(item)) {
    return invitationStateTone(invitationState);
  }
  return "action";
}

async function inviteLink(item: PlanetLinkItem) {
  try {
    inviteLinkGroups.value = await fetchFriendInvitationLinkGroups();
  } catch (e) {
    Toast.error(e instanceof Error ? e.message : "读取友链分组失败");
    return;
  }
  inviteLinkGroupName.value = "";
  inviteGroupDropdownOpen.value = false;
  inviteTarget.value = item;
  inviteMessage.value = "";
  inviteDialogVisible.value = true;
}

function closeInviteDialog() {
  inviteDialogVisible.value = false;
  inviteTarget.value = null;
  inviteMessage.value = "";
  inviteLinkGroupName.value = "";
  inviteGroupDropdownOpen.value = false;
}

function toggleInviteGroupDropdown() {
  inviteGroupDropdownOpen.value = !inviteGroupDropdownOpen.value;
}

function selectInviteGroup(groupName: string) {
  inviteLinkGroupName.value = groupName;
  inviteGroupDropdownOpen.value = false;
}

function selectedInviteGroupLabel() {
  if (!inviteLinkGroupName.value) {
    return "不预设分组";
  }
  return inviteLinkGroups.value.find((group) => group.name === inviteLinkGroupName.value)?.displayName || "不预设分组";
}

async function submitInvite() {
  const item = inviteTarget.value;
  if (!item) {
    return;
  }
  const targetUrl = normalizeComparableUrl(item.url);
  if (!targetUrl) {
    Toast.warning("当前友链没有可邀请的目标地址");
    return;
  }
  if (isSelfLink(item)) {
    Toast.warning("不能邀请当前站点自己");
    return;
  }
  if (invitingTargets.value.includes(targetUrl)) {
    return;
  }

  invitingTargets.value = [...invitingTargets.value, targetUrl];
  try {
    const lookup = await lookupAstraHubSiteByUrl(String(item.url || "").trim());
    if (!lookup.registeredByPlugin) {
      Toast.warning(lookup.invitationMessage || invitationStateText(lookup.invitationState));
      return;
    }

    const siteId = String(lookup.siteId || "").trim();
    if (!siteId) {
      Toast.warning("该站点未生成 AstraHub 站点标识，当前不能邀请");
      return;
    }

    if (!lookup.supportsInvitation) {
      Toast.warning(lookup.invitationMessage || invitationStateText(lookup.invitationState));
      return;
    }

    await createFriendInvitation(siteId, inviteMessage.value.trim(), inviteLinkGroupName.value.trim());
    markOutboxActive(item.url);
    closeInviteDialog();
    Toast.success("已向该站点发起 AstraHub 邀请");
  } catch (e) {
    Toast.error(e instanceof Error ? e.message : "友链邀请失败");
  } finally {
    invitingTargets.value = invitingTargets.value.filter((url) => url !== targetUrl);
  }
}

// 是否允许对该卡片显示"删除"按钮。
// 与按钮显示"已加"对齐：对方已接入插件、可以接受邀请、当前已成边（mutual 或 single-direction following+registered）。
// 注意：following 但对方未接入（registered=false）这种情形按钮文案是"尚未注册"，
// 不会显示"已加"，自然也不显示删除按钮——这与 inviteButtonText 的判定保持同步。
function canRemoveRelation(item: PlanetLinkItem) {
  if (isSelfLink(item)) {
    return false;
  }
  if (!item.targetRegistered || !item.targetSupportsInvitation) {
    return false;
  }
  if (!hasLocalLink(item)) {
    return false;
  }
  if (isInviting(item)) {
    return false;
  }
  return true;
}

function isRemoving(item: PlanetLinkItem) {
  const id = String(item.targetSiteId || "").trim();
  return id !== "" && removingTargets.value.includes(id);
}

function openRemoveDialog(item: PlanetLinkItem) {
  if (!canRemoveRelation(item)) {
    return;
  }
  removeTarget.value = item;
  removeReason.value = "";
  removeDialogVisible.value = true;
}

function closeRemoveDialog() {
  if (removeTarget.value && isRemoving(removeTarget.value)) {
    return;
  }
  removeDialogVisible.value = false;
  removeTarget.value = null;
  removeReason.value = "";
}

async function submitRemove() {
  const item = removeTarget.value;
  if (!item) return;
  const peerSiteId = String(item.targetSiteId || "").trim();
  if (!peerSiteId) {
    Toast.warning("缺少对端站点编号，无法解除");
    return;
  }
  if (removingTargets.value.includes(peerSiteId)) {
    return;
  }

  removingTargets.value = [...removingTargets.value, peerSiteId];
  try {
    const result = await removeFriendRelation(peerSiteId, removeReason.value);
    if (result.removed) {
      Toast.success("已解除友链关系");
    } else {
      // Hub 边本就不存在（理论上 hasLocalLink 校验过不该进入这里，但接口幂等返回成功）。
      Toast.success("关系已解除（无变化）");
    }
    // 成功后直接重置弹窗状态——不能走 closeRemoveDialog()，因为它守卫 isRemoving 仍为 true 时
    // 会拒绝关闭（removingTargets 要等 finally 才清空）。
    removeDialogVisible.value = false;
    removeTarget.value = null;
    removeReason.value = "";
    // 不做乐观本地翻面：服务端 /v1/planet/links 是关系状态唯一真相源。
    // 这次 reload 与稍后到达的 WS 事件触发的 reload 都被 scheduleSilentReload 的 500ms
    // 防抖合并成一次实际 HTTP 拉取，UI 状态以服务端结果为准。
    scheduleSilentReload();
  } catch (e) {
    Toast.error(e instanceof Error ? e.message : "解除友链关系失败");
  } finally {
    removingTargets.value = removingTargets.value.filter((id) => id !== peerSiteId);
  }
}

const isScrolling = ref(false);
let scrollEndTimer: ReturnType<typeof setTimeout> | null = null;
const scrollWrapEl = ref<HTMLElement | null>(null);
const heroEl = ref<HTMLElement | null>(null);

function onScroll(event: Event) {
  isScrolling.value = true;
  if (scrollEndTimer) {
    clearTimeout(scrollEndTimer);
  }
  scrollEndTimer = setTimeout(() => {
    isScrolling.value = false;
    scrollEndTimer = null;
  }, 150);

  const target = event.target as HTMLElement | null;
  if (!target) {
    return;
  }

  if (loading.value || loadingMore.value || !hasMore.value) {
    return;
  }
  const distanceToBottom = target.scrollHeight - target.scrollTop - target.clientHeight;
  if (distanceToBottom < 80) {
    void loadMore();
  }
}

function handleDocumentClick(event: MouseEvent) {
  const target = event.target;
  if (!(target instanceof Element)) {
    return;
  }
  if (!target.closest(".invite-selectbox")) {
    inviteGroupDropdownOpen.value = false;
  }
}

onMounted(() => {
  void reload();
  document.addEventListener("click", handleDocumentClick);
});

// 父组件 AstraHubView 单例 WS 收到事件后，原样透传到这里。
// 处理策略（精确版）：所有事件统一走 silent reload，让服务端 /v1/planet/links 是
// 唯一真相源。不再做任何"乐观本地翻面"——历史经验表明本地推断与 Hub 实际边表
// 状态可能不一致（典型例子：从 mutual 删除时本地推断 none，但服务端权威是 follower），
// 一旦不一致用户就能看到 UI 闪烁。
// 多个 WS 事件 + HTTP 成功回调可能在同一窗口内连发，scheduleSilentReload 用 500ms
// 防抖把它们合并成一次实际拉取，避免打抖动 Hub。
let realtimeReloadTimer: ReturnType<typeof setTimeout> | null = null;
function scheduleSilentReload() {
  if (realtimeReloadTimer) {
    clearTimeout(realtimeReloadTimer);
  }
  realtimeReloadTimer = setTimeout(() => {
    realtimeReloadTimer = null;
    void reload({ silent: true });
  }, 500);
}

function applyRealtimeEvent(event: HubRealtimeEvent<unknown>) {
  const myId = String(props.settings.credentials.siteId || "").trim();
  if (!myId) {
    return;
  }
  // friend_relation_removed: 解除关系，actor + peer 双方都需要刷新卡片。
  if (event.type === "friend_relation_removed") {
    const data = (event.data || {}) as {
      actorSiteId?: string;
      peerSiteId?: string;
    };
    const actorId = String(data.actorSiteId || "").trim();
    const peerId = String(data.peerSiteId || "").trim();
    if (actorId !== myId && peerId !== myId) {
      return;
    }
    scheduleSilentReload();
    return;
  }
  // site_relation_updated: 涵盖审核通过 + 解除关系两类触发。无论 trigger 是什么，
  // 只要本站点被波及（sourceSiteId 或 impactedSiteIds 包含 myId）就刷新。
  if (event.type === "site_relation_updated") {
    const data = (event.data || {}) as HubSiteRelationUpdatedPayload;
    const sourceId = String(data.sourceSiteId || "").trim();
    const impacted = Array.isArray(data.impactedSiteIds) ? data.impactedSiteIds : [];
    let touched = sourceId === myId;
    if (!touched) {
      for (const raw of impacted) {
        if (String(raw || "").trim() === myId) {
          touched = true;
          break;
        }
      }
    }
    if (!touched) {
      return;
    }
    scheduleSilentReload();
    return;
  }
  // 友链邀请类事件：data 是 FriendInvitationItem，按 fromSite/toSite 路由。
  // 仅当本站点是双方之一时才刷新，避免无关事件触发不必要的拉取。
  const invitation = event.data as FriendInvitationItem | undefined;
  if (!invitation) return;
  const fromSiteId = String(invitation.fromSite?.siteId || "").trim();
  const toSiteId = String(invitation.toSite?.siteId || "").trim();
  if (fromSiteId !== myId && toSiteId !== myId) {
    return;
  }
  scheduleSilentReload();
}

watch(
  () => props.realtimeEvent,
  (event) => {
    if (event) {
      applyRealtimeEvent(event);
    }
  }
);

onBeforeUnmount(() => {
  document.removeEventListener("click", handleDocumentClick);
  if (scrollEndTimer) {
    clearTimeout(scrollEndTimer);
    scrollEndTimer = null;
  }
  if (realtimeReloadTimer) {
    clearTimeout(realtimeReloadTimer);
    realtimeReloadTimer = null;
  }
});

watch(
  () => props.settings.connection.hubBaseUrl,
  () => {
    void reload();
  }
);

watch(
  () => props.settings.credentials.siteId,
  () => {
    void reload();
  }
);

watch(
  () => props.activeFilter,
  () => {
    const wrap = scrollWrapEl.value;
    const hero = heroEl.value;
    if (!wrap || !hero) {
      return;
    }
    wrap.scrollTo({ top: hero.offsetHeight, behavior: "smooth" });
  }
);
</script>

<template>
  <div class="planet-links-wrap">
    <!-- 主内容区域 -->
    <div class="pl-main-content">
      <div ref="scrollWrapEl" class="planet-links-table-wrap" :class="{ 'is-scrolling': isScrolling }" @scroll="onScroll">
        <div v-if="loading" class="loading-overlay">
          <div class="uv-loader"><span class="uv-loader-text">loading</span><span class="uv-load"></span></div>
        </div>

        <!-- Hero 首屏：欢迎语，仿 AstraHub 探索页 -->
        <section ref="heroEl" class="planet-hero">
          <img
            :src="HERO_MASCOT_DATA_URI"
            alt=""
            aria-hidden="true"
            class="planet-hero-mascot"
            draggable="false"
          />
          <div class="planet-hero-scroll-hint" aria-hidden="true">
            <span class="planet-hero-scroll-text">下滑探索星球</span>
            <svg viewBox="0 0 24 24" fill="none" class="planet-hero-scroll-icon">
              <path d="M6 9l6 6 6-6" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" />
            </svg>
          </div>
          <div class="planet-hero-inner">
            <h2 class="planet-hero-title">
              探索
              <br />
              <span class="planet-hero-title-accent">创作者星系</span>
            </h2>
            <p class="planet-hero-desc">
              欢迎来到 AstraHub 生态系统的中央情报枢纽。在这里，每一个博客都是一颗发光恒星，
              通过通用数字协议互联，共同构建辽阔的知识银河。
            </p>
          </div>
        </section>

        <div class="planet-links-table">
          <div v-if="error" class="planet-links-empty">
            <EmptyState :text="error" hint="请检查 Hub 地址配置或网络连接" />
          </div>

          <div v-else-if="!loading && !renderItems.length" class="planet-links-empty">
            <EmptyState text="暂无可展示的友链数据" hint="接入星链并完成同步后，友链数据将在此展示" />
          </div>

            <template v-for="item in renderItems" :key="item.url">
              <!-- 本站卡片：独特样式，不展示状态信息和邀请按钮 -->
              <div v-if="isSelfLink(item)" class="planet-links-self-card">
                <span class="self-card-corner">本站</span>
                <div class="self-card-inner">
                  <div class="self-card-avatar">
                    <img
                      v-if="item.logo"
                      :src="item.logo"
                      alt=""
                      class="self-card-logo"
                      @error="($event.target as HTMLImageElement).src = DEFAULT_AVATAR_DATA_URI"
                    />
                    <img v-else :src="DEFAULT_AVATAR_DATA_URI" alt="" class="self-card-logo" />
                  </div>
                  <div class="self-card-main">
                    <div class="self-card-title">{{ item.title || item.url }}</div>
                    <div class="self-card-desc">{{ item.description || "暂无简介" }}</div>
                  </div>
                  <div class="self-card-meta-block">
                    <span class="self-card-meta-item self-card-meta-host">
                      <svg viewBox="0 0 24 24" fill="none" class="self-card-meta-icon">
                        <circle cx="12" cy="12" r="9" stroke="currentColor" stroke-width="1.6" />
                        <path d="M3 12h18M12 3c3 3.5 3 14.5 0 18M12 3c-3 3.5-3 14.5 0 18" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" />
                      </svg>
                      {{ displayHost(item.url) }}
                    </span>
                    <span class="self-card-meta-item">
                      <svg viewBox="0 0 24 24" fill="none" class="self-card-meta-icon">
                        <circle cx="12" cy="12" r="9" stroke="currentColor" stroke-width="1.6" />
                        <path d="M12 7v5l3 2" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" />
                      </svg>
                      {{ formatUpdatedAt(item.updatedAt) }}
                    </span>
                  </div>
                </div>
              </div>

              <!-- 普通友链行 -->
              <div
                v-else
                class="planet-links-row"
                :class="`planet-links-row--${inviteButtonTone(item)}`"
              >
                <div class="link-main">
                  <img v-if="item.logo" :src="item.logo" alt="" class="link-logo" @error="($event.target as HTMLImageElement).src = DEFAULT_AVATAR_DATA_URI" />
                  <div v-else class="link-logo link-logo-fallback">
                    <img :src="DEFAULT_AVATAR_DATA_URI" alt="" class="link-logo" />
                  </div>
                  <div class="link-main-text">
                    <div class="link-title-row">
                      <div class="link-title">{{ item.title || item.url }}</div>
                    </div>
                    <div class="link-desc">{{ item.description || "暂无简介" }}</div>
                  </div>
                </div>

                <div class="site-name">
                  <span
                    v-for="name in visibleSourceSiteNames(item)"
                    :key="name"
                    class="source-site-pill"
                  >{{ name }}</span>
                  <span
                    v-if="sourceSiteOverflow(item) > 0"
                    class="overflow-badge overflow-badge--site"
                    tabindex="0"
                    :aria-label="`所属站点：${sourceSiteTooltip(item)}`"
                  >
                    +{{ sourceSiteOverflow(item) }}
                    <span class="overflow-popover">
                      <span
                        v-for="sourceName in sourceSiteNames(item).slice(0, OVERFLOW_POPOVER_MAX)"
                        :key="sourceName"
                        class="overflow-popover-entry"
                      >
                        {{ sourceName }}
                      </span>
                      <span
                        v-if="sourceSiteNames(item).length > OVERFLOW_POPOVER_MAX"
                        class="overflow-popover-entry overflow-popover-more"
                      >
                        还有 {{ sourceSiteNames(item).length - OVERFLOW_POPOVER_MAX }} 个…
                      </span>
                    </span>
                  </span>
                  <span v-if="!sourceSiteNames(item).length" class="tag-empty">-</span>
                </div>

                <div class="relation-text">
                  <span class="relation-pill relation-summary-pill" :class="relationSummary(item).tone">
                    {{ relationSummary(item).text }}
                  </span>
                </div>

                <div class="link-url">{{ displayHost(item.url) }}</div>

                <div class="rss-time">{{ formatUpdatedAt(item.updatedAt) }}</div>

                <div class="tag-list">
                  <span
                    v-for="tag in visibleTags(item)"
                    :key="tag"
                    class="tag-pill"
                  >
                    {{ tag }}
                  </span>
                  <span
                    v-if="tagOverflow(item) > 0"
                    class="overflow-badge overflow-badge--tag"
                    tabindex="0"
                    :aria-label="`标签：${tagTooltip(item)}`"
                  >
                    +{{ tagOverflow(item) }}
                    <span class="overflow-popover">
                      <span
                        v-for="tag in (item.tags || []).slice(0, OVERFLOW_POPOVER_MAX)"
                        :key="tag"
                        class="overflow-popover-entry"
                      >
                        {{ tag }}
                      </span>
                      <span
                        v-if="(item.tags || []).length > OVERFLOW_POPOVER_MAX"
                        class="overflow-popover-entry overflow-popover-more"
                      >
                        还有 {{ (item.tags || []).length - OVERFLOW_POPOVER_MAX }} 个…
                      </span>
                    </span>
                  </span>
                  <span v-if="!(item.tags || []).length" class="tag-empty">-</span>
                </div>

                <div class="action-cell">
                  <button
                    class="fav-btn"
                    :class="{ 'fav-btn--active': isFavorite(item) }"
                    :title="isFavorite(item) ? '取消收藏' : '收藏'"
                    @click.stop="toggleFavorite(item)"
                  >
                    <svg viewBox="0 0 1024 1024" width="20" height="20" xmlns="http://www.w3.org/2000/svg"><path d="M959.24224 401.32608c-7.36256-24.57088-28.78976-43.22304-63.6928-55.43936a15.36 15.36 0 0 0-1.3824-0.43008l-189.54752-51.4304c-41.09824-62.68416-82.25792-125.34272-123.40736-188.01664-0.17408-0.24576-0.54272-0.8192-0.7168-1.06496-19.79904-27.68896-44.48256-42.94656-69.46304-42.94656-18.06336 0-44.544 7.78752-68.38784 45.2096L337.08032 278.69184c-47.27296 14.32576-94.54592 28.71808-141.84448 43.10016L126.1056 342.81984C93.91104 353.28 73.56928 370.2016 65.64864 393.12896c-8.30976 24.0896-2.46784 52.1728 17.87904 85.87264 0.49152 0.82432 1.03424 1.60768 1.61792 2.34496a203168.54272 203168.54272 0 0 1 124.05248 157.1584c-2.11968 75.5712-4.2752 151.2192-6.47168 226.87232-3.15392 36.21888 2.08896 61.74208 16 78.0288 10.54208 12.3392 25.20064 18.59072 43.5712 18.59072 14.07488 0 30.7712-3.67616 53.2992-11.86816l182.52288-74.2912a116757.43744 116757.43744 0 0 0 207.60064 77.18912c13.62432 4.38784 26.23488 6.60992 37.49888 6.60992 25.68192 0 69.27872-11.81184 72.76544-90.96192a21.24288 21.24288 0 0 0-0.02048-2.42176c-3.81952-67.45088-7.68-134.74304-11.53536-202.08128l-0.0512-0.88576 134.43584-180.78208c20.74112-29.82912 27.61728-57.15968 20.4288-81.1776z" :fill="isFavorite(item) ? '#FCD62C' : '#d1d5db'" /><path d="M905.0112 455.04l-139.04896 186.95168a23.63904 23.63904 0 0 0-4.55168 15.43168l0.5376 9.51808c3.82976 66.90304 7.67488 133.7856 11.4688 200.8064-2.35008 46.63296-20.44416 46.63296-30.20288 46.63296-7.08096 0-15.54432-1.56672-24.31488-4.36736a142424.4224 142424.4224 0 0 1-214.05696-79.63136 20.1984 20.1984 0 0 0-14.63808 0.21504l-189.06624 76.9792c-16.9984 6.1696-29.70624 9.1648-38.8352 9.1648-8.85248 0-11.20256-2.74944-12.08832-3.77344-2.45248-2.87744-7.85408-12.90752-5.05344-44.02688 0.03584-0.4864 0.07168-0.96768 0.08704-1.4592 2.2784-78.78656 4.52608-157.55264 6.7328-236.23168a23.6032 23.6032 0 0 0-4.97152-15.21664 163892.8896 163892.8896 0 0 0-128.37376-162.66752c-11.77088-19.80928-16.2816-35.2256-13.02016-44.63616 3.82976-11.10528 20.0192-18.432 32.5632-22.50752L206.9504 365.312c49.8432-15.16544 99.62496-30.3104 149.43232-45.40928a21.36064 21.36064 0 0 0 11.96032-9.3696l109.7216-178.2272c7.27552-11.42272 18.91328-25.05216 32.98304-25.05216 11.37664 0 24.01792 8.91904 35.29216 24.6784 42.65472 64.95744 85.31456 129.90464 127.91296 194.87744a21.28896 21.28896 0 0 0 12.1856 8.98048L882.90816 389.12c20.21888 7.17312 32.91648 16.37376 35.78368 25.93792 2.7392 9.14432-2.2784 23.54176-13.68064 39.98208z" :fill="isFavorite(item) ? '#FCD62C' : '#d1d5db'" /></svg>
                  </button>
                  <button
                    class="invite-btn"
                    :class="`invite-btn--${inviteButtonTone(item)}`"
                    :disabled="!canInvite(item) || isInviting(item)"
                    @click="inviteLink(item)"
                  >
                    {{ inviteButtonText(item) }}
                  </button>
                  <button
                    v-if="canRemoveRelation(item) || isRemoving(item)"
                    class="invite-btn invite-btn--remove"
                    :disabled="isRemoving(item)"
                    @click.stop="openRemoveDialog(item)"
                  >
                    {{ isRemoving(item) ? "处理中..." : "删除" }}
                  </button>
                </div>
              </div>
            </template>

            <div v-if="hasMore" class="planet-links-more">
              上滑继续加载更多友链
            </div>
          </div>
      </div>

    <div v-if="inviteDialogVisible" class="invite-mask" @click.self="closeInviteDialog">
      <div class="invite-dialog">
        <div class="invite-dialog-title">发起站点邀请</div>
        <div class="invite-dialog-sub">
          {{ inviteTarget?.title || inviteTarget?.url || "-" }}
        </div>

        <div class="invite-field">
          <label class="invite-label">友链分组</label>
          <div class="invite-selectbox">
            <button type="button" class="invite-select-trigger" @click.stop="toggleInviteGroupDropdown">
              <span>{{ selectedInviteGroupLabel() }}</span>
              <svg
                viewBox="0 0 20 20"
                fill="none"
                class="invite-select-arrow"
                :class="{ open: inviteGroupDropdownOpen }"
              >
                <path d="M5 7.5L10 12.5L15 7.5" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" />
              </svg>
            </button>
            <div v-if="inviteGroupDropdownOpen" class="invite-select-menu">
              <button
                type="button"
                class="invite-select-option"
                :class="{ active: !inviteLinkGroupName }"
                @click.stop="selectInviteGroup('')"
              >
                <span>不预设分组</span>
                <span class="invite-option-hint">手动</span>
              </button>
              <button
                v-for="group in inviteLinkGroups"
                :key="group.name"
                type="button"
                class="invite-select-option"
                :class="{ active: inviteLinkGroupName === group.name }"
                @click.stop="selectInviteGroup(group.name)"
              >
                <span>{{ group.displayName }}</span>
              </button>
            </div>
          </div>
          <div class="invite-field-hint">不预设时，由对方在审核时手动选择分组。</div>
        </div>

        <div class="invite-field">
          <label class="invite-label">留言</label>
          <textarea
            v-model="inviteMessage"
            class="invite-textarea"
            placeholder="可选，给对方留一句话"
          ></textarea>
        </div>

        <div class="invite-actions">
          <button class="invite-btn-ghost" :disabled="inviteTarget ? isInviting(inviteTarget) : false" @click="closeInviteDialog">
            取消
          </button>
          <button class="invite-btn-primary" :disabled="inviteTarget ? isInviting(inviteTarget) : true" @click="submitInvite">
            {{ inviteTarget && isInviting(inviteTarget) ? "发送中..." : "确认发送" }}
          </button>
        </div>
      </div>
    </div>

    <div v-if="removeDialogVisible" class="invite-mask" @click.self="closeRemoveDialog">
      <div class="invite-dialog">
        <div class="invite-dialog-title">解除友链关系</div>
        <div class="invite-dialog-sub">
          {{ removeTarget?.title || removeTarget?.url || "-" }}
        </div>

        <p class="remove-warning">
          解除后将立即删除本站对该友链的本地链接，并通过邮件通知对方。此操作不可恢复。
        </p>

        <div class="invite-field">
          <label class="invite-label">解除原因（可选）</label>
          <textarea
            v-model="removeReason"
            class="invite-textarea"
            placeholder="留空则邮件中不展示原因"
            maxlength="300"
          ></textarea>
        </div>

        <div class="invite-actions">
          <button
            class="invite-btn-ghost"
            :disabled="removeTarget ? isRemoving(removeTarget) : false"
            @click="closeRemoveDialog"
          >
            取消
          </button>
          <button
            class="invite-btn-primary invite-btn-primary--danger"
            :disabled="removeTarget ? isRemoving(removeTarget) : true"
            @click="submitRemove"
          >
            {{ removeTarget && isRemoving(removeTarget) ? "解除中..." : "确认解除" }}
          </button>
        </div>
      </div>
    </div>

    </div><!-- /pl-main-content -->
  </div>
</template>

<style scoped>
.planet-links-wrap{flex:1;display:flex;flex-direction:column;padding:16px 20px;overflow-y:auto;position:relative}
.pl-main-content{flex:1;display:flex;flex-direction:column;min-height:0;position:relative}
.planet-links-table-wrap{position:relative;flex:1;min-height:0;overflow:auto;scrollbar-width:none;-ms-overflow-style:none}
.planet-links-table-wrap::-webkit-scrollbar{display:none}
.planet-links-table-wrap.is-scrolling .planet-links-row,.planet-links-table-wrap.is-scrolling .invite-btn{pointer-events:none}
.planet-hero{position:relative;display:flex;align-items:center;justify-content:center;min-height:100%;padding:40px 24px 56px;box-sizing:border-box;margin-bottom:16px}
.planet-hero-mascot{position:absolute;right:calc(50% + 240px);left:auto;top:50%;transform:translateY(-54%);z-index:0;width:clamp(160px,18vw,300px);height:auto;object-fit:contain;opacity:.92;pointer-events:none;user-select:none;-webkit-user-drag:none}
@media (max-width:1100px){.planet-hero-mascot{display:none}}
.planet-hero-inner{position:relative;z-index:1;max-width:680px;width:100%;display:flex;flex-direction:column;align-items:center;text-align:center;gap:24px}
.planet-hero-title{margin:0;font-family:"STHupo","华文琥珀","Chalkboard SE","Yuanti SC","STYuanti","华文圆体","Comic Sans MS","Microsoft YaHei UI","PingFang SC",system-ui,sans-serif;font-size:clamp(40px,7vw,76px);line-height:1.2;font-weight:normal;letter-spacing:.04em;color:#0f172a;padding-bottom:8px;-webkit-font-smoothing:antialiased;-moz-osx-font-smoothing:grayscale}
.planet-hero-title-accent{display:inline-block;background:linear-gradient(90deg,#38bdf8 0%,#a78bfa 50%,#f472b6 100%);-webkit-background-clip:text;background-clip:text;color:transparent;padding:0 .08em .12em .08em}
.planet-hero-desc{margin:0;max-width:560px;font-size:15px;line-height:1.75;color:#64748b;letter-spacing:.01em}
.planet-hero-scroll-hint{position:absolute;bottom:16px;left:50%;transform:translateX(-50%);display:flex;flex-direction:column;align-items:center;gap:4px;color:#64748b;font-size:12px;font-weight:600;z-index:2}
.planet-hero-scroll-icon{width:18px;height:18px;color:#64748b;animation:planet-hero-bounce 2.4s ease-in-out infinite}
@keyframes planet-hero-bounce{0%,100%{transform:translateY(0)}50%{transform:translateY(4px)}}
.planet-links-table{display:flex;flex-direction:column;min-width:0;min-height:100%;gap:8px;padding:4px 0}
.planet-links-self-card{position:relative;display:flex;align-items:stretch;padding:10px 14px;border-radius:20px;background:linear-gradient(135deg,#0f766e 0%,#0e7490 55%,#1e3a8a 100%);color:#f0fdfa;border:1px solid rgba(45,212,191,.55);box-shadow:0 2px 8px rgba(0,0,0,.06),inset 0 1px 0 rgba(255,255,255,.18);overflow:hidden}
.planet-links-self-card::before{content:"";position:absolute;inset:0;background:radial-gradient(circle at 85% 0%,rgba(153,246,228,.35),transparent 45%),radial-gradient(circle at 0% 100%,rgba(59,130,246,.28),transparent 50%);pointer-events:none}
.self-card-corner{position:absolute;top:50%;right:14px;transform:translateY(-50%);display:inline-flex;align-items:center;height:22px;padding:0 10px;border-radius:999px;background:rgba(255,255,255,.22);color:#ecfeff;font-size:11px;font-weight:800;letter-spacing:.08em;border:1px solid rgba(255,255,255,.35);z-index:1}
.self-card-inner{position:relative;z-index:1;display:flex;align-items:center;gap:12px;width:100%;min-width:0;padding-right:64px}
.self-card-avatar{flex-shrink:0;width:34px;height:34px;display:flex;align-items:center;justify-content:center}
.self-card-logo{width:100%;height:100%;border-radius:10px;object-fit:cover;background:transparent}
.self-card-main{flex:1;min-width:0;display:flex;flex-direction:column;justify-content:center;gap:1px}
.self-card-title{font-size:13px;font-weight:700;color:#f0fdfa;line-height:1.3;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.self-card-desc{font-size:12px;color:rgba(236,254,255,.78);line-height:1.45;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.self-card-meta-block{flex-shrink:0;display:flex;flex-direction:row;align-items:center;justify-content:center;gap:14px}
.self-card-meta-item{display:inline-flex;align-items:center;gap:4px;font-size:11.5px;font-weight:600;color:rgba(236,254,255,.88);white-space:nowrap}
.self-card-meta-host{color:#ccfbf1}
.self-card-meta-icon{width:12px;height:12px;color:rgba(204,251,241,.9)}
.planet-links-row{display:grid;grid-template-columns:minmax(250px,2fr) minmax(136px,1fr) 110px minmax(150px,1fr) minmax(140px,1fr) minmax(140px,1fr) 120px;gap:12px;align-items:center;padding:10px 14px;border-radius:20px;background:transparent;border:1px solid rgba(0,0,0,.05);box-shadow:0 2px 8px rgba(0,0,0,.03)}
.planet-links-row:hover{box-shadow:0 4px 14px rgba(0,0,0,.06)}
.planet-links-row--action{border-color:rgba(147,197,253,.4)}
.planet-links-row--linked{background:linear-gradient(90deg,rgba(236,253,245,.92),rgba(255,255,255,.88) 52%,rgba(209,250,229,.58))}
.planet-links-row--pending{background:linear-gradient(90deg,rgba(255,247,237,.92),rgba(255,255,255,.88) 52%,rgba(255,237,213,.58))}
.planet-links-row--unregistered{background:linear-gradient(90deg,rgba(248,250,252,.96),rgba(255,255,255,.9) 52%,rgba(226,232,240,.56))}
.planet-links-row--credential{background:linear-gradient(90deg,rgba(245,243,255,.94),rgba(255,255,255,.9) 52%,rgba(237,233,254,.62))}
.planet-links-row--inactive{background:linear-gradient(90deg,rgba(254,242,242,.94),rgba(255,255,255,.9) 52%,rgba(254,226,226,.62))}
.planet-links-row--warning{background:linear-gradient(90deg,rgba(255,251,235,.94),rgba(255,255,255,.9) 52%,rgba(254,243,199,.62))}
.planet-links-row--loading{background:linear-gradient(90deg,rgba(239,246,255,.94),rgba(255,255,255,.9) 52%,rgba(219,234,254,.62))}
.planet-links-row--muted{background:linear-gradient(90deg,rgba(255,241,242,.94),rgba(255,255,255,.9) 52%,rgba(255,228,230,.62))}
.planet-links-row:last-child{border-bottom:none}
.link-main{display:flex;align-items:center;gap:12px;min-width:0}
.link-logo{width:34px;height:34px;border-radius:10px;object-fit:cover;background:#f1f5f9;border:1px solid #e2e8f0;flex-shrink:0}
.link-logo-fallback{display:flex;align-items:center;justify-content:center}
.link-main-text{min-width:0}
.link-title-row{display:flex;align-items:center;gap:8px;flex-wrap:wrap;min-width:0}
.link-title{font-size:13px;font-weight:600;color:#0f172a;line-height:1.3;word-break:break-word}
.link-desc{margin-top:1px;font-size:12px;color:#64748b;line-height:1.45;display:-webkit-box;-webkit-line-clamp:1;-webkit-box-orient:vertical;overflow:hidden}
.site-name{display:flex;align-items:center;justify-content:center;flex-wrap:wrap;gap:5px;font-size:12px;color:#334155;font-weight:600;line-height:1.4;min-width:0;overflow:visible;text-align:center}
.source-site-pill{display:inline-flex;align-items:center;height:20px;padding:0 7px;border-radius:999px;background:linear-gradient(135deg,#f0fdf4,#dcfce7);color:#166534;border:1px solid rgba(134,239,172,.6);font-size:11px;font-weight:600;white-space:nowrap}
.overflow-badge{position:relative;display:inline-flex;align-items:center;justify-content:center;height:20px;padding:0 7px;border-radius:999px;font-size:11px;font-weight:800;cursor:help;outline:none;flex-shrink:0;letter-spacing:.01em}
.overflow-badge--site{background:linear-gradient(135deg,#ccfbf1,#99f6e4);color:#0f766e;border:1px solid rgba(45,212,191,.5)}
.overflow-badge--tag{background:linear-gradient(135deg,#dbeafe,#bfdbfe);color:#1d4ed8;border:1px solid rgba(147,197,253,.6)}
.overflow-popover{position:absolute;left:50%;bottom:calc(100% + 10px);z-index:8;width:max-content;max-width:360px;max-height:240px;overflow-y:auto;transform:translate(-50%,6px);padding:9px 11px;border-radius:12px;background:rgba(15,23,42,.96);box-shadow:0 18px 42px rgba(15,23,42,.24);color:#f8fafc;opacity:0;pointer-events:none;transition:opacity .16s ease,transform .16s ease;display:flex;flex-wrap:wrap;gap:5px}
.overflow-popover::after{content:"";position:absolute;left:50%;bottom:-5px;width:10px;height:10px;transform:translateX(-50%) rotate(45deg);background:rgba(15,23,42,.96)}
.overflow-popover-entry{display:inline-flex;align-items:center;height:20px;padding:0 8px;border-radius:999px;background:rgba(255,255,255,.12);white-space:nowrap;font-size:11px;font-weight:600}
.overflow-popover-more{background:transparent;color:rgba(248,250,252,.6);font-weight:500}
.overflow-badge:hover .overflow-popover,.overflow-badge:focus-visible .overflow-popover{opacity:1;transform:translate(-50%,0)}
.relation-pill{display:inline-flex;align-items:center;height:22px;padding:0 8px;border-radius:999px;font-size:11px;font-weight:700}
.relation-text{display:flex;align-items:center;justify-content:center;min-width:0;text-align:center}
.relation-pill.ok{background:#ecfdf5;color:#047857}
.relation-pill.muted{background:#f8fafc;color:#64748b}
.relation-summary-pill.mutual{background:#ecfdf5;color:#047857}
.relation-summary-pill.one-way-out{background:#fff7ed;color:#c2410c}
.relation-summary-pill.one-way-in{background:#eff6ff;color:#2563eb}
.relation-summary-pill.pending{background:#fff7ed;color:#c2410c}
.link-url{display:flex;align-items:center;justify-content:center;min-width:0;font-size:12px;color:#334155;line-height:1.45;word-break:break-all;text-align:center}
.rss-time{display:flex;align-items:center;justify-content:center;font-size:12px;color:#475569;line-height:1.45;word-break:break-word;text-align:center}
.tag-list{display:flex;flex-wrap:wrap;align-items:center;justify-content:center;gap:6px;overflow:visible;text-align:center}
.tag-pill{display:inline-flex;align-items:center;height:22px;padding:0 8px;border-radius:999px;background:#eff6ff;color:#2563eb;font-size:11px;font-weight:600}
.tag-empty{font-size:12px;color:#94a3b8}
.action-cell{display:flex;align-items:center;justify-content:center;gap:6px}
.fav-btn{display:inline-flex;align-items:center;justify-content:center;width:34px;height:34px;border:none;border-radius:50%;background:transparent;color:#d1d5db;cursor:pointer;transition:color .15s,transform .15s}
.fav-btn:hover{color:#FCD62C;transform:scale(1.2)}
.fav-btn--active{color:#FCD62C;filter:drop-shadow(0 0 4px rgba(252,214,44,.5))}
.invite-btn{display:inline-flex;align-items:center;justify-content:center;outline:none;padding:5px 12px;border:2px dashed #64748b;border-radius:15px;background-color:#f1f5f9;color:#64748b;font-size:11px;font-weight:600;cursor:pointer;transition:box-shadow .2s ease,filter .2s ease;box-shadow:0 0 0 3px #f1f5f9,1.5px 1.5px 3px 1px rgba(0,0,0,.15);white-space:nowrap}
.invite-btn:hover{box-shadow:0 0 0 3px #f1f5f9,2px 5px 0 0 currentColor;filter:brightness(.96)}
.invite-btn:active{box-shadow:0 0 0 3px #f1f5f9,0 0 0 0 currentColor;filter:brightness(.92)}
.invite-btn--action{border-color:#075985;color:#075985;background-color:#f0f9ff;box-shadow:0 0 0 3px #f0f9ff,1.5px 1.5px 3px 1px rgba(0,0,0,.15)}
.invite-btn--action:hover{box-shadow:0 0 0 3px #f0f9ff,2px 5px 0 0 #075985}
.invite-btn--linked{border-color:#047857;color:#047857;background-color:#ecfdf5;box-shadow:0 0 0 3px #ecfdf5,1.5px 1.5px 3px 1px rgba(0,0,0,.15)}
.invite-btn--linked:hover{box-shadow:0 0 0 3px #ecfdf5,2px 5px 0 0 #047857}
.invite-btn--pending{border-color:#fdba74;background:linear-gradient(180deg,#fff7ed 0%,#ffedd5 100%);color:#c2410c}
.invite-btn--unregistered{border-color:#cbd5e1;background:linear-gradient(180deg,#f8fafc 0%,#e2e8f0 100%);color:#475569}
.invite-btn--credential{border-color:#c4b5fd;background:linear-gradient(180deg,#f5f3ff 0%,#ede9fe 100%);color:#6d28d9}
.invite-btn--inactive{border-color:#fca5a5;background:linear-gradient(180deg,#fef2f2 0%,#fee2e2 100%);color:#b91c1c}
.invite-btn--warning{border-color:#fcd34d;background:linear-gradient(180deg,#fffbeb 0%,#fef3c7 100%);color:#b45309}
.invite-btn--loading{border-color:#93c5fd;background:linear-gradient(180deg,#eff6ff 0%,#dbeafe 100%);color:#1d4ed8}
.invite-btn--muted{border-color:#fda4af;background:linear-gradient(180deg,#fff1f2 0%,#ffe4e6 100%);color:#be123c}
.invite-btn--remove{border-color:#fca5a5;background:linear-gradient(180deg,#fef2f2 0%,#fee2e2 100%);color:#b91c1c}
.invite-btn--remove:hover{box-shadow:0 0 0 3px #fef2f2,2px 5px 0 0 #b91c1c}
.invite-btn:disabled{cursor:not-allowed;opacity:1}
.planet-links-empty{flex:1;display:flex;align-items:center;justify-content:center;min-height:200px}
.planet-links-more{padding:14px 16px;text-align:center;font-size:12px;color:#94a3b8;background:#fff}
.loading-overlay{position:absolute;inset:0;display:flex;align-items:center;justify-content:center;background:#ffffff;z-index:5}
.uv-loader{width:80px;height:50px;position:relative}
.uv-loader-text{position:absolute;top:0;padding:0;margin:0;color:#C8B6FF;animation:uvtext 3.5s ease both infinite;font-size:.8rem;letter-spacing:1px}
.uv-load{background-color:#9A79FF;border-radius:50px;display:block;height:16px;width:16px;bottom:0;position:absolute;transform:translateX(64px);animation:uvloading 3.5s ease both infinite}
.uv-load::before{position:absolute;content:"";width:100%;height:100%;background-color:#D1C2FF;border-radius:inherit;animation:uvloading2 3.5s ease both infinite}
@keyframes uvtext{0%{letter-spacing:1px;transform:translateX(0px)}40%{letter-spacing:2px;transform:translateX(26px)}80%{letter-spacing:1px;transform:translateX(32px)}90%{letter-spacing:2px;transform:translateX(0px)}100%{letter-spacing:1px;transform:translateX(0px)}}
@keyframes uvloading{0%{width:16px;transform:translateX(0px)}40%{width:100%;transform:translateX(0px)}80%{width:16px;transform:translateX(64px)}90%{width:100%;transform:translateX(0px)}100%{width:16px;transform:translateX(0px)}}
@keyframes uvloading2{0%{transform:translateX(0px);width:16px}40%{transform:translateX(0%);width:80%}80%{width:100%;transform:translateX(0px)}90%{width:80%;transform:translateX(15px)}100%{transform:translateX(0px);width:16px}}

.sp-header-btn{display:inline-flex;align-items:center;gap:5px;padding:6px 14px;border:1px solid rgba(125,211,252,.72);border-radius:9px;font-size:12px;font-weight:600;background:linear-gradient(180deg,#f0f9ff 0%,#e0f2fe 100%);color:#0369a1;cursor:pointer;transition:all .15s;white-space:nowrap}
.sp-header-btn:disabled{opacity:.5;cursor:not-allowed}
.invite-mask{position:fixed;inset:0;background:rgba(15,23,42,.32);display:flex;align-items:center;justify-content:center;z-index:120;padding:20px}
.invite-dialog{width:100%;max-width:420px;background:#ffffff;border:2px dashed #64748b;border-radius:18px;box-shadow:0 0 0 3px #ffffff,4px 6px 0 0 rgba(15,23,42,.12);padding:20px}
.invite-dialog-title{font-size:15px;font-weight:700;color:#0f172a}
.invite-dialog-sub{margin-top:4px;font-size:12px;color:#64748b;line-height:1.6}
.invite-field{margin-top:14px}
.invite-label{display:block;margin-bottom:6px;font-size:12px;font-weight:600;color:#475569}
.invite-selectbox{position:relative}
.invite-select-trigger{width:100%;height:42px;padding:0 14px;border:1px solid #dbe3ee;border-radius:12px;background:linear-gradient(180deg,#ffffff 0%,#f8fbff 100%);display:flex;align-items:center;justify-content:space-between;gap:12px;font-size:13px;font-weight:600;color:#0f172a;cursor:pointer;box-shadow:0 8px 20px rgba(148,163,184,.12);transition:border-color .15s,box-shadow .15s}
.invite-select-trigger:hover{border-color:#bae6fd;box-shadow:0 10px 24px rgba(14,116,144,.12)}
.invite-select-arrow{width:16px;height:16px;color:#0369a1;transition:transform .16s}
.invite-select-arrow.open{transform:rotate(180deg)}
.invite-select-menu{position:absolute;left:0;right:0;top:calc(100% + 8px);padding:8px;background:#fff;border:1px solid #dbe3ee;border-radius:14px;box-shadow:0 18px 40px rgba(15,23,42,.14);display:flex;flex-direction:column;gap:4px;z-index:4}
.invite-select-option{width:100%;padding:10px 12px;border:none;border-radius:10px;background:transparent;display:flex;align-items:center;justify-content:space-between;gap:10px;font-size:13px;font-weight:600;color:#334155;cursor:pointer;transition:background .15s,color .15s}
.invite-select-option:hover{background:#f8fafc;color:#0f172a}
.invite-select-option.active{background:#eff6ff;color:#0369a1}
.invite-option-hint{font-size:11px;font-weight:700;color:#94a3b8}
.invite-field-hint{margin-top:8px;font-size:12px;line-height:1.5;color:#94a3b8}
.invite-textarea{width:100%;min-height:100px;padding:10px 12px;border:1px solid #dbe3ee;border-radius:10px;background:#fff;font-size:13px;color:#0f172a;box-sizing:border-box;resize:vertical}
.invite-actions{display:flex;justify-content:flex-end;gap:14px;margin-top:18px}
.invite-btn-ghost,.invite-btn-primary{display:inline-flex;align-items:center;justify-content:center;outline:none;padding:5px 14px;border:2px dashed #64748b;border-radius:15px;background-color:#f1f5f9;color:#64748b;font-size:11px;font-weight:600;cursor:pointer;transition:transform .2s ease-out;box-shadow:0 0 0 3px #f1f5f9,1.5px 1.5px 3px 1px rgba(0,0,0,.15)}
.invite-btn-ghost:hover,.invite-btn-primary:hover{transform:translateY(-4px) translateX(-2px);box-shadow:0 0 0 3px #f1f5f9,2px 5px 0 0 currentColor}
.invite-btn-ghost:active,.invite-btn-primary:active{transform:translateY(1px) translateX(1px);box-shadow:0 0 0 3px #f1f5f9,0 0 0 0 currentColor}
.invite-btn-primary{border-color:#075985;color:#075985;background-color:#f0f9ff;box-shadow:0 0 0 3px #f0f9ff,1.5px 1.5px 3px 1px rgba(0,0,0,.15)}
.invite-btn-primary--danger{border-color:#b91c1c;color:#b91c1c;background-color:#fef2f2;box-shadow:0 0 0 3px #fef2f2,1.5px 1.5px 3px 1px rgba(0,0,0,.15)}
.invite-btn-primary--danger:hover{box-shadow:0 0 0 3px #fef2f2,2px 5px 0 0 #b91c1c}
.remove-warning{margin:14px 0 0;padding:10px 12px;border-radius:10px;border:1px solid #fecaca;background:#fff1f2;color:#9f1239;font-size:12px;line-height:1.6}
</style>
