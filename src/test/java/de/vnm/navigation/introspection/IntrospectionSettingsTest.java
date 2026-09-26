package de.vnm.navigation.introspection;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** There is no auth-optional mode: both values are required, and neither is ever echoed. */
class IntrospectionSettingsTest {

    private static final String URL = "http://localhost:3005/auth/v1/introspect";
    private static final String SECRET = "s3cr3t-value-never-echoed";

    @Test
    void theUrlIsKeptVerbatim() {
        assertThat(IntrospectionSettings.of(URL, SECRET).endpoint()).hasToString(URL);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void refusesToStartWithoutTheUrl(String url) {
        assertThatThrownBy(() -> IntrospectionSettings.of(url, SECRET))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AUTH_INTROSPECTION_URL is required");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void refusesToStartWithoutTheSecret(String secret) {
        assertThatThrownBy(() -> IntrospectionSettings.of(URL, secret))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AUTH_INTROSPECTION_SECRET is required");
    }

    @ParameterizedTest
    @ValueSource(strings = {" " + SECRET, SECRET + "\n", SECRET + "\u0000x"})
    void refusesASecretTheWireWouldAlter(String secret) {
        assertThatThrownBy(() -> IntrospectionSettings.of(URL, secret))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AUTH_INTROSPECTION_SECRET")
                .hasMessageNotContaining(SECRET);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "localhost:3005/auth/v1/introspect",
            "/auth/v1/introspect",
            "ftp://localhost/auth/v1/introspect",
            "http://user:" + SECRET + "@localhost:3005/auth/v1/introspect",
            "http://localhost:3005/auth/v1/introspect?secret=" + SECRET,
            "http://localhost:3005/auth/v1/introspect#frag",
            "http://local host/auth",
    })
    void refusesAnythingButAPlainAbsoluteHttpEndpoint(String url) {
        assertThatThrownBy(() -> IntrospectionSettings.of(url, SECRET))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AUTH_INTROSPECTION_URL")
                .hasMessageNotContaining(SECRET);
    }

    @Test
    void neverPrintsTheSecret() {
        assertThat(IntrospectionSettings.of(URL, SECRET).toString()).doesNotContain(SECRET);
    }
}
