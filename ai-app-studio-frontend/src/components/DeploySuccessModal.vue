<template>
  <a-modal v-model:open="visible" :footer="null" width="600px">
    <template #title>
      <span class="modal-title">
        <span class="modal-title-mark"><AppstoreFilled /></span>
        部署成功
      </span>
    </template>
    <div class="deploy-success">
      <div class="success-icon">
        <CheckCircleOutlined />
      </div>
      <h3>网站部署成功！</h3>
      <p>你的网站已经成功部署，可以通过以下链接访问：</p>
      <div class="deploy-url">
        <a-input :value="deployUrl" readonly>
          <template #suffix>
            <a-button type="text" @click="handleCopyUrl">
              <CopyOutlined />
            </a-button>
          </template>
        </a-input>
      </div>
      <div class="deploy-actions">
        <a-button type="primary" @click="handleOpenSite">访问网站</a-button>
        <a-button @click="handleClose">关闭</a-button>
      </div>
    </div>
  </a-modal>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { message } from 'ant-design-vue'
import { AppstoreFilled, CheckCircleOutlined, CopyOutlined } from '@ant-design/icons-vue'

interface Props {
  open: boolean
  deployUrl: string
}

interface Emits {
  (e: 'update:open', value: boolean): void
  (e: 'open-site'): void
}

const props = defineProps<Props>()
const emit = defineEmits<Emits>()

const visible = computed({
  get: () => props.open,
  set: (value) => emit('update:open', value),
})

const handleCopyUrl = async () => {
  try {
    await navigator.clipboard.writeText(props.deployUrl)
    message.success('链接已复制到剪贴板')
  } catch (error) {
    console.error('复制失败：', error)
    message.error('复制失败')
  }
}

const handleOpenSite = () => {
  emit('open-site')
}

const handleClose = () => {
  visible.value = false
}
</script>

<style scoped>
/* 弹窗标题栏：品牌标记 + 标题文字，和全局导航栏 logo 保持一致 */
.modal-title {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}

.modal-title-mark {
  display: grid;
  place-items: center;
  width: 22px;
  height: 22px;
  border-radius: var(--radius-sm);
  background: var(--brand);
  color: #fff;
  font-size: 13px;
}

.deploy-success {
  padding: var(--sp-5) var(--sp-4);
  text-align: center;
}

.success-icon {
  margin-bottom: var(--sp-4);
  font-size: 48px;
  color: #52c41a;
}

.deploy-success h3 {
  margin: 0 0 var(--sp-4);
  font-size: var(--fs-lg);
  font-weight: 600;
  color: var(--text-1);
}

.deploy-success p {
  margin: 0 0 var(--sp-5);
  font-size: var(--fs-base);
  color: var(--text-3);
}

.deploy-url {
  margin-bottom: var(--sp-5);
}

.deploy-actions {
  display: flex;
  justify-content: center;
  gap: var(--sp-3);
}
</style>
