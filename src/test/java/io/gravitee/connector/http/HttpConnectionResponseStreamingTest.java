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

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import io.gravitee.common.http.HttpMethod;
import io.gravitee.connector.http.endpoint.HttpClientOptions;
import io.gravitee.connector.http.endpoint.HttpEndpoint;
import io.gravitee.gateway.api.ExecutionContext;
import io.gravitee.gateway.api.http.HttpHeaders;
import io.gravitee.gateway.api.proxy.ProxyRequest;
import io.gravitee.gateway.reactive.api.tracing.Tracer;
import io.gravitee.node.opentelemetry.tracer.noop.NoOpTracer;
import io.gravitee.reporter.api.http.Metrics;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
public class HttpConnectionResponseStreamingTest {

    private static final String BODY_FIRST_PART = "first-part-of-the-upstream-response-body-";
    private static final String BODY_SECOND_PART = "second-part-of-the-upstream-response-body";
    private static final String UPSTREAM_BODY = BODY_FIRST_PART + BODY_SECOND_PART;

    private static final String UPSTREAM_HOST = "localhost";
    private static final String CONTENT_LENGTH_RESPONSE_HEAD = "HTTP/1.1 200 OK\r\nContent-Length: ";
    private static final String END_OF_HEADERS = "\r\n\r\n";

    private static final long UPSTREAM_TRICKLE_DELAY_MS = 50;
    private static final long BODY_HANDLER_ATTACH_DELAY_MS = 20;
    private static final long LATE_BODY_HANDLER_ATTACH_DELAY_MS = 300;
    private static final long CLIENT_RESUME_DELAY_MS = 500;
    private static final long QUIET_PERIOD_MS = 500;
    // Longer than the connector's 1000ms drain budget, so the budget expires while the downstream
    // is still paused.
    private static final long DOWNSTREAM_PAUSE_MS = 1_500;
    private static final long DOWNSTREAM_CHUNK_PAUSE_MS = 50;
    private static final long EXCHANGE_TIMEOUT_SECONDS = 10;
    private static final long SHORT_DRAIN_MAX_PAUSE_MS = 200;

    private final List<NetServer> upstreams = new ArrayList<>();

    private Vertx vertx;
    private NetServer upstream;
    private HttpClient client;

    @Mock
    private ExecutionContext context;

    @Mock
    private HttpEndpoint endpoint;

    @Mock
    private ProxyRequest request;

    @BeforeEach
    void setUp() throws Exception {
        vertx = Vertx.vertx();
        upstream = startUpstream(socket -> respondThenCloseWithoutTerminatingChunk(vertx, socket));

        client = vertx.createHttpClient(new io.vertx.core.http.HttpClientOptions().setKeepAlive(false));

        lenient().when(endpoint.getHttpClientOptions()).thenReturn(new HttpClientOptions());
        when(request.headers()).thenReturn(HttpHeaders.create());
        when(request.method()).thenReturn(HttpMethod.GET);
        when(request.uri()).thenReturn("http://localhost");
        when(context.getTracer()).thenReturn(new Tracer(null, new NoOpTracer()));
        lenient().when(request.metrics()).thenReturn(Metrics.on(System.currentTimeMillis()).build());
    }

    @AfterEach
    void tearDown() throws Exception {
        client.close().toCompletionStage().toCompletableFuture().get(EXCHANGE_TIMEOUT_SECONDS, SECONDS);
        for (NetServer started : upstreams) {
            started.close().toCompletionStage().toCompletableFuture().get(EXCHANGE_TIMEOUT_SECONDS, SECONDS);
        }
        vertx.close().toCompletionStage().toCompletableFuture().get(EXCHANGE_TIMEOUT_SECONDS, SECONDS);
    }

    private NetServer startUpstream(Handler<NetSocket> respondToRequest) throws Exception {
        NetServer started = vertx
            .createNetServer()
            .connectHandler(socket -> socket.handler(upstreamRequest -> respondToRequest.handle(socket)))
            .listen(0)
            .toCompletionStage()
            .toCompletableFuture()
            .get(EXCHANGE_TIMEOUT_SECONDS, SECONDS);
        upstreams.add(started);
        return started;
    }

