package com.haoy.aiapp.service.app;

import com.haoy.aiapp.model.entity.User;
import com.mybatisflex.core.service.IService;
import com.mybatisflex.core.query.QueryWrapper;
import com.haoy.aiapp.model.entity.App;
import com.haoy.aiapp.model.dto.app.AppAddRequest;
import com.haoy.aiapp.model.dto.app.AppQueryRequest;
import com.haoy.aiapp.model.vo.app.AppVO;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 应用 服务层。
 *
 * @author <a>程序员haoy</a>
 */
public interface AppService extends IService<App> {

    /**
     * 创建应用
     * <p>
     * 由 AI 智能路由根据用户初始提示词自动选择代码生成类型。
     *
     * @param appAddRequest 创建应用请求
     * @param loginUser     登录用户
     * @return 新应用 id
     */
    Long createApp(AppAddRequest appAddRequest, User loginUser);

    /**
     * 获取应用视图对象（包含创建用户信息）
     *
     * @param app 应用实体
     * @return 应用视图对象
     */
    AppVO getAppVO(App app);

    /**
     * 获取应用视图对象列表（包含创建用户信息）
     *
     * @param appList 应用实体列表
     * @return 应用视图对象列表
     */
    List<AppVO> getAppVOList(List<App> appList);

    /**
     * 根据查询条件构造数据查询参数
     *
     * @param appQueryRequest 应用查询请求
     * @return 查询参数
     */
    QueryWrapper getQueryWrapper(AppQueryRequest appQueryRequest);

    /**
     * 聊天生成代码
     *
     * @param appId 应用id
     * @param message 用户提示词
     * @param loginUser 登录用户
     * @return 代码流
     */
    Flux<String> chatToGenCode(Long appId, String message, User loginUser);

    /**
     * 部署应用，将生成的代码复制到部署目录
     *
     * @param appId     应用 id
     * @param loginUser 登录用户
     * @return 可访问的部署 URL
     */
    String deployApp(Long appId, User loginUser);

    /**
     * 异步生成应用截图并更新封面
     *
     * @param appId  应用ID
     * @param appUrl 应用访问URL
     */
    void generateAppScreenshotAsync(Long appId, String appUrl);
}
