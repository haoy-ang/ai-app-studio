package com.haoy.aiapp.service.app.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.haoy.aiapp.config.DeployProperties;
import com.haoy.aiapp.constant.AppConstant;
import com.haoy.aiapp.ai.AiCodeGenTypeRoutingService;
import com.haoy.aiapp.ai.AiCodeGenTypeRoutingServiceFactory;
import com.haoy.aiapp.core.AiCodeGeneratorFacade;
import com.haoy.aiapp.core.builder.VueProjectBuilder;
import com.haoy.aiapp.core.handler.StreamHandlerExecutor;
import com.haoy.aiapp.exception.ThrowUtils;
import com.haoy.aiapp.model.dto.app.AppAddRequest;
import com.haoy.aiapp.model.enums.ChatHistoryMessageTypeEnum;
import com.haoy.aiapp.model.enums.CodeGenTypeEnum;
import com.haoy.aiapp.service.screen.ScreenshotService;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import com.haoy.aiapp.exception.BusinessException;
import com.haoy.aiapp.exception.ErrorCode;
import com.haoy.aiapp.manager.CosManager;
import com.haoy.aiapp.mapper.app.AppMapper;
import com.haoy.aiapp.model.dto.app.AppQueryRequest;
import com.haoy.aiapp.model.entity.App;
import com.haoy.aiapp.model.entity.User;
import com.haoy.aiapp.model.vo.app.AppVO;
import com.haoy.aiapp.model.vo.user.AppUserVO;
import com.haoy.aiapp.service.app.AppService;
import com.haoy.aiapp.service.appversion.AppVersionService;
import com.haoy.aiapp.service.chathistory.ChatHistoryService;
import com.haoy.aiapp.service.user.UserService;
import com.haoy.aiapp.utils.SortFieldValidator;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.io.File;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 应用 服务层实现。
 *
 * @author <a>程序员haoy</a>
 */
@Slf4j
@Service
public class AppServiceImpl extends ServiceImpl<AppMapper, App> implements AppService {

    /**
     * 允许排序的字段白名单（防止前端传入任意 SQL 片段造成注入）
     */
    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of(
            "id", "appName", "codeGenType", "priority", "userId", "createTime", "updateTime", "deployedTime");

    @Resource
    private ScreenshotService screenshotService;

    @Resource
    private DeployProperties deployProperties;

    @Resource
    private CosManager cosManager;

    @Resource
    private UserService userService;

    @Resource
    private AiCodeGeneratorFacade aiCodeGeneratorFacade;

    @Resource
    private ChatHistoryService chatHistoryService;

    @Resource
    private StreamHandlerExecutor streamHandlerExecutor;

    @Resource
    private VueProjectBuilder vueProjectBuilder;

    @Resource
    private AiCodeGenTypeRoutingServiceFactory aiCodeGenTypeRoutingServiceFactory;

    @Resource
    private AppVersionService appVersionService;

