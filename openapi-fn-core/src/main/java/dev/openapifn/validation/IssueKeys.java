package dev.openapifn.validation;

/**
 * Issue keys. Schema violations append {@code .schema.<keyword>} to their location key, e.g.
 * {@code request.body.schema.required} or {@code request.parameter.query.schema.maximum}.
 */
public final class IssueKeys {

    public static final String REQUEST_PATH_MISSING = "request.path.missing";
    public static final String REQUEST_OPERATION_NOT_ALLOWED = "request.operation.notAllowed";
    public static final String REQUEST_SECURITY_MISSING = "request.security.missing";
    /** Followed by the parameter location, e.g. {@code request.parameter.query}. */
    public static final String REQUEST_PARAMETER = "request.parameter";
    public static final String REQUEST_QUERY_UNKNOWN = "request.parameter.query.unknown";
    public static final String REQUEST_BODY_MISSING = "request.body.missing";
    public static final String REQUEST_BODY_UNEXPECTED = "request.body.unexpected";
    public static final String REQUEST_BODY_INVALID = "request.body.invalid";
    public static final String REQUEST_BODY_SCHEMA = "request.body.schema";
    public static final String REQUEST_CONTENT_TYPE_MISSING = "request.contentType.missing";
    public static final String REQUEST_CONTENT_TYPE_INVALID = "request.contentType.invalid";
    public static final String REQUEST_CONTENT_TYPE_NOT_ALLOWED = "request.contentType.notAllowed";
    public static final String REQUEST_ACCEPT_NOT_ACCEPTABLE = "request.accept.notAcceptable";
    public static final String RESPONSE_STATUS_UNKNOWN = "response.status.unknown";
    public static final String RESPONSE_HEADER = "response.header";
    public static final String RESPONSE_BODY_MISSING = "response.body.missing";
    public static final String RESPONSE_BODY_UNEXPECTED = "response.body.unexpected";
    public static final String RESPONSE_BODY_INVALID = "response.body.invalid";
    public static final String RESPONSE_BODY_SCHEMA = "response.body.schema";
    public static final String RESPONSE_CONTENT_TYPE_MISSING = "response.contentType.missing";
    public static final String RESPONSE_CONTENT_TYPE_INVALID = "response.contentType.invalid";
    public static final String RESPONSE_CONTENT_TYPE_NOT_ALLOWED = "response.contentType.notAllowed";

    private IssueKeys() {
    }

    /**
     * The HTTP status a failed request validation should be answered with, based on the first
     * error key: 404 for unknown paths, 405 for unsupported methods, 401 for missing
     * credentials, 415 and 406 for content negotiation and 400 otherwise.
     */
    public static int status(String key) {
        return switch (key) {
            case REQUEST_PATH_MISSING -> 404;
            case REQUEST_OPERATION_NOT_ALLOWED -> 405;
            case REQUEST_SECURITY_MISSING -> 401;
            case REQUEST_CONTENT_TYPE_NOT_ALLOWED -> 415;
            case REQUEST_ACCEPT_NOT_ACCEPTABLE -> 406;
            default -> 400;
        };
    }
}
