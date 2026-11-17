package com.hmdp.config;

import com.hmdp.dto.Result;
import com.hmdp.pharmacy.ai.inbound.OcrServiceUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class WebExceptionAdvice {

    @ExceptionHandler(OcrServiceUnavailableException.class)
    public ResponseEntity<Result> handleOcrUnavailable(OcrServiceUnavailableException e) {
        log.warn("OCR 服务暂不可用；上传请求未创建入库草稿");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Result.fail(e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public Result handleIllegalArgumentException(IllegalArgumentException e) {
        return Result.fail(e.getMessage());
    }

    @ExceptionHandler(SecurityException.class)
    public Result handleSecurityException(SecurityException e) {
        return Result.fail("无权管理该门店");
    }

    @ExceptionHandler(RuntimeException.class)
    public Result handleRuntimeException(RuntimeException e) {
        log.error(e.toString(), e);
        return Result.fail("服务器异常");
    }
}
