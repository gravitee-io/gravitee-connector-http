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
import java.util.Map;
import org.junit.Before;
import org.junit.Test;

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
        assertThat(((HttpConnector) connector).endpoint.getHeaders())
            .containsExactly(new HttpHeader("X-Gravitee-Api", "test"), new HttpHeader("Empty-Header", ""));
    }
}
