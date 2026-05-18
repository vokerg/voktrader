package com.vokerg.voktrader.trade.model;

public enum OrderReconciliationSource {
    AUTO_WORKER,
    MANUAL_DASHBOARD,
    POST_SUBMIT,
    POST_CANCEL,
    POST_FILL_AUDIT,
    API
}
