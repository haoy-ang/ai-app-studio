/**
 * ⚠️ 本文件由 openapi 生成，但以下几处是**手工补充**的（本项目没有"重新生成"的流水线，
 * 重新生成时请把下面带 [手工] 标记的部分重新补上）：
 *  - AppAddRequest.appName / codeGenType        [手工]
 *  - AppVO.codeReady、AppVO.user 的类型          [手工]
 *  - AppUserVO                                   [手工]
 *  - listAppChatHistoryParams.lastId             [手工]
 *  - ChatHistoryQueryRequest.lastId              [手工]
 *  - UserProfileUpdateRequest                    [手工]
 *  - SnowflakeId 及所有 id 字段                   [手工]
 */
declare namespace API {
  /**
   * [手工] 雪花 id 在 JSON 里是**字符串**
   *
   * 后端 `JsonConfig` 给 Long 注册了 `ToStringSerializer`，所以 id 到前端时是字符串
   * `"469348007004553216"`。这是有意为之：雪花号远超 JS 安全整数上限
   * （2^53-1 ≈ 9.007e15），一旦被当成 number，`469348007004553216` 会变成
   * `469348007004553200`，表现就是「参数明明传了，后端说记录不存在」。
   *
   * ⇒ **id 一律按字符串传递**，禁止 `Number(id)` / `parseInt(id)` 这类转换。
   * 生成的 typings 原本写的是 number，那是不准确的，这里按实际情况改正。
   */
  type SnowflakeId = string

  type AppAddRequest = {
    initPrompt?: string
    /** [手工] 应用名称，不传则后端取 initPrompt 前 12 位 */
    appName?: string
    /** [手工] 代码生成类型：html / multi_file / vue_project，不传则走 AI 智能路由 */
    codeGenType?: string
  }

  type AppAdminUpdateRequest = {
    id?: API.SnowflakeId
    appName?: string
    cover?: string
    priority?: number
  }

  type AppDeployRequest = {
    appId?: API.SnowflakeId
  }

  type AppQueryRequest = {
    pageNum?: number
    pageSize?: number
    sortField?: string
    sortOrder?: string
    id?: number
    appName?: string
    cover?: string
    initPrompt?: string
    codeGenType?: string
    deployKey?: string
    priority?: number
    userId?: number
  }

  type AppUpdateRequest = {
    id?: API.SnowflakeId
    appName?: string
  }

  type AppVO = {
    id?: API.SnowflakeId
    appName?: string
    cover?: string
    initPrompt?: string
    codeGenType?: string
    deployKey?: string
    deployedTime?: string
    priority?: number
    userId?: API.SnowflakeId
    createTime?: string
    updateTime?: string
    /** [手工] 公开场景的脱敏用户信息（不含 userAccount） */
    user?: AppUserVO
    /** [手工] 代码是否已生成过，前端据此决定展示预览还是"尚未生成" */
    codeReady?: boolean
  }

  /** [手工] 应用作者信息：后端 AppUserVO，刻意不含 userAccount */
  type AppUserVO = {
    id?: API.SnowflakeId
    userName?: string
    userAvatar?: string
    userProfile?: string
    userRole?: string
  }

  type BaseResponseAppVO = {
    code?: number
    data?: AppVO
    message?: string
  }

  type BaseResponseBoolean = {
    code?: number
    data?: boolean
    message?: string
  }

  type BaseResponseLoginUserVO = {
    code?: number
    data?: LoginUserVO
    message?: string
  }

  type BaseResponseLong = {
    code?: number
    data?: number
    message?: string
  }

  type BaseResponsePageAppVO = {
    code?: number
    data?: PageAppVO
    message?: string
  }

  type BaseResponsePageChatHistory = {
    code?: number
    data?: PageChatHistory
    message?: string
  }

  type BaseResponsePageUserVO = {
    code?: number
    data?: PageUserVO
    message?: string
  }

  type BaseResponseString = {
    code?: number
    data?: string
    message?: string
  }

  type BaseResponseUser = {
    code?: number
    data?: User
    message?: string
  }

