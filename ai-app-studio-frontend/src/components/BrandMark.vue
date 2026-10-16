<template>
  <span
    class="brand-mark"
    :style="{ width: `${size}px`, height: `${size}px`, borderRadius: `${size * 0.28}px` }"
  >
    <svg :width="size" :height="size" viewBox="0 0 32 32" aria-hidden="true" focusable="false">
      <!-- 三格已完成 -->
      <rect x="6.5" y="6.5" width="8.4" height="8.4" rx="2.2" fill="#fff" fill-opacity="0.42" />
      <rect x="17.1" y="6.5" width="8.4" height="8.4" rx="2.2" fill="#fff" fill-opacity="0.42" />
      <rect x="6.5" y="17.1" width="8.4" height="8.4" rx="2.2" fill="#fff" fill-opacity="0.42" />
      <!-- 第四格：正在生成的那一格 -->
      <rect
        :class="{ 'cell-active': pulse }"
        x="17.1"
        y="17.1"
        width="8.4"
        height="8.4"
        rx="2.2"
        fill="#fff"
      />
    </svg>
  </span>
</template>

<script setup lang="ts">
/**
 * 品牌标识：蓝底四宫格，其中第四格是实心的。
 *
 * 沿用项目已有的 favicon 造型（蓝底四宫格），只把第四格做成实心 ——
 * 语义是「三格已完成、一格正在生成」，正好是这个产品的核心动作。
 * 这样品牌符号不是凭空造的，和 favicon / 网页图标是同一套语言。
 */
withDefaults(
  defineProps<{
    /** 边长（px） */
    size?: number
    /** 第四格是否呼吸（只在"正在生成"的语境下开启，平时保持静止） */
    pulse?: boolean
  }>(),
  {
    size: 32,
    pulse: false,
  },
)
</script>

<style scoped>
.brand-mark {
  display: inline-grid;
  place-items: center;
  flex-shrink: 0;
  background: var(--brand);
  /* 一点点内高光，避免纯色块显得像贴纸 */
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.22);
}

.cell-active {
  animation: cell-breathe 1.8s var(--ease) infinite;
  transform-origin: center;
}

@keyframes cell-breathe {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0.35;
  }
}

@media (prefers-reduced-motion: reduce) {
  .cell-active {
    animation: none;
  }
}
</style>
