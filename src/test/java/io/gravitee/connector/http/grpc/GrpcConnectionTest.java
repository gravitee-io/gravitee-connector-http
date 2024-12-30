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
package io.gravitee.connector.http.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import io.gravitee.connector.http.endpoint.HttpClientOptions;
import io.gravitee.connector.http.endpoint.HttpEndpoint;
import io.gravitee.connector.http.stub.DummyHttpClientRequest;
import io.gravitee.gateway.api.ExecutionContext;
import io.gravitee.gateway.api.http.HttpHeaderNames;
import io.gravitee.gateway.api.http.HttpHeaders;
import io.gravitee.gateway.api.proxy.ProxyRequest;
import io.gravitee.gateway.reactive.api.tracing.Tracer;
import io.gravitee.node.opentelemetry.tracer.noop.NoOpTracer;
import io.vertx.core.Future;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.RequestOptions;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class GrpcConnectionTest {

    public static final String FIRST_HEADER = "First-Header";
    public static final String SECOND_HEADER = "Second-Header";
    public static final String FIRST_HEADER_VALUE_1 = "first-header-value-1";
    public static final String FIRST_HEADER_VALUE_2 = "first-header-value-2";
    public static final String SECOND_HEADER_VALUE = "second-header-value";
    protected static final String BROTLI = "br";

    private GrpcConnection cut;

    @Mock
    private ExecutionContext executionContext;

    @Mock
    private HttpEndpoint endpoint;

    @Mock
    private ProxyRequest request;

    @Mock
    private HttpClient client;

    private HttpClientRequest httpClientRequest;

    private HttpHeaders headers;
    private HttpClientOptions httpClientOptions;

    @Before
    public void setUp() {
        cut = new GrpcConnection(endpoint, request);

        headers = HttpHeaders.create();
        headers.add(FIRST_HEADER, FIRST_HEADER_VALUE_1);
        headers.add(FIRST_HEADER, FIRST_HEADER_VALUE_2);
        headers.add(SECOND_HEADER, SECOND_HEADER_VALUE);
        headers.add(HttpHeaderNames.HOST, "my-host");
        headers.add(HttpHeaderNames.TRANSFER_ENCODING, "transfer_encoding");

        when(request.headers()).thenReturn(headers);

        httpClientOptions = new HttpClientOptions();
        when(endpoint.getHttpClientOptions()).thenReturn(httpClientOptions);
        when(client.request(any(RequestOptions.class)))
            .thenAnswer(invocation -> {
                RequestOptions options = invocation.getArgument(0);
                httpClientRequest = spy(new DummyHttpClientRequest(options));
                return Future.succeededFuture(httpClientRequest);
            });

        when(executionContext.getTracer()).thenReturn(new Tracer(null, new NoOpTracer()));
    }

    @Test
    public void should_write_upstream_headers() {
        cut.connect(
            executionContext,
            client,
            getAvailablePort(),
            "host",
            "/",
            unused -> {},
            result -> new AtomicInteger(1).decrementAndGet()
        );

        cut.writeUpstreamHeaders();

        assertThat(headers.contains(io.vertx.core.http.HttpHeaders.TRANSFER_ENCODING)).isFalse();
        assertThat(httpClientRequest.headers().getAll(SECOND_HEADER)).hasSize(1).containsExactly(SECOND_HEADER_VALUE);

        assertThat(httpClientRequest.headers().getAll(FIRST_HEADER)).hasSize(2).containsExactly(FIRST_HEADER_VALUE_1, FIRST_HEADER_VALUE_2);
    }

    @Test
    public void should_prevent_duplicated_headers() {
        cut.connect(
            executionContext,
            client,
            getAvailablePort(),
            "host",
            "/",
            unused -> {},
            result -> new AtomicInteger(1).decrementAndGet()
        );

        headers.add(HttpHeaderNames.CONTENT_TYPE, "application/grpc");

        cut.writeUpstreamHeaders();

        assertThat(httpClientRequest.headers().getAll(HttpHeaderNames.CONTENT_TYPE)).hasSize(1).containsExactly("application/grpc");
    }

    @Test
    public void should_remove_host_header() {
        cut.connect(
            executionContext,
            client,
            getAvailablePort(),
            "host",
            "/",
            unused -> {},
            result -> new AtomicInteger(1).decrementAndGet()
        );

        cut.writeUpstreamHeaders();

        assertThat(httpClientRequest.headers().contains(HttpHeaderNames.HOST)).isFalse();
    }

    private int getAvailablePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }
}
