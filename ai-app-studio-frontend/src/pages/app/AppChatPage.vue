<template>
  <div class="app-chat-page">
    <!-- 顶部栏 -->
    <div class="header-bar">
      <div class="header-left">
        <h1 class="app-name">{{ appInfo?.appName || '网站生成器' }}</h1>
        <a-tag v-if="appInfo?.codeGenType" color="blue" class="code-gen-type-tag">
          {{ formatCodeGenType(appInfo.codeGenType) }}
        </a-tag>
      </div>
      <div class="header-right">
        <a-button type="default" @click="showAppDetail">
          <template #icon>
            <InfoCircleOutlined />
          </template>
          应用详情
        </a-button>
        <a-button
            type="primary"
            ghost
            @click="downloadCode"
            :loading="downloading"
            :disabled="!isOwner"
        >
          <template #icon>
            <DownloadOutlined />
          </template>
          下载代码
        </a-button>
        <a-button type="primary" @click="deployApp" :loading="deploying">
          <template #icon>
            <CloudUploadOutlined />
          </template>
          部署
        </a-button>
      </div>
    </div>

    <!-- 主要内容区域 -->
    <div class="main-content">
      <!-- 左侧对话区域 -->
      <div class="chat-section">
        <!-- 消息区域 -->
        <div class="messages-container" ref="messagesContainer">
          <!-- 加载更多按钮 -->
          <div v-if="hasMoreHistory" class="load-more-container">
            <a-button type="link" @click="loadMoreHistory" :loading="loadingHistory" size="small">
              加载更多历史消息
            </a-button>
          </div>
          <div v-for="(message, index) in messages" :key="index" class="message-item">
            <div v-if="message.type === 'user'" class="user-message">
              <div class="message-content">{{ message.content }}</div>
              <div class="message-avatar">
                <a-avatar :src="loginUserStore.loginUser.userAvatar || defaultAvatar" />
              </div>
            </div>
            <div v-else class="ai-message">
              <div class="message-avatar">
                <a-avatar :src="aiAvatar" />
              </div>
              <div class="message-content">
                <!-- 流式期间只渲染纯文本。
                     markdown 解析和代码高亮都是针对"全文"做的，每个 chunk 都跑一遍
                     就是 O(n²)：内容涨到几万字符时，单次渲染要上百毫秒，主线程被打满，
                     表现就是整个页面卡死动不了。生成结束后再一次性渲染 markdown。 -->
                <div v-if="message.streaming" class="stream-plain-text">{{ message.content }}</div>
                <MarkdownRenderer v-else-if="message.content" :content="message.content" />
                <div v-if="message.loading" class="loading-indicator">
                  <a-spin size="small" />
                  <span>AI 正在思考...</span>
                </div>
              </div>
            </div>
          </div>
        </div>

        <!-- 选中元素信息展示 -->
        <a-alert
            v-if="selectedElementInfo"
            class="selected-element-alert"
            type="info"
            closable
            @close="clearSelectedElement"
        >
          <template #message>
            <div class="selected-element-info">
              <div class="element-header">
                <span class="element-tag">
                  选中元素：{{ selectedElementInfo.tagName.toLowerCase() }}
                </span>
                <span v-if="selectedElementInfo.id" class="element-id">
                  #{{ selectedElementInfo.id }}
                </span>
                <span v-if="selectedElementInfo.className" class="element-class">
                  .{{ selectedElementInfo.className.split(' ').join('.') }}
                </span>
              </div>
              <div class="element-details">
                <div v-if="selectedElementInfo.textContent" class="element-item">
                  内容: {{ selectedElementInfo.textContent.substring(0, 50) }}
                  {{ selectedElementInfo.textContent.length > 50 ? '...' : '' }}
                </div>
                <div v-if="selectedElementInfo.pagePath" class="element-item">
                  页面路径: {{ selectedElementInfo.pagePath }}
                </div>
                <div class="element-item">
                  选择器:
                  <code class="element-selector-code">{{ selectedElementInfo.selector }}</code>
                </div>
              </div>
            </div>
          </template>
        </a-alert>

        <!-- 用户消息输入框 -->
        <div class="input-container">
          <div class="input-wrapper">
            <a-tooltip v-if="!isOwner" title="无法在别人的作品下对话哦~" placement="top">
              <a-textarea
                  v-model:value="userInput"
                  :placeholder="getInputPlaceholder()"
                  :rows="4"
                  :maxlength="1000"
                  @keydown.enter.prevent="sendMessage"
                  :disabled="isGenerating || !isOwner"
              />
            </a-tooltip>
            <a-textarea
                v-else
                v-model:value="userInput"
                :placeholder="getInputPlaceholder()"
                :rows="4"
                :maxlength="1000"
                @keydown.enter.prevent="sendMessage"
                :disabled="isGenerating"
            />
            <div class="input-actions">
              <!-- 生成长时间没有反馈也没法中断，用户除了干等什么都做不了，
                   所以生成期间额外给一个「停止」入口（停止的是接收，不是服务端任务） -->
              <a-button v-if="isGenerating" danger size="small" @click="stopGenerating">
                停止生成
              </a-button>
              <a-button
                  type="primary"
                  @click="sendMessage"
                  :loading="isGenerating"
                  :disabled="!isOwner"
              >
                <template #icon>
                  <SendOutlined />
                </template>
              </a-button>
            </div>
          </div>
        </div>
      </div>
      <!-- 右侧网页展示区域 -->
      <div class="preview-section">
        <div class="preview-header">
          <h3>生成后的网页展示</h3>
          <div class="preview-actions">
            <a-button
                v-if="isOwner && previewUrl"
                type="link"
                @click="toggleEditMode"
                :class="['edit-mode-toggle', { 'edit-mode-active': isEditMode }]"
            >
              <template #icon>
                <EditOutlined />
              </template>
              {{ isEditMode ? '退出编辑' : '编辑模式' }}
            </a-button>
            <a-button v-if="previewUrl" type="link" @click="openInNewTab">
              <template #icon>
                <ExportOutlined />
              </template>
              新窗口打开
            </a-button>
          </div>
        </div>
        <div class="preview-content">
          <div v-if="!previewUrl && !isGenerating" class="preview-placeholder">
            <div class="placeholder-icon">🌐</div>
            <p>网站文件生成完成后将在这里展示</p>
          </div>
          <div v-else-if="isGenerating" class="preview-loading">
            <a-spin size="large" />
            <p>正在生成网站...</p>
          </div>
          <iframe
              v-else
              :src="previewUrl"
              class="preview-iframe"
              frameborder="0"
              @load="onIframeLoad"
          ></iframe>
        </div>
      </div>
    </div>

    <!-- 应用详情弹窗 -->
    <AppDetailModal
        v-model:open="appDetailVisible"
        :app="appInfo"
        :show-actions="isOwner || isAdmin"
        @edit="editApp"
        @delete="deleteApp"
    />

    <!-- 部署成功弹窗 -->
    <DeploySuccessModal
        v-model:open="deployModalVisible"
        :deploy-url="deployUrl"
        @open-site="openDeployedSite"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, nextTick, onUnmounted, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { useLoginUserStore } from '@/stores/loginUser'
