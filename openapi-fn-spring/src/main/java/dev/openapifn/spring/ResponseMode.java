package dev.openapifn.spring;

/** How the validation filters treat invalid responses. */
public enum ResponseMode {
    /** Log a warning and pass the response on. */
    LOG,
    /** Replace the response with a 500 problem (or end a stream before the invalid item). */
    FAIL
}
