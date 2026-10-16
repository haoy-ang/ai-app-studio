<template>
  <div class="markdown-content" v-html="renderedMarkdown"></div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import MarkdownIt from 'markdown-it'
import hljs from 'highlight.js'

// 引入代码高亮样式
import 'highlight.js/styles/github.css'

interface Props {
  content: string
}

const props = defineProps<Props>()

/**
 * 超过这个长度就跳过高亮（见 highlight 回调里的说明）
 * 20000 字符大约是一屏 HTML 源码的量级，再大高亮收益就很低了
 */
const MAX_HIGHLIGHT_LENGTH = 20000

// 配置 markdown-it 实例
const md: MarkdownIt = new MarkdownIt({
  // ⚠️ 必须关掉 raw HTML：开启时 markdown 里的裸 HTML 会被原样交给 v-html 注入 DOM，
  // 而这里的 content 是 AI 生成的（受用户 prompt 影响）不可信内容 ——
  // 一个 <img src=x onerror=...> 就能在用户浏览器里执行脚本。
  // 关掉之后 AI 返回的 HTML 代码会被转义成文本显示，这才是聊天区该有的行为；
  // 代码块的高亮不受影响（围栏内内容走下面的 highlight 回调，与 html 选项无关）。
  html: false,
  linkify: true,
  typographer: true,
  highlight: function (str: string, lang: string): string {
    // ⚠️ 大文本跳过语法高亮。
    // 生成 HTML/多文件模式时，AI 的输出是一整个页面的源码，动辄几万字符；
    // highlight.js 在那种体量上单次耗时可达数百毫秒，而这段渲染发生在主线程，
    // 会造成肉眼可见的卡顿。高亮只是锦上添花，内容正确性优先。
    if (str.length > MAX_HIGHLIGHT_LENGTH) {
      return '<pre class="hljs"><code>' + md.utils.escapeHtml(str) + '</code></pre>'
    }
    if (lang && hljs.getLanguage(lang)) {
      try {
        return (
          '<pre class="hljs"><code>' +
          hljs.highlight(str, { language: lang, ignoreIllegals: true }).value +
          '</code></pre>'
        )
      } catch {
        // 忽略错误，使用默认处理
      }
    }

    return '<pre class="hljs"><code>' + md.utils.escapeHtml(str) + '</code></pre>'
  },
})

// 计算渲染后的 Markdown
const renderedMarkdown = computed(() => {
  return md.render(props.content)
})
</script>

<style scoped>
.markdown-content {
  line-height: 1.6;
  color: var(--text-1);
  word-wrap: break-word;
}

/* 全局样式，影响 v-html 内容 */
.markdown-content :deep(h1),
.markdown-content :deep(h2),
.markdown-content :deep(h3),
.markdown-content :deep(h4),
.markdown-content :deep(h5),
.markdown-content :deep(h6) {
  margin: 1.5em 0 0.5em 0;
  font-weight: 600;
  line-height: 1.25;
  color: var(--text-1);
}

.markdown-content :deep(h1) {
  font-size: 1.5em;
  border-bottom: 1px solid var(--border-1);
  padding-bottom: 0.3em;
}

.markdown-content :deep(h2) {
  font-size: 1.3em;
  border-bottom: 1px solid var(--border-1);
  padding-bottom: 0.3em;
}

.markdown-content :deep(h3) {
  font-size: 1.1em;
}

.markdown-content :deep(p) {
  margin: 0.8em 0;
}

.markdown-content :deep(ul),
.markdown-content :deep(ol) {
  margin: 0.8em 0;
  padding-left: 1.5em;
}

.markdown-content :deep(li) {
  margin: 0.3em 0;
}

.markdown-content :deep(blockquote) {
  margin: 1em 0;
  padding: 0.5em 1em;
  border-left: 3px solid var(--border-strong);
  border-radius: 0 var(--radius-xs) var(--radius-xs) 0;
  background-color: var(--bg-subtle);
  color: var(--text-2);
}

.markdown-content :deep(code) {
  padding: 0.2em 0.4em;
  font-family: 'Monaco', 'Menlo', 'Ubuntu Mono', monospace;
  font-size: 0.9em;
  background-color: var(--bg-subtle);
  border-radius: var(--radius-xs);
}

.markdown-content :deep(pre) {
  margin: 1em 0;
  padding: 1em;
  background-color: var(--bg-subtle);
  border: 1px solid var(--border-1);
  border-radius: var(--radius-sm);
  /* 限高 + 内部滚动：AI 生成的整页源码能有上千行，不限高会把聊天区撑到几十屏，
     既难滚动也让上面的消息彻底看不见。overflow: auto 同时保留横向滚动。 */
  max-height: 360px;
  overflow: auto;
}

.markdown-content :deep(pre code) {
  padding: 0;
  font-size: 0.9em;
  line-height: 1.4;
  background-color: transparent;
  border-radius: 0;
}

.markdown-content :deep(table) {
  width: 100%;
  margin: 1em 0;
  border-collapse: collapse;
}

.markdown-content :deep(table th),
.markdown-content :deep(table td) {
  padding: 0.5em 0.8em;
  text-align: left;
  border: 1px solid var(--border-2);
}

.markdown-content :deep(table th) {
  font-weight: 600;
  background-color: var(--bg-subtle);
}

.markdown-content :deep(table tr:nth-child(even)) {
  background-color: var(--bg-subtle);
}

.markdown-content :deep(a) {
  color: var(--brand);
  text-decoration: none;
}

.markdown-content :deep(a:hover) {
  text-decoration: underline;
}

.markdown-content :deep(img) {
  max-width: 100%;
  height: auto;
  margin: 0.5em 0;
  border-radius: var(--radius-sm);
}

.markdown-content :deep(hr) {
  margin: 1.5em 0;
  border: none;
  border-top: 1px solid var(--border-1);
}

/* ---------- 代码块 ---------- */
.markdown-content :deep(.hljs) {
  background-color: var(--bg-subtle) !important;
  border-radius: var(--radius-sm);
  font-family: 'Monaco', 'Menlo', 'Ubuntu Mono', monospace;
  font-size: 0.9em;
  line-height: 1.4;
}

/* 语法着色沿用 GitHub 风格，只做容器层面的统一 */
.markdown-content :deep(.hljs-keyword) {
  color: #d73a49;
  font-weight: 600;
}

.markdown-content :deep(.hljs-string) {
  color: #032f62;
}

.markdown-content :deep(.hljs-comment) {
  color: #6a737d;
  font-style: italic;
}

.markdown-content :deep(.hljs-number) {
  color: #005cc5;
}

.markdown-content :deep(.hljs-function) {
  color: #6f42c1;
}

.markdown-content :deep(.hljs-tag) {
  color: #22863a;
}

.markdown-content :deep(.hljs-attr) {
  color: #6f42c1;
}

.markdown-content :deep(.hljs-title) {
  color: #6f42c1;
  font-weight: 600;
}
</style>