import {
  getAppVoById,
  deployApp as deployAppApi,
  deleteApp as deleteAppApi,
} from '@/api/appController'
import { CodeGenTypeEnum, formatCodeGenType } from '@/utils/codeGenTypes'
import request from '@/request'

import MarkdownRenderer from '@/components/MarkdownRenderer.vue'
import AppDetailModal from '@/components/AppDetailModal.vue'
import DeploySuccessModal from '@/components/DeploySuccessModal.vue'
import aiAvatar from '@/assets/aiAvatar.png'
import defaultAvatar from '@/assets/defaultAvatar.png'
import { getStaticPreviewUrl } from '@/config/env'
import { VisualEditor, type ElementInfo } from '@/utils/visualEditor'
import { useChatHistory } from '@/composables/useChatHistory'
import { useCodeStream } from '@/composables/useCodeStream'
import type { ChatMessage } from '@/types/chat'

import {
  CloudUploadOutlined,
  SendOutlined,
  ExportOutlined,
  InfoCircleOutlined,
  DownloadOutlined,
  EditOutlined,
} from '@ant-design/icons-vue'

const route = useRoute()
const router = useRouter()
const loginUserStore = useLoginUserStore()

// 应用信息
const appInfo = ref<API.AppVO>()
const appId = ref<API.SnowflakeId>()

