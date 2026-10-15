package com.orpex.audit;

/** Événements du journal d'audit (spécification §11). */
public enum AuditEvent {
    LOGIN_SUCCEEDED,
    LOGIN_FAILED,
    ACCOUNT_CREATED,
    ACCOUNT_DISABLED,
    ACCOUNT_ENABLED,
    ACCOUNT_ROLE_CHANGED,
    ACCOUNT_PASSWORD_RESET,
    PASSWORD_CHANGED,
    IMPORT_APPLIED,
    RULE_UPDATED,
    RULE_ACTIVATED,
    RULE_PAUSED,
    RULE_RESUMED,
    RULE_CLOSED,
    RULE_RESCHEDULED,
    VALIDATION_DROPPED,
    SEND_DECISION_REOPENED,
    /** Écrit par la base (trigger sur {@code occurrences}, V12) à chaque changement d'état d'un envoi. */
    SEND_STATE_CHANGED,
    SEND_RESOLVED,
    TEST_SEND,
    VALIDATION_REMINDER_SENT,
    SMTP_SETTINGS_CHANGED,
    SMTP_TEST_SENT,
    SENDING_PAUSED,
    SENDING_RESUMED,
    HOLIDAY_ADDED,
    HOLIDAY_REMOVED
}
