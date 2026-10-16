package com.haoy.aiapp.constant;

/**
 * 应用常量接口
 *
 * @author <a>程序员haoy</a>
 */
public interface AppConstant {

    /**
     * 应用生成目录（保存 AI 生成的代码，供在线预览读取）
     * <p>
     * 这是「开发中间产物」，读写都用同一个常量、<b>没有跨系统契约</b>，所以跟随工作目录是自洽的。
     */
    String CODE_OUTPUT_ROOT_DIR = System.getProperty("user.dir") + "/tmp/code_output";

    /*
     * 注意：部署相关的 CODE_DEPLOY_ROOT_DIR / CODE_DEPLOY_HOST 已迁到配置类
     * {@link com.haoy.aiapp.config.DeployProperties}（application.yml 的 app.deploy.*）。
     * 原因：部署根目录必须与 Nginx 的 root 严格一致，属于「跨系统契约」，
     * 不能再跟随易变的工作目录（项目目录一改名就会导致部署 404）。
     */

    /**
     * Vue 工程项目目录名前缀
     * <p>
     * 完整路径为 {@code {CODE_OUTPUT_ROOT_DIR}/{VUE_PROJECT_DIR_PREFIX}{appId}}。
     * 抽成常量是为了让「写文件的工具」「读文件的工具」「构建器」「流处理器」
     * 用的是同一个前缀 —— 任何一处写错一个字符，AI 写的文件和预览读的目录就对不上。
     */
    String VUE_PROJECT_DIR_PREFIX = "vue_project_";

    /**
     * 精选应用的优先级
     */
    Integer GOOD_APP_PRIORITY = 99;

    /**
     * 默认应用优先级
     */
    Integer DEFAULT_APP_PRIORITY = 0;
}