    private static void respondThenCloseWithoutTerminatingChunk(Vertx vertx, NetSocket socket) {
        respondWithTransferEncodingThenCloseWithoutTerminatingChunk("Transfer-Encoding: chunked\r\n", vertx, socket);
    }

    private static void respondWithTransferEncodingThenCloseWithoutTerminatingChunk(
        String transferEncodingFieldLines,
        Vertx vertx,
        NetSocket socket
    ) {
        socket.write(Buffer.buffer("HTTP/1.1 200 OK\r\n" + transferEncodingFieldLines + "\r\n" + chunk(BODY_FIRST_PART)));
        vertx.setTimer(UPSTREAM_TRICKLE_DELAY_MS, timer ->
            socket.write(Buffer.buffer(chunk(BODY_SECOND_PART))).onComplete(written -> socket.close())
        );
    }

    private static void respondWithContentLengthOneByteOverTheBodyThenClose(Vertx vertx, NetSocket socket) {
        // One byte more than the backend sends, so the socket closes before the declared length is reached.
        long declaredContentLength = UPSTREAM_BODY.length() + 1;
        socket.write(Buffer.buffer(CONTENT_LENGTH_RESPONSE_HEAD + declaredContentLength + END_OF_HEADERS + BODY_FIRST_PART));
        vertx.setTimer(UPSTREAM_TRICKLE_DELAY_MS, timer ->
            socket.write(Buffer.buffer(BODY_SECOND_PART)).onComplete(written -> socket.close())
        );
    }

    private static String chunk(String payload) {
        return Integer.toHexString(payload.length()) + "\r\n" + payload + "\r\n";
    }

    @Test
    void should_deliver_the_whole_upstream_body_when_connection_is_closed_while_response_is_paused() throws Exception {
        HttpConnection<HttpResponse> cut = new HttpConnection<>(endpoint, request);

        var receivedBody = new StringBuilder();
        var receivedStatus = new AtomicInteger();
        CompletableFuture<String> bodyOnEnd = new CompletableFuture<>();

        cut.responseHandler(response -> {
            receivedStatus.set(response.status());
            response.bodyHandler(chunk -> receivedBody.append(chunk.toString()));
            response.endHandler(end -> bodyOnEnd.complete(receivedBody.toString()));
            vertx.setTimer(CLIENT_RESUME_DELAY_MS, timer -> response.resume());
        });

        cut.connect(context, client, upstream.actualPort(), UPSTREAM_HOST, "/", connected -> cut.end(), tracker -> {});

        assertThat(bodyOnEnd.get(EXCHANGE_TIMEOUT_SECONDS, SECONDS)).isEqualTo(UPSTREAM_BODY);
        assertThat(receivedStatus.get()).isEqualTo(200);
    }

    @Test
    void should_not_deliver_upstream_chunks_after_the_response_has_ended_when_the_client_consumes_one_chunk_at_a_time() throws Exception {
        HttpConnection<HttpResponse> cut = new HttpConnection<>(endpoint, request);

        var bodyBeforeEnd = new StringBuilder();
        var chunksAfterEnd = new AtomicInteger();
        var endSignals = new AtomicInteger();
        CompletableFuture<Void> ended = new CompletableFuture<>();

        cut.responseHandler(response -> {
            response.bodyHandler(chunk -> {
                if (endSignals.get() > 0) {
                    chunksAfterEnd.incrementAndGet();
                } else {
                    bodyBeforeEnd.append(chunk.toString());
                }
                response.pause();
                vertx.setTimer(DOWNSTREAM_CHUNK_PAUSE_MS, timer -> response.resume());
            });
            response.endHandler(end -> {
                endSignals.incrementAndGet();
                ended.complete(null);
                vertx.setTimer(CLIENT_RESUME_DELAY_MS, timer -> response.resume());
            });
            response.resume();
        });

        cut.connect(context, client, upstream.actualPort(), UPSTREAM_HOST, "/", connected -> cut.end(), tracker -> {});

        ended.get(EXCHANGE_TIMEOUT_SECONDS, SECONDS);
        CompletableFuture<Void> quietPeriodElapsed = new CompletableFuture<>();
        vertx.setTimer(CLIENT_RESUME_DELAY_MS * 2, timer -> quietPeriodElapsed.complete(null));
        quietPeriodElapsed.get(EXCHANGE_TIMEOUT_SECONDS, SECONDS);

        assertThat(bodyBeforeEnd.toString()).isEqualTo(UPSTREAM_BODY);
        assertThat(endSignals.get()).isEqualTo(1);
        assertThat(chunksAfterEnd.get()).isZero();
    }

