<script lang="ts" setup>
import { computed, nextTick, onMounted, ref, watch } from "vue";
import { Toast } from "@halo-dev/components";
import type { AstraHubSettings } from "../../types";
import type { SaveSettingsOptions } from "../../composables/useConfigMap";
import { registerSite } from "../../composables/useRegistration";
import { restoreByBoardingCode, sendBoardingCode } from "../../composables/useBoarding";
import {
  registerWithInvitation,
  requestInvitationCode
} from "../../composables/useInvitation";
import { fetchReportStatus, pushGraphNow, pushLinkEdgesNow, type ReportStatusResult } from "../../composables/usePushGraph";
import { syncFriendInvitationInbox, syncFriendInvitationOutbox } from "../../composables/useFriendInvitations";

const props = defineProps<{
  settings: AstraHubSettings;
  saving: boolean;
  persistSettings: (options?: SaveSettingsOptions) => Promise<boolean>;
}>();

const registering = ref(false);
const restoring = ref(false);
const sendingCode = ref(false);
const syncing = ref(false);
const revealApiKey = ref(false);
const boardingDialogVisible = ref(false);
const boardingEmail = ref("");
const boardingCode = ref("");
const boardingExpiresAt = ref("");
const lastSyncResult = ref<ReportStatusResult | null>(null);

// ——— OTP 验证码输入 ———
const OTP_LENGTH = 6;
const otpDigits = ref<string[]>(Array(OTP_LENGTH).fill(""));
const otpRefs = ref<(HTMLInputElement | null)[]>([]);

function setOtpRef(el: any, idx: number) {
  otpRefs.value[idx] = el as HTMLInputElement | null;
}

function onOtpInput(idx: number) {
  const val = otpDigits.value[idx];
  // 只保留最后一个数字字符
  const digit = val.replace(/\D/g, "").slice(-1);
  otpDigits.value[idx] = digit;
  boardingCode.value = otpDigits.value.join("");
  if (digit && idx < OTP_LENGTH - 1) {
    nextTick(() => otpRefs.value[idx + 1]?.focus());
  }
}

function onOtpKeydown(e: KeyboardEvent, idx: number) {
  if (e.key === "Backspace" && !otpDigits.value[idx] && idx > 0) {
    nextTick(() => otpRefs.value[idx - 1]?.focus());
  }
}

function onOtpPaste(e: ClipboardEvent) {
  e.preventDefault();
  const text = (e.clipboardData?.getData("text") || "").replace(/\D/g, "").slice(0, OTP_LENGTH);
  if (!text) return;
  for (let i = 0; i < OTP_LENGTH; i++) {
    otpDigits.value[i] = text[i] || "";
  }
  boardingCode.value = otpDigits.value.join("");
  const focusIdx = Math.min(text.length, OTP_LENGTH - 1);
  nextTick(() => otpRefs.value[focusIdx]?.focus());
}

// ——— 接入星链（申请签发码 + 带码注册）———
const joinDialogVisible = ref(false);
const joinCode = ref("");
const joinExpiresAt = ref("");
const joinConsentChecked = ref(false);
const requestingInvitation = ref(false);

const hasCredentials = computed(() =>
  Boolean(props.settings.credentials.siteId && props.settings.credentials.apiKey)
);
const registerActionLabel = computed(() =>
  hasCredentials.value ? "更新信息" : "接入星链"
);
const busy = computed(
  () =>
    props.saving ||
    registering.value ||
    restoring.value ||
    syncing.value ||
    requestingInvitation.value
);

const missingIdentityFields = computed(() => {
  const missing: string[] = [];
  if (!props.settings.connection.siteName.trim()) {
    missing.push("站点名称");
  }
  if (!props.settings.connection.siteUrl.trim()) {
    missing.push("站点 URL");
  }
  if (!props.settings.connection.contactEmail.trim()) {
    missing.push("联系邮箱");
  }
  if (!props.settings.connection.siteNodeName.trim()) {
    missing.push("星链节点名");
  }
  if (!props.settings.connection.siteNodeAvatar.trim()) {
    missing.push("星链头像链接");
  }
  return missing;
});
const identityComplete = computed(() => missingIdentityFields.value.length === 0);

const maskedApiKey = computed(() => {
  const raw = props.settings.credentials.apiKey || "";
  if (!raw) return "";
  if (revealApiKey.value) return raw;
  if (raw.length <= 8) return "********";
  return `${raw.slice(0, 4)}********${raw.slice(-4)}`;
});

function showStatusValue(value?: string | number | null) {
  if (value === null || value === undefined) {
    return "-";
  }
  const text = String(value).trim();
  return text || "-";
}

function validateConnectionInput() {
  if (!props.settings.connection.hubBaseUrl.trim()) {
    throw new Error("请先填写 Hub 基础地址");
  }
  if (!identityComplete.value) {
    throw new Error(`请完整填写站点身份：${missingIdentityFields.value.join("、")}`);
  }
}

async function persistManagedSettings(options?: SaveSettingsOptions) {
  return props.persistSettings(options);
}

async function refreshReportStatus(silent = false) {
  if (!hasCredentials.value) {
    lastSyncResult.value = null;
    return null;
  }
  try {
    const result = await fetchReportStatus();
    lastSyncResult.value = result;
    return result;
  } catch (error) {
    if (!silent) {
      const message = error instanceof Error ? error.message : "读取同步状态失败";
      Toast.warning(message);
    }
    return null;
  }
}

