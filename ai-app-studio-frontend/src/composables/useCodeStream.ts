import { ref, type Ref } from 'vue'
import { message } from 'ant-design-vue'
import request from '@/request'
import { API_BASE_URL } from '@/config/env'
import type { ChatMessage } from '@/types/chat'

interface UseCodeStreamOptions {
  /** 当前应用 id */
  appId: Ref<API.SnowflakeId | undefined>
  /** 消息列表（流式内容直接写进对应的那条 AI 消息） */
  messages: Ref<ChatMessage[]>
  /**
   * 流正常结束后的收尾动作，由页面决定：
   * 滚动到底部、延迟刷新应用信息、更新预览……
   * 这些都属于"页面关心的事"，不该塞进流控制里。
   */
  onFinished: () => void
}

/**
 * 代码生成的流式控制（SSE）
 * <p>
 * 这里只负责「连接、拼内容、什么时候算结束、出错怎么收尾」，
 * 不碰任何 UI 状态（除了 isGenerating 这个"是否正在生成"的开关）。
 *
 * 从 AppChatPage 抽出来的直接原因：这段代码里有三个很容易写错、需要单独读的地方 ——
 * <ol>
 *   <li>EventSource 必须挂在作用域上，组件卸载时要能主动 close（否则后端继续烧 token）；</li>
 *   <li>chunk 用 rAF 合帧写入，避免一帧内多次触发响应式更新；</li>
 *   <li>服务端推完 done 会直接断开，浏览器会报一次 onerror 并自动重连 —— 这属于"正常结束"。</li>
 * </ol>
 */
