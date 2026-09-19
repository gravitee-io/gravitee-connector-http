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
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetSocket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
public class HttpConnectionResponseStreamingTest {

    private static final String BODY_FIRST_PART = "first-part-of-the-upstream-response-body-";
    private static final String BODY_SECOND_PART = "second-part-of-the-upstream-response-body";
    private static final String UPSTREAM_BODY = BODY_FIRST_PART + BODY_SECOND_PART;

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
    public void setUp() throws Exception {
        vertx = Vertx.vertx();
        upstream = vertx
            .createNetServer()
            .connectHandler(socket -> socket.handler(upstreamRequest -> respondThenCloseWithoutTerminatingChunk(socket)))
            .listen(0)
            .toCompletionStage()
            .toCompletableFuture()
            .get(EXCHANGE_TIMEOUT_SECONDS, SECONDS);

        client = vertx.createHttpClient(new io.vertx.core.http.HttpClientOptions().setKeepAlive(false));

        lenient().when(endpoint.getHttpClientOptions()).thenReturn(new HttpClientOptions());
        when(request.headers()).thenReturn(HttpHeaders.create());
        when(request.method()).thenReturn(HttpMethod.GET);
        when(request.uri()).thenReturn("http://localhost");
        when(context.getTracer()).thenReturn(new Tracer(null, new NoOpTracer()));
        lenient().when(request.metrics()).thenReturn(Metrics.on(System.currentTimeMillis()).build());
    }

    @AfterEach
    public void tearDown() throws Exception {
        client.close().toCompletionStage().toCompletableFuture().get(EXCHANGE_TIMEOUT_SECONDS, SECONDS);
        upstream.close().toCompletionStage().toCompletableFuture().get(EXCHANGE_TIMEOUT_SECONDS, SECONDS);
        vertx.close().toCompletionStage().toCompletableFuture().get(EXCHANGE_TIMEOUT_SECONDS, SECONDS);
    }

    private void respondThenCloseWithoutTerminatingChunk(NetSocket socket) {
        socket.write(Buffer.buffer("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n" + chunk(BODY_FIRST_PART)));
        vertx.setTimer(UPSTREAM_TRICKLE_DELAY_MS, timer ->
            socket.write(Buffer.buffer(chunk(BODY_SECOND_PART))).onComplete(written -> socket.close())
        );
    }

    private static String chunk(String payload) {
        return Integer.toHexString(payload.length()) + "\r\n" + payload + "\r\n";
    }

    @Test
    public void should_deliver_the_whole_upstream_body_when_connection_is_closed_while_response_is_paused() throws Exception {
        HttpConnection<HttpResponse> cut = new HttpConnection<>(endpoint, request);

        var receivedBody = new StringBuilder();
        var receivedStatus = new AtomicInteger();
        var endHandlerCalls = new AtomicInteger();
        var trackerCalls = new AtomicInteger();
        CompletableFuture<String> bodyOnEnd = new CompletableFuture<>();

        cut.responseHandler(response -> {
            receivedStatus.set(response.status());
            response.bodyHandler(chunk -> receivedBody.append(chunk.toString()));
            response.endHandler(end -> {
                endHandlerCalls.incrementAndGet();
                bodyOnEnd.complete(receivedBody.toString());
            });
            vertx.setTimer(CLIENT_RESUME_DELAY_MS, timer -> response.resume());
        });

        cut.connect(
            context,
            client,
            upstream.actualPort(),
            "localhost",
            "/",
            connected -> cut.end(),
            tracker -> trackerCalls.incrementAndGet()
        );

        assertThat(bodyOnEnd.get(EXCHANGE_TIMEOUT_SECONDS, SECONDS)).isEqualTo(UPSTREAM_BODY);
        assertThat(receivedStatus.get()).isEqualTo(200);

        awaitQuietPeriod();

        assertThat(endHandlerCalls.get()).isEqualTo(1);
        assertThat(trackerCalls.get()).isEqualTo(1);
    }

