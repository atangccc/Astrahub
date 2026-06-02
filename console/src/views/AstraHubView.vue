<script lang="ts" setup>
import { onMounted, ref } from "vue";
import { VLoading } from "@halo-dev/components";
import { useConfigMap } from "../composables/useConfigMap";
import { fetchFriendInvitations } from "../composables/useFriendInvitations";

import ConnectionSettings from "../components/settings/ConnectionSettings.vue";
import FriendInvitationManager from "../components/settings/FriendInvitationManager.vue";
import NewsHubPanel from "../components/settings/NewsHubPanel.vue";
import PlanetLinksPanel from "../components/settings/PlanetLinksPanel.vue";
import RelationGraphPanel from "../components/settings/RelationGraphPanel.vue";

type NavId = "maintenance" | "planetLinks" | "friendManagement" | "news" | "relationGraph";
type FriendTab = "all" | "pending" | "accepted" | "rejected" | "outbox";
type PlanetFilter = "all" | "pendingBack" | "following" | "mutual" | "favorites";

const activeNav = ref<NavId>("planetLinks");
const friendTab = ref<FriendTab>("all");
const planetFilter = ref<PlanetFilter>("all");
const planetSearch = ref("");
const newsSearch = ref("");
const relationRefreshSignal = ref(0);
// 收件箱待审核数量：用于「友链管理」导航 + 「待审核」tab 的红色提醒角标。
const pendingInboxCount = ref(0);
const { loading, saving, settings, fetchSettings, saveSettings } = useConfigMap();

function refreshRelationGraph() {
  relationRefreshSignal.value += 1;
}

// 拉取收件箱 pending 数量。失败静默（不打扰用户），角标仅在 >0 时显示。
async function refreshPendingInboxCount() {
  try {
    const resp = await fetchFriendInvitations("inbox", "pending");
    pendingInboxCount.value = Array.isArray(resp.items) ? resp.items.length : 0;
  } catch {
    pendingInboxCount.value = 0;
  }
}

onMounted(() => {
  fetchSettings();
  refreshPendingInboxCount();
});
</script>