async function syncAfterPersist(successMessage: string) {
  try {
    syncing.value = true;
    await pushGraphNow();
    await refreshReportStatus(true);
    Toast.success(successMessage);
    return true;
  } catch (error) {
    await refreshReportStatus(true);
    const message = error instanceof Error ? error.message : "同步失败";
    Toast.warning(`配置已保存，但同步失败：${message}`);
    return false;
  } finally {
    syncing.value = false;
  }
}

async function bootstrapAfterCredentialRefresh(successMessage: string, syncReason: string) {
  const warnings: string[] = [];
  try {
    syncing.value = true;
    lastSyncResult.value = null;
    revealApiKey.value = false;

    try {
      await pushGraphNow(syncReason);
    } catch (error) {
      warnings.push(error instanceof Error ? error.message : "图谱同步失败");
    }

    try {
      await pushLinkEdgesNow();
    } catch (error) {
      warnings.push(error instanceof Error ? error.message : "友链同步失败");
    }

    try {
      await syncFriendInvitationInbox();
    } catch (error) {
      warnings.push(error instanceof Error ? error.message : "收件箱同步失败");
    }

    try {
      await syncFriendInvitationOutbox();
    } catch (error) {
      warnings.push(error instanceof Error ? error.message : "发件箱同步失败");
    }

    await refreshReportStatus(true);

    if (warnings.length > 0) {
      Toast.warning(`${successMessage}，但有部分后续任务失败：${warnings.join("；")}`);
      return false;
    }

    Toast.success(successMessage);
    return true;
  } finally {
    syncing.value = false;
  }
}

async function onSaveConnectionSettings() {
  const saved = await persistManagedSettings();
  if (!saved) {
    return;
  }
}

async function onRegisterSite() {
  if (busy.value) {
    return;
  }
  try {
    validateConnectionInput();
  } catch (error) {
    const message = error instanceof Error ? error.message : "请检查接入信息";
    Toast.error(message);
    return;
  }

  // 已接入 > 走原有"更新接入信息"流程（不需要申请签发码）
  if (hasCredentials.value) {
    await updateExistingRegistration();
    return;
  }

  // 未接入 > 打开"接入星链"对话框，两步式申请
  openJoinDialog();
}

async function updateExistingRegistration() {
  try {
    registering.value = true;
    const result = await registerSite({
      hubBaseUrl: props.settings.connection.hubBaseUrl,
      registerToken: props.settings.connection.registerToken,
      siteName: props.settings.connection.siteName,
      siteUrl: props.settings.connection.siteUrl,
      siteDescription: props.settings.connection.siteDescription,
      siteRssUrl: props.settings.connection.siteRssUrl,
      siteAvatarUrl: props.settings.connection.siteNodeAvatar,
      contactEmail: props.settings.connection.contactEmail,
      siteNodeName: props.settings.connection.siteNodeName,
      siteNodeAvatar: props.settings.connection.siteNodeAvatar
    });

    props.settings.credentials.siteId = result.siteId;
    props.settings.credentials.apiKey = result.apiKey;
    props.settings.credentials.createdAt = result.createdAt;
    const registeredNodeName = (result.nodeName || result.category || "").trim();
    if (registeredNodeName) {
      props.settings.connection.siteNodeName = registeredNodeName;
    }
    const registeredNodeAvatar = (result.nodeAvatar || "").trim();
    if (registeredNodeAvatar) {
      props.settings.connection.siteNodeAvatar = registeredNodeAvatar;
    }

    const saved = await props.persistSettings({ silentSuccess: true });
    if (!saved) {
      return;
    }

    await bootstrapAfterCredentialRefresh(
      "接入信息已更新，已完成重同步",
      "register_bootstrap"
    );
  } catch (error) {
    const message = error instanceof Error ? error.message : "更新接入信息失败";
    Toast.error(message);
  } finally {
    registering.value = false;
  }
}

function openJoinDialog() {
  joinDialogVisible.value = true;
  joinCode.value = "";
  joinExpiresAt.value = "";
  joinConsentChecked.value = false;
}

function closeJoinDialog() {
  if (requestingInvitation.value || registering.value) {
    return;
  }
  joinDialogVisible.value = false;
}

async function onRequestInvitationCode() {
  if (requestingInvitation.value) {
    return;
  }
  if (!joinConsentChecked.value) {
    Toast.warning("请先阅读并勾选《星链接入与数据同步说明》");
    return;
  }
  const hubBaseUrl = props.settings.connection.hubBaseUrl.trim();
  const email = props.settings.connection.contactEmail.trim();
  const siteUrl = props.settings.connection.siteUrl.trim();
  if (!hubBaseUrl) {
    Toast.warning("请先填写 Hub 基础地址");
    return;
  }
  if (!email) {
    Toast.warning("请先填写联系邮箱");
    return;
  }
  if (!siteUrl) {
    Toast.warning("请先填写站点 URL");
    return;
  }

  try {
    requestingInvitation.value = true;
    const result = await requestInvitationCode({
      hubBaseUrl,
      contactEmail: email,
      siteUrl
    });
    joinExpiresAt.value = result.expiresAt;
    Toast.success("签发码已发送，请查收邮箱");
  } catch (error) {
    const message = error instanceof Error ? error.message : "申请签发码失败";
    Toast.error(message);
  } finally {
    requestingInvitation.value = false;
  }
}

