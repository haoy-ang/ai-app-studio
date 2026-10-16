<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { ArrowUpOutlined } from '@ant-design/icons-vue'
import { useLoginUserStore } from '@/stores/loginUser'
import { addApp, listMyAppVoByPage, listGoodAppVoByPage } from '@/api/appController'
import { getDeployUrl } from '@/config/env'
import { CODE_GEN_TYPE_OPTIONS } from '@/utils/codeGenTypes'
import AppCard from '@/components/AppCard.vue'
import EmptyState from '@/components/EmptyState.vue'

const router = useRouter()
const loginUserStore = useLoginUserStore()

// 用户提示词
const userPrompt = ref('')
const creating = ref(false)

// 生成类型：默认「智能选择」—— 此时不把 codeGenType 传给后端，仍由 AI 路由判断
const selectedCodeGenType = ref('auto')
const codeGenTypeOptions = [
  { label: '智能选择（推荐）', value: 'auto' },
  ...CODE_GEN_TYPE_OPTIONS,
]

// 应用名称（可选，不填则后端取提示词前 12 位）
const appName = ref('')

// 快捷提示词（原先以内联超长字符串写在模板 @click 里，抽成数据后更易维护）
const quickPrompts = [
  {
    label: '个人博客网站',
    prompt:
      '创建一个现代化的个人博客网站，包含文章列表、详情页、分类标签、搜索功能、评论系统和个人简介页面。采用简洁的设计风格，支持响应式布局，文章支持Markdown格式，首页展示最新文章和热门推荐。',
  },
  {
    label: '企业官网',
    prompt:
      '设计一个专业的企业官网，包含公司介绍、产品服务展示、新闻资讯、联系我们等页面。采用商务风格的设计，包含轮播图、产品展示卡片、团队介绍、客户案例展示，支持多语言切换和在线客服功能。',
  },
  {
    label: '在线商城',
    prompt:
      '构建一个功能完整的在线商城，包含商品展示、购物车、用户注册登录、订单管理、支付结算等功能。设计现代化的商品卡片布局，支持商品搜索筛选、用户评价、优惠券系统和会员积分功能。',
  },
  {
    label: '作品展示网站',
    prompt:
      '制作一个精美的作品展示网站，适合设计师、摄影师、艺术家等创作者。包含作品画廊、项目详情页、个人简历、联系方式等模块。采用瀑布流或网格布局展示作品，支持图片放大预览和作品分类筛选。',
  },
]

// 我的应用数据
const myApps = ref<API.AppVO[]>([])
const myAppsPage = reactive({
  current: 1,
  pageSize: 6,
  total: 0,
})

// 精选应用数据
const featuredApps = ref<API.AppVO[]>([])
const featuredAppsPage = reactive({
  current: 1,
  pageSize: 6,
  total: 0,
})

// 设置提示词
const setPrompt = (prompt: string) => {
  userPrompt.value = prompt
}

// 空状态里的引导按钮：直接把第一个快捷提示词填进输入框
const useFirstTemplate = () => {
  setPrompt(quickPrompts[0].prompt)
}

// 创建应用
const createApp = async () => {
  if (!userPrompt.value.trim()) {
    message.warning('请输入应用描述')
    return
  }

  if (!loginUserStore.loginUser.id) {
    message.warning('请先登录')
    await router.push('/user/login')
    return
  }

  creating.value = true
  try {
    const res = await addApp({
      initPrompt: userPrompt.value.trim(),
      // 名称为空就不传，由后端按提示词截取兜底
      ...(appName.value.trim() ? { appName: appName.value.trim() } : {}),
      // 「智能选择」时同样不传，走后端 AI 路由；显式选择才传，且后端会校验取值合法性
      ...(selectedCodeGenType.value !== 'auto'
        ? { codeGenType: selectedCodeGenType.value }
        : {}),
    })

    if (res.data.code === 0 && res.data.data) {
      message.success('应用创建成功')
      // 跳转到对话页面，确保ID是字符串类型
      const appId = String(res.data.data)
      await router.push(`/app/chat/${appId}`)
    } else {
      message.error('创建失败：' + res.data.message)
    }
  } catch (error) {
    console.error('创建应用失败：', error)
    message.error('创建失败，请重试')
  } finally {
    creating.value = false
  }
}

