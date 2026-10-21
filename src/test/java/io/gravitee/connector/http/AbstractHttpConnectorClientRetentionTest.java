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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.gravitee.connector.http.endpoint.HttpEndpoint;
import io.gravitee.connector.http.ws.WebSocketConnection;
import io.gravitee.gateway.api.proxy.ProxyRequest;
import io.gravitee.node.api.configuration.Configuration;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.internal.CloseFuture;
import io.vertx.core.internal.ContextInternal;
import io.vertx.core.internal.VertxInternal;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Guards the shared-client workaround of {@link AbstractHttpConnector}: the clients of a stopped connector must leave the
 * close future of the event loop context that created them.
 */
class AbstractHttpConnectorClientRetentionTest {

    private static final int REDEPLOYS = 20;

    private Vertx vertx;
    private Configuration configuration;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
        configuration = mock(Configuration.class);
        lenient()
            .when(configuration.getProperty(anyString(), any(), any()))
            .thenAnswer(invocation -> invocation.getArgument(2));
    }

    @AfterEach
    void tearDown() throws Exception {
        vertx.close().await(10, TimeUnit.SECONDS);
    }

    @Test
    void should_not_retain_the_clients_of_stopped_connectors() throws Exception {
        // Each iteration mimics an API redeploy: a new connector serves an HTTP and a WebSocket request on the event
        // loop of the deployed verticle, then is stopped.
        AtomicReference<CloseFuture> closeFuture = new AtomicReference<>();
        AtomicReference<Integer> before = new AtomicReference<>();
        AtomicReference<Integer> created = new AtomicReference<>(0);

        vertx
            .deployVerticle(
                new AbstractVerticle() {
                    @Override
                    public void start() throws Exception {
                        closeFuture.set(((ContextInternal) context).closeFuture());
                        before.set(retained(closeFuture.get()));
                        for (int i = 0; i < REDEPLOYS; i++) {
                            TestConnector connector = new TestConnector(configuration);
                            connector.start();
                            connector.request(null, proxyRequest(), connection -> {});
                            connector.webSocket = true;
                            connector.request(null, proxyRequest(), connection -> {});
                            created.set(created.get() + connector.httpClients.size() + connector.webSocketClients.size());
                            connector.stop();
                        }
                    }
                }
            )
            .await(10, TimeUnit.SECONDS);

        assertThat(closeFuture.get()).isNotSameAs(((VertxInternal) vertx).closeFuture());
        // Without this the test would pass vacuously.
        assertThat(created.get()).isEqualTo(2 * REDEPLOYS);
        awaitRetained(closeFuture.get(), before.get());
        assertThat(retained(closeFuture.get()) - before.get()).isZero();
    }

    @Test
    void should_still_leak_when_the_client_comes_straight_from_vertx() throws Exception {
        CloseFuture root = ((VertxInternal) vertx).closeFuture();
        int before = retained(root);

        for (int i = 0; i < REDEPLOYS; i++) {
            vertx.createHttpClient(new HttpClientOptions()).close().await(10, TimeUnit.SECONDS);
        }

        // When this starts failing, Vert.x has been fixed (5.2): drop the shared-client workaround and this test class.
        assertThat(retained(root) - before).isEqualTo(REDEPLOYS);
    }

    private static ProxyRequest proxyRequest() {
        ProxyRequest request = mock(ProxyRequest.class, RETURNS_DEEP_STUBS);
        when(request.uri()).thenReturn("http://localhost:8080/api");
        when(request.parameters()).thenReturn(null);
        return request;
    }

    private static void awaitRetained(CloseFuture closeFuture, int expected) throws InterruptedException {
        // close() completes asynchronously, the nested close future unregisters itself once the client is closed.
        long deadline = System.currentTimeMillis() + 5_000;
        while (retained(closeFuture) != expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
    }

    private static int retained(CloseFuture closeFuture) {
        try {
            Field children = CloseFuture.class.getDeclaredField("children");
            children.setAccessible(true);
            Map<?, ?> map = (Map<?, ?>) children.get(closeFuture);
            return map == null ? 0 : map.size();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static class TestConnector extends AbstractHttpConnector<HttpEndpoint> {

        private boolean webSocket;

        TestConnector(Configuration configuration) {
            super(new HttpEndpoint(null, "Endpoint", "http://localhost:8080/api"), configuration);
        }

        @Override
        @SuppressWarnings("unchecked")
        protected AbstractHttpConnection<HttpEndpoint> create(ProxyRequest request) {
            return webSocket ? mock(WebSocketConnection.class) : mock(AbstractHttpConnection.class);
        }
    }
}
