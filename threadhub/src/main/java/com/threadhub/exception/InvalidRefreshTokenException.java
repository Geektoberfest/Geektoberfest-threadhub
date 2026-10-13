package com.threadhub.exception;

/**
 * Thrown when a refresh token is unknown, malformed, expired, revoked or already consumed. The
 * message is deliberately non-specific so responses do not disclose which of those applies.
 */
public class InvalidRefreshTokenException extends RuntimeException {

    public InvalidRefreshTokenException(String message) {
        super(message);
    }
}