async function onConfirmJoinPlanet() {
  if (registering.value) {
    return;
  }
  if (!joinConsentChecked.value) {
    Toast.warning("请先阅读并勾选《星链接入与数据同步说明》");
    return;
  }
  try {
    validateConnectionInput();
  } catch (error) {
    const message = error instanceof Error ? error.message : "请检查接入信息";
    Toast.error(message);
    return;
  }

  const code = joinCode.value.trim();
  if (!code) {
    Toast.warning("请输入邮箱收到的签发码");
    return;
  }

  try {
    registering.value = true;
    const result = await registerWithInvitation({
      hubBaseUrl: props.settings.connection.hubBaseUrl,
      invitationCode: code,
      siteName: props.settings.connection.siteName,
      siteUrl: props.settings.connection.siteUrl,
      siteDescription: props.settings.connection.siteDescription,
      siteRssUrl: props.settings.connection.siteRssUrl,
      siteAvatarUrl: props.settings.connection.siteNodeAvatar,
      contactEmail: props.settings.connection.contactEmail,
      siteNodeName: props.settings.connection.siteNodeName,
      siteNodeAvatar: props.settings.connection.siteNodeAvatar
    });

    props.settings.credentials.siteId = result.siteId;
    props.settings.credentials.apiKey = result.apiKey;
    props.settings.credentials.createdAt = result.createdAt;
    const registeredNodeName = (result.nodeName || result.category || "").trim();
    if (registeredNodeName) {
      props.settings.connection.siteNodeName = registeredNodeName;
    }
    const registeredNodeAvatar = (result.nodeAvatar || "").trim();
    if (registeredNodeAvatar) {
      props.settings.connection.siteNodeAvatar = registeredNodeAvatar;
    }

    const saved = await props.persistSettings({ silentSuccess: true });
    if (!saved) {
      return;
    }

    joinDialogVisible.value = false;
    joinCode.value = "";
    joinExpiresAt.value = "";

    await bootstrapAfterCredentialRefresh(
      "接入星链成功，已完成重同步",
      "register_bootstrap"
    );
  } catch (error) {
    const message = error instanceof Error ? error.message : "接入星链失败";
    Toast.error(message);
  } finally {
    registering.value = false;
  }
}

function openBoardingDialog() {
  if (!props.settings.connection.hubBaseUrl.trim()) {
    Toast.warning("请先填写 Hub 基础地址");
    return;
  }
  boardingDialogVisible.value = true;
  boardingCode.value = "";
  otpDigits.value = Array(OTP_LENGTH).fill("");
  boardingExpiresAt.value = "";
  boardingEmail.value = props.settings.connection.contactEmail || "";
}

function closeBoardingDialog() {
  if (sendingCode.value || restoring.value) {
    return;
  }
  boardingDialogVisible.value = false;
}

async function onSendBoardingCode() {
  if (sendingCode.value) {
    return;
  }
  const hubBaseUrl = props.settings.connection.hubBaseUrl.trim();
  const email = boardingEmail.value.trim();
  if (!hubBaseUrl) {
    Toast.warning("请先填写 Hub 基础地址");
    return;
  }
  if (!email) {
    Toast.warning("请输入邮箱");
    return;
  }

  try {
    sendingCode.value = true;
    const result = await sendBoardingCode({
      hubBaseUrl,
      contactEmail: email
    });
    boardingExpiresAt.value = result.expiresAt;
    Toast.success("验证码已发送，请检查邮箱");
  } catch (error) {
    const message = error instanceof Error ? error.message : "发送验证码失败";
    Toast.error(message);
  } finally {
    sendingCode.value = false;
  }
}

async function onRestoreByBoardingCode() {
  if (restoring.value) {
    return;
  }
  const hubBaseUrl = props.settings.connection.hubBaseUrl.trim();
  const email = boardingEmail.value.trim();
  const code = boardingCode.value.trim();
  if (!hubBaseUrl) {
    Toast.warning("请先填写 Hub 基础地址");
    return;
  }
  if (!email) {
    Toast.warning("请输入邮箱");
    return;
  }
  if (!code) {
    Toast.warning("请输入验证码");
    return;
  }

  try {
    restoring.value = true;
    const result = await restoreByBoardingCode({
      hubBaseUrl,
      contactEmail: email,
      code
    });

    props.settings.connection.siteName = result.siteName;
    props.settings.connection.siteUrl = result.siteUrl;
    props.settings.connection.contactEmail = result.contactEmail;
    props.settings.connection.siteDescription = result.description || "";
    props.settings.connection.siteRssUrl = result.rssUrl || "";
    const restoredNodeName = (result.nodeName || result.category || "").trim();
    if (restoredNodeName) {
      props.settings.connection.siteNodeName = restoredNodeName;
    }
    props.settings.connection.siteNodeAvatar = (result.nodeAvatar || "").trim();

    props.settings.credentials.siteId = result.siteId;
    props.settings.credentials.apiKey = result.apiKey;
    props.settings.credentials.createdAt = result.createdAt;

    const saved = await props.persistSettings({ silentSuccess: true });
    if (!saved) {
      return;
    }

    boardingDialogVisible.value = false;
    boardingCode.value = "";
    otpDigits.value = Array(OTP_LENGTH).fill("");
    boardingExpiresAt.value = "";
    await bootstrapAfterCredentialRefresh("重新登舱成功，已完成重同步", "boarding_restore_bootstrap");
  } catch (error) {
    const message = error instanceof Error ? error.message : "登舱恢复失败";
    Toast.error(message);
  } finally {
    restoring.value = false;
  }
}

