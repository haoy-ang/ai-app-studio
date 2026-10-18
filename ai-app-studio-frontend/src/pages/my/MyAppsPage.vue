<template>
  <div class="my-apps-page">
    <!-- 页头 -->
    <header class="page-head">
      <div class="head-text">
        <h1 class="page-title">我的作品</h1>
        <p class="page-sub">
          共 {{ total }} 个应用 · 按名称搜索、按类型筛选，找不到的那个不用再一页页翻了
        </p>
      </div>
      <a-button type="primary" @click="goCreate">
        <template #icon><PlusOutlined /></template>
        新建应用
      </a-button>
    </header>

    <!-- 工具条 -->
    <div class="toolbar">
      <a-input-search
        v-model:value="query.appName"
        placeholder="搜索应用名称"
        allow-clear
        class="toolbar-search"
        @search="doSearch"
      />
      <a-select
        v-model:value="query.codeGenType"
        :options="typeOptions"
        class="toolbar-select"
        @change="doSearch"
      />
      <a-select
        v-model:value="sortKey"
        :options="sortOptions"
        class="toolbar-select"
        @change="doSearch"
      />
    </div>

    <!-- 作品表 -->
    <a-table
      v-if="loading || rows.length"
      :columns="columns"
      :data-source="rows"
      :pagination="pagination"
      :loading="loading"
      row-key="id"
      class="app-table"
      @change="handleTableChange"
    >
      <template #bodyCell="{ column, record }">
        <!-- 封面 + 名称 + 类型合成一列：单独给封面开一列太浪费横向空间 -->
        <template v-if="column.key === 'app'">
          <div class="app-cell">
            <div class="thumb">
              <img
                v-if="record.cover && !failedCoverKeys.has(String(record.id))"
                :src="record.cover"
                :alt="record.appName"
                @error="markCoverFailed(record)"
              />
              <div v-else class="thumb-empty" aria-hidden="true">
                <svg width="20" height="20" viewBox="0 0 32 32" fill="none" stroke="currentColor"
                     stroke-width="1.8" stroke-linejoin="round">
                  <rect x="4" y="4" width="10" height="10" rx="2.5" />
                  <rect x="18" y="18" width="10" height="10" rx="2.5" opacity="0.45" />
                </svg>
              </div>
            </div>
            <div class="app-meta">
              <RouterLink v-if="record.id" :to="`/app/chat/${record.id}`" class="app-name">
                {{ record.appName || '未命名应用' }}
              </RouterLink>
              <span v-else class="app-name">未命名应用</span>
              <div class="app-tags">
                <span v-if="formatCodeGenTypeShort(record.codeGenType)" class="type-tag">
                  {{ formatCodeGenTypeShort(record.codeGenType) }}
                </span>
                <span v-if="record.codeReady === false" class="draft-tag">代码未生成</span>
              </div>
            </div>
          </div>
        </template>

        <!-- 部署状态：这是管理页最该一眼看清的一件事，别让人逐个点开找 -->
        <template v-else-if="column.key === 'status'">
          <span v-if="record.deployKey" class="status status--live">
            <i class="dot" aria-hidden="true"></i>
            已上线
            <a class="status-link" @click.prevent="viewWork(record)">打开</a>
          </span>
          <span v-else class="status status--idle">
            <i class="dot" aria-hidden="true"></i>
            未部署
          </span>
        </template>

        <template v-else-if="column.key === 'createTime'">
          <span :title="formatTime(record.createTime)">
            {{ formatRelativeTime(record.createTime) }}
          </span>
        </template>

        <template v-else-if="column.key === 'deployedTime'">
          <span v-if="record.deployedTime" :title="formatTime(record.deployedTime)">
            {{ formatRelativeTime(record.deployedTime) }}
          </span>
          <span v-else class="muted">—</span>
        </template>

        <template v-else-if="column.key === 'action'">
          <div class="row-actions">
            <a-button type="link" size="small" class="chat-btn" @click="goChat(record)">
              对话
            </a-button>
            <a-dropdown :trigger="['click']" placement="bottomRight">
              <a-button size="small" type="text" class="more-btn" aria-label="更多操作">
                <MoreOutlined />
              </a-button>
              <template #overlay>
                <a-menu @click="menuHandlerFor(record)">
                  <a-menu-item key="rename">重命名</a-menu-item>
                  <a-menu-item key="download" :disabled="record.codeReady === false">
                    下载代码
                  </a-menu-item>
                  <a-menu-item key="deploy">重新部署</a-menu-item>
                  <a-menu-item key="version">版本历史</a-menu-item>
                  <a-menu-divider />
                  <a-menu-item key="delete" danger>删除应用</a-menu-item>
                </a-menu>
              </template>
            </a-dropdown>
          </div>
        </template>
      </template>
    </a-table>

    <!-- 空状态：筛选后没结果和「一个都还没有」是两回事，文案要分开 -->
    <EmptyState
      v-else
      :title="hasFilter ? '没有匹配的作品' : '还没有作品'"
      :description="
        hasFilter ? '换个关键词或把类型改成「全部」试试' : '描述你的想法，30 秒就能生成第一个应用'
      "
      :action-text="hasFilter ? '' : '去创建第一个应用'"
      @action="goCreate"
    />

    <!-- 版本历史抽屉：与首页共用同一个组件和同一套动作 -->
    <AppVersionDrawer v-model:open="versionOpen" :app="versionApp" @changed="refresh" />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { type MenuProps } from 'ant-design-vue'
