/**
 * Copyright (C) 2015 The Gravitee team (http://gravitee.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gravitee.connector.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.gravitee.common.http.HttpHeader;
import io.gravitee.connector.api.Connection;
import io.gravitee.connector.api.Connector;
import io.gravitee.connector.api.ConnectorBuilder;
import io.gravitee.connector.api.ConnectorContext;
import io.gravitee.connector.http.grpc.GrpcConnector;
import io.gravitee.gateway.api.proxy.ProxyRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

public class HttpConnectorFactoryTest {

    ObjectMapper mapper = new ObjectMapper();

    HttpConnectorFactory factory = new HttpConnectorFactory();

    ConnectorBuilder connectorBuilder;

    @Before
    public void setUp() {
        connectorBuilder = mock(ConnectorBuilder.class);
        when(connectorBuilder.getMapper()).thenReturn(mapper);
    }

    @Test
    public void shouldReturnCorrectSupportedTypes() {
        assertThat(factory.supportedTypes()).containsExactly("http", "grpc");
    }

    @Test
    public void shouldCreateAnHttpConnector() {
        String target = "http://localhost:8080";
        String configuration = "{\"type\":\"http\", \"target\":\"" + target + "\", \"name\":\"test\"}";

        Connector<Connection, ProxyRequest> connector = factory.create(target, configuration, connectorBuilder);
        assertThat(connector).isInstanceOf(HttpConnector.class);
        assertThat(((HttpConnector) connector).endpoint.target()).isEqualTo(target);
    }

    @Test
    public void shouldCreateAGrpcConnector() {
        String target = "http://localhost:8080";
        String configuration = "{\"type\":\"grpc\", \"target\":\"" + target + "\", \"name\":\"test\"}";

        assertThat(factory.create(target, configuration, connectorBuilder)).isInstanceOf(GrpcConnector.class);
    }

    @Test
    public void shouldCreateAConnectorWithSpELEndpoint() {
        String target = "http://localhost:8080";
        String configuration = "{\"type\":\"http\", \"target\":\"{#properties['backend']}\", \"name\":\"test\"}";

        ConnectorContext context = new ConnectorContext();
        context.setProperties(Map.of("backend", "http://localhost:8080"));
        when(connectorBuilder.getContext()).thenReturn(context);

        Connector<Connection, ProxyRequest> connector = factory.create(target, configuration, connectorBuilder);
        assertThat(connector).isInstanceOf(HttpConnector.class);
        assertThat(((HttpConnector) connector).endpoint.target()).isEqualTo(target);
    }

    @Test
    public void shouldCreateAConnectorInParallelWithSpELEndpoint() {
        // The following test tries to create connectors with high concurrency in order to demonstrate there is no more mix with template engine context.
        final ThreadPoolTaskExecutor threadPoolTaskExecutor = new ThreadPoolTaskExecutor();
        threadPoolTaskExecutor.setCorePoolSize(5);
        threadPoolTaskExecutor.setMaxPoolSize(5);
        threadPoolTaskExecutor.setWaitForTasksToCompleteOnShutdown(false);
        threadPoolTaskExecutor.initialize();

        try {
            long startTime = System.currentTimeMillis();

            final AtomicReference<Throwable> lastError = new AtomicReference<>();

            Random r = new Random(System.currentTimeMillis());
            for (int i = 0; i < 100; i++) {
                int index = i;
                threadPoolTaskExecutor.submit(() -> {
                    try {
                        final int randomDelay = r.nextInt(100);
                        final String target = "http://localhost:8080/" + index;
                        final String configuration =
                            "{\"type\":\"http\", \"target\":\"{#properties['backend" + index + "']}\", \"name\":\"test\"}";
                        final ConnectorContext context = new ConnectorContext();
                        final ConnectorBuilder connectorBuilder = mock(ConnectorBuilder.class);

                        context.setProperties(Map.of("backend" + index, target));

                        when(connectorBuilder.getContext()).thenReturn(context);
                        when(connectorBuilder.getMapper()).thenReturn(mapper);

                        // Apply a random delay to make sure connectors are created with high concurrency pressure.
                        Thread.sleep(randomDelay);

                        Connector<Connection, ProxyRequest> connector = factory.create(target, configuration, connectorBuilder);
                        assertThat(connector).isInstanceOf(HttpConnector.class);
                        assertThat(((HttpConnector) connector).endpoint.target()).isEqualTo(target);
                    } catch (Throwable e) {
                        lastError.set(e);
                    }
                });
            }

            final ThreadPoolExecutor executor = threadPoolTaskExecutor.getThreadPoolExecutor();
            while (executor.getActiveCount() > 0 || !executor.getQueue().isEmpty()) {
                if (System.currentTimeMillis() - startTime > 10000) {
                    fail("Not completed after 1000ms");
                }
            }

            if (lastError.get() != null) {
                fail("EL evaluation failed", lastError.get());
            }
        } finally {
            threadPoolTaskExecutor.shutdown();
        }
    }

    @Test
    public void shouldCreateAConnectorWithHttpProxyConfig() {
        String target = "http://localhost:8080";
        String configuration =
            "{\"type\":\"http\", \"target\":\"" +
            target +
            "\", \"name\":\"test\", \"proxy\":{\"host\":\"localhost\", \"username\":\"user\", \"password\":\"pwd\"}}";

        ConnectorContext context = new ConnectorContext();
        context.setProperties(Map.of("backend", "http://localhost:8080"));
        when(connectorBuilder.getContext()).thenReturn(context);

        Connector<Connection, ProxyRequest> connector = factory.create(target, configuration, connectorBuilder);
        assertThat(connector).isInstanceOf(HttpConnector.class);
        assertThat(((HttpConnector) connector).endpoint.getHttpProxy())
            .extracting("host", "username", "password")
            .containsExactly("localhost", "user", "pwd");
    }

    @Test
    public void shouldCreateAConnectorWithDefaultHttpHeaderConfig() {
        String target = "http://localhost:8080";
        String configuration =
            "{\"type\":\"http\", \"target\":\"" +
            target +
            "\", \"name\":\"test\", \"headers\":[{\"name\":\"X-Gravitee-Api\",\"value\":\"test\"}, {\"name\":\"Empty-Header\",\"value\":\"\"}]}";

        ConnectorContext context = new ConnectorContext();
        context.setProperties(Map.of("backend", "http://localhost:8080"));
        when(connectorBuilder.getContext()).thenReturn(context);

        Connector<Connection, ProxyRequest> connector = factory.create(target, configuration, connectorBuilder);
        assertThat(connector).isInstanceOf(HttpConnector.class);
        assertThat(((HttpConnector) connector).endpoint.getHeaders()).containsExactly(
            new HttpHeader("X-Gravitee-Api", "test"),
            new HttpHeader("Empty-Header", "")
        );
    }
}
