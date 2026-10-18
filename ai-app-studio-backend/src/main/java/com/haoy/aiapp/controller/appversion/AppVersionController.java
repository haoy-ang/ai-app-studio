package com.haoy.aiapp.controller.appversion;

import com.haoy.aiapp.common.BaseResponse;
import com.haoy.aiapp.common.ResultUtils;
import com.haoy.aiapp.constant.UserConstant;
import com.haoy.aiapp.exception.BusinessException;
import com.haoy.aiapp.exception.ErrorCode;
import com.haoy.aiapp.exception.ThrowUtils;
import com.haoy.aiapp.model.dto.appversion.AppVersionRequest;
import com.haoy.aiapp.model.entity.App;
import com.haoy.aiapp.model.entity.AppVersion;
import com.haoy.aiapp.model.entity.User;
import com.haoy.aiapp.model.vo.appversion.AppVersionVO;
import com.haoy.aiapp.service.app.AppService;
import com.haoy.aiapp.service.appversion.AppVersionService;
import com.haoy.aiapp.service.user.UserService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.BeanUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 应用版本 控制层（版本列表 / 回滚 / 删除）
 * <p>
 * <b>为什么单独一个 Controller 而不是塞进 AppController：</b>
 * 版本是一块独立的能力，后面还要加「版本对比」「版本备注」，
 * 塞进 AppController 会让那个本来就很长的类更难读。
 * <p>
 * <b>权限只在一处判：</b>列表接口自己校验归属；回滚和删除由 Service 层校验
 * （因为它们需要先拿到版本 → 再反查应用 → 才能判归属，放在 Controller 会重复一遍查询）。
 *
 * @author <a>程序员haoy</a>
 */
@RestController
@RequestMapping("/app/version")
public class AppVersionController {

    @Resource
    private AppVersionService appVersionService;

    @Resource
    private AppService appService;

    @Resource
    private UserService userService;

    /**
     * 【用户】查询某个应用的版本列表（最新在前）
     *
     * @param appId   应用 id
     * @param request 请求
     * @return 版本列表
     */
    @GetMapping("/list")
    public BaseResponse<List<AppVersionVO>> listVersions(@RequestParam Long appId, HttpServletRequest request) {
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用 ID 无效");
        User loginUser = userService.getLoginUser(request);
        App app = appService.getById(appId);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        // 版本列表会带出「用户当时让 AI 改了什么」（prompt），属于应用所有者的私密内容
        boolean isOwner = app.getUserId() != null && app.getUserId().equals(loginUser.getId());
        boolean isAdmin = UserConstant.ADMIN_ROLE.equals(loginUser.getUserRole());
        if (!isOwner && !isAdmin) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权限查看该应用的版本");
        }
        List<AppVersionVO> versions = appVersionService.listByAppId(appId).stream()
                .map(this::toVO)
                .toList();
        return ResultUtils.success(versions);
    }

    /**
     * 【用户】回滚到指定版本
     * <p>
     * ⚠️ 回滚只恢复「生成目录」，不会自动重新部署：先让用户预览确认，再决定要不要上线。
     *
     * @param versionRequest 版本请求
     * @param request        请求
     * @return 回滚到的版本
     */
    @PostMapping("/rollback")
    public BaseResponse<AppVersionVO> rollback(@RequestBody AppVersionRequest versionRequest,
                                               HttpServletRequest request) {
        ThrowUtils.throwIf(versionRequest == null || versionRequest.getVersionId() == null,
                ErrorCode.PARAMS_ERROR, "版本 ID 不能为空");
        User loginUser = userService.getLoginUser(request);
        AppVersion version = appVersionService.rollback(versionRequest.getVersionId(), loginUser);
        return ResultUtils.success(toVO(version));
    }

    /**
     * 【用户】删除一个非当前版本
     *
     * @param versionRequest 版本请求
     * @param request        请求
     * @return 是否删除
     */
    @PostMapping("/delete")
    public BaseResponse<Boolean> deleteVersion(@RequestBody AppVersionRequest versionRequest,
                                               HttpServletRequest request) {
        ThrowUtils.throwIf(versionRequest == null || versionRequest.getVersionId() == null,
                ErrorCode.PARAMS_ERROR, "版本 ID 不能为空");
        User loginUser = userService.getLoginUser(request);
        boolean result = appVersionService.deleteVersion(versionRequest.getVersionId(), loginUser);
        return ResultUtils.success(result);
    }

    private AppVersionVO toVO(AppVersion version) {
        AppVersionVO vo = new AppVersionVO();
        BeanUtils.copyProperties(version, vo);
        vo.setCurrent(Integer.valueOf(1).equals(version.getIsCurrent()));
        return vo;
    }
}
