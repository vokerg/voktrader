package com.vokerg.voktrader.executor;

public enum ExecutorSubmissionFailureType {
    TIMEOUT,
    CONNECTION_RESET,
    HTTP_425_TOO_EARLY,
    HTTP_503_SERVICE_UNAVAILABLE,
    MALFORMED_RESPONSE,
    EXECUTOR_CRASH,
    NETWORK_FAILURE,
    UNKNOWN_FAILURE
}
