package de.vnm.navigation.introspection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The one HTTP call to auth-service: {@code POST <AUTH_INTROSPECTION_URL>} with
 * {@code token=<token>} as a form body and the caller secret in
 * {@value IntrospectionSettings#SECRET_HEADER}.
 *
 * <p>One request per call, a 1 s budget for the whole exchange, <b>zero retries</b> and
 * <b>no cache</b> — all four are the fixture's contract. A retry against a center that is
 * down doubles the latency of every failing request and changes nothing; a cache would be a
 * second verification path with a different answer, and would make revocation mean nothing
 * for its lifetime.
 *
 * <p>Every way of failing collapses to {@link CenterAnswer#UNAVAILABLE}, logged as one line
 * naming the failure class. The token is never logged, parsed or inspected — it is an
 * opaque string URL-encoded into a form body, never into a URL, where an access log would
 * keep it. The secret is never logged either.
 *
 * <p>This is the one exception to "only {@code client} performs HTTP": the center is not an
 * upstream this service relays, it is how this service learns who is calling.
 */
public final class IntrospectionClient implements Introspector, AutoCloseable {

    /**
     * The contract's {@code clientTimeoutMs}. It covers the whole exchange, body included: a
     * center that answers instantly and then dribbles bytes forever is as unavailable as one
     * that never answers.
     */
    public static final Duration TIMEOUT = Duration.ofMillis(1000);

    /**
     * A real answer is about 150 bytes. One larger than this is a center we do not
     * understand, and reading the rest of it only spends memory on the way to the same 503.
     */
    public static final int MAX_RESPONSE_BYTES = 64 * 1024;

    private static final Logger log = LoggerFactory.getLogger(IntrospectionClient.class);

    private final IntrospectionSettings settings;
    private final HttpClient http;

    public IntrospectionClient(IntrospectionSettings settings) {
        this.settings = settings;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(TIMEOUT)
                // A redirect would carry our caller secret to whatever host the Location
                // header named. A 3xx is simply not a 2xx.
                .followRedirects(HttpClient.Redirect.NEVER)
                // The token and our secret go to the endpoint we were given and nowhere else,
                // whatever the JVM's proxy properties happen to say.
                .proxy(HttpClient.Builder.NO_PROXY)
                .build();
    }

    @Override
    public CenterAnswer introspect(String token) {
        CompletableFuture<HttpResponse<Optional<byte[]>>> exchange = null;
        HttpResponse<Optional<byte[]>> response;
        try {
            // Built inside the try: java.net.http validates headers here and its exceptions
            // quote the offending header value. Nothing thrown may escape this method, or
            // Spring's exception handling would log it and put it in a response body.
            HttpRequest request = HttpRequest.newBuilder(settings.endpoint())
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Accept", "application/json")
                    .header(IntrospectionSettings.SECRET_HEADER, settings.secret())
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "token=" + URLEncoder.encode(token, StandardCharsets.UTF_8)))
                    .build();
            exchange = http.sendAsync(request, IntrospectionClient::cappedBody);
            response = exchange.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            exchange.cancel(true);
            log.warn("introspection: the center did not answer within {} ms", TIMEOUT.toMillis());
            return CenterAnswer.UNAVAILABLE;
        } catch (RuntimeException e) {
            // The class only: a message from request building can quote the secret header.
            log.warn("introspection: the request to the center could not be made ({})", e.getClass().getName());
            return CenterAnswer.UNAVAILABLE;
        } catch (InterruptedException e) {
            if (exchange != null) {
                exchange.cancel(true);
            }
            Thread.currentThread().interrupt();
            log.warn("introspection: interrupted while asking the center");
            return CenterAnswer.UNAVAILABLE;
        } catch (ExecutionException e) {
            // The cause names the transport failure (and the endpoint, which the settings
            // guarantee carries no credential). Neither the body nor the headers are in it.
            log.warn("introspection: the center is unreachable: {}", e.getCause().toString());
            return CenterAnswer.UNAVAILABLE;
        }

        int status = response.statusCode();
        if (status < 200 || status > 299) {
            // Includes the center's 401 about OUR secret: relaying it as a 401 would send an
            // operator to sign in again, forever, against a service that cannot accept them.
            log.warn("introspection: the center answered {}", status);
            return CenterAnswer.UNAVAILABLE;
        }
        Optional<byte[]> body = response.body();
        if (body.isEmpty()) {
            log.warn("introspection: the center's answer exceeds {} bytes", MAX_RESPONSE_BYTES);
            return CenterAnswer.UNAVAILABLE;
        }
        try {
            return CenterAnswerParser.parse(body.get());
        } catch (CenterAnswerParser.NotTheContract e) {
            // The body is not logged: a proxy's error page is not ours to copy into a log,
            // and nothing in it helps the operator more than this does.
            log.warn("introspection: the center's answer is not the contract: {}", e.getMessage());
            return CenterAnswer.UNAVAILABLE;
        }
    }

    @Override
    public void close() {
        http.close();
    }

    /**
     * Reads a 2xx body up to {@link #MAX_RESPONSE_BYTES}, then gives up on it (empty); a
     * non-2xx body is never read at all.
     */
    private static HttpResponse.BodySubscriber<Optional<byte[]>> cappedBody(HttpResponse.ResponseInfo info) {
        if (info.statusCode() < 200 || info.statusCode() > 299) {
            return HttpResponse.BodySubscribers.replacing(Optional.empty());
        }
        return new CappedBodySubscriber(MAX_RESPONSE_BYTES);
    }

    /** Collects at most {@code limit} bytes; one byte more cancels the stream and yields empty. */
    private static final class CappedBodySubscriber implements HttpResponse.BodySubscriber<Optional<byte[]>> {

        private final int limit;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private final CompletableFuture<Optional<byte[]>> result = new CompletableFuture<>();
        private Flow.Subscription subscription;

        CappedBodySubscriber(int limit) {
            this.limit = limit;
        }

        @Override
        public CompletionStage<Optional<byte[]>> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            if (result.isDone()) {
                return;
            }
            for (ByteBuffer item : items) {
                if (buffer.size() + item.remaining() > limit) {
                    subscription.cancel();
                    result.complete(Optional.empty());
                    return;
                }
                byte[] bytes = new byte[item.remaining()];
                item.get(bytes);
                buffer.writeBytes(bytes);
            }
        }

        @Override
        public void onError(Throwable throwable) {
            result.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            result.complete(Optional.of(buffer.toByteArray()));
        }
    }
}
