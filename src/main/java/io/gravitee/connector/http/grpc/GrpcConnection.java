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

import io.gravitee.common.http.MediaType;
import io.gravitee.connector.http.HttpConnection;
import io.gravitee.connector.http.HttpResponse;
import io.gravitee.gateway.api.proxy.ProxyRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.vertx.core.Future;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author GraviteeSource Team
 */
public class GrpcConnection extends HttpConnection<HttpResponse> {

    private static final String GRPC_TRAILERS_TE = "trailers";

    public GrpcConnection(GrpcEndpoint endpoint, ProxyRequest request) {
        super(endpoint, request);
    }

    @Override
    protected Future<HttpClientRequest> prepareUpstreamRequest(HttpClient httpClient, int port, String host, String uri) {
        // Prepare HTTP request
        return httpClient
            .request(
                new RequestOptions()
                    .setHost(host)
                    .setMethod(HttpMethod.POST)
                    .setPort(port)
                    .setURI(uri)
                    // Ensure required gRPC headers
                    .putHeader(HttpHeaderNames.CONTENT_TYPE, MediaType.APPLICATION_GRPC)
                    .putHeader(HttpHeaderNames.TE, GRPC_TRAILERS_TE)
                    .setTimeout(endpoint.getHttpClientOptions().getReadTimeout())
                    .setFollowRedirects(endpoint.getHttpClientOptions().isFollowRedirects())
            )
            .map(
                httpClientRequest -> {
                    // Always set chunked mode for gRPC transport
                    return httpClientRequest.setChunked(true);
                }
            );
    }
}
