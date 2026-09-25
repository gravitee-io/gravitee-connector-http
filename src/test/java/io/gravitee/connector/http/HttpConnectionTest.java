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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.gravitee.common.component.Lifecycle;
import io.gravitee.common.http.HttpMethod;
import io.gravitee.connector.api.Response;
import io.gravitee.connector.api.response.ClientConnectionErrorResponse;
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
import io.gravitee.node.api.opentelemetry.http.ObservableHttpClientResponse;
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
import io.vertx.core.http.StreamResetException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.IntStream;
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
    private static final String CHUNK = "chunk";
    private static final String PARAMETERIZED_TEST_NAME = "{0}";
    private static final String CONNECTION_CLOSED_MESSAGE = "connection was closed";
    private static final String TEXT_EVENT_STREAM = "text/event-stream";
    private static final String DECLARED_BODY = "12345";
    private static final String UPSTREAM_FAILURE_MESSAGE = "upstream request failed";

    private static final long CLOSED_RESPONSE_DRAIN_MAX_BUDGET_MS = CloseRecoveryDrain.DrainTimings.DEFAULT.maxBudgetMs();
    private static final long SHORT_DRAIN_MAX_PAUSE_MS = 200;
    private static final long UPSTREAM_CHUNK_FEED_INTERVAL_MS = 5;
    // Twice the drain's 15ms sampling rate, so a sample lands in a gap between two chunks even
    // with timer jitter, yet 30ms inside the 60ms quiet threshold, which is what must keep the
    // drain alive across that gap.
    private static final long GAPPED_UPSTREAM_CHUNK_FEED_INTERVAL_MS = 30;
    private static final long EXCHANGE_TIMEOUT_SECONDS = 10;
    // Several 15ms drain samples and well past the 60ms quiet threshold, so a drain timer that
    // ignored an already-ended exchange would have ended it again by then.
    private static final long DRAIN_SETTLE_MS = 200;
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
    void setUp() {
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
    void should_set_metrics_message_with_vertx_connection_exception() {
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
    void should_notify_tracker_once_when_connection_is_canceled() {
        var trackerCalls = new AtomicInteger();

        cut.connect(context, client, getAvailablePort(), "host", "/", unused -> {}, result -> trackerCalls.incrementAndGet());

        assertThat(trackerCalls.get()).isEqualTo(0);

        cut.cancel();

        assertThat(trackerCalls.get()).isEqualTo(1);

        cut.cancel();

        assertThat(trackerCalls.get()).isEqualTo(1);
    }

    @Test
    void should_write_upstream_headers() {
        cut.connect(context, client, getAvailablePort(), "host", "/", unused -> {}, result -> new AtomicInteger(1).decrementAndGet());

        cut.writeUpstreamHeaders();

        assertThat(headers.contains(io.vertx.core.http.HttpHeaders.TRANSFER_ENCODING)).isFalse();
        assertThat(httpClientRequest.headers().getAll(SECOND_HEADER)).hasSize(1).containsExactly(SECOND_HEADER_VALUE);

        assertThat(httpClientRequest.headers().getAll(FIRST_HEADER)).hasSize(2).containsExactly(FIRST_HEADER_VALUE_1, FIRST_HEADER_VALUE_2);
    }

    @Test
    void should_write_upstream_headers_with_tracing_headers() {
        when(context.getTracer()).thenReturn(new Tracer(null, new DummyTracer()));

        cut.connect(context, client, getAvailablePort(), "host", "/", unused -> {}, result -> new AtomicInteger(1).decrementAndGet());

        cut.writeUpstreamHeaders();

        assertThat(headers.contains(io.vertx.core.http.HttpHeaders.TRANSFER_ENCODING)).isFalse();
        assertThat(httpClientRequest.headers().getAll(SECOND_HEADER)).hasSize(1).containsExactly(SECOND_HEADER_VALUE);

        assertThat(httpClientRequest.headers().getAll(FIRST_HEADER)).hasSize(2).containsExactly(FIRST_HEADER_VALUE_1, FIRST_HEADER_VALUE_2);

        assertThat(httpClientRequest.headers().getAll(TRACEPARENT_HEADER)).hasSize(1).containsExactly(TRACEPARENT_HEADER_VALUE);
    }

    @Test
    void should_replace_existing_traceparent_header_when_tracing_is_enabled() {
        when(context.getTracer()).thenReturn(new Tracer(null, new DummyTracer()));

        headers.set(TRACEPARENT_HEADER, "00-existing-trace-id-existing-span-id-01");

        cut.connect(context, client, getAvailablePort(), "host", "/", unused -> {}, result -> new AtomicInteger(1).decrementAndGet());

        cut.writeUpstreamHeaders();

        assertThat(httpClientRequest.headers().getAll(TRACEPARENT_HEADER)).hasSize(1).containsExactly(TRACEPARENT_HEADER_VALUE);
    }

    @Test
    void should_write() {
        cut.connect(context, client, getAvailablePort(), "host", "/", unused -> {}, result -> new AtomicInteger(1).decrementAndGet());
        assertThat(httpClientRequest.headers().get(CONTENT_LENGTH)).isNull();
        cut.write(io.gravitee.gateway.api.buffer.Buffer.buffer());
        assertThat(httpClientRequest.headers().get(CONTENT_LENGTH)).isEqualTo("0");
    }

    @Test
    void should_propagate_client_accept_encoding_header() {
        httpClientOptions.setUseCompression(false);
        httpClientOptions.setPropagateClientAcceptEncoding(true);

        headers.set(ACCEPT_ENCODING, BROTLI);
        cut.connect(context, client, getAvailablePort(), "host", "/", unused -> {}, result -> new AtomicInteger(1).decrementAndGet());

        cut.writeUpstreamHeaders();

        assertThat(httpClientRequest.headers().getAll(ACCEPT_ENCODING)).hasSize(1).containsExactly(BROTLI);
    }

    @Test
    void should_not_propagate_client_accept_encoding_header_when_no_header() {
        httpClientOptions.setUseCompression(false);
        httpClientOptions.setPropagateClientAcceptEncoding(true);

        cut.connect(context, client, getAvailablePort(), "host", "/", unused -> {}, result -> new AtomicInteger(1).decrementAndGet());

        cut.writeUpstreamHeaders();

        assertThat(httpClientRequest.headers().getAll(ACCEPT_ENCODING)).hasSize(0);
    }

    @Test
    void should_not_propagate_client_accept_encoding_header_when_compression_is_enabled() {
        httpClientOptions.setUseCompression(true);
        httpClientOptions.setPropagateClientAcceptEncoding(true);

        headers.set(ACCEPT_ENCODING, BROTLI);
        cut.connect(context, client, getAvailablePort(), "host", "/", unused -> {}, result -> new AtomicInteger(1).decrementAndGet());

        cut.writeUpstreamHeaders();

        assertThat(httpClientRequest.headers().getAll(ACCEPT_ENCODING)).hasSize(0);
    }

    @Test
    void should_not_propagate_client_accept_encoding_header_when_propagate_is_disabled() {
        httpClientOptions.setUseCompression(false);
        httpClientOptions.setPropagateClientAcceptEncoding(false);

        headers.set(ACCEPT_ENCODING, BROTLI);
        cut.connect(context, client, getAvailablePort(), "host", "/", unused -> {}, result -> new AtomicInteger(1).decrementAndGet());

        cut.writeUpstreamHeaders();

        assertThat(httpClientRequest.headers().getAll(ACCEPT_ENCODING)).hasSize(0);
    }

    @Test
    void should_rewrite_server_null_in_timeout_error_message() {
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
    void should_notify_the_response_end_handler_and_the_tracker_once_when_the_upstream_response_ends() {
        var endHandlerCalls = new AtomicInteger();
        var trackerCalls = new AtomicInteger();
        var clientResponse = givenMockedUpstreamResponse();

        whenUpstreamResponseIsHandled(clientResponse, endHandlerCalls, trackerCalls);
        captureEndHandler(clientResponse).handle(null);

        assertThat(endHandlerCalls.get()).isEqualTo(1);
        assertThat(trackerCalls.get()).isEqualTo(1);
    }

    @Test
    void should_deliver_chunks_received_before_the_downstream_body_handler_is_attached_on_a_non_keep_alive_endpoint() {
        httpClientOptions.setKeepAlive(false);

        var deliveredBody = new StringBuilder();
        var capturedResponse = new AtomicReference<Response>();
        var clientResponse = givenMockedUpstreamResponse();

        whenUpstreamResponseIsHandled(clientResponse, capturedResponse::set, unused -> {});

        var chunkHandler = captureChunkHandler(clientResponse);
        chunkHandler.handle(Buffer.buffer("chunk-1"));
        chunkHandler.handle(Buffer.buffer("chunk-2"));

        capturedResponse.get().bodyHandler(delivered -> deliveredBody.append(delivered.toString()));
        chunkHandler.handle(Buffer.buffer("chunk-3"));

        assertThat(deliveredBody.toString()).isEqualTo("chunk-1chunk-2chunk-3");
    }

    @Test
    void should_deliver_buffered_chunks_before_the_end_signal_when_the_downstream_attaches_its_end_handler_before_its_body_handler() {
        httpClientOptions.setKeepAlive(false);

        var deliveredBody = new StringBuilder();
        var bodyAtEndSignal = new ArrayList<String>();
        var trackerCalls = new AtomicInteger();
        var capturedResponse = new AtomicReference<Response>();
        var clientResponse = givenMockedUpstreamResponse();

        whenUpstreamResponseIsHandled(clientResponse, capturedResponse::set, unused -> trackerCalls.incrementAndGet());

        var chunkHandler = captureChunkHandler(clientResponse);
        chunkHandler.handle(Buffer.buffer("chunk-1"));
        chunkHandler.handle(Buffer.buffer("chunk-2"));
        captureEndHandler(clientResponse).handle(null);

        capturedResponse.get().endHandler(end -> bodyAtEndSignal.add(deliveredBody.toString()));
        capturedResponse.get().bodyHandler(chunk -> deliveredBody.append(chunk.toString()));

        assertThat(deliveredBody.toString()).isEqualTo("chunk-1chunk-2");
        assertThat(bodyAtEndSignal).containsExactly("chunk-1chunk-2");
        assertThat(trackerCalls).hasValue(1);
    }

    static Stream<Arguments> bufferCapBoundaries() {
        return Stream.of(
            Arguments.of("exactly at the cap", chunksFillingTheCapExactly(), List.of()),
            Arguments.of("one byte over the cap", chunksFillingTheCapExactly(), List.of(chunkOf(1, 3))),
            Arguments.of("sixteen 1KiB chunks then four more", kibChunks(0, 16), kibChunks(16, 4))
        );
    }

    @ParameterizedTest(name = PARAMETERIZED_TEST_NAME)
    @MethodSource("bufferCapBoundaries")
    void should_deliver_every_buffered_byte_up_to_the_local_buffer_cap_and_drop_only_the_excess(
        String boundary,
        List<byte[]> chunksWithinTheCap,
        List<byte[]> chunksPastTheCap
    ) {
        httpClientOptions.setKeepAlive(false);
        var capturedResponse = new AtomicReference<Response>();
        var clientResponse = givenMockedUpstreamResponse();

        whenUpstreamResponseIsHandled(clientResponse, capturedResponse::set, unused -> {});

        var chunkHandler = captureChunkHandler(clientResponse);
        chunksWithinTheCap.forEach(chunk -> chunkHandler.handle(Buffer.buffer(chunk)));
        chunksPastTheCap.forEach(chunk -> chunkHandler.handle(Buffer.buffer(chunk)));

        var delivered = new ByteArrayOutputStream();
        capturedResponse.get().bodyHandler(chunk -> delivered.writeBytes(chunk.getBytes()));
        captureEndHandler(clientResponse).handle(null);

        var expected = new ByteArrayOutputStream();
        chunksWithinTheCap.forEach(expected::writeBytes);
        assertThat(delivered.toByteArray()).isEqualTo(expected.toByteArray());
    }

    private static List<byte[]> chunksFillingTheCapExactly() {
        return List.of(chunkOf(LOCAL_UPSTREAM_BUFFER_CAP - 1, 1), chunkOf(1, 2));
    }

    private static List<byte[]> kibChunks(int firstDistinctiveValue, int count) {
        return IntStream.range(firstDistinctiveValue, firstDistinctiveValue + count)
            .mapToObj(i -> chunkOf(1024, i))
            .toList();
    }

    @Test
    void should_discard_chunks_without_failing_when_no_downstream_body_handler_is_registered_on_a_keep_alive_endpoint() {
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
            Arguments.of("connection closed outside a vertx context", new HttpClosedException(CONNECTION_CLOSED_MESSAGE))
        );
    }

    @ParameterizedTest(name = PARAMETERIZED_TEST_NAME)
    @MethodSource("failuresLeavingNothingToDrain")
    void should_end_the_exchange_without_waiting_for_a_drain_when_the_upstream_response_fails(String failure, Throwable upstreamFailure) {
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
                    captureExceptionHandler(clientResponse).handle(new HttpClosedException(CONNECTION_CLOSED_MESSAGE))
            )
        );
    }

    @ParameterizedTest(name = PARAMETERIZED_TEST_NAME)
    @MethodSource("terminationsAfterCancel")
    void should_notify_the_tracker_once_when_the_exchange_terminates_after_the_connection_was_canceled(
        String termination,
        Consumer<HttpClientResponse> terminate
    ) {
        var endHandlerCalls = new AtomicInteger();
        var trackerCalls = new AtomicInteger();
        var deliveredBody = new StringBuilder();
        var capturedResponse = new AtomicReference<HttpResponse>();
        var clientResponse = givenMockedUpstreamResponse();

        whenUpstreamResponseIsHandled(
            clientResponse,
            response -> {
                capturedResponse.set((HttpResponse) response);
                response.bodyHandler(chunk -> deliveredBody.append(chunk.toString()));
                response.endHandler(end -> endHandlerCalls.incrementAndGet());
            },
            unused -> trackerCalls.incrementAndGet()
        );
        var chunkHandler = captureChunkHandler(clientResponse);
        cut.cancel();
        chunkHandler.handle(Buffer.buffer(CHUNK));
        terminate.accept(clientResponse);

        assertThat(capturedResponse.get().bodyHandler()).isNull();
        assertThat(deliveredBody.toString()).isEmpty();
        assertThat(trackerCalls.get()).isEqualTo(1);
        assertThat(endHandlerCalls.get()).isEqualTo(0);
    }

    @Test
    void should_notify_the_tracker_once_when_the_upstream_response_fails_after_the_connection_was_canceled() {
        var trackerCalls = new AtomicInteger();
        Handler<Void> tracker = unused -> trackerCalls.incrementAndGet();
        List<Response> sentResponses = new ArrayList<>();
        cut.responseHandler(sentResponses::add);
        cut.connect(context, client, getAvailablePort(), "host", "/", unused -> {}, tracker);
        cut.cancel();

        cut.handleUpstreamResponse(context, Future.failedFuture(new StreamResetException(0)), tracker, null);

        assertThat(trackerCalls.get()).isEqualTo(1);
        assertThat(sentResponses).isEmpty();
    }

    @Test
    void should_notify_the_tracker_once_and_send_a_connection_error_when_the_upstream_response_fails() {
        when(request.metrics()).thenReturn(Metrics.on(System.currentTimeMillis()).build());
        var trackerCalls = new AtomicInteger();
        Handler<Void> tracker = unused -> trackerCalls.incrementAndGet();
        List<Response> sentResponses = new ArrayList<>();
        cut.responseHandler(sentResponses::add);
        cut.connect(context, client, getAvailablePort(), "host", "/", unused -> {}, tracker);

        cut.handleUpstreamResponse(context, Future.failedFuture(new StreamResetException(0)), tracker, null);

        assertThat(trackerCalls.get()).isEqualTo(1);
        assertThat(sentResponses).singleElement().isInstanceOf(ClientConnectionErrorResponse.class);
    }

    @Test
    void should_clear_the_upstream_exception_handler_and_notify_the_tracker_once_when_an_event_stream_client_disconnects_after_a_cancel() {
        headers.set(HttpHeaderNames.ACCEPT, TEXT_EVENT_STREAM);
        var endHandlerCalls = new AtomicInteger();
        var trackerCalls = new AtomicInteger();
        var clientResponse = givenMockedUpstreamResponse();

        whenUpstreamResponseIsHandled(clientResponse, endHandlerCalls, trackerCalls);
        var closeHandler = captureRequestCloseHandler();
        cut.cancel();
        closeHandler.handle(null);

        verify(clientResponse).exceptionHandler(null);
        assertThat(trackerCalls.get()).isEqualTo(1);
        assertThat(endHandlerCalls.get()).isEqualTo(0);
    }

    @Test
    void should_not_register_a_request_close_handler_when_the_client_does_not_accept_an_event_stream() {
        var clientResponse = givenMockedUpstreamResponse();

        whenUpstreamResponseIsHandled(clientResponse, new AtomicInteger(), new AtomicInteger());

        verify(request, never()).closeHandler(any());
    }

    @Test
    void should_end_the_request_span_with_the_upstream_response_when_it_is_received() {
        var requestSpan = mock(Span.class);
        var nodeTracer = givenTracerStartingSpan(requestSpan);
        var clientResponse = givenMockedUpstreamResponse();
        connectUpstream();

        cut.handleUpstreamResponse(context, Future.succeededFuture(clientResponse), unused -> {}, requestSpan);

        ArgumentCaptor<Object> endedWith = ArgumentCaptor.captor();
        verify(nodeTracer).endWithResponse(any(), eq(requestSpan), endedWith.capture());
        assertThat(endedWith.getValue()).isInstanceOf(ObservableHttpClientResponse.class);
        verify(nodeTracer, never()).endOnError(any(), any(), any(Throwable.class));
    }

    @Test
    void should_end_the_request_span_with_the_error_when_the_upstream_response_fails() {
        when(request.metrics()).thenReturn(Metrics.on(System.currentTimeMillis()).build());
        var requestSpan = mock(Span.class);
        var nodeTracer = givenTracerStartingSpan(requestSpan);
        var upstreamFailure = new StreamResetException(0);
        connectUpstream();

        cut.handleUpstreamResponse(context, Future.failedFuture(upstreamFailure), unused -> {}, requestSpan);

        verify(nodeTracer).endWithResponseAndError(any(), eq(requestSpan), isNull(), eq(upstreamFailure));
        verify(nodeTracer, never()).endWithResponse(any(), any(), any());
    }

    static Stream<Arguments> upstreamFailuresAfterCancel() {
        var requestFailureCause = new RuntimeException(UPSTREAM_FAILURE_MESSAGE);
        var responseFailure = new StreamResetException(0);
        return Stream.of(
            Arguments.of(
                "request stream exception",
                (BiConsumer<HttpConnectionTest, Span>) (test, requestSpan) ->
                    test.captureRequestExceptionHandler().handle(new RuntimeException(requestFailureCause)),
                (BiConsumer<io.gravitee.node.api.opentelemetry.Tracer, Span>) (nodeTracer, requestSpan) ->
                    verify(nodeTracer, times(1)).endOnError(any(), eq(requestSpan), eq(requestFailureCause))
            ),
            Arguments.of(
                "failed upstream response",
                (BiConsumer<HttpConnectionTest, Span>) (test, requestSpan) ->
                    test.cut.handleUpstreamResponse(test.context, Future.failedFuture(responseFailure), unused -> {}, requestSpan),
                (BiConsumer<io.gravitee.node.api.opentelemetry.Tracer, Span>) (nodeTracer, requestSpan) ->
                    verify(nodeTracer, times(1)).endWithResponseAndError(any(), eq(requestSpan), isNull(), eq(responseFailure))
            )
        );
    }

    @ParameterizedTest(name = PARAMETERIZED_TEST_NAME)
    @MethodSource("upstreamFailuresAfterCancel")
    void should_end_the_request_span_when_the_upstream_fails_after_the_connection_was_canceled(
        String failure,
        BiConsumer<HttpConnectionTest, Span> failUpstream,
        BiConsumer<io.gravitee.node.api.opentelemetry.Tracer, Span> verifySpanEnded
    ) {
        var requestSpan = mock(Span.class);
        var nodeTracer = givenTracerStartingSpan(requestSpan);
        var trackerCalls = new AtomicInteger();
        cut.connect(context, client, getAvailablePort(), "host", "/", unused -> {}, unused -> trackerCalls.incrementAndGet());
        cut.cancel();

        failUpstream.accept(this, requestSpan);

        verifySpanEnded.accept(nodeTracer, requestSpan);
        verify(nodeTracer, never()).endWithResponse(any(), any(), any());
        assertThat(trackerCalls.get()).isEqualTo(1);
    }

    static Stream<Arguments> upstreamRequestFailures() {
        return Stream.of(
            Arguments.of(
                "request creation failed",
                (Consumer<HttpConnectionTest>) test -> {
                    when(test.client.request(any())).thenReturn(Future.failedFuture(new RuntimeException(UPSTREAM_FAILURE_MESSAGE)));
                    test.connectUpstream();
                }
            ),
            Arguments.of(
                "request stream exception",
                (Consumer<HttpConnectionTest>) test -> {
                    test.connectUpstream();
                    test.captureRequestExceptionHandler().handle(new RuntimeException(new RuntimeException(UPSTREAM_FAILURE_MESSAGE)));
                }
            ),
            Arguments.of(
                "connection exception",
                (Consumer<HttpConnectionTest>) test -> {
                    test.connectUpstream();
                    test.httpClientRequest.connection().goAway(204, 1, Buffer.buffer(UPSTREAM_FAILURE_MESSAGE));
                }
            )
        );
    }

    @ParameterizedTest(name = PARAMETERIZED_TEST_NAME)
    @MethodSource("upstreamRequestFailures")
    void should_end_the_request_span_on_error_when_the_upstream_request_fails(
        String failure,
        Consumer<HttpConnectionTest> failUpstreamRequest
    ) {
        when(request.metrics()).thenReturn(Metrics.on(System.currentTimeMillis()).build());
        var requestSpan = mock(Span.class);
        var nodeTracer = givenTracerStartingSpan(requestSpan);

        failUpstreamRequest.accept(this);

        ArgumentCaptor<Throwable> endedOn = ArgumentCaptor.captor();
        verify(nodeTracer).endOnError(any(), eq(requestSpan), endedOn.capture());
        assertThat(endedOn.getValue()).hasMessage(UPSTREAM_FAILURE_MESSAGE);
        verify(nodeTracer, never()).endWithResponse(any(), any(), any());
    }

    @Nested
    class WhenTheUpstreamConnectionClosesInsideAVertxContext {

        private Vertx vertx;

        @BeforeEach
        void startVertx() {
            vertx = Vertx.vertx();
        }

        @AfterEach
        void closeVertx() throws Exception {
            vertx.close().toCompletionStage().toCompletableFuture().get(EXCHANGE_TIMEOUT_SECONDS, SECONDS);
        }

        @Test
        void should_end_the_exchange_once_after_the_drain_budget_is_exhausted() throws Exception {
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
                    response.resume();
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
                    vertx.setPeriodic(UPSTREAM_CHUNK_FEED_INTERVAL_MS, timerId -> chunkHandler.handle(Buffer.buffer(CHUNK)))
                );
                exceptionHandler.handle(new HttpClosedException(CONNECTION_CLOSED_MESSAGE));
            });

            long drainDuration = endedAt.get(EXCHANGE_TIMEOUT_SECONDS, SECONDS) - startedAt;
            vertx.cancelTimer(chunkFeedTimerId.get());

            assertThat(drainDuration).isGreaterThanOrEqualTo(CLOSED_RESPONSE_DRAIN_MAX_BUDGET_MS);
            // A quarter of the chunks the feed nominally produces over the budget, so the assertion
            // proves the drain kept delivering for the whole budget without being timing-sensitive.
            assertThat(deliveredChunksWhenEnded.get()).isGreaterThanOrEqualTo(
                (int) (CLOSED_RESPONSE_DRAIN_MAX_BUDGET_MS / UPSTREAM_CHUNK_FEED_INTERVAL_MS / 4)
            );
            assertThat(endHandlerCalls.get()).isEqualTo(1);
            assertThat(trackerCalls.get()).isEqualTo(1);
        }

        @Test
        void should_deliver_every_chunk_when_arrivals_are_gapped_wider_than_the_drain_sampling_rate() throws Exception {
            int chunksToFeed = 5;
            var deliveredChunks = new AtomicInteger();
            var deliveredChunksWhenEnded = new AtomicInteger();
            var endHandlerCalls = new AtomicInteger();
            var remainingChunks = new AtomicInteger(chunksToFeed);
            CompletableFuture<Void> ended = new CompletableFuture<>();
            var clientResponse = givenMockedUpstreamResponse();

            whenUpstreamResponseIsHandled(
                clientResponse,
                response -> {
                    response.bodyHandler(chunk -> deliveredChunks.incrementAndGet());
                    response.endHandler(end -> {
                        endHandlerCalls.incrementAndGet();
                        deliveredChunksWhenEnded.set(deliveredChunks.get());
                        ended.complete(null);
                    });
                    response.resume();
                },
                unused -> {}
            );

            var chunkHandler = captureChunkHandler(clientResponse);
            var exceptionHandler = captureExceptionHandler(clientResponse);

            vertx.runOnContext(unused -> {
                vertx.setPeriodic(GAPPED_UPSTREAM_CHUNK_FEED_INTERVAL_MS, timerId -> {
                    if (remainingChunks.getAndDecrement() > 0) {
                        chunkHandler.handle(Buffer.buffer(CHUNK));
                    } else {
                        vertx.cancelTimer(timerId);
                    }
                });
                exceptionHandler.handle(new HttpClosedException(CONNECTION_CLOSED_MESSAGE));
            });

            ended.get(EXCHANGE_TIMEOUT_SECONDS, SECONDS);

            assertThat(deliveredChunksWhenEnded.get()).isEqualTo(chunksToFeed);
            assertThat(endHandlerCalls.get()).isEqualTo(1);
        }

        @Test
        void should_end_the_exchange_once_on_the_quiet_threshold_when_the_content_length_is_not_numeric() throws Exception {
            var endHandlerCalls = new AtomicInteger();
            var trackerCalls = new AtomicInteger();
            CompletableFuture<Long> endedAt = new CompletableFuture<>();
            var clientResponse = givenMockedUpstreamResponse();
            doReturn("abc").when(clientResponse).getHeader(io.netty.handler.codec.http.HttpHeaderNames.CONTENT_LENGTH);

            whenUpstreamResponseIsHandled(
                clientResponse,
                response -> {
                    response.endHandler(end -> endHandlerCalls.incrementAndGet());
                    response.resume();
                },
                unused -> {
                    trackerCalls.incrementAndGet();
                    endedAt.complete(System.currentTimeMillis());
                }
            );
            var exceptionHandler = captureExceptionHandler(clientResponse);

            long startedAt = System.currentTimeMillis();
            vertx.runOnContext(unused -> exceptionHandler.handle(new HttpClosedException(CONNECTION_CLOSED_MESSAGE)));

            long drainDuration = endedAt.get(EXCHANGE_TIMEOUT_SECONDS, SECONDS) - startedAt;

            assertThat(drainDuration).isLessThan(CLOSED_RESPONSE_DRAIN_MAX_BUDGET_MS / 2);
            assertThat(endHandlerCalls.get()).isEqualTo(1);
            assertThat(trackerCalls.get()).isEqualTo(1);
        }

        @Test
        void should_end_the_exchange_without_draining_when_the_connection_was_already_canceled() throws Exception {
            var endHandlerCalls = new AtomicInteger();
            var trackerCalls = new AtomicInteger();
            CompletableFuture<Void> closeHandled = new CompletableFuture<>();
            var clientResponse = givenMockedUpstreamResponse();

            whenUpstreamResponseIsHandled(clientResponse, endHandlerCalls, trackerCalls);
            var exceptionHandler = captureExceptionHandler(clientResponse);
            cut.cancel();

            vertx.runOnContext(unused -> {
                exceptionHandler.handle(new HttpClosedException(CONNECTION_CLOSED_MESSAGE));
                closeHandled.complete(null);
            });
            closeHandled.get(EXCHANGE_TIMEOUT_SECONDS, SECONDS);

            assertThat(trackerCalls.get()).isEqualTo(1);
            assertThat(endHandlerCalls.get()).isEqualTo(0);
            verify(clientResponse, never()).resume();
        }

        @Test
        void should_end_the_exchange_once_as_soon_as_the_declared_content_length_is_delivered() throws Exception {
            var endHandlerCalls = new AtomicInteger();
            var trackerCalls = new AtomicInteger();
            var deliveredBody = new StringBuilder();
            CompletableFuture<Long> endedAt = new CompletableFuture<>();
            var clientResponse = givenMockedUpstreamResponse();
            doReturn(String.valueOf(DECLARED_BODY.length()))
                .when(clientResponse)
                .getHeader(io.netty.handler.codec.http.HttpHeaderNames.CONTENT_LENGTH);

            whenUpstreamResponseIsHandled(
                clientResponse,
                response -> {
                    response.bodyHandler(chunk -> deliveredBody.append(chunk.toString()));
                    response.endHandler(end -> endHandlerCalls.incrementAndGet());
                },
                unused -> {
                    trackerCalls.incrementAndGet();
                    endedAt.complete(System.currentTimeMillis());
                }
            );
            var chunkHandler = captureChunkHandler(clientResponse);
            var exceptionHandler = captureExceptionHandler(clientResponse);

            long closedAt = System.currentTimeMillis();
            vertx.runOnContext(unused -> {
                exceptionHandler.handle(new HttpClosedException(CONNECTION_CLOSED_MESSAGE));
                chunkHandler.handle(Buffer.buffer(DECLARED_BODY));
            });

            long drainDuration = endedAt.get(EXCHANGE_TIMEOUT_SECONDS, SECONDS) - closedAt;

            assertThat(drainDuration).isLessThan(CLOSED_RESPONSE_DRAIN_MAX_BUDGET_MS / 2);
            assertThat(deliveredBody.toString()).isEqualTo(DECLARED_BODY);
            assertThat(endHandlerCalls.get()).isEqualTo(1);
            assertThat(trackerCalls.get()).isEqualTo(1);
        }

        @Test
        void should_end_the_exchange_once_when_the_upstream_response_ends_while_the_drain_is_pending() throws Exception {
            var endHandlerCalls = new AtomicInteger();
            var trackerCalls = new AtomicInteger();
            CompletableFuture<Void> settled = new CompletableFuture<>();
            var clientResponse = givenMockedUpstreamResponse();

            whenUpstreamResponseIsHandled(clientResponse, endHandlerCalls, trackerCalls);
            var exceptionHandler = captureExceptionHandler(clientResponse);
            var endHandler = captureEndHandler(clientResponse);

            vertx.runOnContext(unused -> {
                exceptionHandler.handle(new HttpClosedException(CONNECTION_CLOSED_MESSAGE));
                endHandler.handle(null);
                vertx.setTimer(DRAIN_SETTLE_MS, timerId -> settled.complete(null));
            });
            settled.get(EXCHANGE_TIMEOUT_SECONDS, SECONDS);

            assertThat(endHandlerCalls.get()).isEqualTo(1);
            assertThat(trackerCalls.get()).isEqualTo(1);
            verify(clientResponse, never()).handler(null);
        }

        @Test
        void should_end_the_exchange_once_when_the_paused_downstream_takes_no_byte_for_the_maximum_pause() throws Exception {
            var defaults = CloseRecoveryDrain.DrainTimings.DEFAULT;
            cut = new HttpConnection<>(
                endpoint,
                request,
                new CloseRecoveryDrain.DrainTimings(
                    defaults.checkDelayMs(),
                    defaults.quietThresholdMs(),
                    defaults.maxBudgetMs(),
                    SHORT_DRAIN_MAX_PAUSE_MS
                )
            );
            var endHandlerCalls = new AtomicInteger();
            var trackerCalls = new AtomicInteger();
            var bodyPaused = new AtomicInteger();
            CompletableFuture<Long> endedAt = new CompletableFuture<>();
            CompletableFuture<Void> settled = new CompletableFuture<>();
            var clientResponse = givenMockedUpstreamResponse();

            whenUpstreamResponseIsHandled(
                clientResponse,
                response -> {
                    response.bodyHandler(chunk -> {
                        if (bodyPaused.getAndIncrement() == 0) {
                            response.pause();
                        }
                    });
                    response.endHandler(end -> endHandlerCalls.incrementAndGet());
                },
                unused -> {
                    trackerCalls.incrementAndGet();
                    endedAt.complete(System.currentTimeMillis());
                }
            );
            var chunkHandler = captureChunkHandler(clientResponse);
            var exceptionHandler = captureExceptionHandler(clientResponse);

            long closedAt = System.currentTimeMillis();
            vertx.runOnContext(unused -> {
                exceptionHandler.handle(new HttpClosedException(CONNECTION_CLOSED_MESSAGE));
                chunkHandler.handle(Buffer.buffer(CHUNK));
            });

            long drainDuration = endedAt.get(EXCHANGE_TIMEOUT_SECONDS, SECONDS) - closedAt;
            vertx.setTimer(DRAIN_SETTLE_MS, timerId -> settled.complete(null));
            settled.get(EXCHANGE_TIMEOUT_SECONDS, SECONDS);

            assertThat(drainDuration).isGreaterThanOrEqualTo(SHORT_DRAIN_MAX_PAUSE_MS);
            assertThat(endHandlerCalls.get()).isEqualTo(1);
            assertThat(trackerCalls.get()).isEqualTo(1);
        }
    }

    private static byte[] chunkOf(int length, int distinctiveValue) {
        byte[] chunk = new byte[length];
        Arrays.fill(chunk, (byte) distinctiveValue);
        return chunk;
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

    private io.gravitee.node.api.opentelemetry.Tracer givenTracerStartingSpan(Span requestSpan) {
        var nodeTracer = mock(io.gravitee.node.api.opentelemetry.Tracer.class);
        when(nodeTracer.startSpanFrom(any(), any())).thenReturn(requestSpan);
        when(context.getTracer()).thenReturn(new Tracer(null, nodeTracer));
        return nodeTracer;
    }

    private void connectUpstream() {
        cut.connect(context, client, getAvailablePort(), "host", "/", unused -> {}, unused -> {});
    }

    private io.vertx.core.Handler<Throwable> captureRequestExceptionHandler() {
        ArgumentCaptor<io.vertx.core.Handler<Throwable>> exceptionHandler = ArgumentCaptor.captor();
        verify(httpClientRequest).exceptionHandler(exceptionHandler.capture());
        return exceptionHandler.getValue();
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

    private Handler<Void> captureRequestCloseHandler() {
        ArgumentCaptor<Handler<Void>> closeHandler = ArgumentCaptor.captor();
        verify(request).closeHandler(closeHandler.capture());
        return closeHandler.getValue();
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
