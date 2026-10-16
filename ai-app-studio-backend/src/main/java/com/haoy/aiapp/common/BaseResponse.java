package com.haoy.aiapp.common;

import com.haoy.aiapp.exception.ErrorCode;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 通用返回类
 * <p>
 * ⚠️ {@code @NoArgsConstructor} 不能删：本类全部构造函数都带参数，Lombok 的 {@code @Data}
 * 在这种情况下不会再补一个无参构造。而 Redis 缓存（精选应用列表）用 Jackson 反序列化
 * {@code BaseResponse} 时需要一个无参构造来实例化 —— 少了它，缓存命中时反序列化直接失败。
 *
 * @param <T> 数据类型
 */
@Data
@NoArgsConstructor
public class BaseResponse<T> implements Serializable {

    /**
     * 状态码
     */
    private int code;

    /**
     * 数据
     */
    private T data;

    /**
     * 信息
     */
    private String message;

    public BaseResponse(int code, T data, String message) {
        this.code = code;
        this.data = data;
        this.message = message;
    }

    public BaseResponse(int code, T data) {
        this(code, data, "");
    }

    public BaseResponse(ErrorCode errorCode) {
        this(errorCode.getCode(), null, errorCode.getMessage());
    }
}