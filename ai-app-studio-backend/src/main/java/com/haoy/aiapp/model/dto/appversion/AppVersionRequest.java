package com.haoy.aiapp.model.dto.appversion;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 应用版本操作请求（回滚 / 删除）
 * <p>
 * 只带 versionId：应用 id 由版本记录反查，
 * 让客户端传 appId 只会多一个「传的和版本不匹配」的校验点，没有收益。
 *
 * @author <a>程序员haoy</a>
 */
@Data
public class AppVersionRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 版本 id
     */
    private Long versionId;
}
