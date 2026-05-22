package com.cragent.core.model;

public enum ReviewCategory {
    SQL_INJECTION,
    XSS,
    PATH_TRAVERSAL,
    COMMAND_INJECTION,
    SENSITIVE_DATA_EXPOSURE,
    INSECURE_DESERIALIZATION,
    AUTH_BYPASS,

    NULL_POINTER,
    RACE_CONDITION,
    RESOURCE_LEAK,
    SWALLOWED_EXCEPTION,
    API_MISUSE,
    LOGIC_ERROR,
    OFF_BY_ONE,

    N_PLUS_ONE_QUERY,
    EXCESSIVE_ALLOCATION,
    INEFFICIENT_DATA_STRUCTURE,
    MISSING_CACHE,
    SYNC_BOTTLENECK,
    UNBUFFERED_IO,
    THREAD_POOL_MISUSE,
    MEMORY_LEAK,

    CODE_STYLE,
    OTHER;

    public static ReviewCategory fromString(String s) {
        if (s == null) return OTHER;
        try {
            return valueOf(s.toUpperCase());
        } catch (IllegalArgumentException e) {
            return OTHER;
        }
    }
}
