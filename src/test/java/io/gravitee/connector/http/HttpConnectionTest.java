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
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.gravitee.common.component.Lifecycle;
import io.gravitee.common.http.HttpMethod;
import io.gravitee.connector.http.endpoint.HttpClientOptions;
import io.gravitee.connector.http.endpoint.HttpEndpoint;
import io.gravitee.connector.http.stub.DummyHttpClientRequest;
import io.gravitee.gateway.api.ExecutionContext;
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
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
    }
}
