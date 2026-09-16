/*
 * Copyright © 2015 The Gravitee team (http://gravitee.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gravitee.connector.http;

import static io.gravitee.common.http.HttpHeaders.ACCEPT_ENCODING;
import static io.gravitee.common.http.HttpHeaders.CONTENT_LENGTH;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.gravitee.common.component.Lifecycle;
import io.gravitee.common.http.HttpMethod;
import io.gravitee.connector.api.Response;
import io.gravitee.connector.http.endpoint.HttpClientOptions;
import io.gravitee.connector.http.endpoint.HttpEndpoint;
import io.gravitee.connector.http.stub.DummyHttpClientRequest;
import io.gravitee.gateway.api.ExecutionContext;
import io.gravitee.gateway.api.handler.Handler;
import io.gravitee.gateway.api.http.HttpHeaderNames;
import io.gravitee.gateway.api.http.HttpHeaders;
import io.gravitee.gateway.api.proxy.ProxyRequest;
import io.gravitee.gateway.reactive.api.tracing.Tracer;
import io.gravitee.node.api.opentelemetry.Span;
import io.gravitee.node.api.opentelemetry.http.ObservableHttpClientRequest;
import io.gravitee.node.opentelemetry.tracer.OpenTelemetryTracer;
import io.gravitee.node.opentelemetry.tracer.noop.NoOpTracer;
import io.gravitee.reporter.api.http.Metrics;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpClosedException;
import io.vertx.core.http.HttpVersion;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * @author Yann TAVERNIER (yann.tavernier at graviteesource.com)
 * @author GraviteeSource Team
 */
@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
public class HttpConnectionTest {

    public static final String FIRST_HEADER = "First-Header";
    public static final String SECOND_HEADER = "Second-Header";
    public static final String FIRST_HEADER_VALUE_1 = "first-header-value-1";
    public static final String FIRST_HEADER_VALUE_2 = "first-header-value-2";
    public static final String SECOND_HEADER_VALUE = "second-header-value";
    public static final String TRACEPARENT_HEADER = "traceparent";
    public static final String TRACEPARENT_HEADER_VALUE = "traceparent-value";
    protected static final String BROTLI = "br";

    private static final int CLOSED_RESPONSE_DRAIN_MAX_CHECKS = 50;
    private static final long CLOSED_RESPONSE_DRAIN_CHECK_DELAY_MS = 15;
    private static final long CLOSED_RESPONSE_DRAIN_CAP_MS = CLOSED_RESPONSE_DRAIN_MAX_CHECKS * CLOSED_RESPONSE_DRAIN_CHECK_DELAY_MS;
    private static final long UPSTREAM_CHUNK_FEED_INTERVAL_MS = 5;
    private static final long EXCHANGE_TIMEOUT_SECONDS = 10;
    private static final int LOCAL_UPSTREAM_BUFFER_CAP = 16 * 1024;

    private HttpConnection<HttpResponse> cut;

    @Mock
    private ExecutionContext context;

    @Mock
    private HttpEndpoint endpoint;

    @Mock
    private ProxyRequest request;

    @Mock
    private HttpClient client;

    @Spy
    private HttpClientRequest httpClientRequest = new DummyHttpClientRequest();

    private HttpHeaders headers;
    private HttpClientOptions httpClientOptions;

    @BeforeEach
    public void setUp() {
        cut = new HttpConnection<>(endpoint, request);

        headers = HttpHeaders.create();
        headers.add(FIRST_HEADER, FIRST_HEADER_VALUE_1);
        headers.add(FIRST_HEADER, FIRST_HEADER_VALUE_2);
        headers.add(SECOND_HEADER, SECOND_HEADER_VALUE);
        headers.add(HttpHeaderNames.TRANSFER_ENCODING, "transfer_encoding");

        when(request.headers()).thenReturn(headers);
        when(request.method()).thenReturn(HttpMethod.GET);

        httpClientOptions = new HttpClientOptions();
        when(endpoint.getHttpClientOptions()).thenReturn(httpClientOptions);
        when(client.request(any())).thenReturn(Future.succeededFuture(httpClientRequest));
        when(context.getTracer()).thenReturn(new Tracer(null, new NoOpTracer()));
        when(request.uri()).thenReturn("http://host.fr");
    }

