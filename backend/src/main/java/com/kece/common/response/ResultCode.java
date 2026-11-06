package com.kece.common.response;

import lombok.Getter;

/** 业务状态码枚举，与全局接口规范一致。 */
@Getter
public enum ResultCode {
    SUCCESS(200, "Success"),
    PARAM_ERROR(4001, "参数错误"),
    UNAUTHORIZED(4002, "未登录或登录已过期"),
    FORBIDDEN(4003, "无权限"),
    NOT_FOUND(4004, "资源不存在"),
    CONFLICT(4009, "资源已发生变化，请刷新后重试"),
    SYSTEM_ERROR(5000, "系统错误");

    private final int code;
    private final String message;

    ResultCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