<template>
  <div class="ah-page">
    <div class="ah-card">
      <!-- 左侧悬浮导航（位于大容器内，ah-body 外） -->
      <div class="ah-float-nav">
        <button class="ah-float-btn" :class="{ active: activeNav === 'planetLinks' }" title="友链星球" @click="activeNav = 'planetLinks'">
          <svg viewBox="0 0 24 24" fill="none" width="16" height="16"><path d="M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" /><path d="M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" /></svg>
        </button>
        <button class="ah-float-btn" :class="{ active: activeNav === 'friendManagement' }" title="友链管理" @click="activeNav = 'friendManagement'">
          <svg viewBox="0 0 24 24" fill="none" width="16" height="16"><path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2" stroke="currentColor" stroke-width="2" /><circle cx="9" cy="7" r="4" stroke="currentColor" stroke-width="2" /><path d="M23 21v-2a4 4 0 0 0-3-3.87M16 3.13a4 4 0 0 1 0 7.75" stroke="currentColor" stroke-width="2" /></svg>
          <span v-if="pendingInboxCount > 0" class="ah-nav-badge">{{ pendingInboxCount > 99 ? '99+' : pendingInboxCount }}</span>
        </button>
        <button class="ah-float-btn" :class="{ active: activeNav === 'news' }" title="资讯" @click="activeNav = 'news'">
          <svg viewBox="0 0 24 24" fill="none" width="16" height="16"><path d="M4 11a9 9 0 0 1 9 9" stroke="currentColor" stroke-width="2" stroke-linecap="round" /><path d="M4 4a16 16 0 0 1 16 16" stroke="currentColor" stroke-width="2" stroke-linecap="round" /><circle cx="5" cy="19" r="1.6" fill="currentColor" /></svg>
        </button>
        <button class="ah-float-btn" :class="{ active: activeNav === 'relationGraph' }" title="关系图" @click="activeNav = 'relationGraph'">
          <svg viewBox="0 0 24 24" fill="none" width="16" height="16"><circle cx="12" cy="12" r="2.5" stroke="currentColor" stroke-width="2" /><circle cx="5" cy="6" r="1.8" stroke="currentColor" stroke-width="2" /><circle cx="19" cy="6" r="1.8" stroke="currentColor" stroke-width="2" /><circle cx="5" cy="18" r="1.8" stroke="currentColor" stroke-width="2" /><circle cx="19" cy="18" r="1.8" stroke="currentColor" stroke-width="2" /><line x1="6.5" y1="7" x2="10.5" y2="11" stroke="currentColor" stroke-width="2" /><line x1="17.5" y1="7" x2="13.5" y2="11" stroke="currentColor" stroke-width="2" /><line x1="6.5" y1="17" x2="10.5" y2="13" stroke="currentColor" stroke-width="2" /><line x1="17.5" y1="17" x2="13.5" y2="13" stroke="currentColor" stroke-width="2" /></svg>
        </button>
        <button class="ah-float-btn" :class="{ active: activeNav === 'maintenance' }" title="接入配置" @click="activeNav = 'maintenance'">
          <svg viewBox="0 0 24 24" fill="none" width="16" height="16"><path d="M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6z" stroke="currentColor" stroke-width="2" /><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06A1.65 1.65 0 0 0 4.68 15a1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06A1.65 1.65 0 0 0 9 4.68 1.65 1.65 0 0 0 10 3.17V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06A1.65 1.65 0 0 0 19.4 9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z" stroke="currentColor" stroke-width="2" /></svg>
        </button>
      </div>

      <!-- 四角装饰 -->
      <div class="ah-corner ah-corner-tl"></div>
      <div class="ah-corner ah-corner-tr"></div>
      <div class="ah-corner ah-corner-bl"></div>
      <div class="ah-corner ah-corner-br"></div>

      <!-- 顶部栏 -->
      <div class="ah-topbar">
        <div class="ah-topbar-left">
          <svg viewBox="0 0 1024 1024" width="26" height="26" aria-hidden="true">
            <path d="M970.474667 521.813333a14.08 14.08 0 0 0-10.24 0l-49.92 29.013334a13.653333 13.653333 0 0 0-5.12 18.346666 13.653333 13.653333 0 0 0 18.346666 4.693334l49.92-29.013334a13.226667 13.226667 0 0 0 5.12-17.92 13.226667 13.226667 0 0 0-8.106666-5.12zM754.581333 218.026667l23.466667-13.653334a29.013333 29.013333 0 0 0 10.666667-39.68 28.586667 28.586667 0 0 0-39.68-10.666666L576.234667 256a28.586667 28.586667 0 0 1-15.786667 0 29.013333 29.013333 0 0 1-17.92-13.653333 29.866667 29.866667 0 0 1 12.373333-40.96l85.333334-50.346667a390.826667 390.826667 0 0 0-526.933334 368.213333 394.24 394.24 0 0 0 37.973334 170.666667l-56.32 32.853333a20.053333 20.053333 0 0 0-7.253334 27.306667 19.626667 19.626667 0 0 0 12.373334 9.386667 20.053333 20.053333 0 0 0 14.933333-2.133334L243.434667 682.666667a32 32 0 0 1 34.56 16.213333 33.28 33.28 0 0 1-3.413334 38.826667L71.488 853.333333a29.44 29.44 0 0 0-10.666667 39.68 30.72 30.72 0 0 0 17.92 13.653334 29.866667 29.866667 0 0 0 21.76-2.986667L388.501333 738.133333a14.506667 14.506667 0 0 1 8.96 6.826667 15.36 15.36 0 0 1-5.546666 20.48l-119.466667 69.546667A389.12 389.12 0 0 0 834.794667 725.333333l-39.68 23.04a15.36 15.36 0 0 1-11.52 0 14.506667 14.506667 0 0 1-8.96-6.826666 14.933333 14.933333 0 0 1 5.546666-20.48l81.066667-47.36a395.093333 395.093333 0 0 0-106.666667-459.093334zM1016.981333 494.933333a12.373333 12.373333 0 0 0-13.226666 0 11.946667 11.946667 0 0 0-6.4 11.52 12.373333 12.373333 0 0 0 6.4 11.52 12.373333 12.373333 0 0 0 13.226666 0 13.226667 13.226667 0 0 0 6.4-11.52 12.373333 12.373333 0 0 0-6.4-11.52zM81.301333 357.12L85.568 354.133333a14.08 14.08 0 0 0 0-5.12v-2.986666A14.08 14.08 0 0 0 85.568 341.333333l-4.266667-3.413333h-27.733333v-23.466667a14.08 14.08 0 0 0 0-5.12l-2.986667-2.986666L42.901333 305.493333H37.781333a6.826667 6.826667 0 0 0-2.986666 2.986667 8.533333 8.533333 0 0 0 0 5.12v23.466667h-29.866667L0.234667 341.333333a14.08 14.08 0 0 0 0 5.12v2.986667a14.08 14.08 0 0 0 0 4.693333l2.986666 2.986667a8.533333 8.533333 0 0 0 5.12 0h24.32v22.613333a8.533333 8.533333 0 0 0 0.853334 4.266667 6.826667 6.826667 0 0 0 2.986666 2.986667H42.901333a14.08 14.08 0 0 0 5.12 0A9.813333 9.813333 0 0 0 52.714667 384a14.08 14.08 0 0 0 0-5.12v-20.906667h22.613333a8.533333 8.533333 0 0 0 5.973333-0.853333zM810.901333 165.12a27.733333 27.733333 0 0 0 28.16 0 28.16 28.16 0 1 0-28.16 0zM876.608 853.333333h-16.64v-12.8a6.826667 6.826667 0 0 0 0-3.84 3.84 3.84 0 0 0-2.56-2.56 6.826667 6.826667 0 0 0-3.84 0 5.12 5.12 0 0 0-3.84 0 5.546667 5.546667 0 0 0-2.986667 2.56 12.373333 12.373333 0 0 0 0 3.84v12.8h-16.64l-2.986666 2.986667a10.24 10.24 0 0 0 0 3.84 12.373333 12.373333 0 0 0 0 3.84l2.986666 2.986667h16.64v12.8a12.373333 12.373333 0 0 0 0 3.84 5.546667 5.546667 0 0 0 2.986667 2.56 5.12 5.12 0 0 0 3.84 0 6.826667 6.826667 0 0 0 3.84 0 3.84 3.84 0 0 0 2.56-2.56 6.826667 6.826667 0 0 0 0-3.84v-12.8h16.64s2.133333 0 2.56-2.986667a6.826667 6.826667 0 0 0 0-3.84 5.12 5.12 0 0 0 0-3.84s-1.706667-2.986667-2.56-2.986667z" fill="#8081FF" />
          </svg>
          <span class="ah-topbar-brand">ASTRA<span class="ah-topbar-accent">HUB</span></span>
          <span class="ah-topbar-page-title" v-html="activeNav === 'maintenance' ? '星链<span class=ah-kw>接入配置</span>' : activeNav === 'planetLinks' ? '<span class=ah-kw>友链</span>星球' : activeNav === 'friendManagement' ? '<span class=ah-kw>友链</span>管理' : activeNav === 'relationGraph' ? '<span class=ah-kw>关系</span>图' : '星链<span class=ah-kw>资讯</span>'"></span>
        </div>
        <div class="ah-topbar-right">
          <template v-if="activeNav === 'planetLinks'">
            <button v-for="t in [{id:'all',label:'全部'},{id:'favorites',label:'已收藏'},{id:'pendingBack',label:'未关注'},{id:'following',label:'已关注'},{id:'mutual',label:'互相关注'}]" :key="t.id" class="ah-topbar-tab" :class="{ active: planetFilter === t.id }" @click="planetFilter = t.id as PlanetFilter">{{ t.label }}</button>
            <div class="ah-topbar-search">
              <svg viewBox="0 0 24 24" fill="none" class="ah-topbar-search-icon" aria-hidden="true">
                <circle cx="11" cy="11" r="7" stroke="currentColor" stroke-width="2" />
                <path d="M20 20l-3.5-3.5" stroke="currentColor" stroke-width="2" stroke-linecap="round" />
              </svg>
              <input v-model="planetSearch" type="search" class="ah-topbar-search-input" placeholder="搜索友链" />
            </div>
          </template>
          <template v-if="activeNav === 'friendManagement'">
            <button v-for="t in [{id:'all',label:'全部'},{id:'pending',label:'待审核'},{id:'accepted',label:'已通过'},{id:'rejected',label:'已拒绝'},{id:'outbox',label:'发出的'}]" :key="t.id" class="ah-topbar-tab ah-topbar-tab--rel" :class="{ active: friendTab === t.id }" @click="friendTab = t.id as FriendTab">
              {{ t.label }}
              <span v-if="t.id === 'pending' && pendingInboxCount > 0" class="ah-tab-badge">{{ pendingInboxCount > 99 ? '99+' : pendingInboxCount }}</span>
            </button>
          </template>
          <template v-if="activeNav === 'news'">
            <div class="ah-topbar-search">
              <svg viewBox="0 0 24 24" fill="none" class="ah-topbar-search-icon" aria-hidden="true">
                <circle cx="11" cy="11" r="7" stroke="currentColor" stroke-width="2" />
                <path d="M20 20l-3.5-3.5" stroke="currentColor" stroke-width="2" stroke-linecap="round" />
              </svg>
              <input v-model="newsSearch" type="search" class="ah-topbar-search-input" placeholder="搜索资讯" />
            </div>
          </template>
          <template v-if="activeNav === 'relationGraph'">
            <button class="ah-topbar-tab" @click="refreshRelationGraph">刷新</button>
          </template>
          <button
            v-if="activeNav === 'maintenance'"
            class="ah-topbar-tab"
            :class="{ active: true }"
            :disabled="saving"
            @click="saveSettings"
          >
            {{ saving ? '保存中...' : '保存设置' }}
          </button>
        </div>
      </div>

      <!-- 内容体容器 -->
      <div class="ah-body">
        <!-- 主内容区域 -->
        <div class="ah-content">
        <div v-if="loading" class="ah-loading">
          <div class="uv-loader"><span class="uv-loader-text">loading</span><span class="uv-load"></span></div>
        </div>

        <ConnectionSettings v-if="!loading && activeNav === 'maintenance'" :settings="settings" :saving="saving" :persist-settings="saveSettings" />
        <PlanetLinksPanel v-if="!loading && activeNav === 'planetLinks'" :settings="settings" :active-filter="planetFilter" :search-query="planetSearch" :persist-settings="saveSettings" />
        <FriendInvitationManager v-if="!loading && activeNav === 'friendManagement'" :settings="settings" :active-tab="friendTab" />
        <NewsHubPanel v-if="!loading && activeNav === 'news'" :settings="settings" :search-query="newsSearch" :persist-settings="saveSettings" />
        <RelationGraphPanel
          v-if="!loading && activeNav === 'relationGraph'"
          :settings="settings"
          :refresh-signal="relationRefreshSignal"
        />
      </div>
    </div><!-- /ah-body -->
    </div>
  </div>