// 对话相关
const messages = ref<ChatMessage[]>([])
const userInput = ref('')
const messagesContainer = ref<HTMLElement>()

// 历史加载（游标分页）和流式生成（SSE）都抽成 composable：
// 页面只负责「消息怎么展示、什么时候刷新预览」，不再背 SSE 的收尾细节。
const { loadingHistory, hasMoreHistory, historyLoaded, loadChatHistory, loadMoreHistory } =
  useChatHistory(appId, messages)
const { isGenerating, generateCode, stopGenerating, closeStream } = useCodeStream({
  appId,
  messages,
  onFinished: handleStreamFinished,
})

// 预览相关
const previewUrl = ref('')
const previewReady = ref(false)

// 部署相关
const deploying = ref(false)
const deployModalVisible = ref(false)
const deployUrl = ref('')

// 下载相关
const downloading = ref(false)

// 可视化编辑相关
const isEditMode = ref(false)
const selectedElementInfo = ref<ElementInfo | null>(null)
const visualEditor = new VisualEditor({
  onElementSelected: (elementInfo: ElementInfo) => {
    selectedElementInfo.value = elementInfo
  },
})

// 权限相关
const isOwner = computed(() => {
  return appInfo.value?.userId === loginUserStore.loginUser.id
})

const isAdmin = computed(() => {
  return loginUserStore.loginUser.userRole === 'admin'
})

// 应用详情相关
const appDetailVisible = ref(false)

// 显示应用详情
const showAppDetail = () => {
  appDetailVisible.value = true
}

// 获取应用信息
const fetchAppInfo = async () => {
  const id = route.params.id as string
  if (!id) {
    message.error('应用ID不存在')
    router.push('/')
    return
  }

  appId.value = id

  try {
    const res = await getAppVoById({ id })
    if (res.data.code === 0 && res.data.data) {
      appInfo.value = res.data.data

      // 先加载对话历史
      await loadChatHistory()
      // 是否展示预览由后端给的 codeReady 决定（它查的是代码目录是否真的存在）。
      // 不能按"历史条数 >= 2"判断：生成失败时历史里有消息但代码目录是空的，
      // 只按条数判断会让用户看到"网站文件生成完成后将在这里展示"，误以为白生成了。
      if (appInfo.value.codeReady) {
        updatePreview()
      }
      // 检查是否需要自动发送初始提示词
      // 只有在是自己的应用且没有对话历史时才自动发送
      if (
          appInfo.value.initPrompt &&
          isOwner.value &&
          messages.value.length === 0 &&
          historyLoaded.value
      ) {
        await sendInitialMessage(appInfo.value.initPrompt)
      }
    } else {
      message.error('获取应用信息失败')
      router.push('/')
    }
  } catch (error) {
    console.error('获取应用信息失败：', error)
    message.error('获取应用信息失败')
    router.push('/')
  }
}

/**
 * 只刷新应用信息，不重拉对话历史
 * <p>
 * 生成结束后如果走 fetchAppInfo，它会顺带把整页历史重新拉回来替换 messages ——
 * 等于把刚生成的那条几万字符的 AI 回复整个重新 markdown 渲染一遍，白白卡一次。
 * 而落库内容与前端流式看到的完全一致（后端 StreamHandlerExecutor 的设计保证），
 * 所以这里只更新 appInfo（codeReady / cover / deployKey 这些），不碰消息列表。
 */
