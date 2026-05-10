package com.vokerg.voktrader.api;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError handleIllegalArgument(IllegalArgumentException exception) {
        return new ApiError("BAD_REQUEST", exception.getMessage(), Instant.now());
    }

    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiError handleIllegalState(IllegalStateException exception) {
        return new ApiError("CONFLICT", exception.getMessage(), Instant.now());
    }

    public record ApiError(
            String code,
            String message,
            Instant timestamp
    ) {
    }
}
