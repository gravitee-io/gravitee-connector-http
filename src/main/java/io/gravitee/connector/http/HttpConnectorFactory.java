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

import io.gravitee.connector.api.*;
import io.gravitee.connector.http.endpoint.HttpEndpoint;
import io.gravitee.connector.http.endpoint.ProtocolVersion;
import io.gravitee.connector.http.endpoint.factory.HttpEndpointFactory;
import io.gravitee.connector.http.grpc.GrpcConnector;
import io.gravitee.el.TemplateEngine;
import io.gravitee.gateway.api.proxy.ProxyRequest;
import java.util.Arrays;
import java.util.Collection;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author GraviteeSource Team
 */
public class HttpConnectorFactory implements ConnectorFactory<Connector<Connection, ProxyRequest>> {

    private final TemplateEngine templateEngine = TemplateEngine.templateEngine();

    private final HttpEndpointFactory endpointFactory = new HttpEndpointFactory();

    private static final Collection<String> SUPPORTED_TYPES = Arrays.asList("http", "grpc");

    @Override
    public Collection<String> supportedTypes() {
        return SUPPORTED_TYPES;
    }

    @Override
    public Connector<Connection, ProxyRequest> create(String target, String configuration, ConnectorBuilder builder) {
        HttpEndpoint httpEndpoint = resolve(endpointFactory.create(configuration, builder.getMapper()), builder.getContext());

        String type = httpEndpoint.type();
        if (type.equalsIgnoreCase("GRPC")) {
            return new GrpcConnector(httpEndpoint, builder.getConfiguration());
        }

        if (httpEndpoint.getHttpClientOptions().getVersion().equals(ProtocolVersion.HTTP_2)) {
            return new Http2Connector(httpEndpoint, builder.getConfiguration());
        }

        return new HttpConnector(httpEndpoint, builder.getConfiguration());
    }

    private HttpEndpoint resolve(final HttpEndpoint httpEndpoint, final ConnectorContext context) {
        if (context != null) {
            templateEngine.getTemplateContext().setVariable("properties", context.getProperties());
        }

        // HTTP endpoint configuration
        httpEndpoint.target(convert(httpEndpoint.target()));

        // HTTP Proxy configuration
        if (httpEndpoint.getHttpProxy() != null) {
            httpEndpoint.getHttpProxy().setHost(convert(httpEndpoint.getHttpProxy().getHost()));
            httpEndpoint.getHttpProxy().setUsername(convert(httpEndpoint.getHttpProxy().getUsername()));
            httpEndpoint.getHttpProxy().setPassword(convert(httpEndpoint.getHttpProxy().getPassword()));
        }

        // Default HTTP headers
        if (httpEndpoint.getHeaders() != null && !httpEndpoint.getHeaders().isEmpty()) {
            httpEndpoint.getHeaders().forEach(httpHeader -> httpHeader.setValue(convert(httpHeader.getValue())));
        }

        return httpEndpoint;
    }

    private String convert(String value) {
        if (value != null && !value.isEmpty()) {
            return templateEngine.convert(value);
        }

        return value;
    }
}