    @Test
    void should_not_lose_upstream_chunks_received_before_the_body_handler_is_attached_when_keep_alive_is_disabled() throws Exception {
        var options = new HttpClientOptions();
        options.setKeepAlive(false);
        when(endpoint.getHttpClientOptions()).thenReturn(options);

        HttpConnection<HttpResponse> cut = new HttpConnection<>(endpoint, request);

        var receivedBody = new StringBuilder();
        CompletableFuture<String> bodyOnEnd = new CompletableFuture<>();

        cut.responseHandler(response ->
            vertx.setTimer(BODY_HANDLER_ATTACH_DELAY_MS, timer -> {
                response.bodyHandler(chunk -> receivedBody.append(chunk.toString()));
                response.endHandler(end -> bodyOnEnd.complete(receivedBody.toString()));
                response.resume();
            })
        );

        cut.connect(context, client, upstream.actualPort(), UPSTREAM_HOST, "/", connected -> cut.end(), tracker -> {});

        assertThat(bodyOnEnd.get(EXCHANGE_TIMEOUT_SECONDS, SECONDS)).isEqualTo(UPSTREAM_BODY);
    }

    @Test
    void should_end_the_exchange_without_failure_when_the_connection_closes_before_the_client_registers_handlers() throws Exception {
        AtomicReference<Throwable> unhandledFailure = new AtomicReference<>();
        vertx.exceptionHandler(unhandledFailure::set);

        var d = CloseRecoveryDrain.DrainTimings.DEFAULT;
        HttpConnection<HttpResponse> cut = new HttpConnection<>(
            endpoint,
            request,
            new CloseRecoveryDrain.DrainTimings(d.checkDelayMs(), d.quietThresholdMs(), d.maxBudgetMs(), SHORT_DRAIN_MAX_PAUSE_MS)
        );

        var receivedStatus = new AtomicInteger();
        CompletableFuture<Void> trackerReleased = new CompletableFuture<>();

        cut.responseHandler(response -> receivedStatus.set(response.status()));

        cut.connect(
            context,
            client,
            upstream.actualPort(),
            UPSTREAM_HOST,
            "/",
            connected -> cut.end(),
            tracker -> trackerReleased.complete(null)
        );

        trackerReleased.get(EXCHANGE_TIMEOUT_SECONDS, SECONDS);
        awaitQuietPeriod();

        assertThat(receivedStatus.get()).isEqualTo(200);
        assertThat(unhandledFailure.get()).isNull();
    }

    @Test
    void should_release_the_tracker_once_when_the_client_registers_a_body_handler_but_no_end_handler() throws Exception {
        AtomicReference<Throwable> unhandledFailure = new AtomicReference<>();
        vertx.exceptionHandler(unhandledFailure::set);

        HttpConnection<HttpResponse> cut = new HttpConnection<>(endpoint, request);

        var receivedBody = new StringBuilder();
        var trackerCalls = new AtomicInteger();
        CompletableFuture<Void> trackerReleased = new CompletableFuture<>();

        cut.responseHandler(response -> {
            response.bodyHandler(chunk -> receivedBody.append(chunk.toString()));
            vertx.setTimer(CLIENT_RESUME_DELAY_MS, timer -> response.resume());
        });

        cut.connect(
            context,
            client,
            upstream.actualPort(),
            UPSTREAM_HOST,
            "/",
            connected -> cut.end(),
            tracker -> {
                trackerCalls.incrementAndGet();
                trackerReleased.complete(null);
            }
        );

        trackerReleased.get(EXCHANGE_TIMEOUT_SECONDS, SECONDS);
        awaitQuietPeriod();

        assertThat(trackerCalls.get()).isEqualTo(1);
        assertThat(receivedBody.toString()).isEqualTo(UPSTREAM_BODY);
        assertThat(unhandledFailure.get()).isNull();
    }

