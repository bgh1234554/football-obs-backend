package com.github.baek.footballobsbackend.error;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.util.HtmlUtils;

import java.io.IOException;
import java.time.Instant;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionAdvice {

    //일반적인 ApiException에 대한 에러 매핑
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResult> apiExceptionHandle(ApiException e, HttpServletRequest request){
        ErrorCode errorCode = e.getErrorCode();
        HttpStatus status = errorCode.getStatus();

        log.warn("[Exception Occurred] ApiException: code={}, message={}, path={}",
                errorCode.name(), errorCode.getMessage(), request.getRequestURI());

        //htmlEscape 미사용시 인텔리제이에서 XSS 보안 문제 관련 지적함
        ErrorResult result = new ErrorResult(errorCode.name(), errorCode.getMessage(), status.value(),
                HtmlUtils.htmlEscape(request.getRequestURI()), Instant.now());
        return ResponseEntity.status(status).body(result);
    }

    //컨트롤러에서 사용되는 @Validation 관련 예외처리
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResult> validationExceptionHandle(MethodArgumentNotValidException e, HttpServletRequest request){
        //어떤 필드들에서 오류가 났는지 오류 목록을 확인하고, 그 목록에 대한 메시지를 반환
        String errorMessage = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining(", "));

        log.warn("Validation error: {}, path={}", errorMessage, request.getRequestURI());

        ErrorResult result = new ErrorResult(
                "VALIDATION_ERROR", errorMessage, HttpStatus.BAD_REQUEST.value(),
                HtmlUtils.htmlEscape(request.getRequestURI()), Instant.now()
        );
        return ResponseEntity.badRequest().body(result);
    }

    //JSON 포맷 오류로 역직렬화 실패
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResult> httpMessageNotReadableExceptionHandle(HttpMessageNotReadableException e, HttpServletRequest request){
        log.warn("Invalid request body format: message={}, path={}", e.getMessage(), request.getRequestURI());
        ErrorResult result = new ErrorResult(
                "INVALID_REQUEST_BODY","요청 본문이 올바른 JSON 형식이 아니거나 구조가 잘못되었습니다.",
                HttpStatus.BAD_REQUEST.value(), HtmlUtils.htmlEscape(request.getRequestURI()), Instant.now()
        );
        return ResponseEntity.badRequest().body(result);
    }

    //이외 예상치 못한 예외로직 처리
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResult> generalExceptionHandle(Exception e, HttpServletRequest request){
        // 클라이언트가 응답 수신 전에 연결을 끊은 경우 — 서버 오류가 아니므로 DEBUG로만 기록
        if (isClientAbort(e)) {
            log.debug("Client disconnected mid-response: path={}", request.getRequestURI());
            return ResponseEntity.ok().build();
        }
        log.error("Unexpected Error occurred: message={}, path={}", e.getMessage(), request.getRequestURI());
        ErrorResult result = new ErrorResult(
                "INTERNAL_SERVER_ERROR",
                "서버 내부에 오류 발생",
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                HtmlUtils.htmlEscape(request.getRequestURI()),
                Instant.now()
        );
        return ResponseEntity.internalServerError().body(result);
    }

    // Tomcat ClientAbortException 또는 Broken pipe IOException 여부 확인
    // Tomcat 클래스를 직접 import하지 않고 이름/메시지로 판별
    private boolean isClientAbort(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if ("ClientAbortException".equals(t.getClass().getSimpleName())) return true;
            if (t instanceof IOException) {
                String msg = t.getMessage();
                if (msg != null && (msg.contains("Broken pipe") || msg.contains("Connection reset"))) return true;
            }
        }
        return false;
    }
}
