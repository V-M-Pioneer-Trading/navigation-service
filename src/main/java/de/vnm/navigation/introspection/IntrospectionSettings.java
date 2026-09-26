package de.vnm.navigation.introspection;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Where the center is and how this service proves itself to it, validated once at startup.
 *
 * <p>Both values are required and there is no auth-optional mode. A service that started
 * without them would answer 503 to every credentialed request and look like an auth outage;
 * a container that refuses to start with one of these messages is diagnosed from one log
 * line. No message ever contains the secret, and none echoes the URL beyond its scheme.
 */
public final class IntrospectionSettings {

    /** The environment variable holding the full endpoint URL. */
    public static final String ENV_URL = "AUTH_INTROSPECTION_URL";

    /** The environment variable holding the caller secret. Never the vault's shared secret. */
    public static final String ENV_SECRET = "AUTH_INTROSPECTION_SECRET";

    /** The header the caller secret travels in, as the fixture's contract spells it. */
    public static final String SECRET_HEADER = "X-Introspection-Secret";

    private final URI endpoint;
    private final String secret;

    private IntrospectionSettings(URI endpoint, String secret) {
        this.endpoint = endpoint;
        this.secret = secret;
    }

    /**
     * Validates the two raw values.
     *
     * @throws IllegalStateException naming the variable at fault, never its secret value
     */
    public static IntrospectionSettings of(String url, String secret) {
        if (url == null || url.isBlank()) {
            throw new IllegalStateException(ENV_URL + " is required — refusing to start without it");
        }
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(ENV_SECRET + " is required — refusing to start without it");
        }
        if (!secret.equals(secret.strip()) || secret.chars().anyMatch(c -> c < 0x20 || c > 0x7e)) {
            // Printable ASCII only. Surrounding whitespace is stripped on the wire, so the
            // center would see a different secret; a control character is not a header value;
            // and java.net.http refuses anything above U+00FF with an exception whose message
            // quotes the whole header value — the secret — on every request. Refused here,
            // once, without echoing it.
            throw new IllegalStateException(
                    ENV_SECRET + " must be printable ASCII with no surrounding whitespace");
        }
        return new IntrospectionSettings(endpointFrom(url), secret);
    }

    private static URI endpointFrom(String url) {
        URI endpoint;
        try {
            endpoint = new URI(url);
        } catch (URISyntaxException e) {
            throw new IllegalStateException(
                    ENV_URL + " must be an absolute URL, for example http://localhost:3005/auth/v1/introspect");
        }
        if (!endpoint.isAbsolute() || endpoint.getHost() == null) {
            throw new IllegalStateException(
                    ENV_URL + " must be an absolute URL, for example http://localhost:3005/auth/v1/introspect");
        }
        String scheme = endpoint.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            // The scheme is the useful half of the diagnosis and carries nothing sensitive.
            throw new IllegalStateException(ENV_URL + " must use http or https, not \"" + scheme + "\"");
        }
        if (endpoint.getRawUserInfo() != null) {
            throw new IllegalStateException(
                    ENV_URL + " must not carry credentials; the secret travels in " + ENV_SECRET);
        }
        if (endpoint.getRawQuery() != null || endpoint.getRawFragment() != null) {
            // A token never goes in a URL, and neither does anything else: an endpoint URL
            // carrying a query is a sign the secret was put there.
            throw new IllegalStateException(
                    ENV_URL + " must be a plain endpoint URL with no query string or fragment");
        }
        return endpoint;
    }

    /**
     * The <b>full</b> introspection endpoint, {@code /auth/v1/introspect} included, POSTed
     * to verbatim: never joined, suffixed or taken apart (token-introspection.md,
     * "Conformance").
     */
    public URI endpoint() {
        return endpoint;
    }

    /** Sent as {@value #SECRET_HEADER}; never logged. */
    public String secret() {
        return secret;
    }

    /** Never prints the secret, so a settings object in a log or a debugger stays harmless. */
    @Override
    public String toString() {
        return "IntrospectionSettings[endpoint=" + endpoint + ", secret=(redacted)]";
    }
}
