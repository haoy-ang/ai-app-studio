package com.haoy.aiapp.common;

import lombok.Data;

import java.io.Serializable;

/**
 * 分页请求类
 */
@Data
public class PageRequest implements Serializable {

    /**
     * 当前页号
     */
    private int pageNum = 1;

    /**
     * 页面大小
     */
    private int pageSize = 10;

    /**
     * 排序字段
     */
    private String sortField;

    /**
     * 排序顺序（默认降序）
     */
    private String sortOrder = "descend";
}