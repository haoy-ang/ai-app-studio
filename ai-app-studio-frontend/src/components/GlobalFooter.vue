<template>
  <footer class="footer">
    <div class="footer-inner">
      <div class="footer-top">
        <!-- 品牌 -->
        <div class="footer-brand">
          <div class="brand-line">
            <BrandMark :size="26" />
            <span class="brand-name">AI应用生成平台</span>
          </div>
          <p class="brand-desc">用一句话把你的想法变成可访问的网页应用。</p>
          <!-- 技术栈徽标：顺带承担"简历展示"的作用，面试官点开网站就能看到栈 -->
          <div class="stack">
            <span v-for="item in techStack" :key="item" class="mono-tag">{{ item }}</span>
          </div>
        </div>

        <!-- 快速入口（只放真实存在的路由，不放死链） -->
        <div class="footer-col">
          <h4 class="col-title">快速入口</h4>
          <RouterLink to="/" class="col-link">首页</RouterLink>
          <RouterLink to="/user/profile" class="col-link">个人资料</RouterLink>
        </div>

        <!-- 关于 -->
        <div class="footer-col">
          <h4 class="col-title">关于</h4>
          <p class="col-text">个人全栈学习项目，前后端分离部署。</p>
          <a
            class="col-link"
            href="https://github.com/haoy-ang/ai-app-studio"
            target="_blank"
            rel="noopener noreferrer"
            >GitHub 源码 ↗</a
          >
        </div>
      </div>

      <div class="footer-bottom">
        <span>© 2026 AI应用生成平台</span>
        <span>Built with Vue 3 &amp; Spring Boot</span>
      </div>
    </div>
  </footer>
</template>

<script setup lang="ts">
import BrandMark from '@/components/BrandMark.vue'

/** 页脚展示的技术栈，按前端 → 后端 → 存储排列 */
const techStack = [
  'Vue 3',
  'TypeScript',
  'Spring Boot',
  'LangChain4j',
  'LangGraph4j',
  'MySQL',
  'Redis',
]
</script>

<style scoped>
.footer {
  margin-top: var(--sp-7);
  border-top: 1px solid var(--border-1);
  background: var(--bg-card);
}

.footer-inner {
  max-width: var(--layout-max);
  margin: 0 auto;
  padding: var(--sp-7) var(--sp-5) var(--sp-5);
}

.footer-top {
  display: grid;
  grid-template-columns: 1.9fr 1fr 1fr;
  gap: var(--sp-6);
  padding-bottom: var(--sp-5);
  border-bottom: 1px solid var(--border-2);
}

/* ---------- 品牌列 ---------- */
.brand-line {
  display: flex;
  align-items: center;
  gap: 9px;
  margin-bottom: 10px;
}

.brand-name {
  font-size: var(--fs-md);
  font-weight: 650;
  color: var(--text-1);
  letter-spacing: -0.3px;
}

.brand-desc {
  max-width: 320px;
  margin: 0 0 var(--sp-4);
  font-size: var(--fs-sm);
  color: var(--text-3);
  line-height: 1.65;
}

.stack {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

/* ---------- 链接列 ---------- */
.col-title {
  margin: 0 0 var(--sp-3);
  font-size: var(--fs-sm);
  font-weight: 650;
  color: var(--text-1);
}

.col-link {
  display: block;
  padding: 4px 0;
  font-size: var(--fs-sm);
  color: var(--text-3);
  text-decoration: none;
  transition: color var(--dur) var(--ease);
}

.col-link:hover {
  color: var(--brand);
}

.col-text {
  margin: 0 0 8px;
  font-size: var(--fs-sm);
  line-height: 1.6;
  color: var(--text-3);
}

/* ---------- 版权行 ---------- */
.footer-bottom {
  display: flex;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: var(--sp-3);
  padding-top: var(--sp-4);
  font-size: var(--fs-xs);
  color: var(--text-4);
}

@media (max-width: 820px) {
  .footer-top {
    grid-template-columns: 1fr;
    gap: var(--sp-5);
  }
}
</style>
