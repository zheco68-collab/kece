package com.kece.judging.control;

import java.util.List;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpEntity;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.method.support.AsyncHandlerMethodReturnValueHandler;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.mvc.method.annotation.DeferredResultMethodReturnValueHandler;
import org.springframework.web.servlet.mvc.method.annotation.HttpEntityMethodProcessor;

public class JudgeClaimReturnValueHandler implements AsyncHandlerMethodReturnValueHandler {

    private final DeferredResultMethodReturnValueHandler deferredHandler =
            new DeferredResultMethodReturnValueHandler();
    private final HttpEntityMethodProcessor httpEntityHandler;

    public JudgeClaimReturnValueHandler(List<HttpMessageConverter<?>> messageConverters) {
        this.httpEntityHandler = new HttpEntityMethodProcessor(messageConverters);
    }

    @Override
    public boolean isAsyncReturnValue(Object returnValue, MethodParameter returnType) {
        return returnValue instanceof DeferredResult;
    }

    @Override
    public boolean supportsReturnType(MethodParameter returnType) {
        return JudgeMachineController.class.equals(returnType.getContainingClass())
                && returnType.getMethod() != null
                && "claim".equals(returnType.getMethod().getName());
    }

    @Override
    public void handleReturnValue(
            Object returnValue,
            MethodParameter returnType,
            ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest)
            throws Exception {
        if (returnValue instanceof DeferredResult) {
            deferredHandler.handleReturnValue(returnValue, returnType, mavContainer, webRequest);
        } else if (returnValue instanceof HttpEntity) {
            httpEntityHandler.handleReturnValue(returnValue, returnType, mavContainer, webRequest);
        }
    }
}
