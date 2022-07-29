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
package io.gravitee.connector.http.grpc;

import io.gravitee.connector.api.EndpointException;
import io.gravitee.connector.http.Http2Connector;
import io.gravitee.connector.http.endpoint.HttpEndpoint;
import io.gravitee.gateway.api.proxy.ProxyRequest;
import io.gravitee.node.api.configuration.Configuration;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpVersion;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author GraviteeSource Team
 */
public class GrpcConnector extends Http2Connector {

    public GrpcConnector(HttpEndpoint endpoint, Configuration configuration) {
        super(endpoint, configuration);
    }

    @Override
    public HttpClientOptions createHttpClientOptions() throws EndpointException {
        HttpClientOptions options = super.createHttpClientOptions();

        // For GRPC, force HTTP/2 protocol
        options.setProtocolVersion(HttpVersion.HTTP_2).setHttp2ClearTextUpgrade(false);

        return options;
    }

    @Override
    protected GrpcConnection create(ProxyRequest request) {
        return new GrpcConnection(endpoint, request);
    }
}
