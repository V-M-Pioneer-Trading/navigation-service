package de.vnm.navigation.auth;

/**
 * A presented token did not verify. The cause is deliberately not carried in a form
 * that reaches the wire: "expired" vs "bad signature" vs "wrong issuer" is a probing
 * oracle, and the remedy for the caller is the same.
 */
public class InvalidSessionException extends Exception {

    public InvalidSessionException(String reason, Throwable cause) {
        super(reason, cause);
    }

    public InvalidSessionException(String reason) {
        super(reason);
    }
}
