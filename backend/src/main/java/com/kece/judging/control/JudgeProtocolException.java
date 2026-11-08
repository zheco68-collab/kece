package com.kece.judging.control;

import lombok.Getter;

@Getter
public class JudgeProtocolException extends RuntimeException {
    private final int status;
    private final String code;

    public JudgeProtocolException(int status, String code) {
        super(code);
        this.status = status;
        this.code = code;
    }
}