    static Stream<Arguments> upstreamResponsesClosedBeforeTheBodyIsComplete() {
        BiConsumer<Vertx, NetSocket> chunkedWithoutTerminatingChunk =
            HttpConnectionResponseStreamingTest::respondThenCloseWithoutTerminatingChunk;
        BiConsumer<Vertx, NetSocket> contentLengthOneByteOverTheBody =
            HttpConnectionResponseStreamingTest::respondWithContentLengthOneByteOverTheBodyThenClose;
        return Stream.of(
            Arguments.of(
                "chunked response without its terminating chunk",
                HttpClientOptions.DEFAULT_KEEP_ALIVE,
                chunkedWithoutTerminatingChunk
            ),
            Arguments.of("Content-Length declared one byte over the delivered body", false, contentLengthOneByteOverTheBody),
            Arguments.of(
                "chunked coding spelled Chunked",
                HttpClientOptions.DEFAULT_KEEP_ALIVE,
                chunkedWithTransferEncodingFieldLines("Transfer-Encoding: Chunked\r\n")
            ),
            Arguments.of(
                "chunked coding spelled CHUNKED",
                HttpClientOptions.DEFAULT_KEEP_ALIVE,
                chunkedWithTransferEncodingFieldLines("Transfer-Encoding: CHUNKED\r\n")
            ),
            Arguments.of(
                "chunked coding on a second Transfer-Encoding field line",
                HttpClientOptions.DEFAULT_KEEP_ALIVE,
                chunkedWithTransferEncodingFieldLines("Transfer-Encoding: gzip\r\nTransfer-Encoding: chunked\r\n")
            )
        );
    }

