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
        if (s == null || s.isBlank()) return OTHER;
        String normalized = s.trim().toUpperCase().replace('-', '_').replace(' ', '_');
        try {
            return valueOf(normalized);
        } catch (IllegalArgumentException e) {
            // Fallback: keyword matching for common LLM output variations
            return fuzzyMatch(normalized);
        }
    }

    private static ReviewCategory fuzzyMatch(String normalized) {
        // Security
        if (normalized.contains("SQL")) return SQL_INJECTION;
        if (normalized.contains("XSS") || normalized.contains("CROSS")) return XSS;
        if (normalized.contains("PATH") || normalized.contains("TRAVERSAL") || normalized.contains("DIRECTORY")) return PATH_TRAVERSAL;
        if (normalized.contains("COMMAND") || normalized.contains("INJECTION") || normalized.contains("EXEC")) return COMMAND_INJECTION;
        if (normalized.contains("SENSITIVE") || normalized.contains("SECRET") || normalized.contains("EXPOSURE") || normalized.contains("LEAK")
                || normalized.contains("PASSWORD") || normalized.contains("CREDENTIAL") || normalized.contains("HARDCODED")) return SENSITIVE_DATA_EXPOSURE;
        if (normalized.contains("DESERIAL") || normalized.contains("SERIAL")) return INSECURE_DESERIALIZATION;
        if (normalized.contains("AUTH") || normalized.contains("PERMISSION") || normalized.contains("BYPASS")) return AUTH_BYPASS;

        // Bugs
        if (normalized.contains("NULL") || normalized.contains("NPE") || normalized.contains("POINTER")) return NULL_POINTER;
        if (normalized.contains("RACE") || normalized.contains("CONCURRENCY") || normalized.contains("CONCURRENT")) return RACE_CONDITION;
        if (normalized.contains("RESOURCE") || normalized.contains("LEAK") || normalized.contains("CLOSE") || normalized.contains("STREAM")) return RESOURCE_LEAK;
        if (normalized.contains("SWALLOW") || normalized.contains("EXCEPTION") || normalized.contains("CATCH") || normalized.contains("ERROR_HANDLING")) return SWALLOWED_EXCEPTION;
        if (normalized.contains("API") || normalized.contains("MISUSE") || normalized.contains("DEPRECATED")) return API_MISUSE;
        if (normalized.contains("LOGIC") || normalized.contains("CONDITION")) return LOGIC_ERROR;
        if (normalized.contains("OFF_BY_ONE") || normalized.contains("BOUNDARY") || normalized.contains("INDEX")) return OFF_BY_ONE;

        // Performance
        if (normalized.contains("N_PLUS") || normalized.contains("N+1") || normalized.contains("QUERY_IN_LOOP")) return N_PLUS_ONE_QUERY;
        if (normalized.contains("ALLOCATION") || normalized.contains("EXCESSIVE") || normalized.contains("BOXING")) return EXCESSIVE_ALLOCATION;
        if (normalized.contains("DATA_STRUCTURE") || normalized.contains("COLLECTION") || normalized.contains("INEFFICIENT")) return INEFFICIENT_DATA_STRUCTURE;
        if (normalized.contains("CACHE") || normalized.contains("CACHING")) return MISSING_CACHE;
        if (normalized.contains("SYNC") || normalized.contains("SYNCHRONIZED") || normalized.contains("LOCK") || normalized.contains("BOTTLENECK")) return SYNC_BOTTLENECK;
        if (normalized.contains("BUFFER") || normalized.contains("IO") || normalized.contains("UNBUFFERED")) return UNBUFFERED_IO;
        if (normalized.contains("THREAD") || normalized.contains("POOL") || normalized.contains("EXECUTOR")) return THREAD_POOL_MISUSE;
        if (normalized.contains("MEMORY") || normalized.contains("LEAK") || normalized.contains("GC")) return MEMORY_LEAK;

        if (normalized.contains("CODE_STYLE") || normalized.contains("STYLE") || normalized.contains("FORMAT")) return CODE_STYLE;
        return OTHER;
    }
}
