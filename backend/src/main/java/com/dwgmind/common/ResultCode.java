package com.dwgmind.common;

public enum ResultCode {

    SUCCESS(200, "成功"),
    BAD_REQUEST(400, "请求参数有误"),
    NOT_FOUND(404, "资源不存在"),
    TOO_MANY_REQUESTS(429, "请求过于频繁，请稍后再试"),
    INTERNAL_ERROR(500, "服务器内部错误"),
    PARSE_FAILED(1001, "图纸解析失败"),
    UNSUPPORTED_FORMAT(1002, "不支持的图纸格式"),
    FILE_TOO_LARGE(1003, "文件超出大小限制"),
    LLM_UNAVAILABLE(1004, "大模型服务不可用");

    private final int code;
    private final String message;

    ResultCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
