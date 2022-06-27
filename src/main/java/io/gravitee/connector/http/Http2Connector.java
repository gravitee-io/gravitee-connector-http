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

import io.gravitee.connector.api.EndpointException;
import io.gravitee.connector.http.endpoint.HttpEndpoint;
import io.gravitee.gateway.api.http.HttpHeaderNames;
import io.gravitee.gateway.api.proxy.ProxyRequest;
import io.gravitee.node.api.configuration.Configuration;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpVersion;
import java.net.URL;
import java.util.Set;

/**
 * @author GraviteeSource Team
 */
public class Http2Connector extends AbstractHttpConnector<HttpEndpoint> {

    protected static final Set<CharSequence> HTTP2_ILLEGAL_HEADERS;

    static {
        HTTP2_ILLEGAL_HEADERS =
            Set.of(
                HttpHeaderNames.CONNECTION,
                HttpHeaderNames.HOST,
                HttpHeaderNames.PROXY_CONNECTION,
                HttpHeaderNames.TRANSFER_ENCODING,
                HttpHeaderNames.UPGRADE
            );
    }

    public Http2Connector(HttpEndpoint endpoint, Configuration configuration) {
        super(endpoint, configuration);
    }

    @Override
    public HttpClientOptions createHttpClientOptions() throws EndpointException {
        HttpClientOptions options = super.createHttpClientOptions();

        // Force HTTP/2 protocol
        options.setProtocolVersion(HttpVersion.HTTP_2);

        return options;
    }

    @Override
    protected void convertHeadersForHttpVersion(URL url, ProxyRequest request, String host) {
        // TODO: support HTTP2 headers, something like:
        //        request.headers().set(Http2PseudoHeaderNames.AUTHORITY, host);
        //
        //        if (!request.headers().contains(Http2PseudoHeaderNames.SCHEME)) {
        //            String scheme = request.headers().contains(HttpHeaderNames.X_FORWARDED_PROTO)
        //                ? request.headers().get(HttpHeaderNames.X_FORWARDED_PROTO)
        //                : url.getProtocol();
        //            request.headers().set(Http2PseudoHeaderNames.SCHEME, scheme);
        //        }
        //        if (!request.headers().contains(Http2PseudoHeaderNames.PATH)) {
        //            request.headers().set(Http2PseudoHeaderNames.PATH, url.getFile());
        //        }
        // Strip all headers made illegal in HTTP/2 messages:
        HTTP2_ILLEGAL_HEADERS.forEach(name -> request.headers().remove(name));
    }

    @Override
    protected AbstractHttpConnection<HttpEndpoint> create(ProxyRequest request) {
        return new HttpConnection<>(endpoint, request);
    }
}
