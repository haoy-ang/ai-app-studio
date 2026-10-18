<template>
  <header class="header">
    <div class="header-inner">
      <!-- 左侧：品牌标识 -->
      <RouterLink to="/" class="brand">
        <BrandMark :size="30" />
        <span class="brand-name">AI应用生成平台</span>
      </RouterLink>

      <!-- 中间：导航菜单 -->
      <nav class="nav">
        <a-menu
          v-model:selectedKeys="selectedKeys"
          mode="horizontal"
          :items="menuItems"
          @click="handleMenuClick"
        />
      </nav>

      <!-- 右侧：用户区域 -->
      <div class="user-area">
        <a-dropdown v-if="loginUserStore.loginUser.id" placement="bottomRight">
          <div class="user-trigger">
            <a-avatar :size="28" :src="displayAvatar" />
            <span class="user-name">{{ displayName }}</span>
          </div>
          <template #overlay>
            <a-menu>
              <a-menu-item key="profile" @click="goProfile">
                <UserOutlined />
                个人资料
              </a-menu-item>
              <a-menu-item key="logout" @click="doLogout">
                <LogoutOutlined />
                退出登录
              </a-menu-item>
            </a-menu>
          </template>
        </a-dropdown>
        <a-button v-else class="login-btn" @click="router.push('/user/login')">登录</a-button>
      </div>
    </div>
  </header>
</template>

<script setup lang="ts">
import { computed, h, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { type MenuProps, message } from 'ant-design-vue'
import { useLoginUserStore } from '@/stores/loginUser'
import { userLogout } from '@/api/userController'
import { LogoutOutlined, HomeOutlined, UserOutlined } from '@ant-design/icons-vue'
import defaultAvatar from '@/assets/defaultAvatar.png'
import BrandMark from '@/components/BrandMark.vue'

const loginUserStore = useLoginUserStore()
const router = useRouter()
const route = useRoute()

/**
 * 展示用昵称：没有昵称时统一兜底成「用户」。
 * 不回退到 userAccount —— 账号在公开接口里已经不返回了（见后端 AppUserVO），
 * 而且把登录账号显示在导航栏上本身也不合适。
 */
const displayName = computed(() => loginUserStore.loginUser.userName?.trim() || '用户')

/**
 * 展示用头像：没设置过就用默认头像，避免导航栏出现一个空框
 */
const displayAvatar = computed(() => loginUserStore.loginUser.userAvatar || defaultAvatar)

// 跳转个人资料页
const goProfile = () => {
  router.push('/user/profile')
}

// 当前选中菜单
const selectedKeys = ref<string[]>([route.path])

// 用 watch 跟随路由更新高亮项。
// 原先在 setup 顶层调 router.afterEach，有两个问题：
//   1. afterEach 的签名是 (to, from, failure?)，写 next 是无效参数
//   2. 它注册的是永久守卫且从不注销，组件每次实例化都会多挂一个
watch(
  () => route.path,
  (path) => {
    selectedKeys.value = [path]
  },
)

// 菜单配置项
const originItems = [
  {
    key: '/',
    icon: () => h(HomeOutlined),
    label: '主页',
    title: '主页',
  },
  {
    key: '/my/apps',
    label: '我的作品',
    title: '我的作品',
  },
  {
    key: '/admin/userManage',
    label: '用户管理',
    title: '用户管理',
  },
  {
    key: '/admin/appManage',
    label: '应用管理',
    title: '应用管理',
  },
]

/**
 * 过滤菜单项：
 * - /admin 下的入口仅管理员可见
 * - /my 下的入口仅登录用户可见（未登录点进去只会被守卫弹回登录页，不如不显示）
 */
const filterMenus = (menus = [] as MenuProps['items']) => {
  return menus?.filter((menu) => {
    const menuKey = menu?.key as string
    const loginUser = loginUserStore.loginUser
    if (menuKey?.startsWith('/admin')) {
      if (!loginUser || loginUser.userRole !== 'admin') {
        return false
      }
    }
    if (menuKey?.startsWith('/my')) {
      if (!loginUser || !loginUser.id) {
        return false
      }
    }
    return true
  })
}

// 展示在菜单的路由数组
const menuItems = computed<MenuProps['items']>(() => filterMenus(originItems))

// 处理菜单点击
const handleMenuClick: MenuProps['onClick'] = (e) => {
  const key = e.key as string
  selectedKeys.value = [key]
  if (key.startsWith('/')) {
    router.push(key)
  }
}

// 退出登录
const doLogout = async () => {
  const res = await userLogout()
  if (res.data.code === 0) {
    loginUserStore.setLoginUser({
      userName: '未登录',
    })
    message.success('退出登录成功')
    await router.push('/user/login')
  } else {
    message.error('退出登录失败，' + res.data.message)
  }
}
</script>

<style scoped>
.header {
  position: sticky;
  top: 0;
  z-index: 100;
  height: var(--header-h);
  /* 半透明 + 毛玻璃：滚动时内容从底下穿过，比实心白更轻 */
  background: rgba(255, 255, 255, 0.85);
  backdrop-filter: blur(14px) saturate(1.6);
  -webkit-backdrop-filter: blur(14px) saturate(1.6);
  border-bottom: 1px solid var(--border-1);
}

/* 顶部 2px 品牌细线：全站识别锚点，成本最低的那个 */
.header::before {
  content: '';
  position: absolute;
  inset: 0 0 auto;
  height: 2px;
  background: linear-gradient(90deg, var(--brand) 0%, var(--accent) 100%);
}

.header-inner {
  display: flex;
  align-items: center;
  gap: var(--sp-6);
  height: 100%;
  max-width: var(--layout-max);
  margin: 0 auto;
  padding: 0 var(--sp-5);
}

/* ---------- 品牌 ---------- */
.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-shrink: 0;
  text-decoration: none;
}