    /**
     * 创建应用
     * <p>
     * 代码生成类型：用户显式指定就用用户选的，没指定才交给 AI 智能路由判断。
     * 用户指定这条路径不只是"给个选择权" —— 它还省掉了创建时那次同步的大模型调用
     * （少等几秒、少烧一次 token），而且避免了"AI 把简单页面判成 Vue 工程、
     * 用户被迫等一分钟 npm 构建"这种没法补救的情况。
     *
     * @param appAddRequest 创建应用请求
     * @param loginUser     登录用户
     * @return 新应用 id
     */
    @Override
    public Long createApp(AppAddRequest appAddRequest, User loginUser) {
        // 参数校验
        ThrowUtils.throwIf(appAddRequest == null, ErrorCode.PARAMS_ERROR);
        String initPrompt = appAddRequest.getInitPrompt();
        ThrowUtils.throwIf(StrUtil.isBlank(initPrompt), ErrorCode.PARAMS_ERROR, "初始化 prompt 不能为空");
        // 构造入库对象
        App app = new App();
        BeanUtils.copyProperties(appAddRequest, app);
        app.setUserId(loginUser.getId());
        // 应用名称：用户填了就用用户的，没填才退化成 initPrompt 前 12 位
        String appName = StrUtil.trim(appAddRequest.getAppName());
        app.setAppName(StrUtil.isNotBlank(appName)
                ? appName
                : initPrompt.substring(0, Math.min(initPrompt.length(), 12)));
        // 解析代码生成类型：用户指定优先
        CodeGenTypeEnum selectedCodeGenType;
        String specifiedCodeGenType = StrUtil.trim(appAddRequest.getCodeGenType());
        if (StrUtil.isNotBlank(specifiedCodeGenType)) {
            selectedCodeGenType = CodeGenTypeEnum.getEnumByValue(specifiedCodeGenType);
            // ⚠️ 必须校验：前端传的字符串是不可信输入，直接落库会在对话阶段炸出
            // "不支持的代码生成类型"（SYSTEM_ERROR），而且脏数据已经进库了，排查起来很绕
            ThrowUtils.throwIf(selectedCodeGenType == null, ErrorCode.PARAMS_ERROR,
                    "不支持的代码生成类型：" + specifiedCodeGenType);
        } else {
            // 使用 AI 智能选择代码生成类型
            // 每次创建都新建一个路由服务（内含全新模型实例），这样多人同时创建应用时不会互相排队
            AiCodeGenTypeRoutingService routingService =
                    aiCodeGenTypeRoutingServiceFactory.createAiCodeGenTypeRoutingService();
            selectedCodeGenType = routingService.routeCodeGenType(initPrompt);
            if (selectedCodeGenType == null) {
                // 路由兜底：AI 偶发返回了无法映射的值时，不让「创建应用」这个核心动作直接失败
                log.warn("AI 智能路由未返回有效类型，降级为多文件模式，initPrompt: {}", initPrompt);
                selectedCodeGenType = CodeGenTypeEnum.MULTI_FILE;
            }
        }
        app.setCodeGenType(selectedCodeGenType.getValue());
        // 默认优先级（0 表示非精选）
        app.setPriority(AppConstant.DEFAULT_APP_PRIORITY);
        // 插入数据库
        boolean result = this.save(app);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
        log.info("应用创建成功，ID: {}，生成类型: {}", app.getId(), selectedCodeGenType.getValue());
        return app.getId();
    }

    /**
     * 获取单个应用视图对象（包含创建用户信息）
     *
     * @param app 应用实体
     * @return 应用视图对象
     */
    @Override
    public AppVO getAppVO(App app) {
        if (app == null) {
            return null;
        }
        AppVO appVO = new AppVO();
        BeanUtils.copyProperties(app, appVO);
        appVO.setCodeReady(isCodeReady(app));
        // 关联查询创建用户信息
        Long userId = app.getUserId();
        if (userId != null) {
            User user = userService.getById(userId);
            // ⚠️ 这里用 getAppUserVO 而不是 getUserVO：AppVO 会从公开接口返回，
            // 带 userAccount 等于把作者登录账号公开（见 AppUserVO 的注释）
            appVO.setUser(userService.getAppUserVO(user));
        }
        return appVO;
    }

