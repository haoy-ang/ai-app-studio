import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { message, Modal } from 'ant-design-vue'
import { deleteApp, deployApp } from '@/api/appController'
import { API_BASE_URL, getDeployUrl } from '@/config/env'

interface UseAppActionsOptions {
  /**
   * 「会改变数据」的动作（部署 / 删除 / 版本回滚）完成后回调，
   * 让调用方自己决定怎么刷新（首页要同时刷精选，管理页只刷自己）。
   */
  onChanged?: () => void | Promise<void>
}

/**
 * 作品管理的动作集：重命名 / 查看作品 / 下载 / 部署 / 删除 / 版本历史
 * <p>
 * <b>为什么抽出来：</b>首页「我的作品」和 `/my/apps` 管理页用的是同一套动作，
 * 复制一遍就意味着以后改文案、改判定（比如 `code === 0 && data`）要改两处，
 * 迟早会漏一个 —— 上一轮「删除提示成功但其实没删」就是这么来的。
 * <p>
 * 抽屉状态（open / app）也放在这里，页面只需要在模板里挂一次
 * `<AppVersionDrawer v-model:open="versionOpen" :app="versionApp" @changed="refresh" />`。
 *
 * @param options 刷新回调
 */
export function useAppActions(options: UseAppActionsOptions = {}) {
  const router = useRouter()

  /** 版本历史抽屉 */
  const versionOpen = ref(false)
  const versionApp = ref<API.AppVO | null>(null)

  const refresh = async () => {
    await options.onChanged?.()
  }

  /**
   * 重命名：复用已有的编辑页（它只改应用名称，不为一个字段再写一个弹窗）
   */
  const editApp = (app: API.AppVO) => {
    if (app.id) {
      router.push(`/app/edit/${app.id}`)
    }
  }

  /**
   * 打开线上页面。没部署时什么都不做 —— 入口本身应该已经按 deployKey 禁用
   */
  const viewWork = (app: API.AppVO) => {
    if (app.deployKey) {
      window.open(getDeployUrl(app.deployKey), '_blank')
    }
  }

  /**
   * 下载源码 zip
   * <p>
   * 走浏览器原生下载，不走 request 封装：zip 是二进制，
   * umi-request 会当成 JSON/text 解析，下载下来是个打不开的坏包。
   */
  const downloadApp = (app: API.AppVO) => {
    if (!app.id) {
      return
    }
    window.open(`${API_BASE_URL}/app/download/${app.id}`, '_blank')
  }

  /**
   * 重新部署
   * <p>
   * Vue 工程要 npm install + build，可能几分钟，所以给一个不会自动消失的 loading，
   * 而不是默认 3 秒就消失的那种（用户会以为卡住了又点一次）。
   */
  const deploy = async (app: API.AppVO) => {
    if (!app.id) {
      return
    }
    const hide = message.loading('部署中，Vue 工程需要构建，可能要几分钟…', 0)
    try {
      // appId 是雪花 id，直接透传字符串：Number() 会丢精度，后端会报「应用不存在」
      const res = await deployApp({ appId: app.id })
      if (res.data.code === 0) {
        message.success('部署成功')
        // 部署成功会写入 deployKey，「查看作品」要跟着出现
        await refresh()
      } else {
        message.error('部署失败：' + (res.data.message || '未知错误'))
      }
    } catch (error) {
      console.error('部署失败：', error)
      message.error('部署失败')
    } finally {
      hide()
    }
  }

  /**
   * 删除应用：不可逆，必须二次确认并把「会连坐什么」说清楚
   */
  const remove = (app: API.AppVO) => {
    if (!app.id) {
      return
    }
    Modal.confirm({
      title: `删除「${app.appName || '未命名应用'}」？`,
      content: '对话记录、版本快照、已部署的线上页面都会一起删除，且无法恢复。',
      okText: '删除',
      okType: 'danger',
      cancelText: '取消',
      onOk: async () => {
        try {
          // id 原样透传（雪花 id，不能 Number）
          const res = await deleteApp({ id: app.id })
          // code === 0 只代表「请求处理完了」，data 才是「真的删掉了没有」
          if (res.data.code === 0 && res.data.data) {
            message.success('已删除')
            await refresh()
          } else {
            message.error('删除失败：' + (res.data.message || '后端未删除任何记录'))
          }
        } catch (error) {
          console.error('删除应用失败：', error)
          message.error('删除失败')
        }
      },
    })
  }

  const openVersions = (app: API.AppVO) => {
    versionApp.value = app
    versionOpen.value = true
  }

  return {
    versionOpen,
    versionApp,
    refresh,
    editApp,
    viewWork,
    downloadApp,
    deploy,
    remove,
    openVersions,
  }
}