const refreshAppInfoOnly = async () => {
  const id = appId.value
  if (!id) return
  try {
    const res = await getAppVoById({ id })
    if (res.data.code === 0 && res.data.data) {
      appInfo.value = res.data.data
    }
  } catch (error) {
    // 刷新失败不影响已经拿到的生成结果，只记日志
    console.error('刷新应用信息失败：', error)
  }
}

// 发送初始消息
const sendInitialMessage = async (prompt: string) => {
  // 添加用户消息
  messages.value.push({
    type: 'user',
    content: prompt,
  })

  // 添加AI消息占位符（streaming=true：先按纯文本渲染，生成结束再切 markdown）
  const aiMessageIndex = messages.value.length
  messages.value.push({
    type: 'ai',
    content: '',
    loading: true,
    streaming: true,
  })

  await nextTick()
  scrollToBottom()

  // 开始生成
  isGenerating.value = true
  await generateCode(prompt, aiMessageIndex)
}

// 发送消息
const sendMessage = async () => {
  if (!userInput.value.trim() || isGenerating.value) {
    return
  }

  let message = userInput.value.trim()
  // 如果有选中的元素，将元素信息添加到提示词中
  if (selectedElementInfo.value) {
    let elementContext = `\n\n选中元素信息：`
    if (selectedElementInfo.value.pagePath) {
      elementContext += `\n- 页面路径: ${selectedElementInfo.value.pagePath}`
    }
    elementContext += `\n- 标签: ${selectedElementInfo.value.tagName.toLowerCase()}\n- 选择器: ${selectedElementInfo.value.selector}`
    if (selectedElementInfo.value.textContent) {
      elementContext += `\n- 当前内容: ${selectedElementInfo.value.textContent.substring(0, 100)}`
    }
    message += elementContext
  }
  userInput.value = ''
  // 添加用户消息（包含元素信息）
  messages.value.push({
    type: 'user',
    content: message,
  })

  // 发送消息后，清除选中元素并退出编辑模式
  if (selectedElementInfo.value) {
    clearSelectedElement()
    if (isEditMode.value) {
      toggleEditMode()
    }
  }

  // 添加AI消息占位符（streaming=true：先按纯文本渲染，生成结束再切 markdown）
  const aiMessageIndex = messages.value.length
  messages.value.push({
    type: 'ai',
    content: '',
    loading: true,
    streaming: true,
  })

  await nextTick()
  scrollToBottom()

  // 开始生成
  isGenerating.value = true
  await generateCode(message, aiMessageIndex)
}

/**
 * 流正常结束后的收尾：滚动到底、延迟刷新应用信息、按需更新预览。
 * <p>
 * 延迟 1 秒是因为后端要先把 AI 回复落库，刷新太早会拿到旧状态。
 * 抽成函数声明（而不是 const 箭头函数）是为了让上面的 useCodeStream
 * 在 setup 阶段就能拿到引用 —— 函数声明会提升，箭头函数会撞 TDZ。
 */
function handleStreamFinished() {
  scrollToBottom()
  setTimeout(async () => {
    await refreshAppInfoOnly()
    if (appInfo.value?.codeReady) {
      updatePreview()
    }
  }, 1000)
}

// 更新预览
const updatePreview = () => {
  if (appId.value) {
    const codeGenType = appInfo.value?.codeGenType || CodeGenTypeEnum.HTML
    const baseUrl = getStaticPreviewUrl(codeGenType, appId.value)
    // 带时间戳破缓存：预览地址本身是不变的，浏览器会直接复用上一次的响应，
    // 结果就是「AI 明明改完了，预览还是旧页面」，要手动刷新才生效。
    // 后端 StaticResourceController 没设 Cache-Control，浏览器会按启发式规则缓存静态资源，这个坑很容易踩。
    previewUrl.value = `${baseUrl}${baseUrl.includes('?') ? '&' : '?'}_t=${Date.now()}`
    previewReady.value = true
  }
}