    @Test
    public void should_set_metrics_message_with_vertx_connection_exception() {
        final Metrics requestMetrics = Metrics.on(System.currentTimeMillis()).build();
        requestMetrics.setApi("api-id");
        requestMetrics.setRequestId("request-id");
        when(request.metrics()).thenReturn(requestMetrics);

        cut.connect(context, client, getAvailablePort(), "host", "/", unused -> {}, result -> new AtomicInteger(1).decrementAndGet());
        // Rely on testing class ThrowingOnGoAwayHttpConnection to make the connection fail and trigger the exceptionHandler we want to test
        httpClientRequest.connection().goAway(204, 1, Buffer.buffer("💥 Connection error"));

        assertThat(requestMetrics.getMessage()).isEqualTo("💥 Connection error");
    }

    @Test
    public void should_notify_tracker_once_when_connection_is_canceled() {
        final AtomicInteger trackerCalls = new AtomicInteger();

        cut.connect(context, client, getAvailablePort(), "host", "/", unused -> {}, result -> trackerCalls.incrementAndGet());

        assertThat(trackerCalls.get()).isEqualTo(0);

        cut.cancel();

        assertThat(trackerCalls.get()).isEqualTo(1);

        cut.cancel();

        assertThat(trackerCalls.get()).isEqualTo(1);
    }

    @Test
    public void should_write_upstream_headers() {
        cut.connect(context, client, getAvailablePort(), "host", "/", unused -> {}, result -> new AtomicInteger(1).decrementAndGet());

        cut.writeUpstreamHeaders();

        assertThat(headers.contains(io.vertx.core.http.HttpHeaders.TRANSFER_ENCODING)).isFalse();
        assertThat(httpClientRequest.headers().getAll(SECOND_HEADER)).hasSize(1).containsExactly(SECOND_HEADER_VALUE);

        assertThat(httpClientRequest.headers().getAll(FIRST_HEADER)).hasSize(2).containsExactly(FIRST_HEADER_VALUE_1, FIRST_HEADER_VALUE_2);
    }

    @Test
    public void should_write_upstream_headers_with_tracing_headers() {
        when(context.getTracer()).thenReturn(new Tracer(null, new DummyTracer()));

        cut.connect(context, client, getAvailablePort(), "host", "/", unused -> {}, result -> new AtomicInteger(1).decrementAndGet());

        cut.writeUpstreamHeaders();

        assertThat(headers.contains(io.vertx.core.http.HttpHeaders.TRANSFER_ENCODING)).isFalse();
        assertThat(httpClientRequest.headers().getAll(SECOND_HEADER)).hasSize(1).containsExactly(SECOND_HEADER_VALUE);

        assertThat(httpClientRequest.headers().getAll(FIRST_HEADER)).hasSize(2).containsExactly(FIRST_HEADER_VALUE_1, FIRST_HEADER_VALUE_2);

        assertThat(httpClientRequest.headers().getAll(TRACEPARENT_HEADER)).hasSize(1).containsExactly(TRACEPARENT_HEADER_VALUE);
    }

    @Test
    public void should_replace_existing_traceparent_header_when_tracing_is_enabled() {
        when(context.getTracer()).thenReturn(new Tracer(null, new DummyTracer()));

        headers.set(TRACEPARENT_HEADER, "00-existing-trace-id-existing-span-id-01");

        cut.connect(context, client, getAvailablePort(), "host", "/", unused -> {}, result -> new AtomicInteger(1).decrementAndGet());

        cut.writeUpstreamHeaders();

        assertThat(httpClientRequest.headers().getAll(TRACEPARENT_HEADER)).hasSize(1).containsExactly(TRACEPARENT_HEADER_VALUE);
    }

    @Test
    public void should_write() {
        cut.connect(context, client, getAvailablePort(), "host", "/", unused -> {}, result -> new AtomicInteger(1).decrementAndGet());
        assertThat(httpClientRequest.headers().get(CONTENT_LENGTH)).isNull();
        cut.write(io.gravitee.gateway.api.buffer.Buffer.buffer());
        assertThat(httpClientRequest.headers().get(CONTENT_LENGTH)).isEqualTo("0");
    }

