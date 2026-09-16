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
import java.util.concurrent.atomic.AtomicInteger;
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
    private static final long CLIENT_RESUME_DELAY_MS = 500;
    private static final long QUIET_PERIOD_MS = 500;
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
                response.pause();
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

        assertThat(bodyBeforeEnd.toString()).isEqualTo(BODY_FIRST_PART);
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

    private void awaitQuietPeriod() throws Exception {
        CompletableFuture<Void> quietPeriodElapsed = new CompletableFuture<>();
        vertx.setTimer(QUIET_PERIOD_MS, timer -> quietPeriodElapsed.complete(null));
        quietPeriodElapsed.get(EXCHANGE_TIMEOUT_SECONDS, SECONDS);
    }
}
