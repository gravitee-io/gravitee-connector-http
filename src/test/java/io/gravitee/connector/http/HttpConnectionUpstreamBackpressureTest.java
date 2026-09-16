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
import java.util.concurrent.CompletableFuture;
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

    private static final String BODY_FIRST_CHUNK = "first-part-of-the-upstream-response-body-";
    private static final String BODY_SECOND_CHUNK = "second-part-of-the-upstream-response-body";
    private static final String UPSTREAM_BODY = BODY_FIRST_CHUNK + BODY_SECOND_CHUNK;
    private static final long UPSTREAM_TRICKLE_DELAY_MS = 100;
    private static final long EXCHANGE_TIMEOUT_SECONDS = 5;

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
            .connectHandler(socket ->
                socket.handler(upstreamRequest -> {
                    socket.write(Buffer.buffer("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n" + chunk(BODY_FIRST_CHUNK)));
                    vertx.setTimer(UPSTREAM_TRICKLE_DELAY_MS, timer -> socket.write(Buffer.buffer(chunk(BODY_SECOND_CHUNK) + "0\r\n\r\n")));
                })
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

    private static String chunk(String payload) {
        return Integer.toHexString(payload.length()) + "\r\n" + payload + "\r\n";
    }

    @Test
    public void should_deliver_the_response_body_for_a_non_keep_alive_endpoint_without_the_downstream_ever_calling_resume()
        throws Exception {
        HttpClientOptions options = new HttpClientOptions();
        options.setKeepAlive(false);
        when(endpoint.getHttpClientOptions()).thenReturn(options);

        HttpConnection<HttpResponse> cut = new HttpConnection<>(endpoint, request);

        var receivedBody = new StringBuilder();
        CompletableFuture<String> bodyOnEnd = new CompletableFuture<>();

        cut.responseHandler(response -> {
            response.bodyHandler(chunk -> receivedBody.append(chunk.toString()));
            // Deliberately never calls response.resume() - a non-keep-alive endpoint must not
            // depend on it to make progress, since the whole point of this fix is to stop
            // pausing (and needing a matching resume) for these endpoints in the first place.
            response.endHandler(end -> bodyOnEnd.complete(receivedBody.toString()));
        });

        cut.connect(context, client, upstream.actualPort(), "localhost", "/", connected -> cut.end(), tracker -> {});

        assertThat(bodyOnEnd.get(EXCHANGE_TIMEOUT_SECONDS, SECONDS)).isEqualTo(UPSTREAM_BODY);
    }
}