async function onCopyApiKey() {
  const value = props.settings.credentials.apiKey;
  if (!value) {
    return;
  }
  try {
    await navigator.clipboard.writeText(value);
    Toast.success("接入密钥已复制");
  } catch {
    Toast.warning("复制失败，请手动复制");
  }
}

async function onSyncNow() {
  if (busy.value || !hasCredentials.value) {
    return;
  }
  await syncAfterPersist("同步完成");
}

onMounted(() => {
  void refreshReportStatus(true);
});

watch(
  () => [props.settings.credentials.siteId, props.settings.credentials.apiKey].join("|"),
  () => {
    void refreshReportStatus(true);
  }
);
</script>

<template>
  <div class="stats-wrapper">
    <div class="sp-card sp-card--blue">
      <div class="sp-card-title">
        <div class="sp-card-title-main">
          <span class="sp-dot" style="background:#3b82f6"></span>
          <span>接入信息</span>
          <span class="sp-title-separator">·</span>
          <span class="sp-status-pill" :class="hasCredentials ? 'ok' : 'pending'">
            {{ hasCredentials ? "已接入" : "未接入" }}
          </span>
        </div>
      </div>
      <div class="sp-card-body">
        <div class="sp-form-item">
          <label class="sp-form-label">Hub 基础地址</label>
          <input
            :value="props.settings.connection.hubBaseUrl"
            class="sp-input"
            readonly
          />
        </div>

        <div class="sp-form-grid-2">
          <div class="sp-form-item">
            <label class="sp-form-label">站点名称</label>
            <input v-model="props.settings.connection.siteName" class="sp-input" placeholder="站点显示名称" />
          </div>
          <div class="sp-form-item">
            <label class="sp-form-label">站点 URL</label>
            <input v-model="props.settings.connection.siteUrl" class="sp-input" placeholder="https://your-site.com" />
          </div>
        </div>

        <div class="sp-form-grid-2">
          <div class="sp-form-item">
            <label class="sp-form-label">联系邮箱</label>
            <input
              v-model="props.settings.connection.contactEmail"
              class="sp-input"
              placeholder="admin@example.com"
              :readonly="hasCredentials"
            />
          </div>
          <div class="sp-form-item">
            <label class="sp-form-label">星链节点名</label>
            <input v-model="props.settings.connection.siteNodeName" class="sp-input" placeholder="例如：可爱的星链" />
          </div>
        </div>

        <div class="sp-form-item">
          <label class="sp-form-label">站点简介</label>
          <textarea
            v-model="props.settings.connection.siteDescription"
            class="sp-textarea"
            placeholder="用于友链邀请、站点展示等场景，建议填写一句清晰的站点介绍"
          ></textarea>
        </div>

        <div class="sp-form-grid-2">
          <div class="sp-form-item">
            <label class="sp-form-label">星链头像链接</label>
            <input v-model="props.settings.connection.siteNodeAvatar" class="sp-input" placeholder="https://example.com/avatar.png" />
          </div>
          <div class="sp-form-item">
            <label class="sp-form-label">站点 RSS</label>
            <input v-model="props.settings.connection.siteRssUrl" class="sp-input" placeholder="https://your-site.com/rss.xml" />
          </div>
        </div>


      </div>
    </div>

    <div class="sp-card sp-card--amber">
      <div class="sp-card-title">
        <div class="sp-card-title-main">
          <span class="sp-dot" style="background:#f59e0b"></span>
          <span>注册与凭据</span>
        </div>
        <div class="sp-title-actions">
          <button class="sp-header-btn sp-header-btn-primary" :disabled="busy" @click="onRegisterSite">
            {{ registering ? "处理中..." : registerActionLabel }}
          </button>
          <button class="sp-header-btn" :disabled="busy" @click="openBoardingDialog">
            重新登舱
          </button>
        </div>
      </div>
      <div class="sp-card-body">
        <div class="sp-form-grid-2">
          <div class="sp-form-item">
            <label class="sp-form-label">站点编号</label>
            <input :value="props.settings.credentials.siteId || '-'" class="sp-input" readonly />
          </div>
          <div class="sp-form-item">
            <label class="sp-form-label">创建时间</label>
            <input :value="props.settings.credentials.createdAt || '-'" class="sp-input" readonly />
          </div>
        </div>

        <div class="sp-form-item">
          <label class="sp-form-label">接入密钥</label>
          <div class="sp-input-row">
            <input :value="maskedApiKey || '-'" class="sp-input" readonly />
            <button type="button" class="sp-input-btn" @click="revealApiKey = !revealApiKey">
              {{ revealApiKey ? "隐藏" : "显示" }}
            </button>
            <button type="button" class="sp-input-btn" @click="onCopyApiKey">复制</button>
          </div>
        </div>

      </div>
    </div>

    <div class="sp-card sp-card--cyan">
      <div class="sp-card-title">
        <div class="sp-card-title-main">
          <span class="sp-dot" style="background:#06b6d4"></span>
          <span>最近同步</span>
        </div>
        <div class="sp-title-actions">
          <button class="sp-header-btn" :disabled="busy || !hasCredentials" @click="onSyncNow">
            {{ syncing ? "同步中..." : "重新同步" }}
          </button>
        </div>
      </div>
      <div class="sp-card-body">
        <div v-if="lastSyncResult" class="sp-protocol-grid">
          <div class="sp-protocol-item">
            <span class="sp-protocol-label">返回状态</span>
            <span class="sp-protocol-value">{{ lastSyncResult.status }}</span>
          </div>
          <div class="sp-protocol-item">
            <span class="sp-protocol-label">提示</span>
            <span class="sp-protocol-value">{{ showStatusValue(lastSyncResult.message) }}</span>
          </div>
          <div class="sp-protocol-item">
            <span class="sp-protocol-label">完成时间</span>
            <span class="sp-protocol-value">
              {{ showStatusValue(lastSyncResult.pushedAt || lastSyncResult.lastSuccessfulPushAt || lastSyncResult.updatedAt) }}
            </span>
          </div>
        </div>

        <div v-else class="sp-inline-note">这里显示服务端记录的最近一次同步状态。</div>

      </div>
    </div>

    <div class="sp-card sp-card--violet">
      <div class="sp-card-title">
        <span class="sp-dot" style="background:#0ea5e9"></span>
        前台显示
      </div>
      <div class="sp-card-body">
        <div class="sp-toggle-item sp-toggle-last">
          <div class="sp-toggle-info">
            <span class="sp-toggle-label">显示前台挂件</span>
            <span class="sp-toggle-desc">关闭后将不再显示前台挂件。</span>
          </div>
          <label class="sp-toggle"><input type="checkbox" v-model="props.settings.widget.enabled" /><span class="sp-toggle-slider"></span></label>
        </div>
      </div>
    </div>

    <div class="sp-card sp-card--cyan">
      <div class="sp-card-title">
        <span class="sp-dot" style="background:#06b6d4"></span>
        主星实时播报
      </div>
      <div class="sp-card-body sp-card-body--stack">
        <div class="sp-toggle-item">
          <div class="sp-toggle-info">
            <span class="sp-toggle-label">启用主星实时播报</span>
            <span class="sp-toggle-desc">开启后，主星推送的接入、同步、文章推荐等消息会通过前台吉祥物气泡展示。</span>
          </div>
          <label class="sp-toggle"><input type="checkbox" v-model="props.settings.realtimeBroadcast.enabled" /><span class="sp-toggle-slider"></span></label>
        </div>
      </div>
    </div>

    <div v-if="boardingDialogVisible" class="sp-modal-mask" @click.self="closeBoardingDialog">
      <div class="sp-modal">
        <div class="sp-modal-title">重新登舱</div>
        <div class="sp-modal-sub">通过联系邮箱恢复接入信息，并自动重新同步。</div>

        <div class="sp-form-item">
          <label class="sp-form-label">邮箱</label>
          <input v-model="boardingEmail" class="sp-input" placeholder="admin@example.com" :disabled="sendingCode || restoring" />
        </div>

        <div class="sp-form-item">
          <label class="sp-form-label">验证码</label>
          <div class="sp-otp-row">
            <div class="sp-otp-boxes">
              <input
                v-for="(_, idx) in OTP_LENGTH"
                :key="idx"
                :ref="(el) => setOtpRef(el, idx)"
                v-model="otpDigits[idx]"
                class="sp-otp-cell"
                type="text"
                inputmode="numeric"
                maxlength="1"
                :disabled="restoring"
                @input="onOtpInput(idx)"
                @keydown="onOtpKeydown($event, idx)"
                @paste="onOtpPaste"
              />
            </div>
            <button type="button" class="sp-input-btn" :disabled="sendingCode || restoring" @click="onSendBoardingCode">
              {{ sendingCode ? "发送中" : "发送验证码" }}
            </button>
          </div>
          <div v-if="boardingExpiresAt" class="sp-inline-note">有效期至：{{ boardingExpiresAt }}</div>
        </div>

        <div class="sp-actions sp-actions-end">
          <button class="sp-header-btn" :disabled="sendingCode || restoring" @click="closeBoardingDialog">取消</button>
          <button class="sp-header-btn sp-header-btn-primary" :disabled="restoring" @click="onRestoreByBoardingCode">
            {{ restoring ? "恢复中..." : "恢复并同步" }}
          </button>
        </div>
      </div>
    </div>

    <div v-if="joinDialogVisible" class="sp-modal-mask" @click.self="closeJoinDialog">
      <div class="sp-modal">
        <div class="sp-modal-title">接入星链</div>
        <div class="sp-modal-sub">
          系统将把签发码发送到你的联系邮箱 {{ props.settings.connection.contactEmail || "—" }}，
          收到后填入下方完成接入。
        </div>

        <div class="sp-form-item sp-consent-block">
          <details class="sp-consent-details">
            <summary class="sp-consent-summary">查看《星链接入与数据同步说明》</summary>
            <div class="sp-consent-text">
              <div class="sp-consent-section">
                <p class="sp-consent-section-title">一、接入后您将获得的能力</p>
                <p>接入主星「{{ props.settings.connection.hubBaseUrl || "—" }}」后，您的站点将加入"博客星球"创作者网络，与全网独立博主互联互通。本插件会把您站点已公开的内容定时同步至主星，帮助您：</p>
                <ul>
                  <li><strong>扩大曝光</strong>：站点与博文将出现在主星首页的"探索"与"信号流"中，被更多读者发现</li>
                  <li><strong>拓宽圈子</strong>：自动加入主题星系（按标签 / 分类 / 节点聚类），与同好建立连接</li>
                  <li><strong>互通友链</strong>：通过签发码邀请协议与其它接入站点一键建立友链，免去手工填写</li>
                  <li><strong>聚合发现</strong>：您的友链关系会被纳入图谱可视化，访客能从一个节点跳转探索整片星系</li>
                  <li><strong>实时播报</strong>：当您发布新文章或与其它节点产生互动时，主星会通过吉祥物气泡实时播报到您和好友的前台</li>
                </ul>
              </div>

              <div class="sp-consent-section">
                <p class="sp-consent-section-title">二、同步至主星的数据范围</p>
                <p><strong>A. 站点公开内容</strong>（来自您已对外发布、公众可直接访问的内容）：</p>
                <ul>
                  <li><strong>站点元信息</strong>：站点名称、访问地址（URL）、节点标识与头像、RSS 订阅地址</li>
                  <li><strong>友情链接</strong>：友链分组配置、友链条目列表</li>
                  <li><strong>博文公开元数据</strong>：文章标题、永久链接、标签、分类、发布时间、内容摘要、正文内容</li>
                </ul>
                <p style="margin-top:6px"><strong>B. 联系邮箱</strong>（用于身份识别与系统通知）：</p>
                <ul>
                  <li>您填写的<strong>联系邮箱</strong>会随接入凭据一并上传至主星，用于接收<strong>签发码、友链邀请、互动通知</strong>等系统消息</li>
                </ul>
              </div>

              <div class="sp-consent-section">
                <p class="sp-consent-section-title">三、隐私与边界声明</p>
                <p>A 类数据均<strong>来源于本站点已对外发布、可被公众直接访问的公开内容</strong>，不涉及任何非公开、受限或私密信息。</p>
              </div>

              <div class="sp-consent-section">
                <p class="sp-consent-section-title">四、用途承诺</p>
                <p>上述数据用于<strong>内容聚合发现、友链协议互通、关系图谱构建、消息通知投递</strong>等社区聚合服务，严格遵循公开、透明、合规的数据交互原则。</p>
              </div>

              <div class="sp-consent-section">
                <p class="sp-consent-section-title">五、身份与控制权</p>
                <p>接入凭据（站点 ID / 密钥）由主星签发并存储在本站点本地配置中，您可随时通过"重新登舱"恢复接入身份。</p>
              </div>
            </div>
          </details>
          <label class="sp-consent-check">
            <input type="checkbox" v-model="joinConsentChecked" :disabled="registering || requestingInvitation" />
            <span>我已阅读并同意《星链接入与数据同步说明》，授权本插件按上述范围将我的站点公开数据与联系邮箱同步至主星「{{ props.settings.connection.hubBaseUrl || "—" }}」。</span>
          </label>
        </div>

        <div class="sp-form-item">
          <label class="sp-form-label">签发码</label>
          <div class="sp-input-row">
            <input
              v-model="joinCode"
              class="sp-input"
              placeholder="例如 bphub-A3F7B2C1"
              :disabled="registering"
            />
            <button
              type="button"
              class="sp-input-btn"
              :disabled="requestingInvitation || registering || !joinConsentChecked"
              @click="onRequestInvitationCode"
            >
              {{ requestingInvitation ? "发送中" : "发送签发码" }}
            </button>
          </div>
          <div v-if="joinExpiresAt" class="sp-inline-note">
            有效期至：{{ joinExpiresAt }}
          </div>
          <div v-else class="sp-inline-note">
            一张签发码仅可注册一个站点，请妥善保管。
          </div>
        </div>

        <div class="sp-actions sp-actions-end">
          <button
            class="sp-header-btn"
            :disabled="requestingInvitation || registering"
            @click="closeJoinDialog"
          >
            取消
          </button>
          <button
            class="sp-header-btn sp-header-btn-primary"
            :disabled="registering || !joinCode.trim() || !joinConsentChecked"
            @click="onConfirmJoinPlanet"
          >
            {{ registering ? "接入中..." : "确认" }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.stats-wrapper{padding:16px 20px;flex:1;overflow-y:auto;min-height:0;display:flex;flex-direction:column}
.sp-header-bar{display:flex;align-items:center;justify-content:space-between;margin-bottom:14px}
.sp-th-left{display:flex;align-items:center;gap:10px}
.sp-th-icon{width:32px;height:32px;border-radius:10px;flex-shrink:0;display:flex;align-items:center;justify-content:center}
.sp-th-title{font-size:13px;font-weight:600;color:#1e293b;line-height:1.3}
.sp-th-sub{font-size:11px;color:#94a3b8;line-height:1.3;margin-top:1px}
.sp-card{background:transparent;border:1px solid rgba(0,0,0,.05);border-radius:20px;padding:12px 14px;margin-bottom:10px;overflow:hidden;display:flex;flex-direction:column;flex-shrink:0}
.sp-card--blue{background:transparent;height:440px}
.sp-card--amber{background:transparent;height:200px}
.sp-card--cyan{background:transparent;height:140px}
.sp-card-title{display:flex;align-items:center;justify-content:space-between;gap:12px;font-size:13px;font-weight:600;color:#1e293b;margin-bottom:10px;flex-shrink:0}
.sp-card-title-main{display:flex;align-items:center;gap:8px;min-width:0}
.sp-title-separator{color:#94a3b8;font-weight:600}
.sp-title-actions{display:flex;align-items:center;gap:14px;flex-shrink:0}
.sp-dot{width:10px;height:10px;border-radius:50%;flex-shrink:0}
.sp-card-body{padding:0;flex:1;min-height:0;overflow:hidden}
.sp-form-grid-2{display:grid;grid-template-columns:1fr 1fr;gap:12px}
.sp-form-item{margin-bottom:8px}
.sp-form-item:last-child{margin-bottom:0}
.sp-form-label{display:block;font-size:12px;font-weight:500;color:#64748b;margin-bottom:4px}
.sp-input{width:100%;padding:6px 10px;border:1px solid rgba(203,213,225,.88);border-radius:8px;font-size:13px;background:rgba(255,255,255,.88);box-sizing:border-box;transition:border-color .15s,box-shadow .15s;color:#1e293b}
.sp-input:focus{outline:none;border-color:#3b82f6;box-shadow:0 0 0 2px rgba(59,130,246,.1)}
.sp-input::placeholder{color:#cbd5e1}
.sp-input[readonly]{background:#f8fafc;color:#334155}
.sp-textarea{width:100%;min-height:44px;padding:7px 10px;border:1px solid rgba(203,213,225,.88);border-radius:8px;font-size:13px;background:rgba(255,255,255,.88);box-sizing:border-box;transition:border-color .15s,box-shadow .15s;color:#1e293b;resize:vertical;line-height:1.45}
.sp-textarea:focus{outline:none;border-color:#3b82f6;box-shadow:0 0 0 2px rgba(59,130,246,.1)}
.sp-textarea::placeholder{color:#cbd5e1}
.sp-input-row{display:flex;gap:10px;align-items:center}
.sp-input-row .sp-input{flex:1}
.sp-input-btn{display:inline-flex;align-items:center;outline:none;padding:5px 14px;border:2px dashed #64748b;border-radius:15px;background-color:#f1f5f9;color:#64748b;font-size:11px;font-weight:600;cursor:pointer;transition:transform .2s ease-out;box-shadow:0 0 0 3px #f1f5f9,1.5px 1.5px 3px 1px rgba(0,0,0,.15);white-space:nowrap}
.sp-input-btn:hover:not(:disabled){transform:translateY(-4px) translateX(-2px);box-shadow:0 0 0 3px #f1f5f9,2px 5px 0 0 currentColor}
.sp-input-btn:active:not(:disabled){transform:translateY(1px) translateX(1px);box-shadow:0 0 0 3px #f1f5f9,0 0 0 0 currentColor}
.sp-input-btn:disabled{opacity:.5;cursor:not-allowed}
.sp-otp-row{display:flex;gap:10px;align-items:center}
.sp-otp-boxes{display:flex;gap:6px}
.sp-otp-cell{width:36px;height:40px;border:1px solid rgba(203,213,225,.88);border-radius:8px;font-size:18px;font-weight:600;text-align:center;background:rgba(255,255,255,.88);color:#1e293b;transition:border-color .15s,box-shadow .15s;padding:0;box-sizing:border-box}
.sp-otp-cell:focus{outline:none;border-color:#3b82f6;box-shadow:0 0 0 2px rgba(59,130,246,.15)}
.sp-otp-cell:disabled{opacity:.5;background:#f8fafc;cursor:not-allowed}
.sp-status-row{display:flex;align-items:center;gap:8px;margin-bottom:8px;flex-wrap:wrap}
.sp-status-pill{display:inline-flex;align-items:center;height:20px;padding:0 8px;border-radius:999px;font-size:11px;font-weight:600}
.sp-status-pill.ok{background:#ecfdf5;color:#059669}
.sp-status-pill.pending{background:#eff6ff;color:#2563eb}
.sp-status-pill.warn{background:#fff7ed;color:#c2410c}
.sp-status-pill.info{background:#f8fafc;color:#475569}
.sp-warning{font-size:12px;color:#b45309;background:#fff7ed;border:1px solid #fed7aa;border-radius:8px;padding:8px 10px}
.sp-actions{display:flex;align-items:center;gap:14px;margin-top:14px}
.sp-actions-end{justify-content:flex-end}
.sp-header-btn{display:inline-flex;align-items:center;outline:none;padding:5px 14px;border:2px dashed #64748b;border-radius:15px;background-color:#f1f5f9;color:#64748b;font-size:11px;font-weight:600;cursor:pointer;transition:transform .2s ease-out;box-shadow:0 0 0 3px #f1f5f9,1.5px 1.5px 3px 1px rgba(0,0,0,.15);white-space:nowrap}
.sp-header-btn:hover:not(:disabled){transform:translateY(-4px) translateX(-2px);box-shadow:0 0 0 3px #f1f5f9,2px 5px 0 0 currentColor}
.sp-header-btn:active:not(:disabled){transform:translateY(1px) translateX(1px);box-shadow:0 0 0 3px #f1f5f9,0 0 0 0 currentColor}
.sp-header-btn:disabled{opacity:.5;cursor:not-allowed}
.sp-header-btn-primary{border-color:#075985;color:#075985;background-color:#f0f9ff;box-shadow:0 0 0 3px #f0f9ff,1.5px 1.5px 3px 1px rgba(0,0,0,.15)}
.sp-header-btn-primary:hover:not(:disabled){box-shadow:0 0 0 3px #f0f9ff,2px 5px 0 0 #075985}
.sp-tag-row{display:flex;align-items:center;gap:8px;flex-wrap:wrap;margin-bottom:14px}
.sp-tag{display:inline-flex;align-items:center;height:24px;padding:0 10px;border-radius:999px;background:#f8fafc;border:1px solid #e2e8f0;color:#475569;font-size:11px;font-weight:600}
.sp-protocol-grid{display:grid;grid-template-columns:repeat(3,1fr);gap:8px;margin-top:12px}
.sp-protocol-item{background:#f8fafc;border-radius:8px;padding:8px}
.sp-protocol-label{display:block;font-size:11px;color:#64748b}
.sp-protocol-value{display:block;font-size:12px;color:#1f2937;font-weight:600;margin-top:2px;word-break:break-word}
.sp-inline-note{font-size:11px;color:#94a3b8;margin-top:6px}
.sp-modal-mask{position:fixed;inset:0;background:rgba(15,23,42,.35);display:flex;align-items:center;justify-content:center;z-index:99}
.sp-modal{width:380px;max-width:calc(100vw - 32px);background:#fff;border:2px dashed #64748b;border-radius:18px;padding:18px 20px;box-shadow:0 0 0 3px #fff,4px 6px 0 0 rgba(15,23,42,.12)}
.sp-modal-title{font-size:14px;font-weight:600;color:#0f172a}
.sp-modal-sub{font-size:12px;color:#64748b;margin-top:4px;margin-bottom:12px;line-height:1.6}
.sp-card--violet{background:transparent}
.sp-card-body--stack{display:flex;flex-direction:column;gap:10px}
.sp-toggle-item{display:flex;align-items:center;justify-content:space-between;padding:12px;border:1px solid rgba(221,214,254,.62);border-radius:12px;background:linear-gradient(135deg,rgba(255,255,255,.9),rgba(245,243,255,.72));gap:12px}
.sp-card--cyan .sp-toggle-item{border-color:rgba(125,211,252,.58);background:linear-gradient(135deg,rgba(255,255,255,.92),rgba(236,254,255,.72))}
.sp-toggle-last{border-bottom:none}
.sp-toggle-info{display:flex;flex-direction:column;gap:4px;min-width:0}
.sp-toggle-label{font-size:13px;font-weight:500;color:#1e293b}
.sp-toggle-desc{font-size:11px;color:#94a3b8;line-height:1.55}
.sp-toggle{position:relative;display:inline-block;width:36px;height:20px;flex-shrink:0}
.sp-toggle input{opacity:0;width:0;height:0}
.sp-toggle-slider{position:absolute;cursor:pointer;inset:0;background:#e2e8f0;border-radius:20px;transition:.2s}
.sp-toggle-slider::before{content:'';position:absolute;height:16px;width:16px;left:2px;bottom:2px;background:#fff;border-radius:50%;transition:.2s;box-shadow:0 1px 3px rgba(0,0,0,.1)}
.sp-toggle input:checked+.sp-toggle-slider{background:linear-gradient(90deg,#8b5cf6,#06b6d4)}
.sp-toggle input:checked+.sp-toggle-slider::before{transform:translateX(16px)}
.sp-consent-block{margin-bottom:12px;padding:10px 12px;border:1px dashed #cbd5e1;border-radius:12px;background:#f8fafc}
.sp-consent-details{margin:0}
.sp-consent-summary{cursor:pointer;font-size:12px;font-weight:600;color:#3b82f6;outline:none;list-style:none;display:inline-flex;align-items:center;gap:4px}
.sp-consent-summary::-webkit-details-marker{display:none}
.sp-consent-summary::before{content:"▸";display:inline-block;transition:transform .15s ease;color:#94a3b8}
.sp-consent-details[open] .sp-consent-summary::before{transform:rotate(90deg)}
.sp-consent-summary:hover{color:#2563eb}
.sp-consent-text{margin-top:8px;padding:10px 12px;background:#fff;border:1px solid #e2e8f0;border-radius:8px;font-size:12px;line-height:1.65;color:#334155;max-height:320px;overflow-y:auto}
.sp-consent-text p{margin:0 0 6px 0}
.sp-consent-text p:last-child{margin-bottom:0}
.sp-consent-text strong{color:#0f172a;font-weight:600}
.sp-consent-text ul{margin:4px 0 6px 18px;padding:0}
.sp-consent-text li{margin:3px 0;list-style:disc}
.sp-consent-section{margin-bottom:12px;padding-bottom:8px;border-bottom:1px dashed #e2e8f0}
.sp-consent-section:last-child{margin-bottom:0;padding-bottom:0;border-bottom:none}
.sp-consent-section-title{font-weight:600;color:#0f172a;font-size:12.5px;margin:0 0 4px 0}
.sp-consent-check{margin-top:10px;display:flex;align-items:flex-start;gap:8px;cursor:pointer;font-size:12px;line-height:1.5;color:#1e293b;user-select:none}
.sp-consent-check input[type=checkbox]{margin-top:2px;width:14px;height:14px;cursor:pointer;accent-color:#3b82f6;flex-shrink:0}
.sp-consent-check input[type=checkbox]:disabled{cursor:not-allowed;opacity:.5}
.sp-consent-check span{flex:1}
</style>
