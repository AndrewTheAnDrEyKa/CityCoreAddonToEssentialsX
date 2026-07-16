package ru.citycore.permission;

/** Stable internal abilities used by panel modules. External groups are never checked directly. */
public enum Capability {
    MAYOR_WORKSPACE,
    GOVERNMENT_WORKSPACE,
    ADMIN_WORKSPACE,
    REVIEW_CITY_FOUNDATIONS,
    ISSUE_CURRENCY,
    MANAGE_INDUSTRY,
    ADMIN_INDUSTRY,
    MANAGE_EVENTS
}