</template>

<style>
.ah-page {
  padding: 16px;
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;
  height: calc(100vh - 64px);
  max-height: calc(100vh - 64px);
  box-sizing: border-box;
  font-family: "Comic Sans MS", "Yuanti SC", "圆体-简", "华文圆体", "HYWenHei-85W", "LXGW WenKai", "Microsoft YaHei UI", "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei", system-ui, sans-serif;
  letter-spacing: .02em;
}

/* 大容器 - 白色玻璃感圆角卡片 */
.ah-card {
  position: relative;
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  padding: 16px;
  padding-left: 72px;
  background: rgba(255,255,255,.75);
  backdrop-filter: blur(24px);
  border: 1px solid rgba(0,0,0,.08);
  border-radius: 24px;
  box-shadow: 0 18px 48px rgba(2,6,23,.06);
  overflow: hidden;
}

/* 四角装饰 */
.ah-corner { position: absolute; width: 24px; height: 24px; pointer-events: none; z-index: 5; }
.ah-corner-tl { top: 8px; left: 8px; border-top: 2px solid rgba(37,99,235,.2); border-left: 2px solid rgba(37,99,235,.2); border-top-left-radius: 24px; }
.ah-corner-tr { top: 8px; right: 8px; border-top: 2px solid rgba(37,99,235,.2); border-right: 2px solid rgba(37,99,235,.2); border-top-right-radius: 24px; }
.ah-corner-bl { bottom: 8px; left: 8px; border-bottom: 2px solid rgba(37,99,235,.2); border-left: 2px solid rgba(37,99,235,.2); border-bottom-left-radius: 24px; }
.ah-corner-br { bottom: 8px; right: 8px; border-bottom: 2px solid rgba(37,99,235,.2); border-right: 2px solid rgba(37,99,235,.2); border-bottom-right-radius: 24px; }

