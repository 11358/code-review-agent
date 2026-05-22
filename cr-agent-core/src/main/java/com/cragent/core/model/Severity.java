package com.cragent.core.model;

public enum Severity {
    CRITICAL,
    WARNING,
    INFO;

    public static Severity fromString(String s) {
        if (s == null) return INFO;
        return switch (s.toUpperCase()) {
            case "CRITICAL" -> CRITICAL;
            case "WARNING" -> WARNING;
            default -> INFO;
        };
    }
}