// 滚动到底部
const scrollToBottom = () => {
  if (messagesContainer.value) {
    messagesContainer.value.scrollTop = messagesContainer.value.scrollHeight
  }
}

// 下载代码
const downloadCode = async () => {
  if (!appId.value) {
    message.error('应用ID不存在')
    return
  }
  downloading.value = true
  try {
    const API_BASE_URL = request.defaults.baseURL || ''
    const url = `${API_BASE_URL}/app/download/${appId.value}`
    const response = await fetch(url, {
      method: 'GET',
      credentials: 'include',
    })
    if (!response.ok) {
      throw new Error(`下载失败: ${response.status}`)
    }
    // 获取文件名：优先用 RFC 5987 的 filename*（中文等非 ASCII 文件名走这里），再兼容老格式 filename
    const contentDisposition = response.headers.get('Content-Disposition') || ''
    const fileNameStar = contentDisposition.match(/filename\*=UTF-8''([^;]+)/i)?.[1]
    const fileNamePlain = contentDisposition.match(/filename="?([^;"]+)"?/i)?.[1]
    const rawFileName = fileNameStar || fileNamePlain || `app-${appId.value}.zip`
    let fileName = rawFileName
    try {
      fileName = decodeURIComponent(rawFileName)
    } catch {
      // 不是合法的百分号编码就原样使用
      fileName = rawFileName
    }
    // 下载文件
    const blob = await response.blob()
    const downloadUrl = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = downloadUrl
    link.download = fileName
    link.click()
    // 清理
    URL.revokeObjectURL(downloadUrl)
    message.success('代码下载成功')
  } catch (error) {
    console.error('下载失败：', error)
    message.error('下载失败，请重试')
  } finally {
    downloading.value = false
  }
}

// 部署应用
const deployApp = async () => {
  if (!appId.value) {
    message.error('应用ID不存在')
    return
  }

  deploying.value = true
  try {
    const res = await deployAppApi({
      appId: appId.value,
    })

    if (res.data.code === 0 && res.data.data) {
      deployUrl.value = res.data.data
      deployModalVisible.value = true
      message.success('部署成功')
    } else {
      message.error('部署失败：' + res.data.message)
    }
  } catch (error) {
    console.error('部署失败：', error)
    message.error('部署失败，请重试')
  } finally {
    deploying.value = false
  }
}

// 在新窗口打开预览
const openInNewTab = () => {
  if (previewUrl.value) {
    window.open(previewUrl.value, '_blank')
  }
}

// 打开部署的网站
const openDeployedSite = () => {
  if (deployUrl.value) {
    window.open(deployUrl.value, '_blank')
  }
}

// iframe加载完成
const onIframeLoad = () => {
  previewReady.value = true
  const iframe = document.querySelector('.preview-iframe') as HTMLIFrameElement
  if (iframe) {
    visualEditor.init(iframe)
    visualEditor.onIframeLoad()
  }
}

// 编辑应用
const editApp = () => {
  if (appInfo.value?.id) {
    router.push(`/app/edit/${appInfo.value.id}`)
  }
}

// 删除应用
const deleteApp = async () => {
  if (!appInfo.value?.id) return

  try {
    const res = await deleteAppApi({ id: appInfo.value.id })
    if (res.data.code === 0) {
      message.success('删除成功')
      appDetailVisible.value = false
      router.push('/')
    } else {
      message.error('删除失败：' + res.data.message)
    }
  } catch (error) {
    console.error('删除失败：', error)
    message.error('删除失败')
  }
}

// 可视化编辑相关函数
const toggleEditMode = () => {
  // 检查 iframe 是否已经加载
  const iframe = document.querySelector('.preview-iframe') as HTMLIFrameElement
  if (!iframe) {
    message.warning('请等待页面加载完成')
    return
  }
  // 确保 visualEditor 已初始化
  if (!previewReady.value) {
    message.warning('请等待页面加载完成')
    return
  }
  const newEditMode = visualEditor.toggleEditMode()
  isEditMode.value = newEditMode
}

