package com.haoy.aiapp.service.user.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.BCrypt;
import com.haoy.aiapp.constant.UserConstant;
import com.haoy.aiapp.exception.BusinessException;
import com.haoy.aiapp.exception.ErrorCode;
import com.haoy.aiapp.exception.ThrowUtils;
import com.haoy.aiapp.manager.CosManager;
import com.haoy.aiapp.mapper.user.UserMapper;
import com.haoy.aiapp.model.dto.user.UserProfileUpdateRequest;
import com.haoy.aiapp.model.dto.user.UserQueryRequest;
import com.haoy.aiapp.model.entity.App;
import com.haoy.aiapp.model.entity.User;
import com.haoy.aiapp.model.enums.UserRoleEnum;
import com.haoy.aiapp.model.vo.user.AppUserVO;
import com.haoy.aiapp.model.vo.user.LoginUserVO;
import com.haoy.aiapp.model.vo.user.UserVO;
import com.haoy.aiapp.service.app.AppService;
import com.haoy.aiapp.service.chathistory.ChatHistoryService;
import com.haoy.aiapp.service.user.UserService;
import com.haoy.aiapp.utils.SortFieldValidator;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 用户 服务层实现。
 *
 * @author <a>程序员haoy</a>
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

    /**
     * 用户登录态键
     */
    public static final String USER_LOGIN_STATE = "user_login_state";

    /**
     * 昵称最大长度
     */
    private static final int USER_NAME_MAX_LENGTH = 20;

    /**
     * 简介最大长度
     */
    private static final int USER_PROFILE_MAX_LENGTH = 200;

    /**
     * 头像 URL 最大长度
     */
    private static final int USER_AVATAR_MAX_LENGTH = 512;

    /**
     * 账号列长度上限（与表结构 userAccount varchar(256) 对齐）
     */
    private static final int USER_ACCOUNT_MAX_LENGTH = 256;

    /**
     * 头像文件体积上限：2MB（比 yml 里的 5MB 更严，图标类图片没必要更大）
     */
    private static final long AVATAR_MAX_SIZE = 2 * 1024 * 1024;

    /**
     * 校验图片真实类型时读取的文件头长度
     * <p>
     * 12 字节：PNG / JPEG / GIF 都只用到前 4 字节，但 WebP 需要读到第 12 字节
     * （RIFF....WEBP），所以统一读 12 字节，一次判断四种格式。
     */
    private static final int IMAGE_HEADER_LENGTH = 12;

    @Resource
    private CosManager cosManager;

    /**
     * 删除用户时要连带清掉他的应用，而 AppServiceImpl 又依赖本服务（查作者、查登录用户），
     * 两边直接互相注入会形成循环依赖 → 用 @Lazy 让 Spring 注入延迟代理，启动时不再互相等待。
     * （同样的处理见 {@code ChatHistoryServiceImpl#appService}）
     */
    @Resource
    @Lazy
    private AppService appService;

    @Resource
    private ChatHistoryService chatHistoryService;

    /**
     * 允许排序的字段白名单（防止前端传入任意 SQL 片段造成注入）
     */
    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of(
            "id", "userAccount", "userName", "userRole", "createTime", "updateTime");

    @Override
    public Long userRegister(String userAccount, String userPassword, String checkPassword) {
        // 1. 校验
        if (StrUtil.hasBlank(userAccount, userPassword, checkPassword)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "参数为空");
        }
        if (userAccount.length() < 4) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户账号过短");
        }
        if (userPassword.length() < 8 || checkPassword.length() < 8) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户密码过短");
        }
        if (!userPassword.equals(checkPassword)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "两次输入的密码不一致");
        }

        // 2. 检查是否重复
        QueryWrapper queryWrapper = new QueryWrapper();
        queryWrapper.eq("userAccount", userAccount);
        long count = this.mapper.selectCountByQuery(queryWrapper);
        if (count > 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "账号重复");
        }

        // 3. 加密
        String encryptPassword = getEncryptPassword(userPassword);

        // 4. 插入数据
        User user = new User();
        user.setUserAccount(userAccount);
        user.setUserPassword(encryptPassword);
        // 默认昵称：用「用户 + 账号后 4 位」而不是写死的「无名」。
        // 原因有两个：一是全站一片「无名」根本分不清谁是谁；
        // 二是这个昵称会展示在公开的应用卡片上，直接用完整 userAccount 等于把账号公开，
        // 所以只取尾部 4 位 —— 足够区分，又不至于暴露账号全貌。
        user.setUserName(buildDefaultUserName(userAccount));
        user.setUserRole(UserRoleEnum.USER.getValue());
        boolean saveResult = this.save(user);
        if (!saveResult) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "注册失败,数据库错误");
        }
        return user.getId();
    }

    /**
     * 获取加密后的密码。
     *
     * <p>使用 BCrypt（自带随机盐、计算成本可调），相比 MD5 加固定盐的关键区别：
     * <ul>
     *   <li>每个密码的盐都是随机的 → 彩虹表失效；</li>
     *   <li>计算成本可调（默认 2^10 轮），暴力破解成本远高于 MD5；</li>
     *   <li>哈希结果 60 位定长、自带盐与版本信息，无需单独存盐字段。</li>
     * </ul>
     *
     * @param userPassword 原始密码
     * @return BCrypt 哈希值
     */
    @Override
    public String getEncryptPassword(String userPassword) {
        return BCrypt.hashpw(userPassword, BCrypt.gensalt());
    }

    /**
     * 校验原始密码与数据库中的哈希是否匹配。
     *
     * <p>历史数据里存的是 32 位 MD5（旧实现的遗留），BCrypt 的 {@code checkpw} 遇到这种
     * 格式会抛 {@link IllegalArgumentException}。这里显式兜住，避免历史账号登录直接 500，
     * 统一按「密码错误」处理。
     *
     * @param rawPassword    用户输入的原始密码
     * @param storedPassword 数据库中的密码哈希
     * @return 是否匹配
     */
    private boolean checkPassword(String rawPassword, String storedPassword) {
        // BCrypt 哈希固定以 $2a$ / $2b$ / $2y$ 开头；不是这种格式说明是历史 MD5 数据，
        // 直接判为不匹配，避免 checkpw 抛异常
        if (StrUtil.isBlank(storedPassword) || !storedPassword.startsWith("$2")) {
            return false;
        }
        try {
            return BCrypt.checkpw(rawPassword, storedPassword);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * 获取脱敏的已登录用户信息
     *
     * @return
     */
    @Override
    public LoginUserVO getLoginUserVO(User user) {
        if (user == null) {
            return null;
        }
        LoginUserVO loginUserVO = new LoginUserVO();
        BeanUtils.copyProperties(user, loginUserVO);
        return loginUserVO;
    }

    /**
     * 用户登录
     *
     * @param userAccount 用户账号
     * @param userPassword 用户密码
     * @param request 请求对象
     * @return 脱敏后的用户信息
     */
    @Override
    public LoginUserVO userLogin(String userAccount, String userPassword, HttpServletRequest request) {
        // 1. 校验
        if (StrUtil.hasBlank(userAccount, userPassword)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "参数为空");
        }
        if (userAccount.length() < 4) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "账号错误");
        }
        if (userPassword.length() < 8) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "密码错误");
        }

        // 2. 先按账号查出用户，再用 BCrypt 校验密码。
        // ⚠️ 不能沿用 MD5 时期「把输入密码哈希后 where userPassword = ?」的写法：
        // BCrypt 每次哈希都会生成新的随机盐，等值查询永远匹配不上。
        QueryWrapper queryWrapper = new QueryWrapper();
        queryWrapper.eq("userAccount", userAccount);
        User user = this.mapper.selectOneByQuery(queryWrapper);
        // 用户不存在与密码错误统一返回同一提示，避免暴露「账号是否存在」
        if (user == null || !checkPassword(userPassword, user.getUserPassword())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户不存在或密码错误");
        }

        // 3. 记录用户的登录态
        request.getSession().setAttribute(USER_LOGIN_STATE, user);

        // 4. 返回脱敏后的用户信息
        return this.getLoginUserVO(user);
    }

    /**
     * 获取当前登录用户（从 Session 中读取并校验）
     *
     * @param request 请求对象
     * @return 当前登录用户（数据库最新数据）
     * @throws BusinessException 未登录时抛出
     */
    @Override
    public User getLoginUser(HttpServletRequest request) {
        // 先判断是否已登录
        Object userObj = request.getSession().getAttribute(USER_LOGIN_STATE);
        User currentUser = (User) userObj;
        if (currentUser == null || currentUser.getId() == null) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }
        // 从数据库查询（追求性能的话可以注释，直接返回 Session 中的结果）
        long userId = currentUser.getId();
        currentUser = this.getById(userId);
        if (currentUser == null) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }
        return currentUser;
    }

    /**
     * 用户登出（移除 Session 登录态）
     *
     * @param request 请求对象
     * @return 登出成功
     * @throws BusinessException 未登录时抛出
     */
    @Override
    public boolean userLogout(HttpServletRequest request) {
        // 先判断是否已登录
        Object userObj = request.getSession().getAttribute(USER_LOGIN_STATE);
        if (userObj == null) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "未登录");
        }
        // 移除登录态
        request.getSession().removeAttribute(USER_LOGIN_STATE);
        return true;
    }

    /**
     * 获取单个用户脱敏视图对象
     *
     * @param user 用户实体
     * @return 脱敏用户
     */
    @Override
    public UserVO getUserVO(User user) {
        if (user == null) {
            return null;
        }
        UserVO userVO = new UserVO();
        BeanUtils.copyProperties(user, userVO);
        return userVO;
    }

    /**
     * 获取用户列表脱敏视图对象
     *
     * @param userList 用户实体列表
     * @return 脱敏用户列表
     */
    @Override
    public List<UserVO> getUserVOList(List<User> userList) {
        if (CollUtil.isEmpty(userList)) {
            return new ArrayList<>();
        }
        return userList.stream().map(this::getUserVO).collect(Collectors.toList());
    }

    /**
     * 获取应用作者视图对象（公开场景使用，不含登录账号）
     *
     * @param user 用户实体
     * @return 应用作者视图对象
     */
    @Override
    public AppUserVO getAppUserVO(User user) {
        if (user == null) {
            return null;
        }
        AppUserVO appUserVO = new AppUserVO();
        // AppUserVO 没有 userAccount 字段，copyProperties 会自动跳过，
        // 所以这里不存在「不小心把账号带出去」的可能 —— 脱敏靠类型本身保证，而不是靠人记得手动置空
        BeanUtils.copyProperties(user, appUserVO);
        return appUserVO;
    }

    /**
     * 更新当前登录用户的个人资料
     *
     * @param loginUser     当前登录用户
     * @param updateRequest 资料更新请求
     * @return 更新后的用户实体
     */
    @Override
    public User updateMyProfile(User loginUser, UserProfileUpdateRequest updateRequest) {
        ThrowUtils.throwIf(loginUser == null || loginUser.getId() == null, ErrorCode.NOT_LOGIN_ERROR);
        ThrowUtils.throwIf(updateRequest == null, ErrorCode.PARAMS_ERROR);
        // ⚠️ 只逐字段组装「允许用户修改」的内容，绝不 BeanUtils.copyProperties(updateRequest, user)。
        // 那种写法一旦将来 DTO 里多了 userRole 之类的字段，这里就静默变成一条提权后门。
        User updateUser = new User();
        updateUser.setId(loginUser.getId());

        // 昵称：必填
        String userName = StrUtil.trim(updateRequest.getUserName());
        ThrowUtils.throwIf(StrUtil.isBlank(userName), ErrorCode.PARAMS_ERROR, "昵称不能为空");
        ThrowUtils.throwIf(userName.length() > USER_NAME_MAX_LENGTH,
                ErrorCode.PARAMS_ERROR, "昵称不能超过 " + USER_NAME_MAX_LENGTH + " 个字符");
        updateUser.setUserName(userName);

        // 简介：null = 不修改；空串 = 清空。两者语义不同，见 DTO 注释
        String userProfile = updateRequest.getUserProfile();
        if (userProfile != null) {
            ThrowUtils.throwIf(userProfile.length() > USER_PROFILE_MAX_LENGTH,
                    ErrorCode.PARAMS_ERROR, "简介不能超过 " + USER_PROFILE_MAX_LENGTH + " 个字符");
            updateUser.setUserProfile(userProfile);
        }

        // 头像：null / 空串 = 不修改；传值必须是 http/https 链接
        String userAvatar = StrUtil.trim(updateRequest.getUserAvatar());
        if (StrUtil.isNotBlank(userAvatar)) {
            ThrowUtils.throwIf(userAvatar.length() > USER_AVATAR_MAX_LENGTH,
                    ErrorCode.PARAMS_ERROR, "头像地址过长");
            // 只放行 http/https：挡掉 javascript:、data: 这类伪协议。
            // 前端 <img src> 拿 javascript: 不会执行，但这个值还会出现在别的地方（如文档、分享），
            // 校验入口收窄是成本最低的做法。
            boolean isHttpUrl = userAvatar.startsWith("http://") || userAvatar.startsWith("https://");
            ThrowUtils.throwIf(!isHttpUrl, ErrorCode.PARAMS_ERROR, "头像地址必须是 http/https 链接");
            updateUser.setUserAvatar(userAvatar);
        }

        // updateById 会忽略 null 字段，所以上面「没赋值 = 不修改」的语义天然成立
        boolean result = this.updateById(updateUser);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR, "更新个人资料失败");

        // 换头像后清理 COS 上的旧文件，避免对象存储里堆积再也访问不到的孤儿图片。
        // 顺序很重要：必须等 updateById 成功之后再删 —— 反过来的话，更新失败就把用户旧头像删没了。
        if (StrUtil.isNotBlank(userAvatar) && !userAvatar.equals(loginUser.getUserAvatar())) {
            cleanupOldAvatar(loginUser.getUserAvatar());
        }

        User latest = this.getById(loginUser.getId());
        ThrowUtils.throwIf(latest == null, ErrorCode.NOT_FOUND_ERROR, "用户不存在");
        return latest;
    }

    /**
     * 上传用户头像到对象存储
     *
     * @param file      头像文件
     * @param loginUser 当前登录用户
     * @return 头像访问 URL
     */
    @Override
    public String uploadAvatar(MultipartFile file, User loginUser) {
        ThrowUtils.throwIf(loginUser == null || loginUser.getId() == null, ErrorCode.NOT_LOGIN_ERROR);
        ThrowUtils.throwIf(file == null || file.isEmpty(), ErrorCode.PARAMS_ERROR, "请选择要上传的图片");
        ThrowUtils.throwIf(file.getSize() > AVATAR_MAX_SIZE, ErrorCode.PARAMS_ERROR, "头像大小不能超过 2MB");

        // ⚠️ 校验的是文件头魔数，不是扩展名、也不是 Content-Type。
        // 后两者都由客户端提供：把 evil.txt 改名成 evil.png，或者手改请求头里的
        // Content-Type: image/png，都能骗过它们。魔数在文件内容里，伪造不了。
        String realType;
        try (InputStream inputStream = file.getInputStream()) {
            realType = detectImageType(inputStream.readNBytes(IMAGE_HEADER_LENGTH));
        } catch (IOException e) {
            log.error("读取头像文件失败", e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "图片读取失败，请重试");
        }
        ThrowUtils.throwIf(realType == null, ErrorCode.PARAMS_ERROR,
                "仅支持 png / jpg / gif / webp 格式的图片");

        // 对象键里的扩展名用「识别出来的真实类型」，不用用户传的文件名 —— 用户文件名是不可信输入
        String key = "user/avatar/" + loginUser.getId() + "_" + System.currentTimeMillis() + "." + realType;
        String url;
        try (InputStream inputStream = file.getInputStream()) {
            url = cosManager.uploadFile(key, inputStream, file.getSize(), "image/" + toMimeSubType(realType));
        } catch (IOException e) {
            log.error("上传头像失败，key: {}", key, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "图片上传失败，请重试");
        }
        ThrowUtils.throwIf(StrUtil.isBlank(url), ErrorCode.SYSTEM_ERROR, "图片上传失败，请重试");
        return url;
    }

    /**
     * 构造默认昵称：用户 + 账号后 4 位
     *
     * @param userAccount 用户账号
     * @return 默认昵称
     */
    private String buildDefaultUserName(String userAccount) {
        if (StrUtil.isBlank(userAccount)) {
            return "用户";
        }
        int keepLength = Math.min(4, userAccount.length());
        return "用户" + userAccount.substring(userAccount.length() - keepLength);
    }

    /**
     * 清理旧头像文件
     * <p>
     * 走 {@link CosManager#deleteFileByUrl} 而不是自己拼 key：那个方法内部有
     * 「不属于本桶的 URL 直接跳过」的前缀校验。头像 URL 理论上只有本服务会写，
     * 但历史数据里可能存着外部链接，直接按 host 截断去删是有风险的。
     *
     * @param oldAvatarUrl 旧头像访问 URL
     */
    private void cleanupOldAvatar(String oldAvatarUrl) {
        if (StrUtil.isBlank(oldAvatarUrl)) {
            return;
        }
        try {
            cosManager.deleteFileByUrl(oldAvatarUrl);
        } catch (Exception e) {
            // 删不掉旧图不影响「资料已更新成功」这个事实，只记日志
            log.error("删除旧头像文件失败，url: {}", oldAvatarUrl, e);
        }
    }

    /**
     * 通过文件头魔数识别图片真实类型
     *
     * @param header 文件头字节
     * @return png / jpg / gif / webp，无法识别返回 null
     */
    private String detectImageType(byte[] header) {
        if (header == null || header.length < IMAGE_HEADER_LENGTH) {
            return null;
        }
        // PNG: 89 50 4E 47 0D 0A 1A 0A
        if ((header[0] & 0xFF) == 0x89 && header[1] == 'P' && header[2] == 'N' && header[3] == 'G') {
            return "png";
        }
        // JPEG: FF D8 FF
        if ((header[0] & 0xFF) == 0xFF && (header[1] & 0xFF) == 0xD8 && (header[2] & 0xFF) == 0xFF) {
            return "jpg";
        }
        // GIF: "GIF87a" / "GIF89a"
        if (header[0] == 'G' && header[1] == 'I' && header[2] == 'F' && header[3] == '8') {
            return "gif";
        }
        // WebP: "RIFF" .... "WEBP"（第 4~7 字节是文件长度，跳过）
        if (header[0] == 'R' && header[1] == 'I' && header[2] == 'F' && header[3] == 'F'
                && header[8] == 'W' && header[9] == 'E' && header[10] == 'B' && header[11] == 'P') {
            return "webp";
        }
        return null;
    }

    /**
     * 把内部类型名映射成 MIME 子类型
     *
     * @param imageType 内部类型名
     * @return MIME 子类型
     */
    private String toMimeSubType(String imageType) {
        return "jpg".equals(imageType) ? "jpeg" : imageType;
    }

    /**
     * 根据查询请求构造 QueryWrapper
     *
     * @param userQueryRequest 查询请求
     * @return 查询包装器
     */
    @Override
    public QueryWrapper getQueryWrapper(UserQueryRequest userQueryRequest) {
        if (userQueryRequest == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "请求参数为空");
        }
        Long id = userQueryRequest.getId();
        String userAccount = userQueryRequest.getUserAccount();
        String userName = userQueryRequest.getUserName();
        String userProfile = userQueryRequest.getUserProfile();
        String userRole = userQueryRequest.getUserRole();
        String sortField = userQueryRequest.getSortField();
        String sortOrder = userQueryRequest.getSortOrder();
        QueryWrapper queryWrapper = QueryWrapper.create()
                .eq("id", id)
                .eq("userRole", userRole)
                .like("userAccount", userAccount)
                .like("userName", userName)
                .like("userProfile", userProfile);
        // 排序字段必须命中白名单，否则忽略（防止前端传入任意 SQL 片段造成注入）
        String safeSortField = SortFieldValidator.validate(sortField, ALLOWED_SORT_FIELDS);
        if (safeSortField != null) {
            queryWrapper.orderBy(safeSortField, "ascend".equals(sortOrder));
        }
        return queryWrapper;
    }

    /**
     * 删除用户并级联清理其名下数据
     * <p>
     * 顺序是「先把依赖数据删干净，最后删用户自己」：
     * 反过来的话一旦中途失败（或事务没覆盖到磁盘操作），用户没了、应用还在，
     * 那些应用就彻底没人能管——列表里查不到（查作者时用户已不存在），后台也定位不到归属人。
     * <p>
     * ⚠️ 磁盘上的部署产物、COS 封面、版本快照都不在事务里，只能靠「全部失败都吞掉」保证主流程走完。
     * 顺序上仍然把「删应用」放在最前面：{@code AppService.removeById} 自己就是按
     * 「对话 → 封面 → 部署目录 → 应用记录 → 版本」排的。
     *
     * @param userId     要删除的用户 id
     * @param operatorId 操作人 id
     * @return 是否删除成功
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean removeUser(Long userId, Long operatorId) {
        ThrowUtils.throwIf(userId == null || userId <= 0, ErrorCode.PARAMS_ERROR, "用户 ID 无效");

        User target = this.getById(userId);
        ThrowUtils.throwIf(target == null, ErrorCode.NOT_FOUND_ERROR, "用户不存在或已被删除");

        // 保护一：不能删自己。删掉就立刻失去后台入口，而这时候已经没有第二个账号能救回来
        if (userId.equals(operatorId)) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "不能删除当前登录的账号");
        }

        // 保护二：系统必须保留至少一个管理员，否则后台永久锁死
        if (UserConstant.ADMIN_ROLE.equals(target.getUserRole())
                && this.count(QueryWrapper.create().eq("userRole", UserConstant.ADMIN_ROLE)) <= 1) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "系统至少要保留一个管理员");
        }

        // 1. 逐个删应用：对话历史 / COS 封面 / 部署目录 / 版本快照都在这里一起清掉
        List<App> apps = appService.list(QueryWrapper.create().eq("userId", userId));
        for (App app : apps) {
            if (app.getId() != null && !appService.removeById(app.getId())) {
                // 单个应用删失败不阻断整体：留着它比「用户删了一半」更容易收拾
                log.warn("级联删除应用失败，跳过，appId: {}, userId: {}", app.getId(), userId);
            }
        }

        // 2. 兜底清理：理论上应用删完对话就没了，但历史脏数据（应用已删、对话还在）得照顾到
        chatHistoryService.remove(QueryWrapper.create().eq("userId", userId));

        // 3. 释放账号：逻辑删除只把 isDelete 置 1，行还占着 uk_userAccount 唯一索引，
        //    不释放的话这个账号永远注册不回来（用户看到的是「注册失败,数据库错误」，
        //    而注册接口明明查不到重复账号，排查起来极其绕）
        User releaseAccount = new User();
        releaseAccount.setId(userId);
        releaseAccount.setUserAccount(buildReleasedAccount(target));
        this.updateById(releaseAccount);

        // 4. 头像文件也从对象存储删掉（外部链接会被 deleteFileByUrl 自动跳过）
        cleanupOldAvatar(target.getUserAvatar());

        boolean removed = this.removeById(userId);
        ThrowUtils.throwIf(!removed, ErrorCode.OPERATION_ERROR, "删除用户失败");
        log.info("已删除用户并清理其名下数据，userId: {}, 应用数: {}, 操作人: {}", userId, apps.size(), operatorId);
        return true;
    }

    /**
     * 构造「已释放」的账号名
     * <p>
     * 形如 {@code zhangsan#deleted#469348007004553216}：既让唯一索引让开，
     * 又能从库里直接看出原账号是什么、是谁被删了（排查数据问题时不用翻日志）。
     *
     * @param user 被删除的用户
     * @return 改写后的账号
     */
    private String buildReleasedAccount(User user) {
        String suffix = "#deleted#" + user.getId();
        String account = StrUtil.blankToDefault(user.getUserAccount(), "user");
        // userAccount 列是 varchar(256)，截断原账号给后缀留位置
        int keepLength = Math.min(account.length(), USER_ACCOUNT_MAX_LENGTH - suffix.length());
        return account.substring(0, keepLength) + suffix;
    }
}