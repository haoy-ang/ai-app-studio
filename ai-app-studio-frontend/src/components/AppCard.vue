<template>
  <div class="app-card" :class="{ 'app-card--featured': featured }">
    <div class="app-preview">
      <img
        v-if="app.cover && !coverError"
        :src="app.cover"
        :alt="app.appName"
        @error="coverError = true"
      />
      <!-- 无封面兜底：蓝图网格 + 品牌图标（原先是一块灰底 + 🤖 emoji） -->
      <div v-else class="app-placeholder">
        <svg
          class="placeholder-icon"
          width="40"
          height="40"
          viewBox="0 0 32 32"
          fill="none"
          stroke="currentColor"
          stroke-width="1.6"
          stroke-linejoin="round"
          aria-hidden="true"
        >
          <rect x="4" y="4" width="10" height="10" rx="2.5" />
          <rect x="18" y="4" width="10" height="10" rx="2.5" opacity="0.45" />
          <rect x="4" y="18" width="10" height="10" rx="2.5" opacity="0.45" />
          <rect x="18" y="18" width="10" height="10" rx="2.5" opacity="0.45" />
        </svg>
      </div>

      <!-- 角标：数据全部来自现成的 AppVO 字段，不需要后端加东西 -->
      <span v-if="featured" class="badge badge--featured">模板</span>
      <span v-if="typeLabel" class="badge badge--type">{{ typeLabel }}</span>

      <div class="app-overlay">
        <a-space>
          <a-button type="primary" @click="handleViewChat">查看对话</a-button>
          <a-button v-if="app.deployKey" type="default" @click="handleViewWork">查看作品</a-button>
        </a-space>
      </div>
    </div>
    <div class="app-info">
      <div class="app-info-left">
        <a-avatar :src="app.user?.userAvatar || defaultAvatar" :size="36" />
      </div>
      <div class="app-info-right">
        <h3 class="app-title">{{ app.appName || '未命名应用' }}</h3>
        <p class="app-author">
          {{ app.user?.userName?.trim() || (featured ? '官方' : '用户') }}
        </p>
      </div>
      <!--
        作品管理入口：只在自己的作品里出现。
        精选区的卡片是别人的东西，不提供任何修改动作。
        @click.stop 是为了将来给卡片加整卡点击时，菜单不会连带触发。
      -->
      <div v-if="owned" class="app-actions" @click.stop>
        <a-dropdown :trigger="['click']" placement="bottomRight">
          <button type="button" class="more-btn" aria-label="作品管理">⋯</button>
          <template #overlay>
            <a-menu @click="handleMenuClick">
              <a-menu-item key="edit">重命名</a-menu-item>
              <a-menu-item key="download" :disabled="app.codeReady === false">下载代码</a-menu-item>
              <a-menu-item key="deploy">重新部署</a-menu-item>
              <a-menu-item key="version">版本历史</a-menu-item>
              <a-menu-divider />
              <a-menu-item key="delete" danger>删除应用</a-menu-item>
            </a-menu>
          </template>
        </a-dropdown>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import defaultAvatar from '@/assets/defaultAvatar.png'
import { formatCodeGenTypeShort } from '@/utils/codeGenTypes'

interface Props {
  app: API.AppVO
  featured?: boolean
  /** 是否为自己的作品：只有自己的才显示「⋯」管理菜单 */
  owned?: boolean
}

interface Emits {
  (e: 'view-chat', appId: string | number | undefined): void
  (e: 'view-work', app: API.AppVO): void
  (e: 'edit', app: API.AppVO): void
  (e: 'download', app: API.AppVO): void
  (e: 'deploy', app: API.AppVO): void
  (e: 'version', app: API.AppVO): void
  (e: 'delete', app: API.AppVO): void
}

const props = withDefaults(defineProps<Props>(), {
  featured: false,
  owned: false,
})

const emit = defineEmits<Emits>()

// 封面右上角的生成类型角标（HTML / 多文件 / Vue），取不到就不显示
const typeLabel = computed(() => formatCodeGenTypeShort(props.app.codeGenType))

// 封面加载失败时降级为占位图：封面生成失败、COS 挂了、外链失效三种情况一起兜住
const coverError = ref(false)
watch(
  () => props.app.cover,
  () => {
    coverError.value = false
  },
)

const handleViewChat = () => {
  emit('view-chat', props.app.id)
}