    @Test
    public void should_not_deliver_upstream_chunks_after_the_response_has_ended() throws Exception {
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
                // A client that takes one chunk at a time and comes back for the next, which is
                // what the gateway does as downstream demand runs out and is replenished.
                response.pause();
                vertx.setTimer(DOWNSTREAM_CHUNK_PAUSE_MS, timer -> response.resume());
            });
            response.endHandler(end -> {
                endSignals.incrementAndGet();
                ended.complete(null);
                vertx.setTimer(CLIENT_RESUME_DELAY_MS, timer -> response.resume());
            });
        });

        cut.connect(context, client, upstream.actualPort(), "localhost", "/", connected -> cut.end(), tracker -> {});

        ended.get(EXCHANGE_TIMEOUT_SECONDS, SECONDS);
        CompletableFuture<Void> quietPeriodElapsed = new CompletableFuture<>();
        vertx.setTimer(CLIENT_RESUME_DELAY_MS * 2, timer -> quietPeriodElapsed.complete(null));
        quietPeriodElapsed.get(EXCHANGE_TIMEOUT_SECONDS, SECONDS);

        assertThat(bodyBeforeEnd.toString()).isEqualTo(UPSTREAM_BODY);
        assertThat(endSignals.get()).isEqualTo(1);
        assertThat(chunksAfterEnd.get()).isZero();
    }

    @Test
    public void should_not_lose_upstream_chunks_received_before_the_body_handler_is_attached_when_keep_alive_is_disabled()
        throws Exception {
        HttpClientOptions options = new HttpClientOptions();
        options.setKeepAlive(false);
        when(endpoint.getHttpClientOptions()).thenReturn(options);

        HttpConnection<HttpResponse> cut = new HttpConnection<>(endpoint, request);

        var receivedBody = new StringBuilder();
        CompletableFuture<String> bodyOnEnd = new CompletableFuture<>();

        cut.responseHandler(response ->
            vertx.setTimer(BODY_HANDLER_ATTACH_DELAY_MS, timer -> {
                response.bodyHandler(chunk -> receivedBody.append(chunk.toString()));
                response.endHandler(end -> bodyOnEnd.complete(receivedBody.toString()));
            })
        );

        cut.connect(context, client, upstream.actualPort(), "localhost", "/", connected -> cut.end(), tracker -> {});

        assertThat(bodyOnEnd.get(EXCHANGE_TIMEOUT_SECONDS, SECONDS)).isEqualTo(UPSTREAM_BODY);
    }

    @Test
    public void should_end_the_exchange_without_failure_when_the_connection_closes_before_the_client_registers_handlers() throws Exception {
        AtomicReference<Throwable> unhandledFailure = new AtomicReference<>();
        vertx.exceptionHandler(unhandledFailure::set);

        HttpConnection<HttpResponse> cut = new HttpConnection<>(endpoint, request);

        AtomicInteger receivedStatus = new AtomicInteger();
        CompletableFuture<Void> trackerReleased = new CompletableFuture<>();

        cut.responseHandler(response -> receivedStatus.set(response.status()));

        cut.connect(
            context,
            client,
            upstream.actualPort(),
            "localhost",
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
    public void should_release_the_tracker_once_when_the_client_registers_a_body_handler_but_no_end_handler() throws Exception {
        AtomicReference<Throwable> unhandledFailure = new AtomicReference<>();
        vertx.exceptionHandler(unhandledFailure::set);

        HttpConnection<HttpResponse> cut = new HttpConnection<>(endpoint, request);

        StringBuilder receivedBody = new StringBuilder();
        AtomicInteger trackerCalls = new AtomicInteger();
        CompletableFuture<Void> trackerReleased = new CompletableFuture<>();

        cut.responseHandler(response -> {
            response.bodyHandler(chunk -> receivedBody.append(chunk.toString()));
            vertx.setTimer(CLIENT_RESUME_DELAY_MS, timer -> response.resume());
        });

        cut.connect(
            context,
            client,
            upstream.actualPort(),
            "localhost",
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

    @Test
    public void should_wait_for_the_drain_deadline_instead_of_a_short_quiet_gap_when_content_length_is_declared_but_never_fully_delivered()
        throws Exception {
        HttpClientOptions options = new HttpClientOptions();
        options.setKeepAlive(false);
        when(endpoint.getHttpClientOptions()).thenReturn(options);

        // One byte more than the backend will ever actually send: Netty can then never satisfy the
        // declared length, so this deterministically forces the same close/exceptionHandler
        // recovery path as respondThenCloseWithoutTerminatingChunk, but for Content-Length framing.
        long declaredContentLength = UPSTREAM_BODY.length() + 1;
        NetServer contentLengthUpstream = vertx
            .createNetServer()
            .connectHandler(socket ->
                socket.handler(upstreamRequest -> {
                    socket.write(
                        Buffer.buffer("HTTP/1.1 200 OK\r\nContent-Length: " + declaredContentLength + "\r\n\r\n" + BODY_FIRST_PART)
                    );
                    vertx.setTimer(UPSTREAM_TRICKLE_DELAY_MS, timer ->
                        socket.write(Buffer.buffer(BODY_SECOND_PART)).onComplete(written -> socket.close())
                    );
                })
            )
            .listen(0)
            .toCompletionStage()
            .toCompletableFuture()
            .get(EXCHANGE_TIMEOUT_SECONDS, SECONDS);

        try {
            HttpConnection<HttpResponse> cut = new HttpConnection<>(endpoint, request);

            var receivedBody = new StringBuilder();
            CompletableFuture<String> bodyOnEnd = new CompletableFuture<>();
            long start = System.nanoTime();
            AtomicLong endedAfterMs = new AtomicLong();

            cut.responseHandler(response -> {
                response.bodyHandler(chunk -> receivedBody.append(chunk.toString()));
                response.endHandler(end -> {
                    endedAfterMs.set(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
                    bodyOnEnd.complete(receivedBody.toString());
                });
            });

            cut.connect(context, client, contentLengthUpstream.actualPort(), "localhost", "/", connected -> cut.end(), tracker -> {});

            assertThat(bodyOnEnd.get(EXCHANGE_TIMEOUT_SECONDS, SECONDS)).isEqualTo(UPSTREAM_BODY);
            // The old quiet-gap heuristic (60ms of inactivity) would have ended this exchange almost
            // immediately after the last chunk arrived. A declared Content-Length that is never fully
            // satisfied must instead wait out the full drain deadline (1000ms) before giving up.
            assertThat(endedAfterMs.get()).isGreaterThanOrEqualTo(900L);
        } finally {
            contentLengthUpstream.close().toCompletionStage().toCompletableFuture().get(EXCHANGE_TIMEOUT_SECONDS, SECONDS);
        }
    }

    @Test
    public void should_wait_for_the_drain_deadline_instead_of_a_short_quiet_gap_when_the_upstream_response_is_chunked() throws Exception {
        HttpConnection<HttpResponse> cut = new HttpConnection<>(endpoint, request);

        var receivedBody = new StringBuilder();
        CompletableFuture<String> bodyOnEnd = new CompletableFuture<>();
        long start = System.nanoTime();
        AtomicLong endedAfterMs = new AtomicLong();

        cut.responseHandler(response -> {
            response.bodyHandler(chunk -> receivedBody.append(chunk.toString()));
            response.endHandler(end -> {
                endedAfterMs.set(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
                bodyOnEnd.complete(receivedBody.toString());
            });
        });

        cut.connect(context, client, upstream.actualPort(), "localhost", "/", connected -> cut.end(), tracker -> {});

        assertThat(bodyOnEnd.get(EXCHANGE_TIMEOUT_SECONDS, SECONDS)).isEqualTo(UPSTREAM_BODY);
        // The terminating chunk never arrives here, so nothing tells the connector the body is
        // complete and it has to wait out the full drain deadline (1000ms). Ending on the 60ms
        // quiet gap instead would be faster and would silence the give-up log, but that gap is a
        // guess that fires between two chunks still to come - the failure APIM-15055 was opened
        // for. Chunked responses deliberately do not take it: an over-eager log costs nothing a
        // client can see, and a body cut short costs everything.
        assertThat(endedAfterMs.get()).isGreaterThanOrEqualTo(900L);
    }

    @Test
    public void should_deliver_every_received_byte_when_the_downstream_pauses_for_longer_than_the_drain_budget() throws Exception {
        // One byte more than the backend ever sends, the same device the deadline test above uses:
        // Netty can then never complete the message, which is what makes the connection close
        // surface as HttpClosedException and put the connector on its drain path. What is at stake
        // here is not that missing byte but the bytes that did arrive and are still queued.
        long declaredContentLength = UPSTREAM_BODY.length() + 1;
        NetServer contentLengthUpstream = vertx
            .createNetServer()
            .connectHandler(socket ->
                socket.handler(upstreamRequest -> {
                    socket.write(
                        Buffer.buffer("HTTP/1.1 200 OK\r\nContent-Length: " + declaredContentLength + "\r\n\r\n" + BODY_FIRST_PART)
                    );
                    vertx.setTimer(UPSTREAM_TRICKLE_DELAY_MS, timer ->
                        socket.write(Buffer.buffer(BODY_SECOND_PART)).onComplete(written -> socket.close())
                    );
                })
            )
            .listen(0)
            .toCompletionStage()
            .toCompletableFuture()
            .get(EXCHANGE_TIMEOUT_SECONDS, SECONDS);

        try {
            HttpConnection<HttpResponse> cut = new HttpConnection<>(endpoint, request);

            var receivedBody = new StringBuilder();
            var downstreamPaused = new AtomicBoolean();
            CompletableFuture<String> bodyOnEnd = new CompletableFuture<>();

            cut.responseHandler(response -> {
                response.bodyHandler(chunk -> {
                    receivedBody.append(chunk.toString());
                    // The gateway pauses whenever downstream demand drops to zero
                    // (FlowableProxyResponse.handleChunk), and a slow client holds that pause well
                    // past the drain budget.
                    if (downstreamPaused.compareAndSet(false, true)) {
                        response.pause();
                        vertx.setTimer(DOWNSTREAM_PAUSE_MS, timer -> response.resume());
                    }
                });
                response.endHandler(end -> bodyOnEnd.complete(receivedBody.toString()));
            });

            cut.connect(context, client, contentLengthUpstream.actualPort(), "localhost", "/", connected -> cut.end(), tracker -> {});

            assertThat(bodyOnEnd.get(EXCHANGE_TIMEOUT_SECONDS, SECONDS)).isEqualTo(UPSTREAM_BODY);
        } finally {
            contentLengthUpstream.close().toCompletionStage().toCompletableFuture().get(EXCHANGE_TIMEOUT_SECONDS, SECONDS);
        }
    }

    @Test
    public void should_deliver_the_body_and_the_end_signal_when_the_upstream_response_ends_before_the_client_attaches_handlers()
        throws Exception {
        HttpClientOptions options = new HttpClientOptions();
        options.setKeepAlive(false);
        when(endpoint.getHttpClientOptions()).thenReturn(options);

        NetServer completeResponseUpstream = vertx
            .createNetServer()
            .connectHandler(socket ->
                socket.handler(upstreamRequest ->
                    socket
                        .write(Buffer.buffer("HTTP/1.1 200 OK\r\nContent-Length: " + UPSTREAM_BODY.length() + "\r\n\r\n" + UPSTREAM_BODY))
                        .onComplete(written -> socket.close())
                )
            )
            .listen(0)
            .toCompletionStage()
            .toCompletableFuture()
            .get(EXCHANGE_TIMEOUT_SECONDS, SECONDS);

        try {
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
                })
            );

            cut.connect(context, client, completeResponseUpstream.actualPort(), "localhost", "/", connected -> cut.end(), tracker -> {});

            assertThat(bodyOnEnd.get(EXCHANGE_TIMEOUT_SECONDS, SECONDS)).isEqualTo(UPSTREAM_BODY);

            awaitQuietPeriod();

            assertThat(endHandlerCalls.get()).isEqualTo(1);
        } finally {
            completeResponseUpstream.close().toCompletionStage().toCompletableFuture().get(EXCHANGE_TIMEOUT_SECONDS, SECONDS);
        }
    }

    @Test
    public void should_release_the_tracker_once_and_leave_the_downstream_end_to_the_gateway_when_the_connection_is_canceled_mid_response()
        throws Exception {
        HttpClientOptions options = new HttpClientOptions();
        options.setKeepAlive(false);
        when(endpoint.getHttpClientOptions()).thenReturn(options);

        // Declares the whole body but only ever writes the first half and never closes, so the
        // exchange is genuinely mid-response - with body still outstanding upstream - at the moment
        // the connection is canceled.
        NetServer trickleUpstream = vertx
            .createNetServer()
            .connectHandler(socket ->
                socket.handler(upstreamRequest ->
                    socket.write(
                        Buffer.buffer("HTTP/1.1 200 OK\r\nContent-Length: " + UPSTREAM_BODY.length() + "\r\n\r\n" + BODY_FIRST_PART)
                    )
                )
            )
            .listen(0)
            .toCompletionStage()
            .toCompletableFuture()
            .get(EXCHANGE_TIMEOUT_SECONDS, SECONDS);

        try {
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
            });

            cut.connect(
                context,
                client,
                trickleUpstream.actualPort(),
                "localhost",
                "/",
                connected -> cut.end(),
                tracker -> trackerCalls.incrementAndGet()
            );

            canceled.get(EXCHANGE_TIMEOUT_SECONDS, SECONDS);
            awaitQuietPeriod();

            // Every gateway caller of Connection.cancel() has already terminated the downstream by
            // its own hand before cancelling - FlowableProxyResponse calls subscriber.onComplete()
            // itself, or is reacting to a cancelled Rx subscription, and ApiReactorHandler has
            // already written an error response. An end signal from here would be a second terminal
            // signal on a stream that is already finished, so the connector deliberately sends none.
            assertThat(endSignals.get()).isZero();
            assertThat(receivedBody.toString()).isEqualTo(BODY_FIRST_PART);
            assertThat(trackerCalls.get()).isEqualTo(1);
        } finally {
            trickleUpstream.close().toCompletionStage().toCompletableFuture().get(EXCHANGE_TIMEOUT_SECONDS, SECONDS);
        }
    }

    private void awaitQuietPeriod() throws Exception {
        CompletableFuture<Void> quietPeriodElapsed = new CompletableFuture<>();
        vertx.setTimer(QUIET_PERIOD_MS, timer -> quietPeriodElapsed.complete(null));
        quietPeriodElapsed.get(EXCHANGE_TIMEOUT_SECONDS, SECONDS);
    }
}