  type BaseResponseUserVO = {
    code?: number
    data?: UserVO
    message?: string
  }

  type ChatHistory = {
    id?: API.SnowflakeId
    message?: string
    messageType?: string
    appId?: API.SnowflakeId
    userId?: API.SnowflakeId
    createTime?: string
    updateTime?: string
    isDelete?: number
  }

  type ChatHistoryQueryRequest = {
    pageNum?: number
    pageSize?: number
    sortField?: string
    sortOrder?: string
    id?: number
    message?: string
    messageType?: string
    appId?: number
    userId?: number
    /** [手工] 游标：上一页最后一条记录的 id（优先） */
    lastId?: API.SnowflakeId
    lastCreateTime?: string
  }

  type chatToGenCodeParams = {
    appId: API.SnowflakeId
    message: string
  }

  type DeleteRequest = {
    id?: API.SnowflakeId
  }

  type downloadAppCodeParams = {
    appId: API.SnowflakeId
  }

  type getAppVOByIdByAdminParams = {
    id: API.SnowflakeId
  }

  type getAppVOByIdParams = {
    id: API.SnowflakeId
  }

  type getUserByIdParams = {
    id: API.SnowflakeId
  }

  type getUserVOByIdParams = {
    id: API.SnowflakeId
  }

  type listAppChatHistoryParams = {
    appId: API.SnowflakeId
    pageSize?: number
    /** [手工] 游标：上一页最后一条消息的 id（优先于 lastCreateTime） */
    lastId?: API.SnowflakeId
    /** [手工] 旧游标，仅为兼容保留 */
    lastCreateTime?: string
  }

  type LoginUserVO = {
    id?: API.SnowflakeId
    userAccount?: string
    userName?: string
    userAvatar?: string
    userProfile?: string
    userRole?: string
    createTime?: string
    updateTime?: string
  }

  type PageAppVO = {
    records?: AppVO[]
    pageNumber?: number
    pageSize?: number
    totalPage?: number
    totalRow?: number
    optimizeCountQuery?: boolean
  }

  type PageChatHistory = {
    records?: ChatHistory[]
    pageNumber?: number
    pageSize?: number
    totalPage?: number
    totalRow?: number
    optimizeCountQuery?: boolean
  }

  type PageUserVO = {
    records?: UserVO[]
    pageNumber?: number
    pageSize?: number
    totalPage?: number
    totalRow?: number
    optimizeCountQuery?: boolean
  }

  type ServerSentEventString = true

  type serveStaticResourceParams = {
    deployKey: string
  }

  type User = {
    id?: number
    userAccount?: string
    userPassword?: string
    userName?: string
    userAvatar?: string
    userProfile?: string
    userRole?: string
    editTime?: string
    createTime?: string
    updateTime?: string
    isDelete?: number
  }

  type UserAddRequest = {
    userName?: string
    userAccount?: string
    userAvatar?: string
    userProfile?: string
    userRole?: string
  }

  type UserLoginRequest = {
    userAccount?: string
    userPassword?: string
  }

  type UserQueryRequest = {
    pageNum?: number
    pageSize?: number
    sortField?: string
    sortOrder?: string
    id?: number
    userName?: string
    userAccount?: string
    userProfile?: string
    userRole?: string
  }

  type UserRegisterRequest = {
    userAccount?: string
    userPassword?: string
    checkPassword?: string
  }

  type UserUpdateRequest = {
    id?: API.SnowflakeId
    userName?: string
    userAvatar?: string
    userProfile?: string
    userRole?: string
  }

  type UserVO = {
    id?: API.SnowflakeId
    userAccount?: string
    userName?: string
    userAvatar?: string
    userProfile?: string
    userRole?: string
    createTime?: string
  }

  /**
   * [手工] 用户自助更新个人资料。
   * 注意这里没有 id / userRole —— 后端 DTO 里也没有，改的是谁由登录态决定。
   */
  type UserProfileUpdateRequest = {
    userName?: string
    userAvatar?: string
    userProfile?: string
  }
}
