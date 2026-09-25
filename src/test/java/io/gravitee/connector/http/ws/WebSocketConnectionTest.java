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
package io.gravitee.connector.http.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.gravitee.common.http.HttpMethod;
import io.gravitee.connector.http.HttpConnector;
import io.gravitee.connector.http.endpoint.HttpClientOptions;
import io.gravitee.connector.http.endpoint.HttpEndpoint;
import io.gravitee.gateway.api.ExecutionContext;
import io.gravitee.gateway.api.http.HttpHeaders;
import io.gravitee.gateway.api.proxy.ws.WebSocketProxyRequest;
import io.gravitee.node.api.configuration.Configuration;
import io.gravitee.reporter.api.http.Metrics;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Proxies a WebSocket through the connector to a real upstream that supports per-message deflate,
 * with a downstream client that offers compression the way browsers do.
 */
class WebSocketConnectionTest {

    private static final String SEC_WEBSOCKET_EXTENSIONS = "Sec-WebSocket-Extensions";
    private static final String CLIENT_OFFER = "permessage-deflate; client_max_window_bits";
    private static final String MESSAGE = "hello from upstream ".repeat(20);
    private static final String CLOSED = "<connection closed before any message>";

    private Vertx vertx;
    private HttpServer upstream;
    private HttpConnector connector;

    private final CompletableFuture<String> upstreamOffer = new CompletableFuture<>();
    private final CompletableFuture<String> relayed = new CompletableFuture<>();

    @BeforeEach
    void setUp() throws Exception {
        vertx = Vertx.vertx();
        upstream = vertx
            .createHttpServer(new HttpServerOptions().setPerMessageWebSocketCompressionSupported(true))
            .webSocketHandler(ws -> {
                String offer = ws.headers().get(SEC_WEBSOCKET_EXTENSIONS);
                upstreamOffer.complete(offer == null ? "" : offer);
                ws.writeTextMessage(MESSAGE);
            })
            .listen(0)
            .toCompletionStage()
            .toCompletableFuture()
            .get(5, TimeUnit.SECONDS);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connector != null) {
            connector.stop();
        }
        vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    @Test
    void should_relay_compressed_upstream_messages_when_compression_is_enabled() throws Exception {
        proxyThroughConnector(true);

        assertThat(relayed.get(5, TimeUnit.SECONDS)).isEqualTo(MESSAGE);
        assertThat(upstreamOffer.get(5, TimeUnit.SECONDS)).contains("permessage-deflate");
    }

    @Test
    void should_not_offer_compression_upstream_when_compression_is_disabled() throws Exception {
        proxyThroughConnector(false);

        assertThat(relayed.get(5, TimeUnit.SECONDS)).isEqualTo(MESSAGE);
        assertThat(upstreamOffer.get(5, TimeUnit.SECONDS)).doesNotContain("deflate");
    }

    private void proxyThroughConnector(boolean useCompression) throws Exception {
        String target = "http://localhost:" + upstream.actualPort() + "/ws";

        HttpClientOptions clientOptions = new HttpClientOptions();
        clientOptions.setUseCompression(useCompression);
        HttpEndpoint endpoint = mock(HttpEndpoint.class);
        when(endpoint.getHttpClientOptions()).thenReturn(clientOptions);
        when(endpoint.target()).thenReturn(target);

        Configuration configuration = mock(Configuration.class);
        lenient().when(configuration.getProperty("http.ssl.openssl", Boolean.class, false)).thenReturn(false);

        HttpHeaders headers = HttpHeaders.create();
        headers.set("Connection", "Upgrade");
        headers.set("Upgrade", "websocket");
        headers.set("Sec-WebSocket-Version", "13");
        headers.set("Sec-WebSocket-Key", "dGhlIHNhbXBsZSBub25jZQ==");
        headers.set(SEC_WEBSOCKET_EXTENSIONS, CLIENT_OFFER);

        WebSocketProxyRequest downstream = mock(WebSocketProxyRequest.class);
        when(downstream.method()).thenReturn(HttpMethod.GET);
        when(downstream.uri()).thenReturn(target);
        when(downstream.headers()).thenReturn(headers);
        when(downstream.metrics()).thenReturn(Metrics.on(0L).build());
        when(downstream.upgrade()).thenReturn(CompletableFuture.completedFuture(downstream));
        when(downstream.write(any())).thenAnswer(invocation -> {
            io.gravitee.gateway.api.ws.WebSocketFrame frame = invocation.getArgument(0);
            if (frame.type() == io.gravitee.gateway.api.ws.WebSocketFrame.Type.TEXT) {
                relayed.complete(frame.data().toString());
            }
            return downstream;
        });
        when(downstream.close()).thenAnswer(invocation -> {
            relayed.complete(CLOSED);
            return downstream;
        });

        connector = new HttpConnector(endpoint, configuration);
        connector.start();

        vertx.getOrCreateContext().runOnContext(v -> connector.request(mock(ExecutionContext.class), downstream, connection -> {}));
    }
}
