package de.vnm.navigation.introspection;

import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The wire, beyond what the fixture pins: the body cap, redirects, encoding and the exact
 * request. The fixture's own center conditions are in {@link IntrospectionConformanceTest}.
 */
class IntrospectionClientTest {

    private static final String SECRET = "client-test-secret";
    private static final String ACTIVE =
            "{\"active\":true,\"sub\":\"user_1\",\"scope\":\"universe:refresh\",\"exp\":4102444800,\"kind\":\"operator\"";

    @Test
    void postsTheTokenAsAFormBodyToTheEndpointVerbatim() {
        try (StubCenter center = StubCenter.answering(StubCenter.Reply.of(200, ACTIVE + "}"));
             IntrospectionClient client = clientFor(center)) {

            String token = "a+b/c=d&e f%";
            assertThat(client.introspect(token)).isInstanceOf(CenterAnswer.Active.class);

            List<StubCenter.Received> received = center.received();
            assertThat(received).hasSize(1);
            StubCenter.Received request = received.get(0);
            assertThat(request.method()).isEqualTo("POST");
            assertThat(request.requestUri()).isEqualTo(StubCenter.PATH);
            assertThat(request.rawQuery()).isNull();
            assertThat(request.header("Content-Type")).isEqualTo("application/x-www-form-urlencoded");
            assertThat(request.header("X-Introspection-Secret")).isEqualTo(SECRET);
            assertThat(request.body()).isEqualTo("token=" + URLEncoder.encode(token, StandardCharsets.UTF_8));
            assertThat(request.body()).isEqualTo("token=a%2Bb%2Fc%3Dd%26e+f%25");
        }
    }

    @Test
    void anAnswerExactlyAtTheCapIsRead() {
        String body = padded(IntrospectionClient.MAX_RESPONSE_BYTES);
        try (StubCenter center = StubCenter.answering(StubCenter.Reply.of(200, body));
             IntrospectionClient client = clientFor(center)) {
            assertThat(client.introspect("t")).isInstanceOf(CenterAnswer.Active.class);
        }
    }

    @Test
    void anAnswerOneByteOverTheCapIsUnavailable() {
        String body = padded(IntrospectionClient.MAX_RESPONSE_BYTES + 1);
        try (StubCenter center = StubCenter.answering(StubCenter.Reply.of(200, body));
             IntrospectionClient client = clientFor(center)) {
            assertThat(client.introspect("t")).isEqualTo(CenterAnswer.UNAVAILABLE);
            assertThat(center.calls()).isEqualTo(1);
        }
    }

    /** A redirect would carry the caller secret wherever {@code Location} pointed. */
    @Test
    void aRedirectIsNotFollowed() {
        try (StubCenter elsewhere = StubCenter.answering(StubCenter.Reply.of(200, ACTIVE + "}"));
             StubCenter center = StubCenter.answering(StubCenter.Reply.redirectTo(elsewhere.url()));
             IntrospectionClient client = clientFor(center)) {
            assertThat(client.introspect("t")).isEqualTo(CenterAnswer.UNAVAILABLE);
            assertThat(center.calls()).isEqualTo(1);
            assertThat(elsewhere.calls()).isZero();
        }
    }

    @Test
    void anyTwoHundredIsAnAnswer() {
        try (StubCenter center = StubCenter.answering(StubCenter.Reply.of(203, ACTIVE + "}"));
             IntrospectionClient client = clientFor(center)) {
            assertThat(client.introspect("t")).isInstanceOf(CenterAnswer.Active.class);
        }
    }

    @Test
    void aRefusedConnectionIsUnavailable() {
        StubCenter gone = StubCenter.answering(StubCenter.Reply.inactive());
        String url = gone.url();
        gone.close();
        try (IntrospectionClient client = new IntrospectionClient(IntrospectionSettings.of(url, SECRET))) {
            assertThat(client.introspect("t")).isEqualTo(CenterAnswer.UNAVAILABLE);
        }
    }

    /** An active answer padded with an extension member to exactly {@code size} bytes. */
    private static String padded(int size) {
        String head = ACTIVE + ",\"pad\":\"";
        String tail = "\"}";
        return head + "x".repeat(size - head.length() - tail.length()) + tail;
    }

    private static IntrospectionClient clientFor(StubCenter center) {
        return new IntrospectionClient(IntrospectionSettings.of(center.url(), SECRET));
    }
}