import { MoreOutlined, PlusOutlined } from '@ant-design/icons-vue'
import { listMyAppVoByPage } from '@/api/appController'
import { CODE_GEN_TYPE_OPTIONS, formatCodeGenTypeShort } from '@/utils/codeGenTypes'
import { formatRelativeTime, formatTime } from '@/utils/time'
import { useAppActions } from '@/composables/useAppActions'
import AppVersionDrawer from '@/components/AppVersionDrawer.vue'
import EmptyState from '@/components/EmptyState.vue'

/**
 * 每页条数固定 20：后端 `/app/my/list/page/vo` 把上限写死成 20（`pageSize > 20` 直接拒绝），
 * 与其让用户选了 50 再看到一句报错，不如根本不提供这个选项。
 */
const PAGE_SIZE = 20

const router = useRouter()

const columns = [
  { title: '作品', key: 'app', width: 320 },
  { title: '状态', key: 'status', width: 140 },
  { title: '创建时间', key: 'createTime', width: 120 },
  { title: '最近部署', key: 'deployedTime', width: 120 },
  { title: '操作', key: 'action', width: 140, align: 'right' as const },
]

const typeOptions = [{ label: '全部类型', value: '' }, ...CODE_GEN_TYPE_OPTIONS]

/**
 * 排序选项直接对应后端 `SortFieldValidator` 的白名单字段，
 * 写错字段后端会静默忽略（不报错、也不排序），所以这里只放确认可用的三个。
 */
const sortOptions = [
  { label: '最近创建', value: 'createTime_desc' },
  { label: '名称 A → Z', value: 'appName_ascend' },
  { label: '最近部署', value: 'deployedTime_desc' },
]
const sortKey = ref('createTime_desc')

const rows = ref<API.AppVO[]>([])
const total = ref(0)
// 初始为 true：不然首屏会先闪一下「还没有作品」，再被数据顶掉
const loading = ref(true)

const query = reactive<API.AppQueryRequest>({
  pageNum: 1,
  pageSize: PAGE_SIZE,
  appName: '',
  codeGenType: '',
})

const hasFilter = computed(() => !!(query.appName?.trim() || query.codeGenType))

// 封面加载失败的记录：封面生成失败 / COS 挂了 / 外链失效一起兜成占位图
const failedCoverKeys = ref<Set<string>>(new Set())
const markCoverFailed = (record: API.AppVO) => {
  failedCoverKeys.value.add(String(record.id))
}

const fetchList = async () => {
  loading.value = true
  try {
    const [sortField, sortOrder] = sortKey.value.split('_')
    const res = await listMyAppVoByPage({
      pageNum: query.pageNum,
      pageSize: PAGE_SIZE,
      // 空串要显式去掉：后端是 `eq(codeGenType, ?)`，传 '' 会变成「类型等于空串」，
      // 一条都查不到（null 才会被跳过）
      appName: query.appName?.trim() || undefined,
      codeGenType: query.codeGenType || undefined,
      sortField,
      sortOrder,
    })
    if (res.data.code === 0 && res.data.data) {
      rows.value = res.data.data.records ?? []
      total.value = res.data.data.totalRow ?? 0
      failedCoverKeys.value.clear()
    } else {
      rows.value = []
      total.value = 0
      message.error('加载失败：' + (res.data.message || '未知错误'))
    }
  } catch (error) {
    console.error('加载我的作品失败：', error)
    message.error('加载失败，请重试')
  } finally {
    loading.value = false
  }
}

// 重命名 / 下载 / 部署 / 删除 / 版本历史：与首页共用
const { versionOpen, versionApp, refresh, editApp, viewWork, downloadApp, deploy, remove, openVersions } =
  useAppActions({
    onChanged: async () => {
      // 删掉当前页最后一条时回退一页，否则会停在一张空白页上（用户以为数据全没了）
      if (rows.value.length === 1 && (query.pageNum ?? 1) > 1) {
        query.pageNum = (query.pageNum ?? 1) - 1
      }
      await fetchList()
    },
  })

const pagination = computed(() => ({
  current: query.pageNum ?? 1,
  pageSize: PAGE_SIZE,
  total: total.value,
  showSizeChanger: false,
  showTotal: (t: number) => `共 ${t} 个`,
}))

// 只关心页码；排序走上面的下拉，不开放表头点击排序（字段白名单只有三个，表头点了会静默不生效）
const handleTableChange = (pag: { current?: number }) => {
  query.pageNum = pag.current ?? 1
  fetchList()
}

const doSearch = () => {
  query.pageNum = 1
  fetchList()
}

const goCreate = () => {
  router.push('/')
}

const goChat = (app: API.AppVO) => {
  if (app.id) {
    router.push(`/app/chat/${app.id}`)
  }
}