.brand-name {
  font-size: var(--fs-md);
  font-weight: 650;
  color: var(--text-1);
  letter-spacing: -0.3px;
  transition: color var(--dur) var(--ease);
}

.brand:hover .brand-name {
  color: var(--brand);
}

/* ---------- 导航 ---------- */
.nav {
  display: flex;
  align-items: center;
  flex: 1;
  min-width: 0;
}

.nav :deep(.ant-menu) {
  background: transparent;
  border-bottom: none;
  line-height: 1;
}

.nav :deep(.ant-menu-horizontal) {
  display: flex;
  align-items: center;
  border-bottom: none;
  line-height: 1;
}

/* 菜单项做成药丸，而不是 Antd 默认的「文字 + 蓝色下划线」 */
.nav :deep(.ant-menu-item) {
  height: 34px;
  line-height: 34px;
  margin-inline: 2px;
  padding-inline: 12px;
  border-radius: var(--radius-md);
  color: var(--text-2);
  transition:
    background var(--dur) var(--ease),
    color var(--dur) var(--ease);
}

.nav :deep(.ant-menu-item:hover) {
  background: var(--bg-hover);
  color: var(--text-1);
}

/* 干掉 Antd 默认的蓝色下划线高亮 */
.nav :deep(.ant-menu-horizontal > .ant-menu-item::after),
.nav :deep(.ant-menu-horizontal > .ant-menu-item-selected::after) {
  display: none !important;
}

.nav :deep(.ant-menu-item-selected) {
  background: var(--bg-subtle);
  color: var(--text-1);
  font-weight: 600;
}

/* ---------- 用户区 ---------- */
.user-area {
  display: flex;
  align-items: center;
  flex-shrink: 0;
}

.user-trigger {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  padding: 4px 12px 4px 4px;
  border-radius: var(--radius-full);
  cursor: pointer;
  transition: background var(--dur) var(--ease);
}

.user-trigger:hover {
  background: var(--bg-subtle);
}

.user-name {
  font-size: var(--fs-base);
  color: var(--text-1);
}

/* 登录按钮：深色胶囊。把蓝色的"配额"留给真正的行动（发送 / 生成 / 部署） */
.login-btn {
  height: 34px !important;
  padding-inline: 18px !important;
  border-radius: var(--radius-full) !important;
  border-color: var(--text-1) !important;
  background: var(--text-1) !important;
  color: #fff !important;
  font-weight: 600 !important;
  box-shadow: none !important;
  transition:
    background var(--dur) var(--ease),
    border-color var(--dur) var(--ease) !important;
}

.login-btn:hover {
  border-color: var(--brand) !important;
  background: var(--brand) !important;
}
</style>