const clearSelectedElement = () => {
  selectedElementInfo.value = null
  visualEditor.clearSelection()
}

const getInputPlaceholder = () => {
  if (selectedElementInfo.value) {
    return `正在编辑 ${selectedElementInfo.value.tagName.toLowerCase()} 元素，描述您想要的修改...`
  }
  return '请描述你想生成的网站，越详细效果越好哦'
}

// iframe 通过 postMessage 上报的消息统一入口
const handleWindowMessage = (event: MessageEvent) => {
  visualEditor.handleIframeMessage(event)
}

// 页面加载时获取应用信息
onMounted(() => {
  fetchAppInfo()

  // 监听 iframe 消息
  window.addEventListener('message', handleWindowMessage)
})

// 清理资源
onUnmounted(() => {
  // ⚠️ 必须关掉 SSE：连接是长连接，组件卸载不会自动断，
  // 用户生成到一半切走页面的话，后端会继续推流、继续烧 token
  closeStream()
  // window 上的监听不会随组件销毁自动消失：不移除的话每进一次这个页面就多挂一个，
  // 反复切换应用会累积一堆失效监听。
  window.removeEventListener('message', handleWindowMessage)
})
</script>

<style scoped>
.app-chat-page {
  display: flex;
  flex-direction: column;
  gap: var(--sp-4);
  height: calc(100vh - var(--header-h));
  padding: var(--sp-4) var(--sp-5);
}

/* ---------- 顶部操作栏 ---------- */
.header-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-4);
  flex-shrink: 0;
}

.header-left {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  min-width: 0;
}