    @Test
    public void should_propagate_client_accept_encoding_header() {
        httpClientOptions.setUseCompression(false);
        httpClientOptions.setPropagateClientAcceptEncoding(true);

        headers.set(ACCEPT_ENCODING, BROTLI);
        cut.connect(context, client, getAvailablePort(), "host", "/", unused -> {}, result -> new AtomicInteger(1).decrementAndGet());

        cut.writeUpstreamHeaders();

        assertThat(httpClientRequest.headers().getAll(ACCEPT_ENCODING)).hasSize(1).containsExactly(BROTLI);
    }

    @Test
    public void should_not_propagate_client_accept_encoding_header_when_no_header() {
        httpClientOptions.setUseCompression(false);
        httpClientOptions.setPropagateClientAcceptEncoding(true);

        cut.connect(context, client, getAvailablePort(), "host", "/", unused -> {}, result -> new AtomicInteger(1).decrementAndGet());

        cut.writeUpstreamHeaders();

        assertThat(httpClientRequest.headers().getAll(ACCEPT_ENCODING)).hasSize(0);
    }

    @Test
    public void should_not_propagate_client_accept_encoding_header_when_compression_is_enabled() {
        httpClientOptions.setUseCompression(true);
        httpClientOptions.setPropagateClientAcceptEncoding(true);

        headers.set(ACCEPT_ENCODING, BROTLI);
        cut.connect(context, client, getAvailablePort(), "host", "/", unused -> {}, result -> new AtomicInteger(1).decrementAndGet());

        cut.writeUpstreamHeaders();

        assertThat(httpClientRequest.headers().getAll(ACCEPT_ENCODING)).hasSize(0);
    }

    @Test
    public void should_not_propagate_client_accept_encoding_header_when_propagate_is_disabled() {
        httpClientOptions.setUseCompression(false);
        httpClientOptions.setPropagateClientAcceptEncoding(false);

        headers.set(ACCEPT_ENCODING, BROTLI);
        cut.connect(context, client, getAvailablePort(), "host", "/", unused -> {}, result -> new AtomicInteger(1).decrementAndGet());

        cut.writeUpstreamHeaders();

        assertThat(httpClientRequest.headers().getAll(ACCEPT_ENCODING)).hasSize(0);
    }

    @Test
    public void should_rewrite_server_null_in_timeout_error_message() {
        final Metrics requestMetrics = Metrics.on(System.currentTimeMillis()).build();
        requestMetrics.setApi("api-id");
        requestMetrics.setRequestId("request-id");
        when(request.metrics()).thenReturn(requestMetrics);

        int port = getAvailablePort();
        cut.connect(context, client, port, "example.com", "/", unused -> {}, result -> new AtomicInteger(1).decrementAndGet());

        // Simulate timeout exception with "for server null" in the message
        when(client.request(any())).thenReturn(
            Future.failedFuture(
                new java.util.concurrent.TimeoutException(
                    "The timeout period of 10000ms has been exceeded while executing GET /late for server null"
                )
            )
        );

        cut.connect(context, client, port, "example.com", "/late", unused -> {}, result -> new AtomicInteger(1).decrementAndGet());

        assertThat(requestMetrics.getMessage()).isEqualTo(
            "The timeout period of 10000ms has been exceeded while executing GET /late for server example.com:" + port
        );
    }

    @Test
    public void should_notify_the_response_end_handler_and_the_tracker_once_when_the_upstream_response_ends() {
        var endHandlerCalls = new AtomicInteger();
        var trackerCalls = new AtomicInteger();
        var clientResponse = givenMockedUpstreamResponse();

        whenUpstreamResponseIsHandled(clientResponse, endHandlerCalls, trackerCalls);
        captureEndHandler(clientResponse).handle(null);

        assertThat(endHandlerCalls.get()).isEqualTo(1);
        assertThat(trackerCalls.get()).isEqualTo(1);
    }

