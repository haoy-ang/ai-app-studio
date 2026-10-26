/**
 * 对话页的消息模型
 * <p>
 * 抽到独立文件是因为它现在被页面和两个 composable 共用，
 * 定义在 .vue 里会导致「工具函数反向 import 页面」的怪结构。
 */
export interface ChatMessage {
  type: 'user' | 'ai'
  content: string
  /** 还没收到第一个字符（气泡显示"思考中"） */
  loading?: boolean
  /**
   * 是否正在流式接收中。
   * true → 只渲染纯文本（跳过 markdown 解析与代码高亮，避免 O(n²) 卡顿）；
   * 生成结束后置为 false，整体渲染一次 markdown。
   */
  streaming?: boolean
  createTime?: string
}
