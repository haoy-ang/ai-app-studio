package com.haoy.aiapp.common;

import lombok.Data;

import java.io.Serializable;

/**
 * 删除请求类
 */
@Data
public class DeleteRequest implements Serializable {

    /**
     * id
     */
    private Long id;
}