    /**
     * 获取应用列表脱敏视图对象
     * 批量查询创建用户信息，避免 N+1 查询
     *
     * @param appList 应用实体列表
     * @return 应用视图对象列表
     */
    @Override
    public List<AppVO> getAppVOList(List<App> appList) {
        if (CollUtil.isEmpty(appList)) {
            return new ArrayList<>();
        }
        // 1. 收集所有 userId
        Set<Long> userIds = appList.stream()
                .map(App::getUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        // 2. 一次性批量查询用户信息，构建 userId => AppUserVO 的映射
        Map<Long, AppUserVO> appUserVOMap = CollUtil.isEmpty(userIds)
                ? new HashMap<>()
                : userService.listByIds(userIds).stream()
                .collect(Collectors.toMap(User::getId, userService::getAppUserVO));
        // 3. 组装 AppVO，直接从 Map 中取用户信息
        return appList.stream().map(app -> {
            AppVO appVO = new AppVO();
            BeanUtils.copyProperties(app, appVO);
            appVO.setCodeReady(isCodeReady(app));
            appVO.setUser(appUserVOMap.get(app.getUserId()));
            return appVO;
        }).collect(Collectors.toList());
    }

    /**
     * 判断应用的代码目录是否真的已经生成过
     * <p>
     * 目录命名规则与 {@code CodeFileSaverTemplate} 强绑定：{@code {codeGenType}_{appId}}。
     * 抽这个方法是为了让「写代码的」和「判断有没有代码的」用同一条规则 ——
     * 任何一处写错一个字符，前端就会永远看到「还没生成」。
     *
     * @param app 应用实体
     * @return 代码目录是否存在
     */
    private boolean isCodeReady(App app) {
        if (app == null || app.getId() == null || StrUtil.isBlank(app.getCodeGenType())) {
            return false;
        }
        File codeDir = new File(AppConstant.CODE_OUTPUT_ROOT_DIR + File.separator
                + app.getCodeGenType() + "_" + app.getId());
        return codeDir.exists() && codeDir.isDirectory();
    }

    /**
     * 根据查询请求构造 QueryWrapper
     *
     * @param appQueryRequest 查询请求
     * @return 查询包装器
     */
    @Override
    public QueryWrapper getQueryWrapper(AppQueryRequest appQueryRequest) {
        if (appQueryRequest == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "请求参数为空");
        }
        Long id = appQueryRequest.getId();
        String appName = appQueryRequest.getAppName();
        String cover = appQueryRequest.getCover();
        String initPrompt = appQueryRequest.getInitPrompt();
        String codeGenType = appQueryRequest.getCodeGenType();
        String deployKey = appQueryRequest.getDeployKey();
        Integer priority = appQueryRequest.getPriority();
        Long userId = appQueryRequest.getUserId();
        String sortField = appQueryRequest.getSortField();
        String sortOrder = appQueryRequest.getSortOrder();
        QueryWrapper queryWrapper = QueryWrapper.create()
                .eq("id", id)
                .like("appName", appName)
                .like("cover", cover)
                .like("initPrompt", initPrompt)
                .eq("codeGenType", codeGenType)
                .eq("deployKey", deployKey)
                .eq("priority", priority)
                .eq("userId", userId);
        // 排序字段必须命中白名单，否则忽略（防止前端传入任意 SQL 片段造成注入）
        String safeSortField = SortFieldValidator.validate(sortField, ALLOWED_SORT_FIELDS);
        if (safeSortField != null) {
            queryWrapper.orderBy(safeSortField, "ascend".equals(sortOrder));
        }
        return queryWrapper;
    }
    @Override
    public Flux<String> chatToGenCode(Long appId, String message, User loginUser) {
        // 1. 参数校验
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用 ID不能为空");
        ThrowUtils.throwIf(StrUtil.isBlank(message), ErrorCode.PARAMS_ERROR, "用户消息不能为空");
        // 2. 查询应用信息
        App app = this.getById(appId);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        // 3. 验证用户是否有权限访问该应用,仅本人可以生成代码
        if (!app.getUserId().equals(loginUser.getId())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权限访问该应用");
        }
        // 4. 获取应用的代码生成类型
        String codeGenTypeStr = app.getCodeGenType();
        CodeGenTypeEnum codeGenTypeEnum = CodeGenTypeEnum.getEnumByValue(codeGenTypeStr);
        if (codeGenTypeEnum == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "不支持的代码生成类型");
        }
        // 5. 通过校验后，先把用户消息落库，保证对话历史完整
        chatHistoryService.addChatMessage(appId, message, ChatHistoryMessageTypeEnum.USER.getValue(), loginUser.getId());
        // 6. 调用 AI 生成代码（流式）
        Flux<String> codeStream = aiCodeGeneratorFacade.generateAndSaveCodeStream(message, codeGenTypeEnum, appId);
        // 7. 交给流处理器执行器：按生成类型选处理器，边转发给前端、边把 AI 回复落库（格式与前端所见一致）
        Flux<String> resultFlux = streamHandlerExecutor.doExecute(
                codeStream, chatHistoryService, appId, loginUser, codeGenTypeEnum);
        // 8. 最后一道兜底：把"建流阶段"的失败也变成用户看得见的一行字。
        // 各 handler 内部的 doOnError 已经负责落库（它在内层，会先执行），这里只负责转发文本，
        // 所以不会出现"同一个错误落两条 AI 消息"的情况。
        // 为什么必须有这一层：doExecute 之前/之中的异常（比如取模型实例失败、限流后重试）根本到不了
        // handler 的 doOnError，不兜住的话前端 EventSource 只能看到一个笼统的「连接错误」。
        return resultFlux.onErrorResume(error -> {
            log.error("生成代码失败，appId: {}, error: {}", appId, error.getMessage(), error);
            return Flux.just("\n\n❌ 生成失败：" + error.getMessage() + "\n\n");
        });
    }

    /**
     * 重写删除方法，删除应用时关联删除该应用下的所有对话历史
     *
     * @param id 应用 id
     * @return 是否删除成功
     */
    @Override
    public boolean removeById(Serializable id) {
        if (id == null) {
            return false;
        }
        Long appId = Long.valueOf(id.toString());
        if (appId <= 0) {
            return false;
        }
        // 先删关联的对话历史：这里做容错设计，即使删除对话历史失败也不阻断应用删除这个核心操作
        try {
            chatHistoryService.deleteByAppId(appId);
        } catch (Exception e) {
            log.error("删除应用关联对话历史失败，appId: {}, error: {}", appId, e.getMessage());
        }
        // 再清理对象存储上的封面图，避免 COS 里堆积孤儿文件；同样不阻断主流程
        cleanupAppCover(appId);
        // 最后删除应用本身
        boolean removed = super.removeById(id);
        // 应用删掉了，它的版本记录和磁盘快照就是纯垃圾，需要一起清掉。
        // 放在 super.removeById 之后：应用删除失败时版本还得留着，否则用户看不到应用、历史版本也没了。
        if (removed) {
            appVersionService.deleteByAppId(appId);
        }
        return removed;
    }

    /**
     * 清理应用封面在对象存储上的文件
     * <p>
     * 这里做容错设计：封面可能为空、可能是管理员手填的外部链接（此时 {@code deleteFileByUrl} 会跳过），
     * 删除失败也不应该影响应用本身的删除。
     *
     * @param appId 应用 id
     */
    private void cleanupAppCover(Long appId) {
        try {
            App app = this.getById(appId);
            if (app == null || StrUtil.isBlank(app.getCover())) {
                return;
            }
            cosManager.deleteFileByUrl(app.getCover());
        } catch (Exception e) {
            log.error("删除应用封面文件失败，appId: {}", appId, e);
        }
    }

    @Override
    public String deployApp(Long appId, User loginUser) {
        // 1. 参数校验
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用 ID 不能为空");
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NOT_LOGIN_ERROR, "用户未登录");
        // 2. 查询应用信息
        App app = this.getById(appId);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        // 3. 校验权限，仅本人可以部署
        if (!app.getUserId().equals(loginUser.getId())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权限部署该应用");
        }
        // 4. 获取部署标识：已有则复用（保证同一个应用的访问地址稳定），没有才生成
        String deployKey = app.getDeployKey();
        if (StrUtil.isBlank(deployKey)) {
            deployKey = RandomUtil.randomString(6);
        }
        // 5. 构建生成目录路径，命名规则与 CodeFileSaverTemplate 保持一致：{codeGenType}_{appId}
        String sourceDirPath = AppConstant.CODE_OUTPUT_ROOT_DIR + File.separator
                + app.getCodeGenType() + "_" + appId;
        File sourceDir = new File(sourceDirPath);
        ThrowUtils.throwIf(!sourceDir.exists() || !sourceDir.isDirectory(),
                ErrorCode.SYSTEM_ERROR, "应用代码不存在，请先生成代码");
        // 6. Vue 工程模式特殊处理：源码不能直接部署，必须先构建出 dist 目录
        CodeGenTypeEnum codeGenTypeEnum = CodeGenTypeEnum.getEnumByValue(app.getCodeGenType());
        if (codeGenTypeEnum == CodeGenTypeEnum.VUE_PROJECT) {
            // Vue 项目需要安装依赖 + 打包，构建失败就直接告诉用户，别部署一份跑不起来的源码
            boolean buildSuccess = vueProjectBuilder.buildProject(sourceDirPath);
            ThrowUtils.throwIf(!buildSuccess, ErrorCode.SYSTEM_ERROR, "Vue 项目构建失败，请检查代码和依赖");
            File distDir = new File(sourceDir, "dist");
            ThrowUtils.throwIf(!distDir.exists(), ErrorCode.SYSTEM_ERROR, "Vue 项目构建完成但未生成 dist 目录");
            // 把 dist 目录作为部署源，Nginx 访问的路径就是 {deployKey}/dist/index.html
            sourceDir = distDir;
            log.info("Vue 项目构建成功，将部署 dist 目录: {}", distDir.getAbsolutePath());
        }
        // 7. 复制文件到部署目录，目录名直接用 deployKey 以缩短访问路径
        String deployDirPath = deployProperties.getRootDir() + File.separator + deployKey;
        File deployDir = new File(deployDirPath);
        FileUtil.mkdir(deployDir);
        try {
            // 先清空再拷贝：copyContent 只覆盖同名文件、不会删除历史文件；
            // 重新部署时（尤其 Vue 重新构建后 assets 的 hash 变了）旧文件会一直残留在目录里
            FileUtil.clean(deployDir);
            FileUtil.copyContent(sourceDir, deployDir, true);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "部署失败：" + e.getMessage());
        }
        log.info("应用 {} 部署完成，部署目录: {}", appId, deployDir.getAbsolutePath());
        // 8. 回写部署信息
        App updateApp = new App();
        updateApp.setId(appId);
        updateApp.setDeployKey(deployKey);
        updateApp.setDeployedTime(LocalDateTime.now());
        boolean updateResult = this.updateById(updateApp);
        ThrowUtils.throwIf(!updateResult, ErrorCode.OPERATION_ERROR, "更新应用部署信息失败");
        // 9. 返回可访问的 URL
        String appDeployUrl = String.format("%s/%s/", deployProperties.getHost(), deployKey);
        // 10. 异步生成截图并更新应用封面
        generateAppScreenshotAsync(appId,appDeployUrl);
        return appDeployUrl;
    }

    /**
     * 异步生成应用截图并更新封面
     * <p>
     * ⚠️ 这是**后台异步任务**，任何异常都没有调用方会去接（虚拟线程里未捕获的异常只会打到 stderr，
     * 不进 logback，日志文件里什么都看不到）。所以整段必须自己 try/catch + log，
     * 并且不能让封面生成失败影响部署这个主流程。
     *
     * @param appId  应用ID
     * @param appUrl 应用访问URL
     */
    @Override
    public void generateAppScreenshotAsync(Long appId, String appUrl) {
        // 使用虚拟线程异步执行（I/O 密集型任务，创建成本极低）
        Thread.startVirtualThread(() -> {
            try {
                // 调用截图服务生成截图并上传
                String screenshotUrl = screenshotService.generateAndUploadScreenshot(appUrl);
                if (StrUtil.isBlank(screenshotUrl)) {
                    log.warn("封面生成失败，跳过封面更新，appId: {}, appUrl: {}", appId, appUrl);
                    return;
                }
                // 更新应用封面字段
                App updateApp = new App();
                updateApp.setId(appId);
                updateApp.setCover(screenshotUrl);
                if (!this.updateById(updateApp)) {
                    // 这里刻意不用 ThrowUtils：异步任务里抛异常没人接，只会把错误藏得更深
                    log.error("更新应用封面字段失败，appId: {}, cover: {}", appId, screenshotUrl);
                }
            } catch (Exception e) {
                log.error("异步生成应用封面失败，appId: {}, appUrl: {}", appId, appUrl, e);
            }
        });
    }
}
