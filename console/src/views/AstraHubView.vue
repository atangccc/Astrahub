<script lang="ts" setup>
import { onMounted, ref } from "vue";
import { VLoading } from "@halo-dev/components";
import { useConfigMap } from "../composables/useConfigMap";

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
const { loading, saving, settings, fetchSettings, saveSettings } = useConfigMap();

function refreshRelationGraph() {
  relationRefreshSignal.value += 1;
}

onMounted(() => {
  fetchSettings();
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
          <svg viewBox="0 0 1142 1024" width="26" height="23">
            <path d="M595.895182 473.097102m-420.977778 0a420.977778 420.977778 0 1 0 841.955556 0 420.977778 420.977778 0 1 0-841.955556 0Z" fill="#9BC0D1" /><path d="M595.895182 74.87488c219.582009 0 398.222222 178.640213 398.222222 398.222222s-178.640213 398.222222-398.222222 398.222222-398.222222-178.640213-398.222222-398.222222c0-219.577458 178.644764-398.222222 398.222222-398.222222z m-443.733333 398.222222c0 244.676836 199.056498 443.733333 443.733333 443.733334s443.733333-199.056498 443.733334-443.733334-199.056498-443.733333-443.733334-443.733333-443.733333 199.056498-443.733333 443.733333z" fill="#6E6E96" /><path d="M567.00928 430.557867m-332.927431 0a332.927431 332.927431 0 1 0 665.854862 0 332.927431 332.927431 0 1 0-665.854862 0Z" fill="#ACD5E8" /><path d="M653.152711 276.803129m-97.848889 0a97.848889 97.848889 0 1 0 195.697778 0 97.848889 97.848889 0 1 0-195.697778 0Z" fill="#FFE4AD" /><path d="M878.760391 180.123876c102.623004-17.658311 174.175573-7.991751 191.424285 25.863964 34.679467 68.098276-131.527111 248.317724-425.483378 398.031076-218.999467 111.556836-439.41888 167.535502-536.025316 136.123733-18.181689-5.911893-30.328604-14.668231-36.113066-26.018702-19.33312-37.965369 26.036907-112.649102 118.401706-194.914987a22.755556 22.755556 0 0 0-30.273991-33.987698C47.222329 586.283236 1.520071 674.916124 32.007964 734.776889c11.459698 22.500693 32.52224 38.866489 62.595983 48.642275 108.858027 35.375787 338.229476-20.411733 570.750293-138.863502 261.465884-133.174613 506.411236-339.380907 445.376284-459.238969-27.971129-54.922809-110.846862-72.235236-239.688817-50.062222a22.769209 22.769209 0 0 0 7.718684 44.869405z" fill="#6E6E96" />
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
            <button v-for="t in [{id:'all',label:'全部'},{id:'pending',label:'待审核'},{id:'accepted',label:'已通过'},{id:'rejected',label:'已拒绝'},{id:'outbox',label:'发出的'}]" :key="t.id" class="ah-topbar-tab" :class="{ active: friendTab === t.id }" @click="friendTab = t.id as FriendTab">{{ t.label }}</button>
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