.app-name {
  margin: 0;
  font-size: var(--fs-md);
  font-weight: 600;
  color: var(--text-1);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.code-gen-type-tag {
  flex-shrink: 0;
  font-size: var(--fs-xs);
}

.header-right {
  display: flex;
  gap: var(--sp-2);
  flex-shrink: 0;
}

/* ---------- 主区域 ---------- */
.main-content {
  flex: 1;
  display: flex;
  gap: var(--sp-4);
  overflow: hidden;
}

.chat-section,
.preview-section {
  display: flex;
  flex-direction: column;
  min-width: 0;
  background: var(--bg-card);
  border: 1px solid var(--border-1);
  border-radius: var(--radius-lg);
  overflow: hidden;
}

.chat-section {
  flex: 2;
}

.preview-section {
  flex: 3;
}

/* ---------- 消息区 ---------- */
.messages-container {
  flex: 1;
  padding: var(--sp-4);
  overflow-y: auto;
  /* 这里刻意不加 scroll-behavior: smooth：
     流式生成时每帧都会赋值 scrollTop，而平滑滚动会把这些赋值排成一串动画，
     既追不上内容增长、又让每一帧都产生额外的布局计算。 */
}

.load-more-container {
  margin-bottom: var(--sp-4);
  text-align: center;
}

.message-item + .message-item {
  margin-top: var(--sp-3);
}

.user-message,
.ai-message {
  display: flex;
  align-items: flex-start;
  gap: var(--sp-2);
}

.user-message {
  justify-content: flex-end;
}

.message-avatar {
  flex-shrink: 0;
}

.message-content {
  max-width: 72%;
  padding: 10px var(--sp-4);
  border-radius: var(--radius-lg);
  font-size: var(--fs-base);
  line-height: 1.65;
  word-wrap: break-word;
}

.user-message .message-content {
  color: #fff;
  background: var(--brand);
  border-top-right-radius: var(--radius-xs);
}

.ai-message .message-content {
  color: var(--text-1);
  background: var(--bg-subtle);
  border-top-left-radius: var(--radius-xs);
}

.loading-indicator {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  font-size: var(--fs-sm);
  color: var(--text-3);
}

/* ---------- 选中元素信息 ---------- */
.selected-element-alert {
  margin: 0 var(--sp-4);
}

.selected-element-info {
  line-height: 1.5;
}

.element-header {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: var(--sp-1) var(--sp-2);
  margin-bottom: var(--sp-2);
}

.element-tag {
  font-family: 'Monaco', 'Menlo', monospace;
  font-size: var(--fs-sm);
  font-weight: 600;
  color: var(--brand);
}

.element-id,
.element-class {
  font-size: var(--fs-xs);
  color: var(--text-3);
}

.element-details {
  font-size: var(--fs-sm);
  color: var(--text-2);
}

.element-item {
  margin-bottom: var(--sp-1);
}

.element-item:last-child {
  margin-bottom: 0;
}

.element-selector-code {
  padding: 1px 6px;
  font-family: 'Monaco', 'Menlo', monospace;
  font-size: var(--fs-xs);
  color: var(--brand);
  background: var(--brand-soft);
  border-radius: var(--radius-xs);
}

/* ---------- 输入区 ---------- */
.input-container {
  flex-shrink: 0;
  padding: var(--sp-4);
  border-top: 1px solid var(--border-1);
}

.input-wrapper {
  position: relative;
}

.input-wrapper :deep(.ant-input) {
  padding: 10px var(--sp-4) 46px;
  background: var(--bg-subtle);
  border: 1px solid transparent;
  border-radius: var(--radius-lg);
  transition:
    background var(--dur) var(--ease),
    border-color var(--dur) var(--ease),
    box-shadow var(--dur) var(--ease);
}

.input-wrapper :deep(.ant-input:focus) {
  background: var(--bg-card);
  border-color: var(--brand);
  box-shadow: 0 0 0 3px var(--brand-soft);
}

.input-actions {
  position: absolute;
  right: 8px;
  bottom: 8px;
  display: flex;
  align-items: center;
  gap: var(--sp-2);
}

/* 流式期间的纯文本渲染：不解析 markdown，只保证换行和等宽可读 */
.stream-plain-text {
  white-space: pre-wrap;
  word-break: break-all;
  font-family: 'Monaco', 'Menlo', 'Ubuntu Mono', monospace;
  font-size: var(--fs-sm);
  line-height: 1.5;
  color: var(--text-2);
}

/* ---------- 预览区 ---------- */
.preview-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-3);
  padding: var(--sp-3) var(--sp-4);
  border-bottom: 1px solid var(--border-1);
}

.preview-header h3 {
  margin: 0;
  font-size: var(--fs-base);
  font-weight: 500;
  color: var(--text-1);
}

.preview-actions {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
}

.preview-content {
  flex: 1;
  position: relative;
  overflow: hidden;
  background: var(--bg-subtle);
}

.preview-placeholder,
.preview-loading {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 100%;
  font-size: var(--fs-sm);
  color: var(--text-3);
}

.placeholder-icon {
  margin-bottom: var(--sp-4);
  font-size: 40px;
}

.preview-loading p {
  margin-top: var(--sp-4);
}

.preview-iframe {
  width: 100%;
  height: 100%;
  border: none;
  background: #fff;
}

/* 编辑模式激活态。
   注意：这批样式原先被误放进 @media (max-width: 768px) 块内，
   导致桌面端完全不生效 —— 现已移出媒体查询。 */
.edit-mode-active {
  color: var(--brand) !important;
  background: var(--brand-soft) !important;
  border-radius: var(--radius-sm);
}

.edit-mode-toggle {
  height: auto;
  padding: 0 var(--sp-2);
}

/* ---------- 响应式 ---------- */
@media (max-width: 1024px) {
  .main-content {
    flex-direction: column;
  }

  .chat-section,
  .preview-section {
    flex: none;
    height: 50%;
  }
}

@media (max-width: 768px) {
  .app-chat-page {
    gap: var(--sp-3);
    padding: var(--sp-3);
  }

  .app-name {
    font-size: var(--fs-base);
  }

  .message-content {
    max-width: 85%;
  }
}
</style>
