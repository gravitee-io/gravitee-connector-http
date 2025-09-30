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
import static org.mockito.Mockito.mock;

import io.gravitee.connector.http.endpoint.HttpEndpoint;
import io.gravitee.gateway.api.proxy.ProxyRequest;
import io.gravitee.node.api.configuration.Configuration;
import io.vertx.core.http.HttpClientOptions;
import org.junit.jupiter.api.Test;

class TestHttpConnector extends AbstractHttpConnector<HttpEndpoint> {

    public TestHttpConnector(HttpEndpoint endpoint, Configuration configuration) {
        super(endpoint, configuration);
    }

    @Override
    protected AbstractHttpConnection<HttpEndpoint> create(ProxyRequest request) {
        return null;
    }
}

public class AbstractHttpConnectorTest {

    @Test
    public void createHttpClientOptions_DoesntEnableSSLForWsProtocol() throws Exception {
        HttpEndpoint endpoint = new HttpEndpoint(null, "Endpoint", "ws://localhost:8080/api");
        Configuration configuration = mock(Configuration.class);
        AbstractHttpConnector<HttpEndpoint> connector = new TestHttpConnector(endpoint, configuration);
        HttpClientOptions result = connector.createHttpClientOptions();
        assertThat(result.isSsl()).isFalse();
    }

    @Test
    public void isSecureProtocol_ReturnTrueForHttpsAndWss() {
        assertThat(TestHttpConnector.isSecureProtocol("https")).isTrue();
        assertThat(TestHttpConnector.isSecureProtocol("wss")).isTrue();
    }

    @Test
    public void isSecureProtocol_ReturnFalseForHttpAndWs() {
        assertThat(TestHttpConnector.isSecureProtocol("http")).isFalse();
        assertThat(TestHttpConnector.isSecureProtocol("ws")).isFalse();
    }
}
