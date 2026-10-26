import { ref, type Ref } from 'vue'
import { message } from 'ant-design-vue'
import { listAppChatHistory } from '@/api/chatHistoryController'
import type { ChatMessage } from '@/types/chat'

/** 每页条数：后端按 lastId 游标翻页，这里决定"一屏历史"的大小 */
const PAGE_SIZE = 10

/**
 * 对话历史加载（游标分页）
 * <p>
 * 从 AppChatPage 里抽出来的原因：这段逻辑（游标、翻页方向、反转顺序）
 * 和「怎么渲染消息」没有关系，放在页面里会让那个文件越滚越大，
 * 也让"翻页丢消息"这类 bug 很难单独测。
 *
 * @param appId    当前应用 id（未加载时为 undefined）
 * @param messages 消息列表，加载结果直接写进去（新消息追加在尾部）
 */
export function useChatHistory(
  appId: Ref<API.SnowflakeId | undefined>,
  messages: Ref<ChatMessage[]>,
) {
  const loadingHistory = ref(false)
  const hasMoreHistory = ref(false)
  /**
   * 游标：上一页最后一条消息的 id。
   * 用 id（雪花号，唯一且递增）而不是 createTime（精度只到秒）；
   * 用户消息和 AI 消息经常落在同一秒，用时间做游标会把同秒的记录整批跳过，翻历史会"丢一段"。
   */
  const lastMessageId = ref<API.SnowflakeId>()
  const historyLoaded = ref(false)

  /**
   * @param isLoadMore true = 往上翻更早的消息；false = 首次加载
   */
  const loadChatHistory = async (isLoadMore = false) => {
    if (!appId.value || loadingHistory.value) return
    loadingHistory.value = true
    try {
      const params: API.listAppChatHistoryParams = {
        appId: appId.value,
        pageSize: PAGE_SIZE,
      }
      // 如果是加载更多，传递上一页最后一条消息的 id 作为游标
      if (isLoadMore && lastMessageId.value) {
        params.lastId = lastMessageId.value
      }
      const res = await listAppChatHistory(params)
      if (res.data.code === 0 && res.data.data) {
        const chatHistories = res.data.data.records || []
        if (chatHistories.length > 0) {
          // 后端返回的是时间倒序（新的在前），页面要的是正序（老消息在上）
          const historyMessages: ChatMessage[] = chatHistories
            .map((chat) => ({
              type: (chat.messageType === 'user' ? 'user' : 'ai') as 'user' | 'ai',
              content: chat.message || '',
              createTime: chat.createTime,
            }))
            .reverse()
          if (isLoadMore) {
            // 加载更多时，将历史消息添加到开头
            messages.value.unshift(...historyMessages)
          } else {
            // 初始加载，直接设置消息列表
            messages.value = historyMessages
          }
          // 更新游标：records 是按时间降序的，最后一条就是本页最老的一条
          lastMessageId.value = chatHistories[chatHistories.length - 1]?.id
          // 检查是否还有更多历史
          hasMoreHistory.value = chatHistories.length === PAGE_SIZE
        } else {
          hasMoreHistory.value = false
        }
        historyLoaded.value = true
      }
    } catch (error) {
      console.error('加载对话历史失败：', error)
      message.error('加载对话历史失败')
    } finally {
      loadingHistory.value = false
    }
  }

  const loadMoreHistory = async () => {
    await loadChatHistory(true)
  }

  return {
    loadingHistory,
    hasMoreHistory,
    lastMessageId,
    historyLoaded,
    loadChatHistory,
    loadMoreHistory,
  }
}
