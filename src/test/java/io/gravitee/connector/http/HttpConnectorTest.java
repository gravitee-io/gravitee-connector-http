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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.Mockito.*;

import io.gravitee.common.http.HttpHeader;
import io.gravitee.common.http.HttpMethod;
import io.gravitee.connector.api.Connection;
import io.gravitee.connector.api.EndpointException;
import io.gravitee.connector.http.endpoint.HttpClientSslOptions;
import io.gravitee.connector.http.endpoint.HttpEndpoint;
import io.gravitee.connector.http.endpoint.TrustStore;
import io.gravitee.connector.http.endpoint.jks.JKSKeyStore;
import io.gravitee.connector.http.endpoint.jks.JKSTrustStore;
import io.gravitee.connector.http.endpoint.pkcs12.PKCS12KeyStore;
import io.gravitee.connector.http.endpoint.pkcs12.PKCS12TrustStore;
import io.gravitee.gateway.api.ExecutionContext;
import io.gravitee.gateway.api.handler.Handler;
import io.gravitee.gateway.api.http.HttpHeaderNames;
import io.gravitee.gateway.api.http.HttpHeaders;
import io.gravitee.gateway.api.proxy.ProxyRequest;
import io.gravitee.node.api.configuration.Configuration;
import io.gravitee.reporter.api.http.Metrics;
import io.vertx.core.Future;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.RequestOptions;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.*;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
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

    @Before
    public void setUp() throws Exception {
        httpClientsOptions.setConnectTimeout(0L);
        httpClientsOptions.setReadTimeout(0L);
        String target = "https://api.gravitee.io/echo";
        when(configuration.getProperty("http.ssl.openssl", Boolean.class, false)).thenReturn(false);
        when(endpoint.getHttpClientOptions()).thenReturn(httpClientsOptions);
        when(endpoint.target()).thenReturn(target);
        when(request.method()).thenReturn(HttpMethod.GET);
        when(request.uri()).thenReturn(target);
        when(request.metrics()).thenReturn(Metrics.on(0L).build());
        HttpClientRequest httpClientRequest = mock(HttpClientRequest.class);
        when(httpClient.request(any(RequestOptions.class))).thenReturn(Future.succeededFuture(httpClientRequest));
        connector.httpClients.put(Thread.currentThread(), httpClient);
        when(request.headers()).thenReturn(spyHeaders);
        connector.doStart();
    }

    @After
    public void tearDown() throws Exception {
        connector.doStop();
    }

    @Test
    public void shouldOverrideHeaders() {
        when(endpoint.getHeaders()).thenReturn(Arrays.asList(new HttpHeader(HttpHeaderNames.CONTENT_TYPE, "application/json")));

        connector.request(executionContext, request, connectionHandler);

        verify(spyHeaders, times(1)).set(eq(HttpHeaderNames.CONTENT_TYPE), headerCaptor.capture());
        List<String> allValues = headerCaptor.getAllValues();
        assertEquals(1, allValues.size());
        assertEquals("application/json", allValues.get(0));
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
}