// 加载我的应用
const loadMyApps = async () => {
  if (!loginUserStore.loginUser.id) {
    return
  }

  try {
    const res = await listMyAppVoByPage({
      pageNum: myAppsPage.current,
      pageSize: myAppsPage.pageSize,
      sortField: 'createTime',
      sortOrder: 'desc',
    })

    if (res.data.code === 0 && res.data.data) {
      myApps.value = res.data.data.records || []
      myAppsPage.total = res.data.data.totalRow || 0
    }
  } catch (error) {
    console.error('加载我的应用失败：', error)
  }
}

// 加载精选应用
const loadFeaturedApps = async () => {
  try {
    const res = await listGoodAppVoByPage({
      pageNum: featuredAppsPage.current,
      pageSize: featuredAppsPage.pageSize,
      sortField: 'createTime',
      sortOrder: 'desc',
    })

    if (res.data.code === 0 && res.data.data) {
      featuredApps.value = res.data.data.records || []
      featuredAppsPage.total = res.data.data.totalRow || 0
    }
  } catch (error) {
    console.error('加载精选应用失败：', error)
  }
}

// 查看对话
const viewChat = (appId: string | number | undefined) => {
  if (appId) {
    router.push(`/app/chat/${appId}`)
  }
}

// 查看作品
const viewWork = (app: API.AppVO) => {
  if (app.deployKey) {
    const url = getDeployUrl(app.deployKey)
    window.open(url, '_blank')
  }
}

// 页面加载时获取数据
onMounted(() => {
  loadMyApps()
  loadFeaturedApps()
})
</script>

<template>
  <div class="home-page">
    <!-- 主视觉 -->
    <section class="hero">
      <h1 class="hero-title">一句话，构建你的网页应用<i class="caret" aria-hidden="true"></i></h1>
      <p class="hero-subtitle">描述你的想法，剩下的交给 AI</p>

      <!--
        输入卡片：文字输入 + 工具条合成一块。
        原先「生成类型下拉」和「应用名输入框」是挂在卡片外面的两个独立控件，
        把首屏切成 5 块、视觉主体被切碎；收进卡片后两者都变成无边框的幽灵控件。
      -->
      <div class="prompt-box">
        <a-textarea
          v-model:value="userPrompt"
          placeholder="例如：帮我创建一个个人博客网站，包含文章列表和评论功能"
          :rows="3"
          :maxlength="1000"
        />
        <div class="prompt-bar">
          <a-select
            v-model:value="selectedCodeGenType"
            :options="codeGenTypeOptions"
            class="ghost-select"
          />
          <a-input
            v-model:value="appName"
            placeholder="应用名称（可不填）"
            :maxlength="50"
            class="ghost-input"
          />
          <a-button class="send-btn" :loading="creating" @click="createApp">
            <template #icon><ArrowUpOutlined /></template>
          </a-button>
        </div>
      </div>

      <!-- 快捷标签：用等宽编号而不是彩色标签堆砌，彩度来源交给"生成中"那一个语义 -->
      <div class="quick-tags">
        <button
          v-for="(item, index) in quickPrompts"
          :key="item.label"
          type="button"
          class="quick-tag"
          @click="setPrompt(item.prompt)"
        >
          <span class="quick-idx">{{ String(index + 1).padStart(2, '0') }}</span>
          <span>{{ item.label }}</span>
        </button>
      </div>
    </section>

    <!-- 我的作品 -->
    <section class="section">
      <h2 class="section-title">我的作品</h2>
      <div v-if="myApps.length" class="app-grid">
        <AppCard
          v-for="app in myApps"
          :key="app.id"
          :app="app"
          @view-chat="viewChat"
          @view-work="viewWork"
        />
      </div>
      <EmptyState
        v-else
        title="还没有作品"
        description="在上方描述你的想法，30 秒就能生成第一个应用"
        action-text="试试「个人博客网站」"
        @action="useFirstTemplate"
      />

      <div v-if="myAppsPage.total > myAppsPage.pageSize" class="pagination-wrap">
        <a-pagination
          v-model:current="myAppsPage.current"
          v-model:page-size="myAppsPage.pageSize"
          :total="myAppsPage.total"
          :show-size-changer="false"
          :show-total="(total: number) => `共 ${total} 个应用`"
          @change="loadMyApps"
        />
      </div>
    </section>

    <!-- 精选案例 -->
    <section class="section">
      <h2 class="section-title">精选案例</h2>
      <div v-if="featuredApps.length" class="app-grid">
        <AppCard
          v-for="app in featuredApps"
          :key="app.id"
          :app="app"
          featured
          @view-chat="viewChat"
          @view-work="viewWork"
        />
      </div>
      <EmptyState v-else title="暂无精选案例" description="优质作品会在这里被推荐" />

      <div v-if="featuredAppsPage.total > featuredAppsPage.pageSize" class="pagination-wrap">
        <a-pagination
          v-model:current="featuredAppsPage.current"
          v-model:page-size="featuredAppsPage.pageSize"
          :total="featuredAppsPage.total"
          :show-size-changer="false"
          :show-total="(total: number) => `共 ${total} 个案例`"
          @change="loadFeaturedApps"
        />
      </div>
    </section>
  </div>