const handleViewWork = () => {
  emit('view-work', props.app)
}

/**
 * 把 a-menu 的 key 映射成独立事件
 * <p>
 * 父组件更愿意写 `@delete="..."` 而不是在一堆字符串 key 里 switch ——
 * 而且事件是强类型的，改名字时 TS 会报错。
 */
const handleMenuClick = ({ key }: { key: string | number }) => {
  switch (key) {
    case 'edit':
      emit('edit', props.app)
      break
    case 'download':
      emit('download', props.app)
      break
    case 'deploy':
      emit('deploy', props.app)
      break
    case 'version':
      emit('version', props.app)
      break
    case 'delete':
      emit('delete', props.app)
      break
  }
}
</script>

<style scoped>
.app-card {
  background: var(--bg-card);
  border: 1px solid var(--border-1);
  border-radius: var(--radius-xl);
  overflow: hidden;
  box-shadow: var(--shadow-xs);
  cursor: pointer;
  transition:
    transform var(--dur) var(--ease),
    box-shadow var(--dur) var(--ease),
    border-color var(--dur) var(--ease);
}

.app-card:hover {
  transform: translateY(-3px);
  border-color: var(--brand);
  box-shadow: var(--shadow-lg);
}

.app-preview {
  position: relative;
  height: 176px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--bg-subtle);
  overflow: hidden;
}

.app-preview img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  transition: transform 0.4s var(--ease);
}

.app-card:hover .app-preview img {
  transform: scale(1.04);
}

/* 无封面：铺一层工程网格，而不是一块死灰 */
.app-placeholder {
  position: absolute;
  inset: 0;
  display: grid;
  place-items: center;
  color: var(--brand);
  background-color: var(--bg-inset);
  background-image:
    linear-gradient(to right, var(--grid-line) 1px, transparent 1px),
    linear-gradient(to bottom, var(--grid-line) 1px, transparent 1px);
  background-size: var(--grid-size) var(--grid-size);
}

.placeholder-icon {
  opacity: 0.75;
}

/* ---------- 角标 ---------- */
.badge {
  position: absolute;
  top: 10px;
  padding: 3px 9px;
  border-radius: var(--radius-sm);
  background: rgba(255, 255, 255, 0.92);
  backdrop-filter: blur(6px);
  -webkit-backdrop-filter: blur(6px);
  box-shadow: var(--shadow-xs);
  line-height: 1.4;
}

.badge--featured {
  left: 10px;
  font-size: 11px;
  font-weight: 600;
  color: var(--text-2);
}

.badge--type {
  right: 10px;
  font-family: var(--font-mono);
  font-size: 10px;
  letter-spacing: 0.03em;
  color: var(--brand);
}

/* ---------- 悬浮操作层 ---------- */
/* 原先是一整块 rgba(17,25,37,.45) 把封面压黑；改成自下而上的渐深 + 极轻模糊，
   图片不被压死，观感轻很多 */
.app-overlay {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(180deg, rgba(11, 21, 36, 0.06) 0%, rgba(11, 21, 36, 0.52) 100%);
  backdrop-filter: blur(2px);
  -webkit-backdrop-filter: blur(2px);
  opacity: 0;
  transition: opacity var(--dur) var(--ease);
}

.app-card:hover .app-overlay {
  opacity: 1;
}

/* ---------- 信息区 ---------- */
.app-info {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  padding: var(--sp-4);
}

.app-info-left {
  flex-shrink: 0;
}

.app-info-right {
  flex: 1;
  min-width: 0;
}

.app-title {
  margin: 0 0 2px;
  font-size: 15px;
  font-weight: 650;
  color: var(--text-1);
  letter-spacing: -0.2px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.app-author {
  margin: 0;
  font-size: var(--fs-sm);
  color: var(--text-3);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* ---------- 作品管理入口 ---------- */
.app-actions {
  flex-shrink: 0;
}

/* 中性色而不是品牌蓝：这是次要操作，卡片上的主行动仍然是「查看对话」 */
.more-btn {
  width: 28px;
  height: 28px;
  display: grid;
  place-items: center;
  padding: 0;
  border: none;
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--text-3);
  font-size: 18px;
  line-height: 1;
  cursor: pointer;
  transition:
    background-color var(--dur) var(--ease),
    color var(--dur) var(--ease);
}

.more-btn:hover {
  background: var(--bg-subtle);
  color: var(--text-1);
}
</style>
