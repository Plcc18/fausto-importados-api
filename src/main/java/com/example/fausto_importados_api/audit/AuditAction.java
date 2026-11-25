package com.example.fausto_importados_api.audit;

/** Eventos registrados na trilha de auditoria. */
public enum AuditAction {
    // ── autenticação ──
    LOGIN_SUCCESS,
    LOGIN_FAILURE,
    LOGIN_LOCKED,
    MFA_FAILURE,
    MFA_ENABLED,
    RECOVERY_CODE_USED,
    RECOVERY_CODES_REGENERATED,
    REAUTH_SUCCESS,
    REAUTH_FAILURE,
    REAUTH_REQUIRED,
    PASSWORD_CHANGED,
    LOGOUT_ALL,
    REFRESH_REUSE_DETECTED,
    ACCESS_DENIED,
    RATE_LIMITED,

    // ── contas administrativas ──
    ADMIN_CREATED,
    ADMIN_EMAIL_CHANGED,
    ADMIN_ACTIVATED,
    ADMIN_DEACTIVATED,
    ADMIN_CREDENTIALS_RESET,
    ADMIN_DELETED,

    // ── produtos e estoque ──
    PRODUCT_CREATED,
    PRODUCT_UPDATED,
    PRODUCT_DELETED,
    STOCK_DECREASED,

    // ── pedidos ──
    ORDER_COMPLETED,
    ORDER_CANCELLED,
    ORDERS_HISTORY_CLEARED,
    SALES_STATS_RESET,
    SALES_REPORT_RESET
}