    @Test
    public void should_not_request_chunks_one_at_a_time_for_a_non_keep_alive_http2_endpoint() {
        httpClientOptions.setKeepAlive(false);
        when(httpClientRequest.version()).thenReturn(HttpVersion.HTTP_2);

        var deliveredBody = new StringBuilder();
        var clientResponse = givenMockedUpstreamResponse();

        whenUpstreamResponseIsHandled(
            clientResponse,
            response -> response.bodyHandler(chunk -> deliveredBody.append(chunk.toString())),
            unused -> {}
        );

        captureChunkHandler(clientResponse).handle(Buffer.buffer("chunk"));

        assertThat(deliveredBody.toString()).isEqualTo("chunk");
        verify(clientResponse, never()).fetch(anyLong());
    }

    @Test
    public void should_discard_chunks_past_the_local_buffer_cap_when_no_downstream_body_handler_is_attached() {
        httpClientOptions.setKeepAlive(false);
        when(httpClientRequest.version()).thenReturn(HttpVersion.HTTP_1_1);
        var deliveredBytes = new AtomicInteger();
        var capturedResponse = new AtomicReference<Response>();
        var clientResponse = givenMockedUpstreamResponse();

        whenUpstreamResponseIsHandled(clientResponse, capturedResponse::set, unused -> {});

        var chunkHandler = captureChunkHandler(clientResponse);
        byte[] chunk = new byte[1024];
        for (int i = 0; i < LOCAL_UPSTREAM_BUFFER_CAP / chunk.length + 4; i++) {
            chunkHandler.handle(Buffer.buffer(chunk));
        }

        capturedResponse.get().bodyHandler(delivered -> deliveredBytes.addAndGet(delivered.length()));
        chunkHandler.handle(Buffer.buffer(chunk));

        assertThat(deliveredBytes.get()).isEqualTo(LOCAL_UPSTREAM_BUFFER_CAP + chunk.length);
    }

    @Test
    public void should_discard_chunks_without_failing_when_no_downstream_body_handler_is_registered_on_a_keep_alive_endpoint() {
        httpClientOptions.setKeepAlive(true);
        var deliveredBody = new StringBuilder();
        var capturedResponse = new AtomicReference<Response>();
        var clientResponse = givenMockedUpstreamResponse();

        whenUpstreamResponseIsHandled(clientResponse, capturedResponse::set, unused -> {});

        var chunkHandler = captureChunkHandler(clientResponse);
        assertThatNoException().isThrownBy(() -> {
            chunkHandler.handle(Buffer.buffer("dropped-1"));
            chunkHandler.handle(Buffer.buffer("dropped-2"));
        });

        capturedResponse.get().bodyHandler(delivered -> deliveredBody.append(delivered.toString()));
        chunkHandler.handle(Buffer.buffer("delivered"));

        assertThat(deliveredBody.toString()).isEqualTo("delivered");
    }

