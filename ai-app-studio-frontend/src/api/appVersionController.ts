/**
 * 应用版本接口
 * <p>
 * 本文件是<b>手写</b>的，不走 `npm run openapi2ts` 生成 —— 版本管理是后加的功能，
 * 重新全量生成会顺带改动一堆无关的生成文件，看不出真实改动。等下次统一重新生成时，
 * 这个文件可以删掉、改用生成的 `appVersionController.ts`。
 */
import request from '@/request'

/** 与后端 AppVersionVO 对应 */
export interface AppVersionVO {
  id: number
  appId: number
  versionNo: number
  codeGenType: string
  fileCount: number
  sizeBytes: number
  /** CHAT / WORKFLOW / ROLLBACK */
  source: string
  prompt?: string
  /** 是否为当前生效版本 */
  current: boolean
  createTime?: string
}

interface BaseResponse<T> {
  code: number
  message?: string
  data?: T
}

/** 查询某个应用的版本列表（最新在前） */
export async function listVersions(params: { appId: number }, options?: { [key: string]: any }) {
  return request<BaseResponse<AppVersionVO[]>>('/app/version/list', {
    method: 'GET',
    params,
    ...(options || {}),
  })
}

/** 回滚到指定版本（只恢复生成目录，不会自动重新部署） */
export async function rollbackVersion(body: { versionId: number }, options?: { [key: string]: any }) {
  return request<BaseResponse<AppVersionVO>>('/app/version/rollback', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}

/** 删除一个非当前版本 */
export async function deleteVersion(body: { versionId: number }, options?: { [key: string]: any }) {
  return request<BaseResponse<boolean>>('/app/version/delete', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}
