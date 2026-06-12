<script lang="ts" setup>
defineProps<{
  text?: string;
  hint?: string;
  framed?: boolean;
}>();
</script>

<template>
  <div class="sp-empty-state" :class="{ 'sp-empty-state--framed': framed === true }">
    <svg class="sp-empty-state-icon" viewBox="0 0 1024 1024" xmlns="http://www.w3.org/2000/svg" width="120" height="120">
      <path d="M217.0088 482.0912l315.36-64.8v133.2z" fill="#416191"/>
      <path d="M851.3288 482.0912l-318.96-64.8v133.2zM211.2488 881.6912l321.12 76.32v-419.76l-321.12-52.56z" fill="#5074AE"/>
      <path d="M853.4888 881.6912l-321.12 76.32v-419.76l321.12-52.56z" fill="#40608F"/>
      <path d="M532.3688 538.2512l88.56 169.92 318.96-92.88-88.56-133.2z" fill="#4B6F9B"/>
      <path d="M535.9688 538.2512l-88.56 169.92-318.96-92.88 88.56-133.2z" fill="#6A90C0"/>
      <path d="M459.44 62c-25.92 11.52-43.2 20.88-52.56 28.08-13.68 11.52-28.08 44.64-40.32 48.24-12.24 4.32 48.24-4.32 68.4-15.84 13.68-7.92 21.6-28.08 24.48-60.48zM869.84 314c-32.4-11.52-56.16-17.28-72-16.56-24.48 1.44-61.2 3.6-72.72-1.44-11.52-5.04 42.48 43.92 75.6 47.52 20.88 2.16 43.92-7.92 69.12-29.52zM354.32 324.08c-18.72 7.92-30.96 15.12-36 20.16-7.92 7.92-20.16 20.16-32.4 24.48-12.24 4.32 32.4 7.92 48.24 0 10.8-5.04 18-20.16 20.16-44.64z" fill="#6A90C0"/>
    </svg>
    <div class="sp-empty-state-text">{{ text || "暂无数据" }}</div>
    <div v-if="hint" class="sp-empty-state-hint">{{ hint }}</div>
  </div>
</template>

<style scoped>
.sp-empty-state{padding:64px 16px;text-align:center;display:flex;flex-direction:column;align-items:center;gap:12px}
.sp-empty-state-icon{opacity:.4}
.sp-empty-state-text{font-size:14px;font-weight:600;color:#64748b}
.sp-empty-state-hint{font-size:12px;color:#94a3b8}
.sp-empty-state--framed{
  background:rgba(255,255,255,.6);
  border:1px dashed #cbd5e1;
  border-radius:14px;
  min-height:240px;
  justify-content:center;
}
</style>
