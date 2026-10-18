<template>
  <a-drawer
    :open="open"
    :title="`版本历史${app?.appName ? ' · ' + app.appName : ''}`"
    :width="560"
    @close="handleClose"
  >
    <!-- 把「回滚做了什么、没做什么」写在最上面：用户最容易误解的是「回滚了线上却没变」 -->
    <a-alert
      type="info"
      show-icon
      :message="'回滚只恢复服务器的生成目录，不会自动重新部署'"
      description="确认代码没问题后，再点「重新部署」让线上生效。"
      class="tip"
    />

    <a-spin :spinning="loading">
      <a-empty v-if="!loading && !versions.length">
        <template #description>
          <p>还没有版本记录</p>
          <p class="empty-hint">在对话里生成一次代码，就会自动留下一个版本</p>
        </template>
      </a-empty>

      <a-list v-else :data-source="versions" item-layout="horizontal" size="small">
        <template #renderItem="{ item }">
          <a-list-item>
            <div class="ver-row">
              <div class="ver-main">
                <span class="ver-no">v{{ item.versionNo }}</span>
                <a-tag v-if="item.current" color="blue">当前</a-tag>
                <a-tag v-else>{{ SOURCE_LABEL[item.source] || item.source }}</a-tag>
                <span v-if="item.current" class="ver-source">{{ SOURCE_LABEL[item.source] || item.source }}</span>
              </div>
              <div class="ver-meta">
                <span>{{ formatSize(item.sizeBytes) }}</span>
                <span class="dot">·</span>
                <span>{{ item.fileCount }} 个文件</span>
                <span class="dot">·</span>
                <span>{{ item.createTime }}</span>
              </div>
              <p v-if="item.prompt" class="ver-prompt" :title="item.prompt">{{ item.prompt }}</p>
            </div>

            <template #actions>
              <a-button
                v-if="!item.current"
                type="link"
                size="small"
                :loading="rollingBackId === item.id"
                @click="handleRollback(item)"
              >
                回滚到此版本
              </a-button>
            </template>
          </a-list-item>
        </template>
      </a-list>
    </a-spin>
  </a-drawer>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { message, Modal } from 'ant-design-vue'
import { listVersions, rollbackVersion, type AppVersionVO } from '@/api/appVersionController'

interface Props {
  open: boolean
  app: API.AppVO | null
}

interface Emits {
  (e: 'update:open', open: boolean): void
  (e: 'changed'): void
}

const props = defineProps<Props>()
const emit = defineEmits<Emits>()

/** 版本来源的中文标签 —— 排查质量时第一句话就是「这次走的是哪条链路」 */
const SOURCE_LABEL: Record<string, string> = {
  CHAT: '对话生成',
  WORKFLOW: 'AI 工作流',
  ROLLBACK: '回滚',
}

const versions = ref<AppVersionVO[]>([])
const loading = ref(false)
const rollingBackId = ref<string | null>(null)

const loadVersions = async () => {
  const appId = props.app?.id
  if (!appId) {
    versions.value = []
    return
  }
  loading.value = true
  try {
    // 注意：appId 是雪花 id，必须原样（字符串）透传。
    // Number(appId) 会丢掉末尾几位 → 后端查不到这个应用
    const res = await listVersions({ appId })
    if (res.data.code === 0 && res.data.data) {
      versions.value = res.data.data
    } else {
      message.error('加载版本失败：' + (res.data.message || '未知错误'))
    }
  } catch (error) {
    console.error('加载版本失败：', error)
    message.error('加载版本失败')
  } finally {
    loading.value = false
  }
}

// 每次打开都重新拉一次：用户可能刚在对话页生成过新版本
watch(
  () => props.open,
  (open) => {
    if (open) {
      loadVersions()
    }
  },
)

const handleClose = () => {
  emit('update:open', false)
}

const handleRollback = (version: AppVersionVO) => {
  Modal.confirm({
    title: `回滚到 v${version.versionNo}？`,
    content: '当前生成目录会被这个版本覆盖。已经部署的线上页面不受影响，需要重新部署才会同步。',
    okText: '回滚',
    cancelText: '取消',
    onOk: async () => {
      rollingBackId.value = version.id
      try {
        const res = await rollbackVersion({ versionId: version.id })
        if (res.data.code === 0) {
          message.success(`已回滚到 v${version.versionNo}`)
          await loadVersions()
          emit('changed')
        } else {
          message.error('回滚失败：' + (res.data.message || '未知错误'))
        }
      } catch (error) {
        console.error('回滚失败：', error)
        message.error('回滚失败')
      } finally {
        rollingBackId.value = null
      }
    },
  })
}

const formatSize = (bytes?: number) => {
  if (!bytes) return '0 KB'
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}
</script>

<style scoped>
.tip {
  margin-bottom: var(--sp-4);
}

.empty-hint {
  margin: var(--sp-2) 0 0;
  font-size: var(--fs-sm);
  color: var(--text-3);
}

.ver-row {
  min-width: 0;
  flex: 1;
}

.ver-main {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
}

.ver-no {
  font-family: var(--font-mono);
  font-size: 14px;
  font-weight: 650;
  color: var(--text-1);
}

.ver-source {
  font-size: var(--fs-sm);
  color: var(--text-3);
}

.ver-meta {
  margin-top: 2px;
  font-size: var(--fs-sm);
  color: var(--text-3);
}

.dot {
  margin: 0 6px;
  opacity: 0.5;
}

.ver-prompt {
  margin: 4px 0 0;
  font-size: var(--fs-sm);
  color: var(--text-2);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 320px;
}
</style>