</template>

<style scoped>
.home-page {
  max-width: var(--layout-max);
  margin: 0 auto;
  padding: 0 var(--sp-5) var(--sp-7);
}

/* ---------- 主视觉 ---------- */
.hero {
  position: relative;
  overflow: hidden;
  padding: var(--sp-9) 0 var(--sp-7);
  text-align: center;
}

/*
 * 网格底纹 + 一处柔光，构成首屏的"空气"。
 * 只放一处光（原来是计划里的三处），并压到 10% 透明度按 —— 彩度配额留给"生成中"。
 */
.hero::before,
.hero::after {
  content: '';
  position: absolute;
  z-index: 0;
  pointer-events: none;
}

.hero::before {
  inset: 0;
  background-image:
    linear-gradient(to right, var(--grid-line) 1px, transparent 1px),
    linear-gradient(to bottom, var(--grid-line) 1px, transparent 1px);
  background-size: var(--grid-size) var(--grid-size);
  -webkit-mask-image: radial-gradient(120% 78% at 50% 4%, #000 22%, transparent 74%);
  mask-image: radial-gradient(120% 78% at 50% 4%, #000 22%, transparent 74%);
}

.hero::after {
  width: 820px;
  height: 460px;
  left: 50%;
  top: -190px;
  transform: translateX(-50%);
  background: radial-gradient(closest-side, rgba(37, 99, 255, 0.1), transparent 72%);
}

.hero > * {
  position: relative;
  z-index: 1;
}

.hero-title {
  margin: 0 0 var(--sp-3);
  font-size: var(--fs-3xl);
  font-weight: 700;
  line-height: 1.16;
  color: var(--text-1);
  letter-spacing: -1.6px;
}

.hero-subtitle {
  margin: 0 0 var(--sp-6);
  font-size: var(--fs-md);
  color: var(--text-3);
}

/* ---------- 输入卡片 ---------- */
.prompt-box {
  max-width: 720px;
  margin: 0 auto;
  background: var(--bg-card);
  border: 1px solid var(--border-1);
  border-radius: var(--radius-xl);
  box-shadow: var(--shadow-md);
  transition:
    border-color var(--dur) var(--ease),
    box-shadow var(--dur) var(--ease);
}

.prompt-box:focus-within {
  border-color: var(--brand);
  box-shadow: var(--shadow-md), var(--ring);
}

/* 只命中卡片自己的 textarea（直接子元素），不误伤工具条里的幽灵输入框 */
.prompt-box > :deep(.ant-input) {
  padding: var(--sp-4) var(--sp-4) var(--sp-2);
  background: transparent;
  border: none;
  box-shadow: none;
  font-size: var(--fs-md);
  line-height: 1.65;
  resize: none;
}

.prompt-bar {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  padding: 0 var(--sp-3) var(--sp-3);
}

/* 幽灵下拉：透明底、无边框，hover / 展开时才有底色 */
.ghost-select {
  flex-shrink: 0;
  width: 158px;
}

.ghost-select :deep(.ant-select-selector) {
  padding-inline: 10px !important;
  background: transparent !important;
  border: none !important;
  border-radius: var(--radius-md) !important;
  box-shadow: none !important;
  font-size: var(--fs-sm) !important;
}

.ghost-select:hover :deep(.ant-select-selector) {
  background: var(--bg-hover) !important;
}

.ghost-select :deep(.ant-select-arrow) {
  color: var(--text-4);
}

/* 幽灵输入框：同理，避免在卡片里再长出一个"表单" */
.ghost-input {
  flex: 1;
  min-width: 0;
}

.ghost-input :deep(.ant-input) {
  padding-inline: 10px;
  background: transparent;
  border: none;
  border-radius: var(--radius-md);
  box-shadow: none;
  font-size: var(--fs-sm);
  transition: background var(--dur) var(--ease);
}

.ghost-input :deep(.ant-input:hover),
.ghost-input :deep(.ant-input:focus) {
  background: var(--bg-hover);
}

.send-btn {
  flex-shrink: 0;
  display: grid;
  place-items: center;
  width: 36px;
  height: 36px;
  margin-left: var(--sp-1);
  padding: 0 !important;
  border: none !important;
  border-radius: var(--radius-full) !important;
  background: var(--brand) !important;
  color: #fff !important;
  box-shadow: 0 6px 14px -6px rgba(37, 99, 255, 0.7) !important;
  transition:
    background var(--dur) var(--ease),
    transform var(--dur) var(--ease) !important;
}

.send-btn:hover {
  background: var(--brand-hover) !important;
  transform: translateY(-1px);
}

.send-btn:active {
  transform: translateY(0);
}

/* ---------- 快捷标签 ---------- */
.quick-tags {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: var(--sp-2);
  margin-top: var(--sp-5);
}

.quick-tag {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 7px 14px 7px 12px;
  font-family: inherit;
  font-size: var(--fs-sm);
  color: var(--text-2);
  background: var(--bg-card);
  border: 1px solid var(--border-1);
  border-radius: var(--radius-full);
  cursor: pointer;
  transition: all var(--dur) var(--ease);
}

.quick-idx {
  font-family: var(--font-mono);
  font-size: 10px;
  letter-spacing: 0.04em;
  color: var(--text-4);
  transition: color var(--dur) var(--ease);
}

.quick-tag:hover {
  color: var(--brand);
  border-color: var(--brand);
  background: var(--brand-soft);
}

.quick-tag:hover .quick-idx {
  color: var(--brand);
}

/* ---------- 内容区块 ---------- */
.section {
  margin-top: var(--sp-7);
}

.section-title {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 0 0 var(--sp-5);
  font-size: var(--fs-lg);
  font-weight: 650;
  color: var(--text-1);
  letter-spacing: -0.3px;
}

.section-title::before {
  content: '';
  width: 3px;
  height: 16px;
  border-radius: 2px;
  background: var(--brand);
}

.app-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: var(--sp-5);
}

.pagination-wrap {
  display: flex;
  justify-content: center;
  margin-top: var(--sp-5);
}

/* ---------- 响应式 ---------- */
@media (max-width: 768px) {
  .hero {
    padding: var(--sp-7) 0 var(--sp-6);
  }

  .hero-title {
    letter-spacing: -1px;
  }

  .app-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 560px) {
  .prompt-bar {
    flex-wrap: wrap;
  }

  .ghost-select {
    width: 100%;
  }

  .ghost-input {
    flex: 1 1 auto;
  }
}
</style>
