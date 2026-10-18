package com.haoy.aiapp.controller.app;

import cn.hutool.core.util.StrUtil;
import com.haoy.aiapp.annotation.AuthCheck;
import com.haoy.aiapp.common.BaseResponse;
import com.haoy.aiapp.common.DeleteRequest;
import com.haoy.aiapp.common.ResultUtils;
import com.haoy.aiapp.constant.AppConstant;
import com.haoy.aiapp.constant.UserConstant;
import com.haoy.aiapp.exception.BusinessException;
import com.haoy.aiapp.exception.ErrorCode;
import com.haoy.aiapp.exception.ThrowUtils;
import com.haoy.aiapp.model.dto.app.AppAddRequest;
import com.haoy.aiapp.model.dto.app.AppAdminUpdateRequest;
import com.haoy.aiapp.model.dto.app.AppDeployRequest;
import com.haoy.aiapp.model.dto.app.AppQueryRequest;
import com.haoy.aiapp.model.dto.app.AppUpdateRequest;
import com.haoy.aiapp.model.entity.App;
import com.haoy.aiapp.model.entity.User;
import com.haoy.aiapp.model.vo.app.AppVO;
import com.haoy.aiapp.ratelimit.annotation.RateLimit;
import com.haoy.aiapp.ratelimit.enums.RateLimitType;
import com.haoy.aiapp.service.app.AppService;
import com.haoy.aiapp.service.projectdownload.ProjectDownloadService;
import com.haoy.aiapp.service.user.UserService;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.BeanUtils;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import cn.hutool.json.JSONUtil;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Mono;

import java.io.File;
import java.util.Map;

import java.time.LocalDateTime;

/**
 * 应用 控制层。
 *
 * @author <a>程序员haoy</a>
 */
@RestController
@RequestMapping("/app")
public class AppController {

    @Resource
    private AppService appService;

    @Resource
    private UserService userService;

    @Resource
    private ProjectDownloadService projectDownloadService;

    /**
     * 【用户】创建应用（须填写 initPrompt）
     *
     * @param appAddRequest 创建应用请求
     * @param request       请求
     * @return 新应用 id
     */
    @PostMapping("/add")
    public BaseResponse<Long> addApp(@RequestBody AppAddRequest appAddRequest, HttpServletRequest request) {
        ThrowUtils.throwIf(appAddRequest == null, ErrorCode.PARAMS_ERROR);
        // 获取当前登录用户
        User loginUser = userService.getLoginUser(request);
        // 创建应用（代码生成类型由 AI 智能路由在 Service 层自动选择）
        Long appId = appService.createApp(appAddRequest, loginUser);
        return ResultUtils.success(appId);
    }