/**
 * 每行的菜单点击处理器
 * <p>
 * 写成「传一行进去、返回处理器」而不是模板里的 `(info) => ...` 内联箭头，
 * 是因为模板表达式没有类型上下文，`info` 会被推成 any，菜单 key 就失去类型保护了；
 * 这里用返回值上的 `MenuProps['onClick']` 注解，让它自己带上下文。
 */
const menuHandlerFor = (app: API.AppVO): NonNullable<MenuProps['onClick']> => (info) => {
  switch (info.key) {
    case 'rename':
      editApp(app)
      break
    case 'download':
      downloadApp(app)
      break
    case 'deploy':
      deploy(app)
      break
    case 'version':
      openVersions(app)
      break
    case 'delete':
      remove(app)
      break
  }
}

onMounted(fetchList)
</script>

<style scoped>
.my-apps-page {
  max-width: var(--layout-max);
  margin: 0 auto;
  padding: var(--sp-7) var(--sp-5) var(--sp-8);
}

/* ---------- 页头 ---------- */
.page-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--sp-4);
  margin-bottom: var(--sp-5);
}

.page-title {
  margin: 0 0 6px;
  font-size: var(--fs-xl);
  font-weight: 700;
  letter-spacing: -0.8px;
  color: var(--text-1);
}

.page-sub {
  margin: 0;
  font-size: var(--fs-sm);
  color: var(--text-3);
}

/* ---------- 工具条 ---------- */
.toolbar {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  margin-bottom: var(--sp-4);
  padding: var(--sp-3) var(--sp-4);
  background: var(--bg-card);
  border: 1px solid var(--border-1);
  border-radius: var(--radius-lg);
}

.toolbar-search {
  flex: 1;
  min-width: 180px;
  max-width: 340px;
}

.toolbar-select {
  width: 148px;
  flex-shrink: 0;
}

/* ---------- 表格 ---------- */
.app-table :deep(.ant-table) {
  background: var(--bg-card);
  border-radius: var(--radius-lg);
}

.app-table :deep(.ant-table-thead > tr > th) {
  background: var(--bg-subtle);
  color: var(--text-3);
  font-weight: 600;
  font-size: var(--fs-sm);
  border-bottom: 1px solid var(--border-1);
}

.app-table :deep(.ant-table-tbody > tr > td) {
  vertical-align: middle;
  border-bottom: 1px solid var(--border-1);
}

.app-table :deep(.ant-table-tbody > tr:hover > td) {
  background: var(--bg-hover);
}

/* ---------- 作品单元格 ---------- */
.app-cell {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  min-width: 0;
}

.thumb {
  flex-shrink: 0;
  width: 64px;
  height: 44px;
  overflow: hidden;
  border: 1px solid var(--border-1);
  border-radius: var(--radius-sm);
  background: var(--bg-inset);
}

.thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

/* 无封面：工程网格，比一块死灰更贴这套视觉 */
.thumb-empty {
  display: grid;
  place-items: center;
  width: 100%;
  height: 100%;
  color: var(--brand);
  opacity: 0.5;
  background-image:
    linear-gradient(to right, var(--grid-line) 1px, transparent 1px),
    linear-gradient(to bottom, var(--grid-line) 1px, transparent 1px);
  background-size: 12px 12px;
}

.app-meta {
  min-width: 0;
}

.app-name {
  display: block;
  max-width: 220px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: var(--fs-base);
  font-weight: 600;
  color: var(--text-1);
  text-decoration: none;
}

.app-name:hover {
  color: var(--brand);
}

.app-tags {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 3px;
}

.type-tag {
  font-family: var(--font-mono);
  font-size: 10px;
  letter-spacing: 0.03em;
  color: var(--brand);
  background: var(--brand-soft);
  border-radius: var(--radius-xs);
  padding: 1px 6px;
}

.draft-tag {
  font-size: 11px;
  color: var(--text-4);
}

/* ---------- 状态 ---------- */
.status {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: var(--fs-sm);
}

.status .dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--text-4);
}

.status--live {
  color: var(--text-1);
}

.status--live .dot {
  background: var(--brand);
}

.status--idle {
  color: var(--text-3);
}

.status-link {
  margin-left: 2px;
  color: var(--brand);
  cursor: pointer;
}

.status-link:hover {
  text-decoration: underline;
}

.muted {
  color: var(--text-4);
}

/* ---------- 操作列 ---------- */
.row-actions {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  justify-content: flex-end;
}

.chat-btn {
  padding-inline: 6px !important;
}

/* 次要操作走中性色，不跟「对话」抢注意力 */
.more-btn {
  color: var(--text-3);
}

.more-btn:hover {
  color: var(--text-1);
  background: var(--bg-subtle);
}

/* ---------- 响应式 ---------- */
@media (max-width: 768px) {
  .page-head {
    flex-direction: column;
    align-items: flex-start;
  }

  .toolbar {
    flex-wrap: wrap;
  }

  .toolbar-search,
  .toolbar-select {
    flex: 1 1 140px;
    max-width: none;
  }
}
</style>
