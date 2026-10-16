import { useLoginUserStore } from '@/stores/loginUser'
import { message } from 'ant-design-vue'
import router from '@/router'

// 是否为首次获取登录用户
let firstFetchLoginUser = true

/**
 * 全局权限校验
 */
router.beforeEach(async (to, from, next) => {
  const loginUserStore = useLoginUserStore()
  let loginUser = loginUserStore.loginUser
  // 确保页面刷新，首次加载时，能够等后端返回用户信息后再校验权限
  if (firstFetchLoginUser) {
    // 兜底：后端未启动 / 网络异常时请求会抛错，若让异常冒泡出去，下面的 next() 不会执行，
    // 导航会被永久挂起，页面上表现为「一直转圈、点什么都没反应」。这里降级为未登录即可。
    try {
      await loginUserStore.fetchLoginUser()
    } catch (e) {
      console.error('[access] 获取登录用户信息失败，按未登录处理：', e)
    }
    loginUser = loginUserStore.loginUser
    firstFetchLoginUser = false
  }
  const toUrl = to.fullPath
  if (toUrl.startsWith('/admin')) {
    if (!loginUser || loginUser.userRole !== 'admin') {
      message.error('没有权限')
      next(`/user/login?redirect=${to.fullPath}`)
      return
    }
  }
  // 个人资料页需要登录态（页面本身会调接口，不拦的话会先渲染再弹错误）
  if (toUrl.startsWith('/user/profile')) {
    if (!loginUser || !loginUser.id) {
      message.warning('请先登录')
      next(`/user/login?redirect=${to.fullPath}`)
      return
    }
  }
  next()
})