/* 顶部栏 - 圆角胶囊形 */
.ah-topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 52px;
  padding: 0 20px;
  margin-left: -56px;
  margin-bottom: 14px;
  background: rgba(255,255,255,.7);
  backdrop-filter: blur(16px);
  border: 1px solid rgba(0,0,0,.06);
  border-radius: 999px;
  box-shadow: 0 4px 16px rgba(0,0,0,.03);
  flex-shrink: 0;
  position: relative;
  z-index: 2;
}
.ah-topbar-left { display: flex; align-items: center; gap: 12px; }
.ah-topbar-brand { font-size: 15px; font-weight: 800; letter-spacing: -0.5px; color: #1e293b; }
.ah-topbar-accent { color: #7c3aed; }
.ah-topbar-page-title { font-size: 13px; font-weight: 600; color: #64748b; font-style: italic; letter-spacing: -0.2px; }
.ah-topbar-page-title :deep(.ah-kw) { color: #7c3aed; font-style: italic; }
.ah-topbar-right { display: flex; align-items: center; gap: 10px; }
.ah-topbar-btn { display: inline-flex; align-items: center; gap: 6px; height: 32px; padding: 0 14px; border-radius: 10px; border: 1px solid rgba(0,0,0,.08); background: rgba(255,255,255,.8); color: #64748b; font-size: 12px; font-weight: 600; cursor: pointer; transition: all .15s; }
.ah-topbar-btn:hover { border-color: rgba(37,99,235,.3); color: #2563eb; }
.ah-topbar-btn:disabled { opacity: .5; cursor: not-allowed; }
.ah-topbar-btn-primary { border-color: rgba(37,99,235,.3); background: rgba(37,99,235,.08); color: #2563eb; }
.ah-topbar-tab { display: inline-flex; align-items: center; outline: none; padding: 5px 14px; border: 2px dashed #64748b; border-radius: 15px; background-color: #f1f5f9; color: #64748b; font-size: 11px; font-weight: 600; cursor: pointer; transition: transform .2s ease-out; box-shadow: 0 0 0 3px #f1f5f9, 1.5px 1.5px 3px 1px rgba(0,0,0,.15); }
.ah-topbar-tab:hover { transform: translateY(-4px) translateX(-2px); box-shadow: 0 0 0 3px #f1f5f9, 2px 5px 0 0 currentColor; }
.ah-topbar-tab:active { transform: translateY(1px) translateX(1px); box-shadow: 0 0 0 3px #f1f5f9, 0 0 0 0 currentColor; }
.ah-topbar-tab.active { border-color: #075985; color: #075985; background-color: #f0f9ff; box-shadow: 0 0 0 3px #f0f9ff, 1.5px 1.5px 3px 1px rgba(0,0,0,.15); }
.ah-topbar-tab.active:hover { box-shadow: 0 0 0 3px #f0f9ff, 2px 5px 0 0 #075985; }
.ah-topbar-search { display: inline-flex; align-items: center; gap: 6px; padding: 4px 12px 4px 12px; height: 30px; border: 2px dashed #64748b; border-radius: 999px; background: #f1f5f9; box-shadow: 0 0 0 3px #f1f5f9, 1.5px 1.5px 3px 1px rgba(0,0,0,.15); transition: transform .2s ease-out; }
.ah-topbar-search:focus-within { transform: translateY(-2px) translateX(-1px); border-color: #075985; background: #f0f9ff; box-shadow: 0 0 0 3px #f0f9ff, 2px 5px 0 0 #075985; }
.ah-topbar-search-icon { width: 14px; height: 14px; color: #64748b; flex-shrink: 0; }
.ah-topbar-search:focus-within .ah-topbar-search-icon { color: #075985; }
.ah-topbar-search-input { width: 140px; height: 22px; border: none; outline: none; background: transparent; color: #0f172a; font-size: 12px; font-weight: 600; padding: 0; }
.ah-topbar-search-input::placeholder { color: #94a3b8; font-weight: 500; }
.ah-topbar-search-input::-webkit-search-cancel-button { -webkit-appearance: none; height: 12px; width: 12px; cursor: pointer; background: linear-gradient(45deg, transparent 45%, #94a3b8 45% 55%, transparent 55%), linear-gradient(-45deg, transparent 45%, #94a3b8 45% 55%, transparent 55%); }

/* 左侧悬浮导航 - 位于大容器左侧（页面级绝对定位） */
.ah-body {
  position: relative;
  flex: 1;
  min-height: 0;
  display: flex;
  background: #ffffff;
  border: 1px solid rgba(0,0,0,.06);
  border-radius: 28px;
  box-shadow: 0 4px 16px rgba(0,0,0,.03);
  overflow: hidden;
}
.ah-float-nav {
  position: absolute;
  left: 16px;
  top: 50%;
  transform: translateY(-50%);
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 10px 8px;
  background: rgba(248,250,252,.95);
  border: 1px solid rgba(0,0,0,.05);
  border-radius: 18px;
  box-shadow: 0 4px 16px rgba(0,0,0,.04);
  z-index: 20;
}
.ah-float-btn {
  position: relative;
  width: 36px;
  height: 36px;
  border-radius: 12px;
  border: none;
  background: transparent;
  color: #94a3b8;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.18s;
}
.ah-float-btn:hover { background: rgba(37,99,235,.06); color: #2563eb; }
.ah-float-btn.active { background: rgba(37,99,235,.1); color: #2563eb; box-shadow: 0 0 12px rgba(37,99,235,.1); }

/* 待审核红点角标：友链管理导航按钮右上角 */
.ah-nav-badge {
  position: absolute;
  top: 2px;
  right: 2px;
  min-width: 16px;
  height: 16px;
  padding: 0 4px;
  box-sizing: border-box;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 999px;
  background: #ef4444;
  color: #fff;
  font-size: 10px;
  font-weight: 700;
  line-height: 1;
  box-shadow: 0 0 0 2px #fff;
  pointer-events: none;
}
/* 待审核 tab 内联红点角标 */
.ah-topbar-tab--rel { position: relative; gap: 6px; }
.ah-tab-badge {
  min-width: 16px;
  height: 16px;
  padding: 0 5px;
  box-sizing: border-box;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 999px;
  background: #ef4444;
  color: #fff;
  font-size: 10px;
  font-weight: 700;
  line-height: 1;
}

/* 主内容 */
.ah-content {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  position: relative;
}
.ah-content *::-webkit-scrollbar { display: none; }
.ah-content * { scrollbar-width: none; -ms-overflow-style: none; }
.ah-loading {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(255,255,255,.8);
  z-index: 10;
}
.ah-loading .uv-loader{width:80px;height:50px;position:relative}
.ah-loading .uv-loader-text{position:absolute;top:0;padding:0;margin:0;color:#C8B6FF;animation:uvtext 3.5s ease both infinite;font-size:.8rem;letter-spacing:1px}
.ah-loading .uv-load{background-color:#9A79FF;border-radius:50px;display:block;height:16px;width:16px;bottom:0;position:absolute;transform:translateX(64px);animation:uvloading 3.5s ease both infinite}
.ah-loading .uv-load::before{position:absolute;content:"";width:100%;height:100%;background-color:#D1C2FF;border-radius:inherit;animation:uvloading2 3.5s ease both infinite}
@keyframes uvtext{0%{letter-spacing:1px;transform:translateX(0px)}40%{letter-spacing:2px;transform:translateX(26px)}80%{letter-spacing:1px;transform:translateX(32px)}90%{letter-spacing:2px;transform:translateX(0px)}100%{letter-spacing:1px;transform:translateX(0px)}}
@keyframes uvloading{0%{width:16px;transform:translateX(0px)}40%{width:100%;transform:translateX(0px)}80%{width:16px;transform:translateX(64px)}90%{width:100%;transform:translateX(0px)}100%{width:16px;transform:translateX(0px)}}
@keyframes uvloading2{0%{transform:translateX(0px);width:16px}40%{transform:translateX(0%);width:80%}80%{width:100%;transform:translateX(0px)}90%{width:80%;transform:translateX(15px)}100%{transform:translateX(0px);width:16px}}
</style>