    private static BiConsumer<Vertx, NetSocket> chunkedWithTransferEncodingFieldLines(String transferEncodingFieldLines) {
        return (vertx, socket) -> respondWithTransferEncodingThenCloseWithoutTerminatingChunk(transferEncodingFieldLines, vertx, socket);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("upstreamResponsesClosedBeforeTheBodyIsComplete")
    void should_wait_out_the_drain_deadline_instead_of_ending_on_an_inactivity_gap_when_the_upstream_closes_before_the_body_is_complete(
        String framing,
        boolean keepAlive,
        BiConsumer<Vertx, NetSocket> respondThenClose
    ) throws Exception {
        var options = new HttpClientOptions();
        options.setKeepAlive(keepAlive);
        when(endpoint.getHttpClientOptions()).thenReturn(options);

        NetServer truncatingUpstream = startUpstream(socket -> respondThenClose.accept(vertx, socket));

        HttpConnection<HttpResponse> cut = new HttpConnection<>(endpoint, request);

        var receivedBody = new StringBuilder();
        CompletableFuture<String> bodyOnEnd = new CompletableFuture<>();
        long start = System.nanoTime();
        var endedAfterMs = new AtomicLong();

        cut.responseHandler(response -> {
            response.bodyHandler(chunk -> receivedBody.append(chunk.toString()));
            response.endHandler(end -> {
                endedAfterMs.set(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
                bodyOnEnd.complete(receivedBody.toString());
            });
            response.resume();
        });

        cut.connect(context, client, truncatingUpstream.actualPort(), UPSTREAM_HOST, "/", connected -> cut.end(), tracker -> {});

        assertThat(bodyOnEnd.get(EXCHANGE_TIMEOUT_SECONDS, SECONDS)).isEqualTo(UPSTREAM_BODY);
        assertThat(endedAfterMs.get()).isGreaterThanOrEqualTo(900L);
    }

    @Test
    void should_deliver_every_received_byte_when_the_downstream_pauses_for_longer_than_the_drain_budget() throws Exception {
        NetServer contentLengthUpstream = startUpstream(socket -> respondWithContentLengthOneByteOverTheBodyThenClose(vertx, socket));

        HttpConnection<HttpResponse> cut = new HttpConnection<>(endpoint, request);

        var receivedBody = new StringBuilder();
        var downstreamPaused = new AtomicBoolean();
        CompletableFuture<String> bodyOnEnd = new CompletableFuture<>();

        cut.responseHandler(response -> {
            response.bodyHandler(chunk -> {
                receivedBody.append(chunk.toString());
                if (downstreamPaused.compareAndSet(false, true)) {
                    response.pause();
                    vertx.setTimer(DOWNSTREAM_PAUSE_MS, timer -> response.resume());
                }
            });
            response.endHandler(end -> bodyOnEnd.complete(receivedBody.toString()));
            response.resume();
        });

        cut.connect(context, client, contentLengthUpstream.actualPort(), UPSTREAM_HOST, "/", connected -> cut.end(), tracker -> {});

        assertThat(bodyOnEnd.get(EXCHANGE_TIMEOUT_SECONDS, SECONDS)).isEqualTo(UPSTREAM_BODY);
    }

    @Test
    void should_not_deliver_upstream_chunks_while_the_downstream_is_paused_when_the_upstream_closes_mid_response() throws Exception {
        NetServer contentLengthUpstream = startUpstream(socket -> respondWithContentLengthOneByteOverTheBodyThenClose(vertx, socket));

        HttpConnection<HttpResponse> cut = new HttpConnection<>(endpoint, request);

        var receivedBody = new StringBuilder();
        var downstreamPaused = new AtomicBoolean();
        var pausedOnce = new AtomicBoolean();
        var chunksWhilePaused = new AtomicInteger();
        var endSignals = new AtomicInteger();
        CompletableFuture<String> bodyOnEnd = new CompletableFuture<>();

        cut.responseHandler(response -> {
            response.bodyHandler(chunk -> {
                if (downstreamPaused.get()) {
                    chunksWhilePaused.incrementAndGet();
                }
                receivedBody.append(chunk.toString());
                if (pausedOnce.compareAndSet(false, true)) {
                    response.pause();
                    downstreamPaused.set(true);
                    vertx.setTimer(CLIENT_RESUME_DELAY_MS, timer -> {
                        downstreamPaused.set(false);
                        response.resume();
                    });
                }
            });
            response.endHandler(end -> {
                endSignals.incrementAndGet();
                bodyOnEnd.complete(receivedBody.toString());
            });
            response.resume();
        });

        cut.connect(context, client, contentLengthUpstream.actualPort(), UPSTREAM_HOST, "/", connected -> cut.end(), tracker -> {});

        assertThat(bodyOnEnd.get(EXCHANGE_TIMEOUT_SECONDS, SECONDS)).isEqualTo(UPSTREAM_BODY);

        awaitQuietPeriod();

        assertThat(chunksWhilePaused.get()).isZero();
        assertThat(endSignals.get()).isEqualTo(1);
    }

    @Test
    void should_deliver_the_body_and_the_end_signal_when_the_upstream_response_ends_before_the_client_attaches_handlers() throws Exception {
        var options = new HttpClientOptions();
        options.setKeepAlive(false);
        when(endpoint.getHttpClientOptions()).thenReturn(options);

        NetServer completeResponseUpstream = startUpstream(socket ->
            socket
                .write(Buffer.buffer(CONTENT_LENGTH_RESPONSE_HEAD + UPSTREAM_BODY.length() + END_OF_HEADERS + UPSTREAM_BODY))
                .onComplete(written -> socket.close())
        );

        HttpConnection<HttpResponse> cut = new HttpConnection<>(endpoint, request);

        var receivedBody = new StringBuilder();
        var endHandlerCalls = new AtomicInteger();
        CompletableFuture<String> bodyOnEnd = new CompletableFuture<>();

        cut.responseHandler(response ->
            vertx.setTimer(LATE_BODY_HANDLER_ATTACH_DELAY_MS, timer -> {
                response.bodyHandler(chunk -> receivedBody.append(chunk.toString()));
                response.endHandler(end -> {
                    endHandlerCalls.incrementAndGet();
                    bodyOnEnd.complete(receivedBody.toString());
                });
                response.resume();
            })
        );

        cut.connect(context, client, completeResponseUpstream.actualPort(), UPSTREAM_HOST, "/", connected -> cut.end(), tracker -> {});

        assertThat(bodyOnEnd.get(EXCHANGE_TIMEOUT_SECONDS, SECONDS)).isEqualTo(UPSTREAM_BODY);

        awaitQuietPeriod();

        assertThat(endHandlerCalls.get()).isEqualTo(1);
    }

    @Test
    void should_release_the_tracker_once_when_the_connection_is_canceled_mid_response() throws Exception {
        NetServer trickleUpstream = givenAnUpstreamThatSendsHalfTheDeclaredBodyAndStaysOpen();

        CanceledExchange exchange = whenTheConnectionIsCanceledMidResponse(trickleUpstream);

        assertThat(exchange.trackerCalls()).isEqualTo(1);
    }

    @Test
    void should_not_signal_the_downstream_end_when_the_connection_is_canceled_mid_response() throws Exception {
        NetServer trickleUpstream = givenAnUpstreamThatSendsHalfTheDeclaredBodyAndStaysOpen();

        CanceledExchange exchange = whenTheConnectionIsCanceledMidResponse(trickleUpstream);

        assertThat(exchange.receivedBody()).isEqualTo(BODY_FIRST_PART);
        assertThat(exchange.endSignals()).isZero();
    }

    private NetServer givenAnUpstreamThatSendsHalfTheDeclaredBodyAndStaysOpen() throws Exception {
        var options = new HttpClientOptions();
        options.setKeepAlive(false);
        when(endpoint.getHttpClientOptions()).thenReturn(options);

        // Declares the whole body but only ever writes the first half and never closes, so the
        // exchange is genuinely mid-response - with body still outstanding upstream - at the moment
        // the connection is canceled.
        return startUpstream(socket ->
            socket.write(Buffer.buffer(CONTENT_LENGTH_RESPONSE_HEAD + UPSTREAM_BODY.length() + END_OF_HEADERS + BODY_FIRST_PART))
        );
    }

    private CanceledExchange whenTheConnectionIsCanceledMidResponse(NetServer trickleUpstream) throws Exception {
        HttpConnection<HttpResponse> cut = new HttpConnection<>(endpoint, request);

        var receivedBody = new StringBuilder();
        var endSignals = new AtomicInteger();
        var trackerCalls = new AtomicInteger();
        CompletableFuture<Void> canceled = new CompletableFuture<>();

        cut.responseHandler(response -> {
            response.bodyHandler(chunk -> {
                receivedBody.append(chunk.toString());
                // Canceled from the event loop, as the gateway does, while the declared body is
                // only half delivered.
                cut.cancel();
                canceled.complete(null);
            });
            response.endHandler(end -> endSignals.incrementAndGet());
            response.resume();
        });

        cut.connect(
            context,
            client,
            trickleUpstream.actualPort(),
            UPSTREAM_HOST,
            "/",
            connected -> cut.end(),
            tracker -> trackerCalls.incrementAndGet()
        );

        canceled.get(EXCHANGE_TIMEOUT_SECONDS, SECONDS);
        awaitQuietPeriod();

        return new CanceledExchange(receivedBody.toString(), endSignals.get(), trackerCalls.get());
    }

    private record CanceledExchange(String receivedBody, int endSignals, int trackerCalls) {}

    private void awaitQuietPeriod() throws Exception {
        CompletableFuture<Void> quietPeriodElapsed = new CompletableFuture<>();
        vertx.setTimer(QUIET_PERIOD_MS, timer -> quietPeriodElapsed.complete(null));
        quietPeriodElapsed.get(EXCHANGE_TIMEOUT_SECONDS, SECONDS);
    }
}