    static Stream<Arguments> failuresLeavingNothingToDrain() {
        return Stream.of(
            Arguments.of("unparsable upstream response framing", new RuntimeException("unparsable upstream response framing")),
            Arguments.of("connection closed outside a vertx context", new HttpClosedException("connection was closed"))
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("failuresLeavingNothingToDrain")
    public void should_end_the_exchange_without_waiting_for_a_drain_when_the_upstream_response_fails(
        String failure,
        Throwable upstreamFailure
    ) {
        var endHandlerCalls = new AtomicInteger();
        var trackerCalls = new AtomicInteger();
        var clientResponse = givenMockedUpstreamResponse();

        whenUpstreamResponseIsHandled(clientResponse, endHandlerCalls, trackerCalls);
        captureExceptionHandler(clientResponse).handle(upstreamFailure);

        assertThat(endHandlerCalls.get()).isEqualTo(1);
        assertThat(trackerCalls.get()).isEqualTo(1);
        verify(clientResponse, never()).resume();
    }

    static Stream<Arguments> terminationsAfterCancel() {
        return Stream.of(
            Arguments.of(
                "upstream response ends",
                (Consumer<HttpClientResponse>) clientResponse -> captureEndHandler(clientResponse).handle(null)
            ),
            Arguments.of(
                "upstream connection closes",
                (Consumer<HttpClientResponse>) clientResponse ->
                    captureExceptionHandler(clientResponse).handle(new HttpClosedException("connection was closed"))
            )
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("terminationsAfterCancel")
    public void should_notify_the_tracker_once_when_the_exchange_terminates_after_the_connection_was_canceled(
        String termination,
        Consumer<HttpClientResponse> terminate
    ) {
        var endHandlerCalls = new AtomicInteger();
        var trackerCalls = new AtomicInteger();
        var clientResponse = givenMockedUpstreamResponse();

        whenUpstreamResponseIsHandled(clientResponse, endHandlerCalls, trackerCalls);
        cut.cancel();
        terminate.accept(clientResponse);

        assertThat(trackerCalls.get()).isEqualTo(1);
        assertThat(endHandlerCalls.get()).isEqualTo(0);
    }

    @Nested
    class WhenTheUpstreamConnectionClosesInsideAVertxContext {

        private Vertx vertx;

        @BeforeEach
        public void startVertx() {
            vertx = Vertx.vertx();
        }

        @AfterEach
        public void closeVertx() throws Exception {
            vertx.close().toCompletionStage().toCompletableFuture().get(EXCHANGE_TIMEOUT_SECONDS, SECONDS);
        }

        @Test
        public void should_end_the_exchange_once_after_the_drain_check_cap_is_exhausted() throws Exception {
            var endHandlerCalls = new AtomicInteger();
            var trackerCalls = new AtomicInteger();
            var deliveredChunks = new AtomicInteger();
            var deliveredChunksWhenEnded = new AtomicInteger();
            var chunkFeedTimerId = new AtomicLong();
            CompletableFuture<Long> endedAt = new CompletableFuture<>();
            var clientResponse = givenMockedUpstreamResponse();

            whenUpstreamResponseIsHandled(
                clientResponse,
                response -> {
                    response.bodyHandler(chunk -> deliveredChunks.incrementAndGet());
                    response.endHandler(end -> {
                        endHandlerCalls.incrementAndGet();
                        deliveredChunksWhenEnded.set(deliveredChunks.get());
                    });
                },
                unused -> {
                    trackerCalls.incrementAndGet();
                    endedAt.complete(System.currentTimeMillis());
                }
            );

            var chunkHandler = captureChunkHandler(clientResponse);
            var exceptionHandler = captureExceptionHandler(clientResponse);

            long startedAt = System.currentTimeMillis();
            vertx.runOnContext(unused -> {
                chunkFeedTimerId.set(
                    vertx.setPeriodic(UPSTREAM_CHUNK_FEED_INTERVAL_MS, timerId -> chunkHandler.handle(Buffer.buffer("chunk")))
                );
                exceptionHandler.handle(new HttpClosedException("connection was closed"));
            });

            long drainDuration = endedAt.get(EXCHANGE_TIMEOUT_SECONDS, SECONDS) - startedAt;
            vertx.cancelTimer(chunkFeedTimerId.get());

            assertThat(drainDuration).isGreaterThanOrEqualTo(CLOSED_RESPONSE_DRAIN_CAP_MS);
            assertThat(deliveredChunksWhenEnded.get()).isGreaterThanOrEqualTo(CLOSED_RESPONSE_DRAIN_MAX_CHECKS);
            assertThat(endHandlerCalls.get()).isEqualTo(1);
            assertThat(trackerCalls.get()).isEqualTo(1);
        }

        @Test
        public void should_end_the_exchange_without_draining_when_the_connection_was_already_canceled() throws Exception {
            var endHandlerCalls = new AtomicInteger();
            var trackerCalls = new AtomicInteger();
            CompletableFuture<Void> closeHandled = new CompletableFuture<>();
            var clientResponse = givenMockedUpstreamResponse();

            whenUpstreamResponseIsHandled(clientResponse, endHandlerCalls, trackerCalls);
            var exceptionHandler = captureExceptionHandler(clientResponse);
            cut.cancel();

            vertx.runOnContext(unused -> {
                exceptionHandler.handle(new HttpClosedException("connection was closed"));
                closeHandled.complete(null);
            });
            closeHandled.get(EXCHANGE_TIMEOUT_SECONDS, SECONDS);

            assertThat(trackerCalls.get()).isEqualTo(1);
            assertThat(endHandlerCalls.get()).isEqualTo(0);
            verify(clientResponse, never()).resume();
        }
    }

    private HttpClientResponse givenMockedUpstreamResponse() {
        var clientResponse = mock(HttpClientResponse.class);
        when(clientResponse.headers()).thenReturn(io.vertx.core.http.HttpHeaders.headers());
        return clientResponse;
    }

    private void whenUpstreamResponseIsHandled(
        HttpClientResponse clientResponse,
        AtomicInteger endHandlerCalls,
        AtomicInteger trackerCalls
    ) {
        whenUpstreamResponseIsHandled(
            clientResponse,
            response -> response.endHandler(end -> endHandlerCalls.incrementAndGet()),
            unused -> trackerCalls.incrementAndGet()
        );
    }

    private void whenUpstreamResponseIsHandled(
        HttpClientResponse clientResponse,
        Consumer<Response> downstreamHandlers,
        Handler<Void> tracker
    ) {
        cut.responseHandler(downstreamHandlers::accept);
        cut.connect(context, client, getAvailablePort(), "host", "/", unused -> {}, tracker);
        cut.handleUpstreamResponse(context, Future.succeededFuture(clientResponse), tracker, null);
    }

    private static io.vertx.core.Handler<Void> captureEndHandler(HttpClientResponse clientResponse) {
        ArgumentCaptor<io.vertx.core.Handler<Void>> endHandler = ArgumentCaptor.captor();
        verify(clientResponse).endHandler(endHandler.capture());
        return endHandler.getValue();
    }

    private static io.vertx.core.Handler<Buffer> captureChunkHandler(HttpClientResponse clientResponse) {
        ArgumentCaptor<io.vertx.core.Handler<Buffer>> chunkHandler = ArgumentCaptor.captor();
        verify(clientResponse).handler(chunkHandler.capture());
        return chunkHandler.getValue();
    }

    private static io.vertx.core.Handler<Throwable> captureExceptionHandler(HttpClientResponse clientResponse) {
        ArgumentCaptor<io.vertx.core.Handler<Throwable>> exceptionHandler = ArgumentCaptor.captor();
        verify(clientResponse).exceptionHandler(exceptionHandler.capture());
        return exceptionHandler.getValue();
    }

    private int getAvailablePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    public class DummyTracer implements io.gravitee.node.api.opentelemetry.Tracer {

        @Override
        public <R> Span startRootSpanFrom(Context context, R r) {
            return null;
        }

        @Override
        public <R> Span startSpanFrom(Context context, R r) {
            if (r instanceof ObservableHttpClientRequest observableHttpClientRequest) {
                observableHttpClientRequest.requestOptions().addHeader(TRACEPARENT_HEADER, TRACEPARENT_HEADER_VALUE);
            }

            return null;
        }

        @Override
        public <R> Span startSpanWithParentFrom(Context context, Span span, R r) {
            return null;
        }

        @Override
        public void end(Context context, Span span) {}

        @Override
        public void endOnError(Context context, Span span, Throwable throwable) {}

        @Override
        public void endOnError(Context context, Span span, String s) {}

        @Override
        public <R> void endWithResponse(Context context, Span span, R r) {}

        @Override
        public <R> void endWithResponseAndError(Context context, Span span, R r, Throwable throwable) {}

        @Override
        public <R> void endWithResponseAndError(Context context, Span span, R r, String s) {}

        @Override
        public void injectSpanContext(Context context, BiConsumer<String, String> biConsumer) {}

        @Override
        public void injectSpanContext(Context context, Span span, BiConsumer<String, String> biConsumer) {}

        @Override
        public Lifecycle.State lifecycleState() {
            return null;
        }

        @Override
        public io.gravitee.node.api.opentelemetry.Tracer start() throws Exception {
            return null;
        }

        @Override
        public io.gravitee.node.api.opentelemetry.Tracer stop() throws Exception {
            return null;
        }

        @Override
        public String traceId(Context context) {
            return null;
        }

        @Override
        public String spanId(Context context) {
            return null;
        }
    }
}
