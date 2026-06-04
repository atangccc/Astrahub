<script lang="ts" setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { Toast } from "@halo-dev/components";
import EmptyState from "../common/EmptyState.vue";
import type { AstraHubSettings, FriendInvitationItem, LinkGroupOption } from "../../types";
import {
  ackFriendInvitation,
  cancelFriendInvitation,
  deleteFriendInvitation,
  fetchFriendInvitationLinkGroups,
  fetchFriendInvitations,
  reconcileFriendInvitation,
  retryFriendInvitation,
  syncFriendInvitationInbox,
  syncFriendInvitationOutbox,
  reviewFriendInvitation
} from "../../composables/useFriendInvitations";
import { useFriendInvitationRealtime, type HubRealtimeEvent } from "../../composables/useFriendInvitationRealtime";
import {
  shouldRefreshPendingInboxCountAfterManagerAction,
  shouldRefreshPendingInboxCountAfterRealtimeEvent,
  type PendingInboxRefreshCause
} from "../../composables/useFriendInvitationPendingRefresh";

type FriendInvitationTab = "all" | "pending" | "accepted" | "rejected" | "outbox";

const props = defineProps<{
  settings: AstraHubSettings;
  activeTab: FriendInvitationTab;
}>();

const emit = defineEmits<{
  (event: "pending-inbox-count-change"): void;
}>();

const settingsRef = computed(() => props.settings);
const activeTab = computed(() => props.activeTab);

const DEFAULT_AVATAR_DATA_URI = `data:image/svg+xml;charset=UTF-8,${encodeURIComponent(`<svg viewBox="0 0 1024 1024" xmlns="http://www.w3.org/2000/svg"><path d="M512 512m-512 0a512 512 0 1 0 1024 0 512 512 0 1 0-1024 0Z" fill="#1A4066"/><path d="M675.623007 719.427534H348.369612a169.86709 169.86709 0 0 0-169.859709 169.867089v11.026784a511.431685 511.431685 0 0 0 666.965432 0v-11.026784a169.86709 169.86709 0 0 0-169.852328-169.867089zM786.783912 461.892345a273.602998 273.602998 0 0 1-74.323771 187.912931H311.539859a274.776532 274.776532 0 1 1 475.244053-187.912931z" fill="#CBD5D8"/><path d="M727.738215 477.731354a215.125616 215.125616 0 0 1-48.631512 136.484128H344.9302A215.716073 215.716073 0 1 1 727.738215 477.731354z" fill="#0E243A"/><path d="M755.342079 684.612714a34.726251 34.726251 0 0 1-10.332997 24.629437 35.737408 35.737408 0 0 1-24.97633 10.185383H303.959867a35.110048 35.110048 0 0 1-35.375753-34.81482 34.549113 34.549113 0 0 1 10.406804-24.629436 35.641459 35.641459 0 0 1 24.97633-10.178002h416.072885a35.043621 35.043621 0 0 1 35.301946 34.807438z" fill="#AD382B"/><path d="M398.624881 487.761741l-51.664985-0.915208a218.779069 218.779069 0 0 1 1.476143-22.28237l51.288568 6.192418a188.222921 188.222921 0 0 0-1.099726 17.00516zM403.437105 451.699582L353.536111 438.244544a149.090385 149.090385 0 0 1 102.673086-106.666052l11.978896 50.26265-5.993138-25.131325 6.214559 25.094421a97.587776 97.587776 0 0 0-64.972409 69.895344z" fill="#CBD5D8"/><path d="M383.58299 780.554591m15.02713 0l226.77238 0q15.02713 0 15.02713 15.02713l0 119.973476q0 15.02713-15.02713 15.02713l-226.77238 0q-15.02713 0-15.02713-15.02713l0-119.973476q0-15.02713 15.02713-15.02713Z" fill="#F7F7F7"/><path d="M449.92083 855.572149m-36.822372 0a36.822373 36.822373 0 1 0 73.644745 0 36.822373 36.822373 0 1 0-73.644745 0Z" fill="#D8D8D8"/><path d="M449.92083 855.572149m-22.511172 0a22.511172 22.511172 0 1 0 45.022344 0 22.511172 22.511172 0 1 0-45.022344 0Z" fill="#C6817B"/></svg>`)}`;

const loading = ref(false);
const error = ref("");
const items = ref<FriendInvitationItem[]>([]);
const total = ref(0);
const reviewing = ref(false);
const reconcilingInviteIds = ref<string[]>([]);
const retryingInviteIds = ref<string[]>([]);
const deletingInviteIds = ref<string[]>([]);
const cancellingInviteIds = ref<string[]>([]);
const reviewDialogVisible = ref(false);
const confirmDialogVisible = ref(false);
const confirmDialogMessage = ref("");
const confirmDialogCallback = ref<(() => void) | null>(null);
const reviewTarget = ref<FriendInvitationItem | null>(null);
const reviewMode = ref<"approve" | "reject">("approve");
const reviewReason = ref("");
const reviewLinkGroupName = ref("");
const reviewGroupDropdownOpen = ref(false);
const linkGroups = ref<LinkGroupOption[]>([]);

const tabItems: Array<{ id: FriendInvitationTab; label: string }> = [
  { id: "all", label: "全部" },
  { id: "pending", label: "待审核" },
  { id: "accepted", label: "已通过" },
  { id: "rejected", label: "已拒绝" },
  { id: "outbox", label: "发出的" }
];

const currentBox = computed(() => (activeTab.value === "outbox" ? "outbox" : "inbox"));
const currentStatus = computed(() => {
  if (activeTab.value === "outbox" || activeTab.value === "all") {
    return "";
  }
  return activeTab.value;
});

const emptyText = computed(() => {
  switch (activeTab.value) {
    case "pending":
      return "当前没有待审核的友链邀请";
    case "accepted":
      return "当前没有已通过的友链邀请";
    case "rejected":
      return "当前没有已拒绝的友链邀请";
    default:
      return "当前没有发出的友链邀请";
  }
});

const reviewGroupOptions = computed(() => [
  { name: "", displayName: "链接管理默认分组" },
  ...linkGroups.value
]);

const selectedReviewGroupLabel = computed(() => {
  const current = reviewGroupOptions.value.find((group) => group.name === reviewLinkGroupName.value);
  return current?.displayName || "链接管理默认分组";
});

function invitationSortKey(item: FriendInvitationItem) {
  const candidates = [item.updatedAt, item.reviewedAt, item.createdAt];
  for (const raw of candidates) {
    const value = String(raw || "").trim();
    if (!value) {
      continue;
    }
    const time = new Date(value).getTime();
    if (!Number.isNaN(time)) {
      return time;
    }
  }
  return 0;
}

