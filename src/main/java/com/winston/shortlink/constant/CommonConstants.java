package com.winston.shortlink.constant;

/**
 * @description: 通用常量
 * @author: Winston
 * @date: 2026/2/1 11:56
 * @version: 1.0
 */
public class CommonConstants {

    /**
     * 原分库数量
     */
    public static final int SHARDING_DATABASE_COUNT = 16;

    /**
     * 原分表数量
     */
    public static final int SHARDING_TABLE_COUNT = 64;

    /**
     * 新分库数量（扩容之后）
     */
    public static final int NEW_SHARDING_DATABASE_COUNT = 32;

    /**
     * 新分表数量（扩容之后）
     */
    public static final int NEW_SHARDING_TABLE_COUNT = 256;

    /**
     * 短链编码长度（更新为10位）
     */
    public static final int SHORT_CODE_LENGTH = 10;
}
