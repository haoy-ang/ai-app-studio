<template>
  <div class="empty-state">
    <!-- 自绘空状态：一张"正在输入的空画布"，虚框 + 三行占位文本 -->
    <svg class="empty-art" width="112" height="80" viewBox="0 0 112 80" aria-hidden="true">
      <rect class="frame" x="16" y="10" width="80" height="60" rx="9" />
      <path class="line" d="M30 28h52M30 40h36M30 52h44" />
      <circle class="dot" cx="97" cy="17" r="3" />
      <circle class="dot dot-alt" cx="13" cy="59" r="2.5" />
    </svg>
    <p class="empty-title">{{ title }}</p>
    <p v-if="description" class="empty-desc">{{ description }}</p>
    <button v-if="actionText" type="button" class="empty-action" @click="emit('action')">
      {{ actionText }}
    </button>
  </div>
</template>

<script setup lang="ts">
/**
 * 统一空状态。
 *
 * 替换原先直接散落在各页面的裸 <a-empty>：Antd 默认的灰色线描图 +
 * "No Data" 气泡是全站最"组件库默认值"的一处，且每个页面都用一次、改不动。
 * 收敛成一个组件后，首页、后台三页、聊天页共用同一套视觉。
 */
withDefaults(
  defineProps<{
    title: string
    description?: string
    /** 给了文案才渲染引导按钮；不给就是纯展示 */
    actionText?: string
  }>(),
  {
    description: '',
    actionText: '',
  },
)

const emit = defineEmits<{ (e: 'action'): void }>()
</script>

<style scoped>
.empty-state {
  /* 限宽居中：整块铺满 1200px 会显得空旷，收窄后反而像一个有意的"占位卡片" */
  max-width: 520px;
  margin: 0 auto;
  padding: var(--sp-7) var(--sp-5);
  text-align: center;
  background: var(--bg-card);
  border: 1px solid var(--border-1);
  border-radius: var(--radius-xl);
}

.empty-art {
  display: block;
  margin: 0 auto;
}

.frame {
  fill: none;
  stroke: var(--border-strong);
  stroke-width: 1.5;
  stroke-dasharray: 5 5;
}

.line {
  fill: none;
  stroke: var(--border-strong);
  stroke-width: 3;
  stroke-linecap: round;
  opacity: 0.75;
}

.dot {
  fill: var(--accent);
  opacity: 0.6;
}

.dot-alt {
  fill: var(--brand);
  opacity: 0.35;
}

.empty-title {
  margin: var(--sp-4) 0 var(--sp-1);
  font-size: var(--fs-md);
  font-weight: 650;
  color: var(--text-1);
  letter-spacing: -0.2px;
}

.empty-desc {
  margin: 0;
  font-size: var(--fs-sm);
  color: var(--text-3);
}

.empty-action {
  margin-top: var(--sp-4);
  padding: 7px 16px;
  font-family: inherit;
  font-size: var(--fs-sm);
  font-weight: 600;
  color: var(--text-1);
  background: var(--bg-card);
  border: 1px solid var(--border-1);
  border-radius: var(--radius-full);
  cursor: pointer;
  transition: all var(--dur) var(--ease);
}

.empty-action:hover {
  color: var(--brand);
  border-color: var(--brand);
  background: var(--brand-soft);
}
</style>
