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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.Mockito.*;

import io.gravitee.common.http.HttpHeader;
import io.gravitee.common.http.HttpMethod;
import io.gravitee.connector.api.Connection;
import io.gravitee.connector.api.EndpointException;
import io.gravitee.connector.http.endpoint.HttpClientSslOptions;
import io.gravitee.connector.http.endpoint.HttpEndpoint;
import io.gravitee.connector.http.endpoint.jks.JKSKeyStore;
import io.gravitee.connector.http.endpoint.jks.JKSTrustStore;
import io.gravitee.connector.http.endpoint.pkcs12.PKCS12KeyStore;
import io.gravitee.connector.http.endpoint.pkcs12.PKCS12TrustStore;
import io.gravitee.connector.http.ws.WebSocketConnection;
import io.gravitee.gateway.api.ExecutionContext;
import io.gravitee.gateway.api.handler.Handler;
import io.gravitee.gateway.api.http.HttpHeaderNames;
import io.gravitee.gateway.api.http.HttpHeaders;
import io.gravitee.gateway.api.proxy.ProxyRequest;
import io.gravitee.gateway.api.proxy.ws.WebSocketProxyRequest;
import io.gravitee.gateway.reactive.api.tracing.Tracer;
import io.gravitee.node.api.configuration.Configuration;
import io.gravitee.node.opentelemetry.tracer.noop.NoOpTracer;
import io.gravitee.reporter.api.http.Metrics;
import io.vertx.core.Future;
import io.vertx.core.http.*;
import io.vertx.core.http.HttpConnection;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class HttpConnectorTest {

    private static final String TRUSTSTORE = "gravitee-truststore";
    private static final String KEYSTORE = "gravitee-keystore";

    @InjectMocks
    HttpConnector connector;

    @Mock
    HttpEndpoint endpoint;

    @Mock
    Configuration configuration;

    @Mock
    Handler<Connection> connectionHandler;

    @Mock
    ExecutionContext executionContext;

    @Mock
    ProxyRequest request;

    @Mock
    HttpClientOptions options;

    @Spy
    HttpClient httpClient;

    @Spy
    HttpHeaders spyHeaders;

    @Captor
    ArgumentCaptor<String> headerCaptor;

    io.gravitee.connector.http.endpoint.HttpClientOptions httpClientsOptions = new io.gravitee.connector.http.endpoint.HttpClientOptions();

    @BeforeEach
    public void setUp() throws Exception {
        lenient().when(executionContext.getTracer()).thenReturn(new Tracer(null, new NoOpTracer()));
        httpClientsOptions.setConnectTimeout(0L);
        httpClientsOptions.setReadTimeout(0L);
        String target = "https://api.gravitee.io/echo";
        when(configuration.getProperty("http.ssl.openssl", Boolean.class, false)).thenReturn(false);
        when(endpoint.getHttpClientOptions()).thenReturn(httpClientsOptions);
        when(endpoint.target()).thenReturn(target);
        lenient().when(request.method()).thenReturn(HttpMethod.GET);
        lenient().when(request.uri()).thenReturn(target);
        lenient().when(request.metrics()).thenReturn(Metrics.on(0L).build());
        HttpClientRequest httpClientRequest = mock(HttpClientRequest.class);
        lenient().when(httpClient.request(any(RequestOptions.class))).thenReturn(Future.succeededFuture(httpClientRequest));
        connector.httpClients.put(Thread.currentThread(), httpClient);
        lenient().when(request.headers()).thenReturn(spyHeaders);
        lenient().when(httpClientRequest.connection()).thenReturn(mock(HttpConnection.class));
        connector.doStart();
    }

    @AfterEach
    public void tearDown() throws Exception {
        connector.doStop();
    }

    @Test
    public void shouldOverrideHeaders() {
        when(endpoint.getHeaders())
            .thenReturn(
                Arrays.asList(
                    new HttpHeader(HttpHeaderNames.HOST, "api.gravitee.io"),
                    new HttpHeader(HttpHeaderNames.HOST, "api2.gravitee.io")
                )
            );

        connector.request(executionContext, request, connectionHandler);

        verify(spyHeaders, times(3)).set(eq(HttpHeaderNames.HOST), headerCaptor.capture());
        List<String> allValues = headerCaptor.getAllValues();
        assertEquals(3, allValues.size());
        assertEquals("api2.gravitee.io", allValues.get(2));
    }

    @Test
    public void shouldCreateHttpClientOptions_PKCSInlineContent() throws EndpointException {
        HttpClientSslOptions httpClientSslOptions = new HttpClientSslOptions();
        PKCS12KeyStore pkcs12KeyStore = new PKCS12KeyStore();
        pkcs12KeyStore.setContent(Base64.getEncoder().encodeToString(KEYSTORE.getBytes()));
        pkcs12KeyStore.setPassword("password");
        httpClientSslOptions.setKeyStore(pkcs12KeyStore);

        PKCS12TrustStore pkcs12TrustStore = new PKCS12TrustStore();
        pkcs12TrustStore.setContent(Base64.getEncoder().encodeToString(TRUSTSTORE.getBytes()));
        pkcs12TrustStore.setPassword("password");
        httpClientSslOptions.setTrustStore(pkcs12TrustStore);

        when(endpoint.getHttpClientSslOptions()).thenReturn(httpClientSslOptions);

        HttpClientOptions httpClientOptions = connector.createHttpClientOptions();

        assertNotNull(httpClientOptions);
        assertNotNull(httpClientOptions.getPfxKeyCertOptions());
        assertEquals(KEYSTORE, httpClientOptions.getPfxKeyCertOptions().getValue().toString());
        assertNotNull(httpClientOptions.getPfxTrustOptions());
        assertEquals(TRUSTSTORE, httpClientOptions.getPfxTrustOptions().getValue().toString());
    }

    @Test
    public void shouldCreateHttpClientOptions_JKSInlineContent() throws EndpointException {
        HttpClientSslOptions httpClientSslOptions = new HttpClientSslOptions();
        JKSKeyStore jksKeyStore = new JKSKeyStore();
        jksKeyStore.setContent(Base64.getEncoder().encodeToString(KEYSTORE.getBytes()));
        jksKeyStore.setPassword("password");
        httpClientSslOptions.setKeyStore(jksKeyStore);

        JKSTrustStore jksTrustStore = new JKSTrustStore();
        jksTrustStore.setContent(Base64.getEncoder().encodeToString(TRUSTSTORE.getBytes()));
        jksTrustStore.setPassword("password");
        httpClientSslOptions.setTrustStore(jksTrustStore);

        when(endpoint.getHttpClientSslOptions()).thenReturn(httpClientSslOptions);

        HttpClientOptions httpClientOptions = connector.createHttpClientOptions();

        assertNotNull(httpClientOptions);
        assertNotNull(httpClientOptions.getKeyStoreOptions());
        assertEquals(KEYSTORE, httpClientOptions.getKeyStoreOptions().getValue().toString());
        assertNotNull(httpClientOptions.getTrustOptions());
        assertEquals(TRUSTSTORE, httpClientOptions.getTrustStoreOptions().getValue().toString());
    }

    @Nested
    class Create {

        @ParameterizedTest
        @ValueSource(strings = { "Upgrade", "upgrade", "UPGRADE", "Upgrade,Keep-Alive" })
        public void should_create_websocket_connection(String connectionHeader) {
            HttpHeaders headers = HttpHeaders.create();
            headers.add("Connection", connectionHeader);
            headers.add("Upgrade", "websocket");

            WebSocketProxyRequest request = mock(WebSocketProxyRequest.class);
            when(request.method()).thenReturn(HttpMethod.GET);
            when(request.headers()).thenReturn(headers);

            AbstractHttpConnection<HttpEndpoint> connection = connector.create(request);

            Assertions.assertThat(connection).isInstanceOf(WebSocketConnection.class);
        }

        @Test
        public void should_create_http_connection() {
            AbstractHttpConnection<HttpEndpoint> connection = connector.create(request);

            Assertions.assertThat(connection).isInstanceOf(io.gravitee.connector.http.HttpConnection.class);
        }
    }
}
