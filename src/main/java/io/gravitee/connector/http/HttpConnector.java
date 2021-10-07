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

import io.gravitee.common.environment.Configuration;
import io.gravitee.common.http.HttpHeaders;
import io.gravitee.common.http.HttpMethod;
import io.gravitee.connector.http.endpoint.HttpEndpoint;
import io.gravitee.connector.http.ws.WebSocketConnection;
import io.gravitee.gateway.api.proxy.ProxyRequest;
import io.netty.handler.codec.http.HttpHeaderValues;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author GraviteeSource Team
 */
public class HttpConnector extends AbstractHttpConnector<HttpEndpoint> {

    public HttpConnector(HttpEndpoint endpoint, Configuration environmentConfiguration) {
        super(endpoint, environmentConfiguration);
    }

    @Override
    protected AbstractHttpConnection<HttpEndpoint> create(ProxyRequest request) {
        String connectionHeader = request.headers().getFirst(HttpHeaders.CONNECTION);
        String upgradeHeader = request.headers().getFirst(HttpHeaders.UPGRADE);

        boolean websocket =
            request.method() == HttpMethod.GET &&
            HttpHeaderValues.UPGRADE.contentEqualsIgnoreCase(connectionHeader) &&
            HttpHeaderValues.WEBSOCKET.contentEqualsIgnoreCase(upgradeHeader);

        return websocket ? new WebSocketConnection(endpoint, request) : new HttpConnection<>(endpoint, request);
    }
}
