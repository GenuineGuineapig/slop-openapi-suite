package dev.openapifn.model;

/**
 * An operation of a callback.
 *
 * @param owner the operation that declares the callback
 * @param name the callback name
 * @param operation the callback operation; its path is the runtime expression of the URL, such as
 *     {@code {$request.body#/callbackUrl}}
 */
public record CallbackOperation(ApiOperation owner, String name, ApiOperation operation) {
}
