package dev.openapifn.validation;

/** Severity of a validation issue. Only {@link #ERROR} makes a report fail. */
public enum Level {
    ERROR,
    WARN,
    INFO,
    /** The issue is dropped from reports. */
    IGNORE
}