    /**
     * 【用户】根据 id 修改自己的应用（目前只支持修改应用名称）
     *
     * @param appUpdateRequest 更新请求
     * @param request          请求
     * @return 更新结果
     */
    @PostMapping("/update")
    public BaseResponse<Boolean> updateApp(@RequestBody AppUpdateRequest appUpdateRequest, HttpServletRequest request) {
        ThrowUtils.throwIf(appUpdateRequest == null || appUpdateRequest.getId() == null, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        long id = appUpdateRequest.getId();
        // 判断是否存在
        App oldApp = appService.getById(id);
        ThrowUtils.throwIf(oldApp == null, ErrorCode.NOT_FOUND_ERROR);
        // 仅本人可更新
        ThrowUtils.throwIf(!loginUser.getId().equals(oldApp.getUserId()), ErrorCode.NO_AUTH_ERROR);
        App app = new App();
        app.setId(id);
        app.setAppName(appUpdateRequest.getAppName());
        // 设置编辑时间（区分用户主动编辑和系统自动更新）
        app.setEditTime(LocalDateTime.now());
        boolean result = appService.updateById(app);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
        return ResultUtils.success(true);
    }

    /**
     * 【用户】根据 id 删除自己的应用（管理员也可通过该接口删除任意应用）
     * <p>
     * 删应用会改变精选集合（用户自己的作品可能正在精选位上），所以必须清掉精选列表缓存。
     * 不清的话最长 5 分钟内首页还会展示一个已经删掉的应用 —— 点进去是 404。
     *
     * @param deleteRequest 删除请求
     * @param request       请求
     * @return 删除结果
     */
    @PostMapping("/delete")
    @CacheEvict(value = "good_app_page", allEntries = true)
    public BaseResponse<Boolean> deleteApp(@RequestBody DeleteRequest deleteRequest, HttpServletRequest request) {
        ThrowUtils.throwIf(deleteRequest == null || deleteRequest.getId() == null || deleteRequest.getId() <= 0,
                ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        long id = deleteRequest.getId();
        // 判断是否存在
        App oldApp = appService.getById(id);
        ThrowUtils.throwIf(oldApp == null, ErrorCode.NOT_FOUND_ERROR);
        // 仅本人或管理员可删除
        if (!loginUser.getId().equals(oldApp.getUserId()) && !UserConstant.ADMIN_ROLE.equals(loginUser.getUserRole())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        }
        boolean result = appService.removeById(id);
        return ResultUtils.success(result);
    }

    /**
     * 【用户】根据 id 查看应用详情
     *
     * @param id 应用 id
     * @return 应用详情（包含创建用户信息）
     */
    @GetMapping("/get/vo")
    public BaseResponse<AppVO> getAppVOById(long id) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR);
        // 查询数据库
        App app = appService.getById(id);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR);
        // 获取封装类（包含创建用户信息）
        return ResultUtils.success(appService.getAppVO(app));
    }

    /**
     * 【用户】分页查询自己的应用列表（支持根据名称查询，每页最多 20 个）
     *
     * @param appQueryRequest 查询请求
     * @param request         请求
     * @return 分页应用列表
     */
    @PostMapping("/my/list/page/vo")
    public BaseResponse<Page<AppVO>> listMyAppVOByPage(@RequestBody AppQueryRequest appQueryRequest,
                                                       HttpServletRequest request) {
        ThrowUtils.throwIf(appQueryRequest == null, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        // 限制每页最多 20 个
        long pageSize = appQueryRequest.getPageSize();
        ThrowUtils.throwIf(pageSize > 20, ErrorCode.PARAMS_ERROR, "每页最多查询 20 个应用");
        long pageNum = appQueryRequest.getPageNum();
        // 只查询当前用户的应用
        appQueryRequest.setUserId(loginUser.getId());
        QueryWrapper queryWrapper = appService.getQueryWrapper(appQueryRequest);
        Page<App> appPage = appService.page(Page.of(pageNum, pageSize), queryWrapper);
        // 数据封装
        Page<AppVO> appVOPage = new Page<>(pageNum, pageSize, appPage.getTotalRow());
        appVOPage.setRecords(appService.getAppVOList(appPage.getRecords()));
        return ResultUtils.success(appVOPage);
    }

    /**
     * 【用户】分页查询精选的应用列表（支持根据名称查询，每页最多 20 个）
     * <p>
     * 加缓存的理由：这是全站访问频率最高的接口，而精选应用由管理员手动设置、更新频率低，
     * 属于典型的「高频读、低频写」场景。走旁路缓存，靠 TTL（5 分钟）自动过期，不主动删缓存。
     * <p>
     * key 交给 {@code CacheKeyUtils.goodAppPageKey} 生成，注意它刻意排除了 priority，
     * condition 只缓存前 10 页 —— 绝大多数用户压根不会翻到第 10 页之后，缓存它们纯属浪费内存。
     *
     * @param appQueryRequest 查询请求
     * @return 分页精选应用列表
     */
    @PostMapping("/good/list/page/vo")
    @Cacheable(
            value = "good_app_page",
            key = "T(com.haoy.aiapp.utils.CacheKeyUtils).goodAppPageKey(#appQueryRequest)",
            condition = "#appQueryRequest.pageNum <= 10"
    )
    public BaseResponse<Page<AppVO>> listGoodAppVOByPage(@RequestBody AppQueryRequest appQueryRequest) {
        ThrowUtils.throwIf(appQueryRequest == null, ErrorCode.PARAMS_ERROR);
        // 限制每页最多 20 个
        long pageSize = appQueryRequest.getPageSize();
        ThrowUtils.throwIf(pageSize > 20, ErrorCode.PARAMS_ERROR, "每页最多查询 20 个应用");
        long pageNum = appQueryRequest.getPageNum();
        // 只查询精选的应用，防止用户传入其他优先级
        appQueryRequest.setPriority(AppConstant.GOOD_APP_PRIORITY);
        QueryWrapper queryWrapper = appService.getQueryWrapper(appQueryRequest);
        // 分页查询
        Page<App> appPage = appService.page(Page.of(pageNum, pageSize), queryWrapper);
        // 数据封装
        Page<AppVO> appVOPage = new Page<>(pageNum, pageSize, appPage.getTotalRow());
        appVOPage.setRecords(appService.getAppVOList(appPage.getRecords()));
        return ResultUtils.success(appVOPage);
    }

    /**
     * 【管理员】根据 id 删除任意应用
     *
     * @param deleteRequest 删除请求
     * @return 删除结果
     */
    @PostMapping("/admin/delete")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    @CacheEvict(value = "good_app_page", allEntries = true)
    public BaseResponse<Boolean> deleteAppByAdmin(@RequestBody DeleteRequest deleteRequest) {
        ThrowUtils.throwIf(deleteRequest == null || deleteRequest.getId() == null || deleteRequest.getId() <= 0,
                ErrorCode.PARAMS_ERROR);
        long id = deleteRequest.getId();
        // 判断是否存在
        App oldApp = appService.getById(id);
        ThrowUtils.throwIf(oldApp == null, ErrorCode.NOT_FOUND_ERROR);
        boolean result = appService.removeById(id);
        return ResultUtils.success(result);
    }

    /**
     * 【管理员】根据 id 更新任意应用（支持更新应用名称、应用封面、优先级）
     * <p>
     * 两条约束写在这里，理由都是「精选位是给用户点开体验的」：
     * <ol>
     *   <li><b>未部署不能设为精选</b>：卡片上的「查看作品」是 {@code v-if="deployKey"}，
     *       没部署就没有按钮，精选位等于占着位置却点不开；</li>
     *   <li><b>改完必须清缓存</b>：精选列表挂了 5 分钟 TTL 的旁路缓存，
     *       不清的话管理员点完「精选」刷新首页看不到，会以为没生效反复点。</li>
     * </ol>
     *
     * @param appAdminUpdateRequest 更新请求
     * @return 更新结果
     */
    @PostMapping("/admin/update")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    @CacheEvict(value = "good_app_page", allEntries = true)
    public BaseResponse<Boolean> updateAppByAdmin(@RequestBody AppAdminUpdateRequest appAdminUpdateRequest) {
        ThrowUtils.throwIf(appAdminUpdateRequest == null || appAdminUpdateRequest.getId() == null,
                ErrorCode.PARAMS_ERROR);
        long id = appAdminUpdateRequest.getId();
        // 判断是否存在
        App oldApp = appService.getById(id);
        ThrowUtils.throwIf(oldApp == null, ErrorCode.NOT_FOUND_ERROR);
        // 设为精选前校验：必须是已部署的应用（deployKey 在部署成功后才写入）
        if (AppConstant.GOOD_APP_PRIORITY.equals(appAdminUpdateRequest.getPriority())
                && StrUtil.isBlank(oldApp.getDeployKey())) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "该应用尚未部署，不能设为精选");
        }
        App app = new App();
        BeanUtils.copyProperties(appAdminUpdateRequest, app);
        // 设置编辑时间
        app.setEditTime(LocalDateTime.now());
        boolean result = appService.updateById(app);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
        return ResultUtils.success(true);
    }

    /**
     * 【管理员】分页查询应用列表（支持根据除时间外的任何字段查询，每页数量不限）
     *
     * @param appQueryRequest 查询请求
     * @return 分页应用列表
     */
    @PostMapping("/admin/list/page/vo")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Page<AppVO>> listAppVOByPageByAdmin(@RequestBody AppQueryRequest appQueryRequest) {
        ThrowUtils.throwIf(appQueryRequest == null, ErrorCode.PARAMS_ERROR);
        long pageNum = appQueryRequest.getPageNum();
        long pageSize = appQueryRequest.getPageSize();
        QueryWrapper queryWrapper = appService.getQueryWrapper(appQueryRequest);
        Page<App> appPage = appService.page(Page.of(pageNum, pageSize), queryWrapper);
        // 数据封装
        Page<AppVO> appVOPage = new Page<>(pageNum, pageSize, appPage.getTotalRow());
        appVOPage.setRecords(appService.getAppVOList(appPage.getRecords()));
        return ResultUtils.success(appVOPage);
    }

    /**
     * 【管理员】根据 id 查看应用详情
     *
     * @param id 应用 id
     * @return 应用详情（包含创建用户信息）
     */
    @GetMapping("/admin/get/vo")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<AppVO> getAppVOByIdByAdmin(long id) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR);
        // 查询数据库
        App app = appService.getById(id);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR);
        // 获取封装类（包含创建用户信息）
        return ResultUtils.success(appService.getAppVO(app));
    }
    /**
     * 【用户】应用聊天生成代码（流式 SSE）
     * <p>
     * 挂限流注解保护：AI 对话是整站最贵的接口（每次都要调大模型、烧 token），
     * 也是唯一会被用户反复点击的接口。按用户维度限制 60 秒 5 次，防止误刷和恶意刷。
     * <p>
     * ⚠️ 限流异常由切面在方法体执行<b>之前</b>抛出，因此它不会走下面的 Flux 返回，
     * 而是抛到 {@code GlobalExceptionHandler}。那边有 SSE 分支负责把它转成
     * {@code event: business-error} 事件回给前端（见 {@code handleSseError}），
     * 否则 EventSource 只会显示一个无意义的「连接错误」。
     *
     * @param appId   应用 ID
     * @param message 用户消息
     * @param request 请求对象
     * @return 生成结果流（标准 SSE 格式，结束时推送 event="done"）
     */
    @GetMapping(value = "/chat/gen/code", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @RateLimit(limitType = RateLimitType.USER, rate = 5, rateInterval = 60,
            message = "AI 对话请求过于频繁，请稍后再试")
    public Flux<ServerSentEvent<String>> chatToGenCode(@RequestParam Long appId,
                                                       @RequestParam String message,
                                                       HttpServletRequest request) {
        // 参数校验
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用ID无效");
        ThrowUtils.throwIf(StrUtil.isBlank(message), ErrorCode.PARAMS_ERROR, "用户消息不能为空");
        // 获取当前登录用户
        User loginUser = userService.getLoginUser(request);
        // 调用服务生成代码（流式）
        Flux<String> contentFlux = appService.chatToGenCode(appId, message, loginUser);
        // 转换为标准 ServerSentEvent 格式，data 包裹 JSON
        return contentFlux.map(chunk -> {
            Map<String, String> wrapper = Map.of("d", chunk);
            String jsonData = JSONUtil.toJsonStr(wrapper);
            return ServerSentEvent.<String>builder()
                    .data(jsonData)
                    .build();
        }).concatWith(Mono.just(
                // 推送结束事件，告知前端流已完成
                ServerSentEvent.<String>builder()
                        .event("done")
                        .data("")
                        .build()
        ));
    }

    /**
     * 【用户】应用部署
     *
     * @param appDeployRequest 部署请求
     * @param request          请求
     * @return 部署后的访问 URL
     */
    @PostMapping("/deploy")
    public BaseResponse<String> deployApp(@RequestBody AppDeployRequest appDeployRequest, HttpServletRequest request) {
        ThrowUtils.throwIf(appDeployRequest == null, ErrorCode.PARAMS_ERROR);
        Long appId = appDeployRequest.getAppId();
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用 ID 不能为空");
        // 获取当前登录用户
        User loginUser = userService.getLoginUser(request);
        // 调用服务部署应用
        String deployUrl = appService.deployApp(appId, loginUser);
        return ResultUtils.success(deployUrl);
    }

    /**
     * 【用户】下载应用代码
     *
     * @param appId    应用ID
     * @param request  请求
     * @param response 响应
     */
    @GetMapping("/download/{appId}")
    public void downloadAppCode(@PathVariable Long appId,
                                HttpServletRequest request,
                                HttpServletResponse response) {
        // 1. 基础校验
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用ID无效");
        // 2. 查询应用信息
        App app = appService.getById(appId);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        // 3. 权限校验:只有应用创建者可以下载代码
        User loginUser = userService.getLoginUser(request);
        if (!app.getUserId().equals(loginUser.getId())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权限下载该应用代码");
        }
        // 4. 构建应用代码目录路径(生成目录,非部署目录)
        String codeGenType = app.getCodeGenType();
        String sourceDirName = codeGenType + "_" + appId;
        String sourceDirPath = AppConstant.CODE_OUTPUT_ROOT_DIR + File.separator + sourceDirName;
        // 5. 检查代码目录是否存在
        File sourceDir = new File(sourceDirPath);
        ThrowUtils.throwIf(!sourceDir.exists() || !sourceDir.isDirectory(),
                ErrorCode.NOT_FOUND_ERROR, "应用代码不存在,请先生成代码");
        // 6. 生成下载文件名
        String downloadFileName = String.valueOf(appId);
        // 7. 调用通用下载服务
        projectDownloadService.downloadProjectAsZip(sourceDirPath, downloadFileName, response);
    }
}