export function useCodeStream({ appId, messages, onFinished }: UseCodeStreamOptions) {
  const isGenerating = ref(false)

  // 当前活跃的 SSE 连接。
  // ⚠️ 必须挂在组合式函数作用域上，而不是 generateCode 的局部变量里：
  // 否则组件卸载时拿不到引用、关不掉连接 —— 用户生成到一半切走页面，后端会继续烧 token。
  let activeEventSource: EventSource | null = null

  /** 关闭当前 SSE 连接（幂等） */
  const closeStream = () => {
    activeEventSource?.close()
    activeEventSource = null
  }

  /** 错误处理：把气泡置成失败态、提示用户、断开连接 */
  const handleError = (error: unknown, aiMessageIndex: number) => {
    console.error('生成代码失败：', error)
    const target = messages.value[aiMessageIndex]
    if (target) {
      target.content = '抱歉，生成过程中出现了错误，请重试。'
      target.loading = false
      target.streaming = false
    }
    message.error('生成失败，请重试')
    isGenerating.value = false
    closeStream()
  }

  /**
   * 生成代码 —— 用 EventSource 接收 SSE
   *
   * @param userMessage    用户这次发的话
   * @param aiMessageIndex 第几条消息是这次生成的 AI 气泡（调用方已经 push 好占位符）
   */
  const generateCode = async (userMessage: string, aiMessageIndex: number) => {
    let streamCompleted = false
    // 流式内容缓冲区 + rAF 句柄。
    // 为什么要缓冲：SSE 可能在一帧内推来多个 chunk，每个都直接写回响应式数据会触发多次
    // 组件更新 + 多次滚动计算；攒到下一帧统一刷一次，重排次数从"每 chunk 一次"降到"每帧一次"。
    let pendingContent = ''
    let rafId = 0

    const flushContent = () => {
      rafId = 0
      const target = messages.value[aiMessageIndex]
      if (target) {
        target.content = pendingContent
      }
    }

    // 流结束的统一收尾：无论正常 done 还是连接被判定为正常关闭，都走这里
    const finishStream = () => {
      if (streamCompleted) return
      streamCompleted = true
      // ⚠️ 收尾前必须把缓冲区刷干净并取消待执行的 rAF，否则最后几个 chunk 会丢
      if (rafId) {
        cancelAnimationFrame(rafId)
        flushContent()
      }
      isGenerating.value = false
      closeStream()
      // 流已结束 → 退出"纯文本模式"，让这一条消息走一次完整的 markdown 渲染
      const target = messages.value[aiMessageIndex]
      if (target) {
        target.streaming = false
        target.loading = false
      }
      onFinished()
    }

    try {
      // 获取 axios 配置的 baseURL（dev 下是 /api，走 vite 代理）
      const baseURL = request.defaults.baseURL || API_BASE_URL

      const params = new URLSearchParams({
        appId: appId.value || '',
        message: userMessage,
      })

      const url = `${baseURL}/app/chat/gen/code?${params}`

      activeEventSource = new EventSource(url, {
        withCredentials: true,
      })

      // 处理接收到的消息
      activeEventSource.onmessage = function (event) {
        if (streamCompleted) return

        try {
          // 解析 JSON 包装的数据（后端每条事件都是 { d: "..." }）
          const parsed = JSON.parse(event.data)
          const content = parsed.d

          // 拼接内容（只进缓冲区，不直接写响应式数据）
          if (content !== undefined && content !== null) {
            pendingContent += content
            const target = messages.value[aiMessageIndex]
            if (target && target.loading) {
              target.loading = false
            }
            if (!rafId) {
              rafId = requestAnimationFrame(flushContent)
            }
          }
        } catch (error) {
          console.error('解析消息失败:', error)
          handleError(error, aiMessageIndex)
        }
      }

      // 处理 done 事件
      activeEventSource.addEventListener('done', function () {
        finishStream()
      })

      // 处理 business-error 事件（后端限流等错误）
      activeEventSource.addEventListener('business-error', function (event: MessageEvent) {
        if (streamCompleted) return

        try {
          const errorData = JSON.parse(event.data)
          console.error('SSE业务错误事件:', errorData)

          const errorMessage = errorData.message || '生成过程中出现错误'
          const target = messages.value[aiMessageIndex]
          if (target) {
            target.content = `❌ ${errorMessage}`
            target.loading = false
            target.streaming = false
          }
          message.error(errorMessage)

          streamCompleted = true
          isGenerating.value = false
          closeStream()
        } catch (parseError) {
          console.error('解析错误事件失败:', parseError, '原始数据:', event.data)
          handleError(new Error('服务器返回错误'), aiMessageIndex)
        }
      })

      // 处理错误
      activeEventSource.onerror = function () {
        if (streamCompleted || !isGenerating.value) return
        // 服务端在推送完 done 之后关闭连接，浏览器会报一次连接错误并自动重连；
        // 走到这里如果已经是 CONNECTING，说明是这种"正常关闭"，按成功收尾即可
        if (activeEventSource?.readyState === EventSource.CONNECTING) {
          finishStream()
        } else {
          handleError(new Error('SSE连接错误'), aiMessageIndex)
        }
      }
    } catch (error) {
      console.error('创建 EventSource 失败：', error)
      handleError(error, aiMessageIndex)
    }
  }

  /**
   * 停止生成 —— 准确说是「停止接收生成结果」
   * <p>
   * ⚠️ 服务端不一定真的停下：Vue 工程模式用的是阻塞式 tokenStream.start()，
   * 客户端断开只会让后续 push 失效，模型仍会跑到结束并把文件写完；
   * HTML / 多文件模式的流式请求更可能被真正中断。这一点要在文案上如实告诉用户，
   * 别让人以为"点了停止 = 不烧 token 了"。
   */
  const stopGenerating = () => {
    closeStream()
    isGenerating.value = false
    const target = messages.value[messages.value.length - 1]
    if (target && target.type === 'ai') {
      target.loading = false
      target.streaming = false
      target.content = target.content
        ? `${target.content}\n\n_（已停止接收生成结果，服务端可能仍在继续）_`
        : '（已停止生成）'
    }
    message.info('已停止接收生成结果')
  }

  return { isGenerating, generateCode, stopGenerating, closeStream }
}
