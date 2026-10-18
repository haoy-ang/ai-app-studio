package com.haoy.aiapp.service.appversion;

import com.haoy.aiapp.model.entity.AppVersion;
import com.haoy.aiapp.model.entity.User;
import com.haoy.aiapp.model.enums.CodeGenTypeEnum;
import com.haoy.aiapp.model.enums.VersionSourceEnum;

import java.util.List;
import java.util.Optional;

/**
 * 应用版本服务（快照 / 列表 / 回滚）
 * <p>
 * <b>它解决的问题：</b>AI 生成是「整目录覆盖」的，用户让模型改一次，上一版就没了 ——
 * 改坏了只能重来，而重来一次既花钱又不保证能回到原来那样。
 * 有了版本快照，「改坏了」就变成一个可逆操作。
 * <p>
 * <b>三个刻意的设计取舍：</b>
 * <ol>
 *   <li><b>快照挂在「生成成功」之后，不在「部署」时</b>：部署是可选动作，
 *       用户可能生成十次才部署一次；版本要覆盖的是「每一次生成」；</li>
 *   <li><b>内容摘要相同就不记新版本</b>：模型经常吐出和上一版一字不差的结果，
 *       那种版本除了把列表灌满没有任何价值；</li>
 *   <li><b>回滚只恢复产物目录，不自动重新部署</b>：把「改代码」和「上线」两件事分开，
 *       用户能先预览确认再决定要不要部署。自动部署在 Vue 模式下还要等一次 npm 构建，
 *       把一次点击变成一分钟的等待，反而更糟。</li>
 * </ol>
 *
 * @author <a>程序员haoy</a>
 */
public interface AppVersionService {

    /**
     * 给应用当前的产物目录打一个快照
     * <p>
     * <b>本方法不会抛异常给调用方</b>：版本记录只是「锦上添花」，
     * 不能因为写快照失败就让用户这次生成白等。
     *
     * @param appId       应用 id
     * @param codeGenType 生成类型（决定产物目录名）
     * @param source      版本来源
     * @param prompt      触发本次生成的用户消息（会截断，可为 null）
     * @param userId      操作人（可为 null）
     * @return 新版本；内容未变化时返回命中的那个已有版本；跳过时返回空
     */
    Optional<AppVersion> snapshot(long appId, CodeGenTypeEnum codeGenType,
                                  VersionSourceEnum source, String prompt, Long userId);

    /**
     * 查询某个应用的全部版本（按版本号倒序，最新在前）
     *
     * @param appId 应用 id
     * @return 版本列表
     */
    List<AppVersion> listByAppId(long appId);

    /**
     * 查询某个应用当前生效的版本
     *
     * @param appId 应用 id
     * @return 当前版本；没有版本时返回 null
     */
    AppVersion getCurrent(long appId);

    /**
     * 回滚到指定版本
     * <p>
     * 做三件事：把快照目录写回产物目录 → 把 current 标记切到这个版本 → 返回该版本。
     *
     * @param versionId 版本 id
     * @param loginUser 操作人（仅应用所有者或管理员）
     * @return 回滚到的版本
     */
    AppVersion rollback(long versionId, User loginUser);

    /**
     * 删除一个非当前版本（当前版本不允许删，否则「当前」会指向不存在的目录）
     *
     * @param versionId 版本 id
     * @param loginUser 操作人（仅应用所有者或管理员）
     * @return 是否删除
     */
    boolean deleteVersion(long versionId, User loginUser);

    /**
     * 删除某个应用的全部版本记录与快照目录（应用被删除时调用，容错不抛异常）
     *
     * @param appId 应用 id
     */
    void deleteByAppId(long appId);
}