function sortInvitationsDescending(list: FriendInvitationItem[]) {
  return [...list].sort((left, right) => {
    const byTime = invitationSortKey(right) - invitationSortKey(left);
    if (byTime !== 0) {
      return byTime;
    }
    return String(right.inviteId || "").localeCompare(String(left.inviteId || ""));
  });
}

function formatTime(value?: string) {
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

function peerSite(item: FriendInvitationItem) {
  return directionOf(item) === "outbox" ? item.toSite : item.fromSite;
}

function externalLinkHref(value?: string) {
  const raw = String(value || "").trim();
  if (!raw) {
    return "";
  }

  const normalized = /^https?:\/\//i.test(raw) ? raw : `https://${raw}`;
  try {
    const parsed = new URL(normalized);
    return parsed.protocol === "http:" || parsed.protocol === "https:" ? parsed.toString() : "";
  } catch {
    return "";
  }
}

function directionOf(item: FriendInvitationItem): "inbox" | "outbox" {
  return (item as FriendInvitationItem & { __direction: "inbox" | "outbox" }).__direction;
}

function tagDirection(items: FriendInvitationItem[], direction: "inbox" | "outbox") {
  return items.map((raw) => {
    const item = { ...raw } as FriendInvitationItem & { __direction: "inbox" | "outbox" };
    item.__direction = direction;
    return item;
  });
}

function isMyInbox(item: FriendInvitationItem) {
  return directionOf(item) === "inbox";
}

function isMyOutbox(item: FriendInvitationItem) {
  return directionOf(item) === "outbox";
}

function canReview(item: FriendInvitationItem) {
  return activeTab.value === "pending" && item.status === "pending";
}

function currentSiteId() {
  return String(props.settings.credentials.siteId || "").trim();
}

function isReconciling(inviteId: string) {
  return reconcilingInviteIds.value.includes(inviteId);
}

function isRetrying(inviteId: string) {
  return retryingInviteIds.value.includes(inviteId);
}

function isDeleting(inviteId: string) {
  return deletingInviteIds.value.includes(inviteId);
}

function isCancelling(inviteId: string) {
  return cancellingInviteIds.value.includes(inviteId);
}

function markReconciling(inviteId: string, value: boolean) {
  if (value) {
    if (!reconcilingInviteIds.value.includes(inviteId)) {
      reconcilingInviteIds.value = [...reconcilingInviteIds.value, inviteId];
    }
    return;
  }
  reconcilingInviteIds.value = reconcilingInviteIds.value.filter((id) => id !== inviteId);
}

function markRetrying(inviteId: string, value: boolean) {
  if (value) {
    if (!retryingInviteIds.value.includes(inviteId)) {
      retryingInviteIds.value = [...retryingInviteIds.value, inviteId];
    }
    return;
  }
  retryingInviteIds.value = retryingInviteIds.value.filter((id) => id !== inviteId);
}

function markDeleting(inviteId: string, value: boolean) {
  if (value) {
    if (!deletingInviteIds.value.includes(inviteId)) {
      deletingInviteIds.value = [...deletingInviteIds.value, inviteId];
    }
    return;
  }
  deletingInviteIds.value = deletingInviteIds.value.filter((id) => id !== inviteId);
}

function markCancelling(inviteId: string, value: boolean) {
  if (value) {
    if (!cancellingInviteIds.value.includes(inviteId)) {
      cancellingInviteIds.value = [...cancellingInviteIds.value, inviteId];
    }
    return;
  }
  cancellingInviteIds.value = cancellingInviteIds.value.filter((id) => id !== inviteId);
}

function canRetry(item: FriendInvitationItem) {
  return activeTab.value === "outbox" && item.status === "accepted" && item.deliveryStatus !== "acknowledged";
}

function canCancel(item: FriendInvitationItem) {
  return activeTab.value === "outbox" && item.status === "pending";
}

function removeLocalItem(inviteId: string) {
  const before = items.value.length;
  items.value = items.value.filter((item) => item.inviteId !== inviteId);
  if (items.value.length < before) {
    total.value = Math.max(0, total.value - 1);
  }
}

function replaceLocalItem(nextItem: FriendInvitationItem) {
  items.value = sortInvitationsDescending(
    items.value.map((item) => item.inviteId === nextItem.inviteId ? nextItem : item)
  );
}

function upsertLocalItem(nextItem: FriendInvitationItem) {
  const exists = items.value.some((item) => item.inviteId === nextItem.inviteId);
  if (exists) {
    replaceLocalItem(nextItem);
    return;
  }
  items.value = sortInvitationsDescending([...items.value, nextItem]);
  total.value += 1;
}

function notifyPendingInboxCountChanged(cause: PendingInboxRefreshCause) {
  if (shouldRefreshPendingInboxCountAfterManagerAction(cause)) {
    emit("pending-inbox-count-change");
  }
}

function statusText(item: FriendInvitationItem) {
  const direction = directionOf(item);
  const tab = activeTab.value;

  if (tab === "outbox") {
    if (item.status === "cancelled") return "我：已撤回";
    if (item.status === "expired") return "已过期";
    return "我：已发送";
  }

  if (tab === "all") {
    // 全部 tab 走单独的两行 statusLines，这里 fallback 用"方向 · 状态"作纯文本
    return `${statusDirectionLine(item)} · ${statusResultLine(item)}`;
  }

  if (item.status === "pending") return "待审核";
  if (item.status === "accepted") return "已通过";
  if (item.status === "rejected") return "已拒绝";
  if (item.status === "cancelled") return "已撤回";
  if (item.status === "expired") return "已过期";
  return "待审核";
}

// 全部 tab 下第一行：谁发起的邀请
function statusDirectionLine(item: FriendInvitationItem) {
  return directionOf(item) === "outbox" ? "我：邀请" : "他：邀请";
}

// 全部 tab 下第二行：当前状态
function statusResultLine(item: FriendInvitationItem) {
  const direction = directionOf(item);
  if (direction === "outbox") {
    if (item.status === "cancelled") return "我：已撤回";
    if (item.status === "expired") return "已过期";
    return "我：已发送";
  }
  if (item.status === "pending") return "待审核";
  if (item.status === "accepted") return "我：已通过";
  if (item.status === "rejected") return "我：已拒绝";
  if (item.status === "cancelled") return "已撤回";
  if (item.status === "expired") return "已过期";
  return "待审核";
}

function statusClass(item: FriendInvitationItem) {
  if (item.status === "accepted") {
    return "ok";
  }
  if (item.status === "rejected") {
    return "warn";
  }
  if (item.status === "cancelled" || item.status === "expired") {
    return "muted";
  }
  return "pending";
}

function reviewStatusInfoLines(item: FriendInvitationItem): Array<{ label: "我" | "他"; text: string }> {
  const direction = directionOf(item);
  const message = String(item.message || "").trim();     // 发起方的留言
  const reviewReason = String(item.reviewReason || "").trim(); // 审核方的留言
  const lines: Array<{ label: "我" | "他"; text: string }> = [];

  if (direction === "outbox") {
    // 我是发起方
    if (message) lines.push({ label: "我", text: message });
    if (reviewReason) lines.push({ label: "他", text: reviewReason });
  } else {
    // 我是收件方
    if (message) lines.push({ label: "他", text: message });
    if (reviewReason) lines.push({ label: "我", text: reviewReason });
  }
  return lines;
}

function deliveryStatusText(status?: string) {
  switch (String(status || "").trim().toLowerCase()) {
    case "acknowledged":
      return "已确认";
    case "delivered":
      return "已送达";
    case "failed":
      return "投递失败";
    case "pending":
      return "投递中";
    default:
      return "-";
  }
}

function shouldShowDeliveryStatus(item: FriendInvitationItem) {
  return String(item.deliveryStatus || "").trim() !== "";
}

function isDeliverySuccess(status?: string) {
  const s = String(status || "").trim().toLowerCase();
  return s === "acknowledged" || s === "delivered";
}

function deliveryIconClass(status?: string) {
  return isDeliverySuccess(status) ? "delivery-ok" : "delivery-fail";
}

function matchesCurrentTab(item: FriendInvitationItem) {
  const direction = directionOf(item);

  if (activeTab.value === "all") {
    return true;
  }
  if (activeTab.value === "outbox") {
    return direction === "outbox";
  }
  if (direction !== "inbox") {
    return false;
  }
  if (activeTab.value === "pending") {
    return item.status === "pending";
  }
  if (activeTab.value === "accepted") {
    return item.status === "accepted";
  }
  if (activeTab.value === "rejected") {
    return item.status === "rejected";
  }
  return false;
}

function applyRealtimeInvitationEvent(event: HubRealtimeEvent<FriendInvitationItem>) {
  const rawInvitation = event.data;
  if (!rawInvitation) {
    return;
  }
  const myId = currentSiteId();
  const fromMe = Boolean(myId) && String(rawInvitation.fromSite?.siteId || "").trim() === myId;
  const invitation = {
    ...rawInvitation,
    __direction: fromMe ? "outbox" : "inbox"
  } as FriendInvitationItem & { __direction: "inbox" | "outbox" };
  const exists = items.value.some((item) => item.inviteId === invitation.inviteId);
  const visible = matchesCurrentTab(invitation);
  if (visible) {
    upsertLocalItem(invitation);
    return;
  }
  if (exists) {
    removeLocalItem(invitation.inviteId);
  }
  if (shouldRefreshPendingInboxCountAfterRealtimeEvent(event.type)) {
    emit("pending-inbox-count-change");
  }
}

async function reload() {
  const hubBaseUrl = String(props.settings.connection.hubBaseUrl || "").trim();
  const siteId = String(props.settings.credentials.siteId || "").trim();
  if (!hubBaseUrl) {
    error.value = "请先配置有效的 Hub 地址";
    items.value = [];
    total.value = 0;
    return;
  }
  if (!siteId) {
    error.value = "当前站点未注册，暂时无法读取友链邀请";
    items.value = [];
    total.value = 0;
    return;
  }

  loading.value = true;
  error.value = "";
  try {
    if (activeTab.value === "all") {
      await Promise.all([
        syncFriendInvitationInbox().catch(() => undefined),
        syncFriendInvitationOutbox().catch(() => undefined)
      ]);
      const [inboxResp, outboxResp] = await Promise.all([
        fetchFriendInvitations("inbox", ""),
        fetchFriendInvitations("outbox", "")
      ]);
      const inboxTagged = tagDirection(inboxResp.items || [], "inbox");
      const outboxTagged = tagDirection(outboxResp.items || [], "outbox");
      const merged = new Map<string, FriendInvitationItem>();
      // 先放 inbox，再用 outbox 覆盖（同一 inviteId 优先保留 outbox 方向标签）
      for (const item of inboxTagged) {
        const key = String(item.inviteId || "").trim();
        if (key) merged.set(key, item);
      }
      for (const item of outboxTagged) {
        const key = String(item.inviteId || "").trim();
        if (key) merged.set(key, item);
      }
      items.value = sortInvitationsDescending(Array.from(merged.values()));
      total.value = items.value.length;
      void reconcileAcceptedOutboxItems(outboxTagged);
    } else {
      if (currentBox.value === "inbox") {
        await syncFriendInvitationInbox();
      } else {
        await syncFriendInvitationOutbox();
      }
      const response = await fetchFriendInvitations(currentBox.value, currentStatus.value);
      const tagged = tagDirection(response.items || [], currentBox.value);
      items.value = sortInvitationsDescending(tagged);
      total.value = response.total;
      if (activeTab.value === "outbox") {
        void reconcileAcceptedOutboxItems(tagged);
      }
    }
    notifyPendingInboxCountChanged("sync");
  } catch (e) {
    error.value = e instanceof Error ? e.message : "读取友链邀请失败";
    items.value = [];
    total.value = 0;
  } finally {
    loading.value = false;
  }
}

async function reconcileAcceptedOutboxItems(list: FriendInvitationItem[]) {
  const siteId = currentSiteId();
  const candidates = list.filter((item) =>
    item.status === "accepted" &&
    item.deliveryStatus !== "acknowledged" &&
    !isReconciling(item.inviteId)
  );

  for (const item of candidates) {
    markReconciling(item.inviteId, true);
    try {
      await reconcileFriendInvitation(item, siteId);
      await ackFriendInvitation(item.inviteId, "");
    } catch (e) {
      const message = e instanceof Error ? e.message : "本地建链失败";
      try {
        await ackFriendInvitation(item.inviteId, message);
      } catch {
        // ignore ack failure here and let later refresh retry
      }
    } finally {
      markReconciling(item.inviteId, false);
    }
  }
}

async function retryOutboxInvitation(item: FriendInvitationItem) {
  if (!canRetry(item) || isRetrying(item.inviteId)) {
    return;
  }
  markRetrying(item.inviteId, true);
  try {
    await retryFriendInvitation(item.inviteId);
    Toast.success("友链邀请已重试");
    await reload();
  } catch (e) {
    Toast.error(e instanceof Error ? e.message : "重试友链邀请失败");
  } finally {
    markRetrying(item.inviteId, false);
  }
}

async function ensureLinkGroups() {
  if (linkGroups.value.length > 0) {
    return;
  }
  try {
    linkGroups.value = await fetchFriendInvitationLinkGroups();
  } catch (e) {
    throw new Error(e instanceof Error ? e.message : "读取友链分组失败");
  }
}

async function openApproveDialog(item: FriendInvitationItem) {
  try {
    await ensureLinkGroups();
    reviewTarget.value = item;
    reviewMode.value = "approve";
    reviewReason.value = "";
    reviewLinkGroupName.value = "";
    reviewGroupDropdownOpen.value = false;
    reviewDialogVisible.value = true;
  } catch (e) {
    Toast.error(e instanceof Error ? e.message : "读取友链分组失败");
  }
}

async function removeInvitation(item: FriendInvitationItem) {
  if (isDeleting(item.inviteId)) {
    return;
  }
  const msg = activeTab.value === "outbox"
    ? "确认删除这条发件记录吗？待审核发件会先撤回邀请，再删除本地记录。"
    : "确认删除这条记录吗？这会删除当前插件本地缓存。";
  showConfirmDialog(msg, async () => {
    markDeleting(item.inviteId, true);
    try {
      await deleteFriendInvitation(item.inviteId);
      Toast.success("友链记录已删除");
      removeLocalItem(item.inviteId);
      notifyPendingInboxCountChanged("delete");
    } catch (e) {
      Toast.error(e instanceof Error ? e.message : "删除友链记录失败");
    } finally {
      markDeleting(item.inviteId, false);
    }
  });
}

async function cancelInvitation(item: FriendInvitationItem) {
  if (!canCancel(item) || isCancelling(item.inviteId)) {
    return;
  }
  showConfirmDialog("确认撤回这条友链邀请吗？", async () => {
    markCancelling(item.inviteId, true);
    try {
      const updated = await cancelFriendInvitation(item.inviteId);
      if (updated) {
        replaceLocalItem(updated);
      }
      Toast.success("友链邀请已撤回");
      notifyPendingInboxCountChanged("cancel");
    } catch (e) {
      Toast.error(e instanceof Error ? e.message : "撤回友链邀请失败");
    } finally {
      markCancelling(item.inviteId, false);
    }
  });
}

function openRejectDialog(item: FriendInvitationItem) {
  reviewTarget.value = item;
  reviewMode.value = "reject";
  reviewReason.value = "";
  reviewLinkGroupName.value = "";
  reviewGroupDropdownOpen.value = false;
  reviewDialogVisible.value = true;
}

function closeReviewDialog() {
  if (reviewing.value) {
    return;
  }
  reviewDialogVisible.value = false;
  reviewTarget.value = null;
  reviewReason.value = "";
  reviewLinkGroupName.value = "";
  reviewGroupDropdownOpen.value = false;
}

function showConfirmDialog(message: string, callback: () => void) {
  confirmDialogMessage.value = message;
  confirmDialogCallback.value = callback;
  confirmDialogVisible.value = true;
}

function closeConfirmDialog() {
  confirmDialogVisible.value = false;
  confirmDialogMessage.value = "";
  confirmDialogCallback.value = null;
}

function executeConfirmDialog() {
  if (confirmDialogCallback.value) {
    confirmDialogCallback.value();
  }
  closeConfirmDialog();
}

function toggleReviewGroupDropdown() {
  if (reviewMode.value !== "approve") {
    return;
  }
  reviewGroupDropdownOpen.value = !reviewGroupDropdownOpen.value;
}

function selectReviewGroup(groupName: string) {
  reviewLinkGroupName.value = groupName;
  reviewGroupDropdownOpen.value = false;
}

function handleDocumentClick(event: MouseEvent) {
  const target = event.target;
  if (!(target instanceof Element)) {
    return;
  }
  if (!target.closest(".review-selectbox")) {
    reviewGroupDropdownOpen.value = false;
  }
}

async function submitReview() {
  if (!reviewTarget.value || reviewing.value) {
    return;
  }
  if (reviewMode.value === "reject" && !reviewReason.value.trim()) {
    Toast.warning("请填写拒绝原因");
    return;
  }

  try {
    reviewing.value = true;
    const reviewedInvitation = await reviewFriendInvitation(
      reviewTarget.value.inviteId,
      reviewMode.value === "approve",
      reviewReason.value.trim(),
      reviewMode.value === "approve" ? reviewLinkGroupName.value.trim() : ""
    );
    if (reviewMode.value === "approve" && reviewedInvitation) {
      await reconcileFriendInvitation(reviewedInvitation, currentSiteId());
    }
    reviewing.value = false;
    closeReviewDialog();
    Toast.success(reviewMode.value === "approve" ? "友链邀请已通过" : "友链邀请已拒绝");
    await reload();
    notifyPendingInboxCountChanged("review");
  } catch (e) {
    Toast.error(e instanceof Error ? e.message : "审核友链邀请失败");
  } finally {
    reviewing.value = false;
  }
}

onMounted(() => {
  reload();
  document.addEventListener("click", handleDocumentClick);
});

const isScrolling = ref(false);
let scrollEndTimer: ReturnType<typeof setTimeout> | null = null;

function positionFloatingPanel(event: Event, panelSelector: string) {
  const trigger = event.currentTarget;
  if (!(trigger instanceof HTMLElement)) {
    return;
  }
  const panel = trigger.querySelector(panelSelector) as HTMLElement | null;
  if (!panel) {
    return;
  }
  // 让 panel 先按内容自然展开，再读尺寸
  panel.style.maxHeight = "";
  const triggerRect = trigger.getBoundingClientRect();
  const panelWidth = panel.offsetWidth;
  const panelHeight = panel.offsetHeight;
  const gap = 10;
  const margin = 8;
  let top = triggerRect.top - panelHeight - gap;
  if (top < margin) {
    // 上方空间不够则放到下方
    top = triggerRect.bottom + gap;
  }
  let left = triggerRect.left + triggerRect.width / 2 - panelWidth / 2;
  const maxLeft = window.innerWidth - panelWidth - margin;
  if (left > maxLeft) left = maxLeft;
  if (left < margin) left = margin;
  panel.style.top = `${top}px`;
  panel.style.left = `${left}px`;
}

function positionDescTooltip(event: Event) {
  positionFloatingPanel(event, ".desc-tooltip");
}

function positionReviewPopover(event: Event) {
  positionFloatingPanel(event, ".review-reason-popover");
}

function onScroll() {
  isScrolling.value = true;
  if (scrollEndTimer) {
    clearTimeout(scrollEndTimer);
  }
  scrollEndTimer = setTimeout(() => {
    isScrolling.value = false;
    scrollEndTimer = null;
  }, 150);
}

onBeforeUnmount(() => {
  document.removeEventListener("click", handleDocumentClick);
  if (scrollEndTimer) {
    clearTimeout(scrollEndTimer);
    scrollEndTimer = null;
  }
});

useFriendInvitationRealtime(settingsRef, (event) => {
  applyRealtimeInvitationEvent(event);
});

watch(activeTab, () => {
  reload();
});

watch(
  () => [props.settings.connection.hubBaseUrl, props.settings.credentials.siteId].join("|"),
  () => {
    reload();
  }
);
</script>

<template>
  <div class="friend-manager-wrap">
    <div class="friend-table-wrap" :class="{ 'is-scrolling': isScrolling }" @scroll="onScroll">
      <div v-if="loading" class="loading-overlay">
        <div class="uv-loader"><span class="uv-loader-text">loading</span><span class="uv-load"></span></div>
      </div>

      <div class="friend-table">
        <div v-if="error" class="friend-empty">
          <EmptyState :text="error" hint="请检查 Hub 地址配置或网络连接" />
        </div>

        <div v-else-if="!loading && !items.length" class="friend-empty">
          <EmptyState :text="emptyText" hint="友链邀请将在此展示" />
        </div>

            <div
              v-for="item in items"
              :key="item.inviteId"
              class="friend-row has-actions"
              :class="`friend-row--${statusClass(item)}`"
            >
              <div class="avatar-cell">
                <img
                  v-if="peerSite(item).avatarUrl"
                  :src="peerSite(item).avatarUrl"
                  alt=""
                  class="site-avatar"
                  @error="($event.target as HTMLImageElement).src = DEFAULT_AVATAR_DATA_URI"
                />
                <img v-else :src="DEFAULT_AVATAR_DATA_URI" alt="" class="site-avatar" />
              </div>

              <div class="name-cell">
                <div class="site-name">{{ peerSite(item).siteName || "-" }}</div>
                <a
                  v-if="externalLinkHref(peerSite(item).siteUrl)"
                  class="site-url external-link"
                  :href="externalLinkHref(peerSite(item).siteUrl)"
                  target="_blank"
                  rel="noopener noreferrer"
                  :title="peerSite(item).siteUrl"
                >
                  {{ peerSite(item).siteUrl }}
                </a>
                <div v-else class="site-url">{{ peerSite(item).siteUrl || "-" }}</div>
              </div>

              <div class="desc-cell">
                <span
                  class="desc-icon-trigger"
                  :aria-label="peerSite(item).description || '暂无简介'"
                >
                  <svg viewBox="0 0 24 24" fill="none" width="15" height="15">
                    <path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/>
                    <path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/>
                  </svg>
                  <span class="desc-tooltip">{{ peerSite(item).description || "暂无简介" }}</span>
                </span>
              </div>

              <div class="rss-cell">
                <a
                  v-if="externalLinkHref(peerSite(item).rssUrl)"
                  class="contact-rss external-link"
                  :href="externalLinkHref(peerSite(item).rssUrl)"
                  target="_blank"
                  rel="noopener noreferrer"
                  :title="peerSite(item).rssUrl"
                >
                  {{ peerSite(item).rssUrl }}
                </a>
                <div v-else class="contact-rss">{{ peerSite(item).rssUrl || "-" }}</div>
              </div>

              <div class="message-cell">
                <div v-if="item.lastError" class="review-reason">失败：{{ item.lastError }}</div>
              </div>

              <div class="status-cell review-status-cell">
                <div class="review-status-line">
                  <span class="status-pill status-pill--stacked" :class="statusClass(item)">
                    <template v-if="activeTab === 'all'">
                      <span class="status-pill-line">{{ statusDirectionLine(item) }}</span>
                      <span class="status-pill-line">{{ statusResultLine(item) }}</span>
                    </template>
                    <template v-else>{{ statusText(item) }}</template>
                  </span>
                  <span
                    class="review-reason-trigger"
                    tabindex="0"
                    aria-label="审核状态说明"
                  >
                    <svg viewBox="0 0 20 20" fill="none" aria-hidden="true">
                      <circle cx="10" cy="10" r="8" stroke="currentColor" stroke-width="1.8" />
                      <path d="M10 9v5" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" />
                      <path d="M10 6h.01" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" />
                    </svg>
                    <span class="review-reason-popover">
                      <template v-if="reviewStatusInfoLines(item).length">
                        <span
                          v-for="(line, idx) in reviewStatusInfoLines(item)"
                          :key="idx"
                          class="review-reason-line"
                        >
                          <span class="review-reason-label" :class="line.label === '我' ? 'me' : 'other'">{{ line.label }}：</span>
                          <span class="review-reason-text">{{ line.text }}</span>
                        </span>
                      </template>
                      <template v-else>无消息</template>
                    </span>
                  </span>
                </div>
              </div>

              <div class="status-cell delivery-status-cell">
                <span v-if="!shouldShowDeliveryStatus(item)" class="delivery-text muted">-</span>
                <span v-else class="delivery-icon" :class="deliveryIconClass(item.deliveryStatus)" :title="deliveryStatusText(item.deliveryStatus)">
                  <svg v-if="isDeliverySuccess(item.deliveryStatus)" viewBox="0 0 20 20" fill="none" width="18" height="18">
                    <circle cx="10" cy="10" r="8.5" stroke="currentColor" stroke-width="1.6" />
                    <path d="M6.5 10.5l2.5 2.5 5-5" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" />
                  </svg>
                  <svg v-else viewBox="0 0 20 20" fill="none" width="18" height="18">
                    <circle cx="10" cy="10" r="8.5" stroke="currentColor" stroke-width="1.6" />
                    <path d="M7 7l6 6M13 7l-6 6" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" />
                  </svg>
                </span>
              </div>

              <div class="time-cell">
                <div>邀请：{{ formatTime(item.createdAt) }}</div>
                <div v-if="item.reviewedAt">审核：{{ formatTime(item.reviewedAt) }}</div>
              </div>

              <div class="action-cell">
                <template v-if="activeTab === 'pending' && canReview(item)">
                  <button class="action-btn approve" @click="openApproveDialog(item)">通过</button>
                  <button class="action-btn reject" @click="openRejectDialog(item)">拒绝</button>
                </template>
                <template v-else-if="activeTab === 'outbox' && canCancel(item)">
                  <button class="action-btn reject" :disabled="isCancelling(item.inviteId)" @click="cancelInvitation(item)">
                    {{ isCancelling(item.inviteId) ? "撤回中..." : "撤回" }}
                  </button>
                </template>
                <template v-else-if="activeTab === 'outbox' && canRetry(item)">
                  <button class="action-btn approve" :disabled="isRetrying(item.inviteId)" @click="retryOutboxInvitation(item)">
                    {{ isRetrying(item.inviteId) ? "重试中..." : "重试" }}
                  </button>
                </template>
                <button
                  v-if="activeTab !== 'pending'"
                  class="action-btn delete"
                  :disabled="isDeleting(item.inviteId)"
                  @click="removeInvitation(item)"
                >
                  {{ isDeleting(item.inviteId) ? "删除中..." : "删除" }}
                </button>
              </div>
            </div>
          </div>
      </div>

    <div v-if="reviewDialogVisible" class="review-mask" @click.self="closeReviewDialog">
      <div class="review-dialog">
        <div class="review-dialog-title">
          {{ reviewMode === "approve" ? "通过友链邀请" : "拒绝友链邀请" }}
        </div>
        <div class="review-dialog-sub">
          {{ reviewTarget ? peerSite(reviewTarget).siteName || reviewTarget.inviteId : "-" }}
        </div>

        <div v-if="reviewMode === 'approve'" class="review-field">
          <label class="review-label">友链分组</label>
          <div class="review-selectbox">
            <button type="button" class="review-select-trigger" @click.stop="toggleReviewGroupDropdown">
              <span>{{ selectedReviewGroupLabel }}</span>
              <svg
                viewBox="0 0 20 20"
                fill="none"
                class="review-select-arrow"
                :class="{ open: reviewGroupDropdownOpen }"
              >
                <path d="M5 7.5L10 12.5L15 7.5" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" />
              </svg>
            </button>
            <div v-if="reviewGroupDropdownOpen" class="review-select-menu">
              <button
                v-for="group in reviewGroupOptions"
                :key="group.name || '__default__'"
                type="button"
                class="review-select-option"
                :class="{ active: reviewLinkGroupName === group.name }"
                @click.stop="selectReviewGroup(group.name)"
              >
                <span>{{ group.displayName }}</span>
                <span v-if="!group.name" class="review-option-hint">未分组</span>
              </button>
            </div>
          </div>
          <div class="review-field-hint">
            未创建分组时，将直接写入链接管理默认分组。
          </div>
        </div>

        <div class="review-field">
          <label class="review-label">{{ reviewMode === "approve" ? "备注" : "拒绝原因" }}</label>
          <textarea
            v-model="reviewReason"
            class="review-textarea"
            :placeholder="reviewMode === 'approve' ? '可选，给这次审核添加备注' : '请填写拒绝原因'"
          ></textarea>
        </div>

        <div class="review-actions">
          <button class="dialog-btn" :disabled="reviewing" @click="closeReviewDialog">取消</button>
          <button class="dialog-btn primary" :disabled="reviewing" @click="submitReview">
            {{ reviewing ? "提交中..." : "确认提交" }}
          </button>
        </div>
      </div>
    </div>

    <div v-if="confirmDialogVisible" class="review-mask" @click.self="closeConfirmDialog">
      <div class="confirm-dialog">
        <div class="confirm-dialog-message">{{ confirmDialogMessage }}</div>
        <div class="confirm-dialog-actions">
          <button class="dialog-btn" @click="closeConfirmDialog">取消</button>
          <button class="dialog-btn danger" @click="executeConfirmDialog">确认</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.friend-manager-wrap{padding:16px 20px;flex:1;overflow:hidden;min-height:0;display:flex;flex-direction:column}
.sp-header-bar{display:flex;align-items:center;justify-content:space-between;margin-bottom:14px}
.sp-th-left{display:flex;align-items:center;gap:10px}
.sp-th-icon{width:32px;height:32px;border-radius:10px;flex-shrink:0;display:flex;align-items:center;justify-content:center}
.sp-th-title{font-size:13px;font-weight:600;color:#1e293b;line-height:1.3}
.sp-th-sub{font-size:11px;color:#94a3b8;line-height:1.3;margin-top:1px}
.sp-header-btn{display:inline-flex;align-items:center;gap:5px;padding:6px 14px;border:1px solid rgba(199,210,254,.86);border-radius:9px;font-size:12px;font-weight:600;background:linear-gradient(180deg,#eef2ff 0%,#e0e7ff 100%);color:#4f46e5;cursor:pointer;transition:all .15s;white-space:nowrap}
.sp-header-btn:disabled{opacity:.5;cursor:not-allowed}
.friend-table-wrap{position:relative;flex:1;min-height:0;overflow:auto;display:flex;flex-direction:column}
.friend-table{display:flex;flex-direction:column;min-width:0;gap:8px;padding:4px 0;flex:1;min-height:100%}
.friend-row{display:grid;grid-template-columns:46px minmax(150px,1fr) 42px minmax(170px,1fr) minmax(210px,1.05fr) 110px 90px 160px 132px;gap:12px;align-items:center;padding:10px 14px;border-radius:20px;background:transparent;border:1px solid rgba(0,0,0,.05);box-shadow:0 2px 8px rgba(0,0,0,.03);box-sizing:border-box;height:60px}
.friend-row:hover{box-shadow:0 4px 14px rgba(0,0,0,.06)}
.friend-row--pending{border-color:rgba(147,197,253,.3)}
.friend-row--ok{border-color:rgba(134,239,172,.3)}
.friend-row--warn{border-color:rgba(252,165,165,.3)}
.friend-row--muted{opacity:.7}
.avatar-cell,.desc-cell,.rss-cell,.message-cell,.status-cell,.time-cell{font-size:12px;line-height:1.45;color:#475569;display:flex;flex-direction:column;justify-content:center;align-items:center;text-align:center}
.name-cell{font-size:12px;line-height:1.45;color:#475569;display:flex;flex-direction:column;justify-content:center;align-items:flex-start;text-align:left}
.action-cell{display:flex;flex-direction:row;gap:8px;justify-content:center;align-items:center;flex-wrap:wrap}
.avatar-cell{display:flex;align-items:center;justify-content:flex-start}
.site-avatar{width:34px;height:34px;border-radius:10px;object-fit:cover;background:#f1f5f9;border:1px solid #e2e8f0;flex-shrink:0}
.name-cell,.rss-cell,.message-cell{min-width:0}
.site-name{font-size:13px;font-weight:600;color:#0f172a;line-height:1.3}
.site-url,.contact-rss{color:#94a3b8;word-break:break-all;font-size:12px;line-height:1.45}
.external-link{display:inline-block;text-decoration:none;transition:color .15s ease,text-decoration-color .15s ease;text-decoration-line:underline;text-decoration-color:transparent;text-underline-offset:3px}
.external-link:hover,.external-link:focus-visible{color:#4f46e5;text-decoration-color:currentColor}
.external-link:focus-visible{outline:2px solid rgba(79,70,229,.35);outline-offset:2px;border-radius:4px}
.rss-cell,.message-cell{word-break:break-word}
.desc-cell{display:flex;align-items:center;justify-content:center;overflow:visible}
.desc-icon-trigger{position:relative;display:inline-flex;align-items:center;justify-content:center;color:#94a3b8;cursor:pointer;transition:color .15s}
.desc-icon-trigger:hover{color:#4f46e5}
.desc-tooltip{position:absolute;left:calc(100% + 10px);top:50%;transform:translate(-6px,-50%);padding:8px 12px;background:rgba(255,255,255,.95);backdrop-filter:blur(8px);color:#334155;font-size:11px;line-height:1.55;border-radius:10px;border:1px solid rgba(203,213,225,.8);white-space:normal;word-break:break-word;width:220px;z-index:100;box-shadow:0 8px 24px rgba(0,0,0,.08);text-align:left;opacity:0;pointer-events:none;transition:opacity .16s ease,transform .16s ease}
.desc-tooltip::after{content:"";position:absolute;left:-5px;top:50%;width:10px;height:10px;transform:translateY(-50%) rotate(45deg);background:rgba(255,255,255,.95);border-left:1px solid rgba(203,213,225,.8);border-bottom:1px solid rgba(203,213,225,.8)}
.desc-icon-trigger:hover .desc-tooltip,.desc-icon-trigger:focus-visible .desc-tooltip{opacity:1;transform:translate(0,-50%);pointer-events:auto}
.message-meta{color:#64748b;margin-top:4px}
.review-reason{color:#b91c1c;margin-top:4px}
.status-cell{display:flex;flex-direction:column;gap:6px;justify-content:center}
.review-status-cell{position:relative;overflow:visible}
.review-status-line{display:inline-flex;align-items:center;gap:7px;min-width:0}
.status-pill{display:inline-flex;align-items:center;justify-content:center;align-self:center;min-height:24px;padding:2px 10px;border-radius:14px;font-size:11px;font-weight:700;line-height:1.2;text-align:center}
.status-pill--stacked{flex-direction:column;gap:0;padding:3px 10px;line-height:1.15}
.status-pill-line{display:block;white-space:nowrap}
.status-pill.pending{background:#eff6ff;color:#2563eb}
.status-pill.ok{background:#ecfdf5;color:#047857}
.status-pill.warn{background:#fef2f2;color:#b91c1c}
.status-pill.muted{background:#f8fafc;color:#64748b}
.review-reason-trigger{position:relative;display:inline-flex;align-items:center;justify-content:center;width:22px;height:22px;border-radius:999px;color:#64748b;background:rgba(241,245,249,.9);border:1px solid rgba(203,213,225,.7);cursor:help;outline:none;transition:color .15s,border-color .15s}
.review-reason-trigger:hover{color:#4f46e5;border-color:#a5b4fc}
.review-reason-trigger svg{width:13px;height:13px;display:block}
.review-reason-popover{position:absolute;left:calc(100% + 10px);top:50%;z-index:100;width:max-content;max-width:260px;transform:translate(-6px,-50%);padding:9px 12px;border-radius:10px;background:rgba(255,255,255,.95);backdrop-filter:blur(8px);border:1px solid rgba(203,213,225,.8);box-shadow:0 8px 24px rgba(0,0,0,.08);color:#334155;font-size:12px;font-weight:500;line-height:1.6;white-space:normal;word-break:break-word;opacity:0;pointer-events:none;transition:opacity .16s ease,transform .16s ease;display:flex;flex-direction:column;gap:4px;text-align:left}
.review-reason-line{display:block}
.review-reason-label{font-weight:700;margin-right:4px}
.review-reason-label.me{color:#4f46e5}
.review-reason-label.other{color:#b45309}
.review-reason-text{color:#334155}
.review-reason-popover::after{content:"";position:absolute;left:-5px;top:50%;width:10px;height:10px;transform:translateY(-50%) rotate(45deg);background:rgba(255,255,255,.92);border-left:1px solid rgba(203,213,225,.8);border-bottom:1px solid rgba(203,213,225,.8)}
.review-reason-trigger:hover .review-reason-popover,.review-reason-trigger:focus-visible .review-reason-popover{opacity:1;transform:translate(0,-50%)}
.delivery-text{font-size:11px;color:#94a3b8;line-height:1.5}
.delivery-icon{display:inline-flex;align-items:center;justify-content:center}
.delivery-icon.delivery-ok{color:#047857}
.delivery-icon.delivery-fail{color:#b91c1c}
.friend-empty{flex:1;display:flex;align-items:center;justify-content:center;min-height:200px}
.loading-overlay{position:absolute;inset:0;display:flex;align-items:center;justify-content:center;background:#ffffff;z-index:5}
.uv-loader{width:80px;height:50px;position:relative}
.uv-loader-text{position:absolute;top:0;padding:0;margin:0;color:#C8B6FF;animation:uvtext 3.5s ease both infinite;font-size:.8rem;letter-spacing:1px}
.uv-load{background-color:#9A79FF;border-radius:50px;display:block;height:16px;width:16px;bottom:0;position:absolute;transform:translateX(64px);animation:uvloading 3.5s ease both infinite}
.uv-load::before{position:absolute;content:"";width:100%;height:100%;background-color:#D1C2FF;border-radius:inherit;animation:uvloading2 3.5s ease both infinite}
@keyframes uvtext{0%{letter-spacing:1px;transform:translateX(0px)}40%{letter-spacing:2px;transform:translateX(26px)}80%{letter-spacing:1px;transform:translateX(32px)}90%{letter-spacing:2px;transform:translateX(0px)}100%{letter-spacing:1px;transform:translateX(0px)}}
@keyframes uvloading{0%{width:16px;transform:translateX(0px)}40%{width:100%;transform:translateX(0px)}80%{width:16px;transform:translateX(64px)}90%{width:100%;transform:translateX(0px)}100%{width:16px;transform:translateX(0px)}}
@keyframes uvloading2{0%{transform:translateX(0px);width:16px}40%{transform:translateX(0%);width:80%}80%{width:100%;transform:translateX(0px)}90%{width:80%;transform:translateX(15px)}100%{transform:translateX(0px);width:16px}}
.action-btn{outline:none;padding:5px 12px;border:2px dashed #64748b;border-radius:15px;background-color:#f1f5f9;color:#64748b;font-size:11px;font-weight:600;cursor:pointer;transition:transform .2s ease-out;box-shadow:0 0 0 3px #f1f5f9,1.5px 1.5px 3px 1px rgba(0,0,0,.15)}
.action-btn:hover{transform:translateY(-4px) translateX(-2px);box-shadow:0 0 0 3px #f1f5f9,2px 5px 0 0 currentColor}
.action-btn:active{transform:translateY(1px) translateX(1px);box-shadow:0 0 0 3px #f1f5f9,0 0 0 0 currentColor}
.friend-table-wrap.is-scrolling .friend-row,.friend-table-wrap.is-scrolling .action-btn{pointer-events:none}
.action-btn.approve{border-color:#047857;color:#047857;background-color:#ecfdf5;box-shadow:0 0 0 3px #ecfdf5,1.5px 1.5px 3px 1px rgba(0,0,0,.15)}
.action-btn.approve:hover{box-shadow:0 0 0 3px #ecfdf5,2px 5px 0 0 #047857}
.action-btn.reject{border-color:#b91c1c;color:#b91c1c;background-color:#fef2f2;box-shadow:0 0 0 3px #fef2f2,1.5px 1.5px 3px 1px rgba(0,0,0,.15)}
.action-btn.reject:hover{box-shadow:0 0 0 3px #fef2f2,2px 5px 0 0 #b91c1c}
.action-btn.delete{border-color:#64748b;color:#64748b;background-color:#f8fafc;box-shadow:0 0 0 3px #f8fafc,1.5px 1.5px 3px 1px rgba(0,0,0,.15)}
.action-btn.delete:hover{box-shadow:0 0 0 3px #f8fafc,2px 5px 0 0 #64748b}
.action-btn:disabled{opacity:.5;cursor:not-allowed;transform:none}
.action-disabled{font-size:12px;color:#94a3b8}
.review-mask{position:fixed;inset:0;background:rgba(15,23,42,.32);display:flex;align-items:center;justify-content:center;z-index:50;padding:20px}
.review-dialog{width:100%;max-width:460px;background:#ffffff;border-radius:18px;border:2px dashed #64748b;box-shadow:0 0 0 3px #ffffff,4px 6px 0 0 rgba(15,23,42,.12);padding:20px}
.review-dialog-title{font-size:15px;font-weight:700;color:#0f172a}
.review-dialog-sub{margin-top:4px;font-size:12px;color:#64748b}
.review-field{margin-top:14px}
.review-label{display:block;margin-bottom:6px;font-size:12px;font-weight:600;color:#475569}
.review-textarea{width:100%;border:1px solid #dbe3ee;border-radius:10px;background:#fff;font-size:13px;color:#0f172a;box-sizing:border-box}
.review-selectbox{position:relative}
.review-select-trigger{width:100%;height:42px;padding:0 14px;border:1px solid #dbe3ee;border-radius:12px;background:linear-gradient(180deg,#ffffff 0%,#f8fbff 100%);display:flex;align-items:center;justify-content:space-between;gap:12px;font-size:13px;font-weight:600;color:#0f172a;cursor:pointer;box-shadow:0 8px 20px rgba(148,163,184,.12);transition:border-color .15s,box-shadow .15s,transform .15s}
.review-select-trigger:hover{border-color:#c7d2fe;box-shadow:0 10px 24px rgba(99,102,241,.14)}
.review-select-trigger:focus-visible{outline:none;border-color:#818cf8;box-shadow:0 0 0 4px rgba(129,140,248,.16)}
.review-select-arrow{width:16px;height:16px;color:#6366f1;transition:transform .16s}
.review-select-arrow.open{transform:rotate(180deg)}
.review-select-menu{position:absolute;left:0;right:0;top:calc(100% + 8px);padding:8px;background:#fff;border:1px solid #dbe3ee;border-radius:14px;box-shadow:0 18px 40px rgba(15,23,42,.14);display:flex;flex-direction:column;gap:4px;z-index:4}
.review-select-option{width:100%;padding:10px 12px;border:none;border-radius:10px;background:transparent;display:flex;align-items:center;justify-content:space-between;gap:10px;font-size:13px;font-weight:600;color:#334155;cursor:pointer;transition:background .15s,color .15s}
.review-select-option:hover{background:#f8fafc;color:#0f172a}
.review-select-option.active{background:#eef2ff;color:#4f46e5}
.review-option-hint{font-size:11px;font-weight:700;color:#94a3b8}
.review-field-hint{margin-top:8px;font-size:12px;line-height:1.5;color:#94a3b8}
.review-textarea{min-height:96px;padding:10px 12px;resize:vertical}
.review-actions{display:flex;justify-content:flex-end;gap:14px;margin-top:18px}
.dialog-btn{display:inline-flex;align-items:center;justify-content:center;outline:none;padding:5px 14px;border:2px dashed #64748b;border-radius:15px;background-color:#f1f5f9;color:#64748b;font-size:11px;font-weight:600;cursor:pointer;transition:transform .2s ease-out;box-shadow:0 0 0 3px #f1f5f9,1.5px 1.5px 3px 1px rgba(0,0,0,.15)}
.dialog-btn:hover{transform:translateY(-4px) translateX(-2px);box-shadow:0 0 0 3px #f1f5f9,2px 5px 0 0 currentColor}
.dialog-btn:active{transform:translateY(1px) translateX(1px);box-shadow:0 0 0 3px #f1f5f9,0 0 0 0 currentColor}
.dialog-btn.primary{border-color:#047857;color:#047857;background-color:#ecfdf5;box-shadow:0 0 0 3px #ecfdf5,1.5px 1.5px 3px 1px rgba(0,0,0,.15)}
.dialog-btn.primary:hover{box-shadow:0 0 0 3px #ecfdf5,2px 5px 0 0 #047857}
.dialog-btn.primary:active{box-shadow:0 0 0 3px #ecfdf5,0 0 0 0 #047857}
.dialog-btn.danger{border-color:#b91c1c;color:#b91c1c;background-color:#fef2f2;box-shadow:0 0 0 3px #fef2f2,1.5px 1.5px 3px 1px rgba(0,0,0,.15)}
.dialog-btn.danger:hover{box-shadow:0 0 0 3px #fef2f2,2px 5px 0 0 #b91c1c}
.dialog-btn.danger:active{box-shadow:0 0 0 3px #fef2f2,0 0 0 0 #b91c1c}
.dialog-btn:disabled{opacity:.5;cursor:not-allowed;transform:none}
.confirm-dialog{background:#ffffff;border:2px dashed #64748b;border-radius:18px;padding:24px 28px;width:100%;max-width:360px;box-shadow:0 0 0 3px #ffffff,4px 6px 0 0 rgba(15,23,42,.12)}
.confirm-dialog-message{font-size:14px;color:#1e293b;line-height:1.6;margin-bottom:20px;text-align:center}
.confirm-dialog-actions{display:flex;justify-content:center;gap:14px}
</style>
