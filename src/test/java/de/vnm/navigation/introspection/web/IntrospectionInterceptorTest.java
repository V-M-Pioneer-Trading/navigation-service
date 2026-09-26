package de.vnm.navigation.introspection.web;

import de.vnm.navigation.introspection.AccessPolicy;
import de.vnm.navigation.introspection.CenterAnswer;
import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.HttpRequestHandler;
import org.springframework.web.servlet.resource.ResourceHttpRequestHandler;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The branches of the interceptor the fixture and the routing tests do not reach: handler
 * types that are not handler methods, and dispatches that continue an already-decided
 * request. The center here only counts, so every assertion on it is about whether it was
 * asked at all.
 */
class IntrospectionInterceptorTest {

    private final AtomicInteger centerCalls = new AtomicInteger();
    private final IntrospectionInterceptor interceptor = new IntrospectionInterceptor(new AccessPolicy(token -> {
        centerCalls.incrementAndGet();
        return CenterAnswer.UNAVAILABLE;
    }));

    /** An unknown handler type is undeclared: a 500 on every method, GET included. */
    @ParameterizedTest
    @ValueSource(strings = {"GET", "HEAD", "OPTIONS", "POST"})
    void aHandlerTypeNothingDeclares_isNeverServed(String method) throws Exception {
        HttpRequestHandler unknown = (request, response) -> {};
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request(method), response, unknown)).isFalse();
        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getContentAsString()).isEqualTo("{\"error\":{\"message\":\"this route declares no required scope\"}}");
        assertThat(centerCalls).hasValue(0);
    }

    @Test
    void staticResources_ignoreCredentials_onSafeMethods() throws Exception {
        MockHttpServletRequest request = request("GET");
        request.addHeader("Authorization", "Bearer never.read");

        assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), new ResourceHttpRequestHandler())).isTrue();
        assertThat(centerCalls).hasValue(0);
    }

    @Test
    void staticResources_refuseAMutation() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request("POST"), response, new ResourceHttpRequestHandler())).isFalse();
        assertThat(response.getStatus()).isEqualTo(500);
    }

    /**
     * An error or async dispatch continues a request that was decided on its way in.
     * Re-deciding it would answer 500 for the error page of a 404.
     */
    @ParameterizedTest
    @EnumSource(value = DispatcherType.class, names = {"ERROR", "ASYNC"})
    void aContinuationDispatch_isNotReDecided(DispatcherType dispatch) throws Exception {
        MockHttpServletRequest request = request("POST");
        request.setDispatcherType(dispatch);
        HttpRequestHandler unknown = (req, res) -> {};

        assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), unknown)).isTrue();
    }

    private static MockHttpServletRequest request(String method) {
        return new MockHttpServletRequest(method, "/anything");
    }
}
