package com.vokerg.voktrader.executor;

import org.springframework.http.HttpStatusCode;

public class ExecutorSubmissionException extends RuntimeException {
    private final ExecutorSubmissionFailureType failureType;
    private final Integer httpStatus;
    private final String evidence;

    public ExecutorSubmissionException(
            ExecutorSubmissionFailureType failureType,
            String message,
            Integer httpStatus,
            String evidence,
            Throwable cause
    ) {
        super(message, cause);
        this.failureType = failureType;
        this.httpStatus = httpStatus;
        this.evidence = evidence;
    }

    public static ExecutorSubmissionException http(
            ExecutorSubmissionFailureType failureType,
            HttpStatusCode status,
            String body,
            Throwable cause
    ) {
        return new ExecutorSubmissionException(
                failureType,
                "Ambiguous executor HTTP " + status.value() + ": " + body,
                status.value(),
                body,
                cause
        );
    }

    public static ExecutorSubmissionException transport(
            ExecutorSubmissionFailureType failureType,
            String message,
            Throwable cause
    ) {
        return new ExecutorSubmissionException(failureType, message, null, null, cause);
    }

    public ExecutorSubmissionFailureType failureType() {
        return failureType;
    }

    public Integer httpStatus() {
        return httpStatus;
    }

    public String evidence() {
        return evidence;
    }
}
