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
import static org.mockito.Mockito.*;

import io.gravitee.common.http.HttpHeader;
import io.gravitee.common.http.HttpMethod;
import io.gravitee.connector.api.Connection;
import io.gravitee.connector.http.endpoint.HttpEndpoint;
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
import java.util.Arrays;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.*;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class HttpConnectorTest {

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
        connector.doStart();
    }

    @After
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
        HttpHeaders spyHeaders = spy(HttpHeaders.class);
        when(request.headers()).thenReturn(spyHeaders);

        connector.request(executionContext, request, connectionHandler);

        verify(spyHeaders, times(3)).set(eq(HttpHeaderNames.HOST), headerCaptor.capture());
        List<String> allValues = headerCaptor.getAllValues();
        assertEquals(3, allValues.size());
        assertEquals("api2.gravitee.io", allValues.get(2));
    }
}
