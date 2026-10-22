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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.gravitee.connector.http.endpoint.HttpEndpoint;
import io.gravitee.connector.http.ws.WebSocketConnection;
import io.gravitee.gateway.api.proxy.ProxyRequest;
import io.gravitee.node.api.configuration.Configuration;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.WebSocketClient;
import io.vertx.core.internal.CloseFuture;
import io.vertx.core.internal.ContextInternal;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Guards the shared-client workaround of {@link AbstractHttpConnector}: the clients of a stopped connector must leave the
 * close future of the event loop contexts that created them, once their connections are closed.
 */
class AbstractHttpConnectorClientRetentionTest {

    private static final int REDEPLOYS = 10;
    private static final int EVENT_LOOPS = 2;

    private Vertx vertx;
    private HttpServer server;
    private Configuration configuration;
    private final List<Context> eventLoops = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        vertx = Vertx.vertx();
        server = vertx
            .createHttpServer()
            .webSocketHandler(ws -> {})
            .requestHandler(request -> request.response().end("ok"))
            .listen(0, "localhost")
            .await(10, TimeUnit.SECONDS);
        configuration = mock(Configuration.class);
        when(configuration.getProperty(anyString(), any(), any())).thenAnswer(invocation -> invocation.getArgument(2));
        // Each verticle runs on its own event loop context, like the instances of the gateway HTTP server verticle.
        for (int i = 0; i < EVENT_LOOPS; i++) {
            Promise<Context> started = Promise.promise();
            vertx
                .deployVerticle(
                    new AbstractVerticle() {
                        @Override
                        public void start() {
                            started.complete(context);
                        }
                    }
                )
                .await(10, TimeUnit.SECONDS);
            eventLoops.add(started.future().await(10, TimeUnit.SECONDS));
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        vertx.close().await(10, TimeUnit.SECONDS);
    }

    @Test
    void should_not_retain_the_clients_of_stopped_connectors() throws Exception {
        assertThat(eventLoops.get(0)).isNotSameAs(eventLoops.get(1));
        List<Integer> before = eventLoops
            .stream()
            .map(context -> retained(closeFuture(context)))
            .toList();
        AtomicInteger clientsCreated = new AtomicInteger();

        // Each iteration mimics an API redeploy: a new connector serves an HTTP and a WebSocket request on every event
        // loop, which creates its clients and opens real connections with them, then is stopped from another thread, as
        // the deployer does.
        for (int i = 0; i < REDEPLOYS; i++) {
            TestConnector connector = new TestConnector(configuration);
            connector.start();
            for (Context eventLoop : eventLoops) {
                onContext(eventLoop, () -> serveHttpAndWebSocket(connector)).await(10, TimeUnit.SECONDS);
            }
            clientsCreated.addAndGet(connector.httpClients.size() + connector.webSocketClients.size());
            connector.stop();
        }

        // Without this the test would pass vacuously.
        assertThat(clientsCreated).hasValue(2 * EVENT_LOOPS * REDEPLOYS);
        for (int i = 0; i < EVENT_LOOPS; i++) {
            CloseFuture closeFuture = closeFuture(eventLoops.get(i));
            assertThat(awaitRetained(closeFuture, before.get(i)) - before.get(i)).as("clients left on event loop %d", i).isZero();
        }
    }

    @Test
    void should_still_leak_when_the_client_comes_straight_from_vertx() throws Exception {
        // This watches the Vert.x version this module is built against (vertx.version in the pom), not the one APIM
        // ships: when it starts failing, remove the workaround here and bump the gateway, not the other way around.
        Context eventLoop = eventLoops.get(0);
        int before = retained(closeFuture(eventLoop));

        for (int i = 0; i < REDEPLOYS; i++) {
            onContext(eventLoop, () -> vertx.createHttpClient(new HttpClientOptions()).close()).await(10, TimeUnit.SECONDS);
        }

        assertThat(retained(closeFuture(eventLoop)) - before)
            .as("Vert.x no longer leaks closed clients: remove the shared-client workaround of AbstractHttpConnector and this test")
            .isEqualTo(REDEPLOYS);
    }

    private Future<Void> serveHttpAndWebSocket(TestConnector connector) {
        connector.request(null, connector.httpRequest, connection -> {});
        connector.request(null, connector.webSocketRequest, connection -> {});
        HttpClient httpClient = connector.httpClients.get(Thread.currentThread());
        WebSocketClient webSocketClient = connector.webSocketClients.get(Thread.currentThread());
        // Keep both connections open: stopping the connector has to close them before the clients can unregister.
        return httpClient
            .request(HttpMethod.GET, server.actualPort(), "localhost", "/")
            .compose(HttpClientRequest::send)
            .compose(HttpClientResponse::body)
            .compose(body -> webSocketClient.connect(server.actualPort(), "localhost", "/"))
            .mapEmpty();
    }

    private static Future<Void> onContext(Context context, Supplier<Future<Void>> action) {
        Promise<Void> done = Promise.promise();
        context.runOnContext(v -> action.get().onComplete(done));
        return done.future();
    }

    private static CloseFuture closeFuture(Context context) {
        return ((ContextInternal) context).closeFuture();
    }

    /** Waits for close() to complete asynchronously and returns the last count observed. */
    private static int awaitRetained(CloseFuture closeFuture, int expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        int retained = retained(closeFuture);
        while (retained != expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
            retained = retained(closeFuture);
        }
        return retained;
    }

    private static int retained(CloseFuture closeFuture) {
        try {
            Field children = CloseFuture.class.getDeclaredField("children");
            children.setAccessible(true);
            Map<?, ?> map = (Map<?, ?>) children.get(closeFuture);
            return map == null ? 0 : map.size();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                "CloseFuture.children is gone: Vert.x internals changed, this does not mean the leak is fixed. " +
                    "Re-check how closed clients are tracked before touching the shared-client workaround.",
                e
            );
        }
    }

    private static class TestConnector extends AbstractHttpConnector<HttpEndpoint> {

        private final ProxyRequest httpRequest = proxyRequest();
        private final ProxyRequest webSocketRequest = proxyRequest();
        private final Function<ProxyRequest, AbstractHttpConnection<HttpEndpoint>> connections = request ->
            request == webSocketRequest ? mock(WebSocketConnection.class) : mock(AbstractHttpConnection.class);

        TestConnector(Configuration configuration) {
            super(new HttpEndpoint(null, "Endpoint", "http://localhost:8080/api"), configuration);
        }

        @Override
        protected AbstractHttpConnection<HttpEndpoint> create(ProxyRequest request) {
            return connections.apply(request);
        }

        private static ProxyRequest proxyRequest() {
            ProxyRequest request = mock(ProxyRequest.class, RETURNS_DEEP_STUBS);
            when(request.uri()).thenReturn("http://localhost:8080/api");
            when(request.parameters()).thenReturn(null);
            return request;
        }
    }
}
