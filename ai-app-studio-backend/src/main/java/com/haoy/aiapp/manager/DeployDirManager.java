package com.haoy.aiapp.manager;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.haoy.aiapp.config.DeployProperties;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;

/**
 * 部署产物目录的管理（目前只有删除）
 * <p>
 * <b>为什么把这段单独抽出来：</b>它做的是「递归删磁盘目录」这种不可逆操作，
 * 和 {@link CosManager} 那种「调个远程接口」性质完全不同 —— 一旦路径算错就是删错东西。
 * 抽成独立的类之后，路径拼接和校验可以被单测钉死，不用为了测它去启动整个 AppService。
 * <p>
 * <b>要解决的具体问题：</b>以前删除应用只清了对话、封面、版本快照，唯独没清部署目录，后果有三层：
 * <ol>
 *   <li>磁盘上永久残留（每个应用几百 KB 到几 MB，攒久了很可观）；</li>
 *   <li>更严重的是 <b>原 URL 仍然能打开</b> —— Nginx 只认目录，不认数据库，
 *       「我删了」和「别人还能访问」同时成立；</li>
 *   <li>应用记录删除后 {@code deployKey} 也随之消失，事后想手工清理，
 *       连目录名都无从查起（目录名就是随机字符串）。</li>
 * </ol>
 *
 * @author <a>程序员haoy</a>
 */
@Slf4j
@Component
public class DeployDirManager {

    /**
     * 合法的部署标识：只允许字母数字，最长 32
     * <p>
     * 它能拼进文件路径，而这个方法是<b>删目录</b>的。虽然 deployKey 现在只由
     * {@code RandomUtil.randomString(6)} 生成，但「删磁盘」这个动作不该依赖
     * 「上游永远干净」这个假设 —— 一旦哪天有人手工改了库，这里就是最后一道闸。
     */
    private static final Pattern DEPLOY_KEY_PATTERN = Pattern.compile("^[A-Za-z0-9]{1,32}$");

    @Resource
    private DeployProperties deployProperties;

    /**
     * 删除某个部署标识对应的产物目录（幂等）
     *
     * @param deployKey 部署标识，可为空
     * @return 是否真的删掉了一个存在的目录
     */
    public boolean deleteByDeployKey(String deployKey) {
        if (StrUtil.isBlank(deployKey)) {
            return false;
        }
        if (!DEPLOY_KEY_PATTERN.matcher(deployKey).matches()) {
            log.warn("拒绝删除部署目录：deployKey 含非法字符，key: {}", deployKey);
            return false;
        }
        Path root = Paths.get(deployProperties.getRootDir()).toAbsolutePath().normalize();
        Path target = root.resolve(deployKey).normalize();
        // 第二道闸：拼出来的路径必须还在部署根目录「里面」。
        // 正则已经挡掉了 `..`，这里再做一次是因为 startsWith 是能直接看懂的语义检查 ——
        // 将来有人放宽了正则，这一行仍然守得住。
        if (target.equals(root) || !target.startsWith(root)) {
            log.warn("拒绝删除部署目录：目标越出根目录，target: {}, root: {}", target, root);
            return false;
        }
        if (!Files.exists(target)) {
            log.debug("部署目录不存在，跳过：{}", target);
            return false;
        }
        try {
            FileUtil.del(target.toFile());
            log.info("已清理应用部署目录：{}", target);
            return true;
        } catch (Exception e) {
            // 删不掉不是致命的（Windows 上偶发文件占用），记录后继续删除应用记录
            log.error("清理部署目录失败，target: {}", target, e);
            return false;
        }
    }
}
