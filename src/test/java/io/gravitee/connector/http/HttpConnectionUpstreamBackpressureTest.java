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
import io.gravitee.connector.api.Response;
import io.gravitee.connector.http.endpoint.HttpClientOptions;
import io.gravitee.connector.http.endpoint.HttpEndpoint;
import io.gravitee.gateway.api.ExecutionContext;
import io.gravitee.gateway.api.http.HttpHeaders;
import io.gravitee.gateway.api.proxy.ProxyRequest;
import io.gravitee.gateway.reactive.api.tracing.Tracer;
import io.gravitee.node.opentelemetry.tracer.noop.NoOpTracer;
import io.gravitee.reporter.api.http.Metrics;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerResponse;
import java.util.concurrent.CompletableFuture;
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
public class HttpConnectionUpstreamBackpressureTest {

    private static final long EXCHANGE_TIMEOUT_SECONDS = 5;
    private static final int UPSTREAM_CHUNK_SIZE = 16 * 1024;
    private static final int UPSTREAM_CHUNK_COUNT = 512;
    private static final long UPSTREAM_BODY_SIZE = (long) UPSTREAM_CHUNK_SIZE * UPSTREAM_CHUNK_COUNT;
    private static final long PAUSED_DOWNSTREAM_WAIT_MS = 500;

    private Vertx vertx;
    private HttpServer upstream;
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
            .createHttpServer()
            .requestHandler(upstreamRequest ->
                writeChunks(upstreamRequest.response().setChunked(true), Buffer.buffer(new byte[UPSTREAM_CHUNK_SIZE]), UPSTREAM_CHUNK_COUNT)
            )
            .listen(0)
            .toCompletionStage()
            .toCompletableFuture()
            .get(EXCHANGE_TIMEOUT_SECONDS, SECONDS);

        client = vertx.createHttpClient(new io.vertx.core.http.HttpClientOptions().setKeepAlive(false));

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

    private static void writeChunks(HttpServerResponse upstreamResponse, Buffer chunk, int remaining) {
        int left = remaining;
        while (left > 0 && !upstreamResponse.writeQueueFull()) {
            upstreamResponse.write(chunk);
            left--;
        }
        if (left == 0) {
            upstreamResponse.end();
        } else {
            int stillToWrite = left;
            upstreamResponse.drainHandler(drained -> writeChunks(upstreamResponse, chunk, stillToWrite));
        }
    }

    @Test
    void should_stop_delivering_upstream_chunks_while_the_downstream_is_paused_on_a_non_keep_alive_endpoint() throws Exception {
        var options = new HttpClientOptions();
        options.setKeepAlive(false);
        when(endpoint.getHttpClientOptions()).thenReturn(options);

        HttpConnection<HttpResponse> cut = new HttpConnection<>(endpoint, request);

        var deliveredBytes = new AtomicLong();
        var endSignals = new AtomicInteger();
        var pausedResponse = new AtomicReference<Response>();
        var downstreamContext = new AtomicReference<Context>();
        CompletableFuture<Long> deliveredOnEnd = new CompletableFuture<>();

        cut.responseHandler(response -> {
            response.bodyHandler(chunk -> {
                if (deliveredBytes.getAndAdd(chunk.length()) == 0) {
                    downstreamContext.set(Vertx.currentContext());
                    response.pause();
                    pausedResponse.set(response);
                }
            });
            response.endHandler(end -> {
                endSignals.incrementAndGet();
                deliveredOnEnd.complete(deliveredBytes.get());
            });
            response.resume();
        });

        cut.connect(context, client, upstream.actualPort(), "localhost", "/", connected -> cut.end(), tracker -> {});

        Thread.sleep(PAUSED_DOWNSTREAM_WAIT_MS);
        assertThat(pausedResponse.get()).isNotNull();
        assertThat(deliveredBytes.get()).isLessThan(UPSTREAM_BODY_SIZE / 4);

        downstreamContext.get().runOnContext(v -> pausedResponse.get().resume());

        assertThat(deliveredOnEnd.get(EXCHANGE_TIMEOUT_SECONDS, SECONDS)).isEqualTo(UPSTREAM_BODY_SIZE);
        assertThat(endSignals.get()).isEqualTo(1);
    }
}
