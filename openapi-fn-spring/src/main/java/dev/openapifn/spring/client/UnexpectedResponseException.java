package dev.openapifn.spring.client;

/** A response whose status the operation does not describe. */
public class UnexpectedResponseException extends RuntimeException {

    private final transient ClientResponse response;

    public UnexpectedResponseException(ClientResponse response) {
        super("Undescribed response status " + response.status());
        this.response = response;
    }

    public ClientResponse response() {
        return response;
    }
}
