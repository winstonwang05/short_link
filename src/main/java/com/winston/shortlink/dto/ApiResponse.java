package com.winston.shortlink.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * @description: 通用Api响应工具类
 * @author: Winston
 * @date: 2026/2/2 21:08
 * @version: 1.0
 */
@JsonInclude(JsonInclude.Include.NON_NULL) // 如果某个字段为null，序列化就忽略它
public class ApiResponse<T> {

    private String message;
    private Integer code;
    private T data;
    private Long timestamp;

    // 无参构造方法--时间戳的记录
    public ApiResponse() {
        timestamp = System.currentTimeMillis();
    }
    // 有参构造
    public ApiResponse(Integer code, String message) {
        // 调用无参构造方法
        this();
        this.message = message;
        this.code = code;
    }

    public ApiResponse(Integer code, String message, T data) {
        // 调用无参构造方法
        this();
        this.message = message;
        this.code = code;
        this.data = data;
    }
    // 成功响应
    public static <T> ApiResponse<T> success() {
        return new ApiResponse<>(200, "操作成功");
    }
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(200, "操作成功", data);
    }
    public static <T> ApiResponse<T> success(String message, T data) {
        return new ApiResponse<>(200, message, data);
    }

    // 失败响应
    public static <T> ApiResponse<T> error() {
        return new ApiResponse<>(500, "操作失败");
    }
    public static <T> ApiResponse<T> error(String message) {
        return new ApiResponse<>(500, message);
    }
    public static <T> ApiResponse<T> error(Integer code, String message) {
        return new ApiResponse<>(code, message);
    }

    // 参数错误
    public static <T> ApiResponse<T> badRequest(String message) {
        return new ApiResponse<>(400, message);
    }

    // 未找到
    public static <T> ApiResponse<T> notFound(String message) {
        return new ApiResponse<>(404, message);
    }

    // 服务器内部错误
    public static <T> ApiResponse<T> internalError(String message) {
        return new ApiResponse<>(500, message);
    }

    // getter 和 setter方法
    public Integer getCode() {
        return code;
    }
    public void setCode(Integer code) {
        this.code = code;
    }
    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
    public T getData() {
        return data;
    }
    public void setData(T data) {
        this.data = data;
    }
    public Long getTimestamp() {
        return timestamp;
    }
    public void setTimestamp(Long timestamp) {
        this.timestamp = timestamp;
    }

    @Override
    public String toString() {
        return "ApiResponse{" +
                "message='" + message + '\'' +
                ", code=" + code +
                ", data=" + data +
                ", timestamp=" + timestamp +
                '}';
    }
}
