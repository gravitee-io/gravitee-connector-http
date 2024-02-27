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

import io.gravitee.common.http.HttpHeadersValues;
import io.gravitee.connector.api.Connection;
import io.gravitee.connector.api.Response;
import io.gravitee.connector.api.response.ClientConnectionErrorResponse;
import io.gravitee.connector.api.response.ClientConnectionTimeoutResponse;
import io.gravitee.connector.http.endpoint.HttpEndpoint;
import io.gravitee.gateway.api.buffer.Buffer;
import io.gravitee.gateway.api.handler.Handler;
import io.gravitee.gateway.api.http.HttpHeaders;
import io.gravitee.gateway.api.http2.HttpFrame;
import io.gravitee.gateway.api.proxy.ProxyRequest;
import io.gravitee.gateway.api.stream.WriteStream;
import io.netty.channel.ConnectTimeoutException;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.http.*;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author GraviteeSource Team
 */
public class HttpConnection<T extends HttpResponse> extends AbstractHttpConnection<HttpEndpoint> {

    private final Logger LOGGER = LoggerFactory.getLogger(this.getClass());

    private static final Set<CharSequence> HOP_HEADERS;

    static {
        Set<CharSequence> hopHeaders = new HashSet<>();

        // Hop-by-hop headers
        hopHeaders.add(HttpHeaderNames.CONNECTION);
        hopHeaders.add(HttpHeaderNames.KEEP_ALIVE);
        hopHeaders.add(HttpHeaderNames.PROXY_AUTHORIZATION);
        hopHeaders.add(HttpHeaderNames.PROXY_AUTHENTICATE);
        hopHeaders.add(HttpHeaderNames.PROXY_CONNECTION);
        hopHeaders.add(HttpHeaderNames.TE);
        hopHeaders.add(HttpHeaderNames.TRAILER);
        hopHeaders.add(HttpHeaderNames.UPGRADE);

        HOP_HEADERS = Collections.unmodifiableSet(hopHeaders);
    }

    protected HttpClientRequest httpClientRequest;
    private final ProxyRequest request;
    private T response;
    private Handler<Throwable> timeoutHandler;
    private boolean canceled = false;
    private boolean transmitted = false;
    private boolean headersWritten = false;
    private boolean content = false;

    public HttpConnection(HttpEndpoint endpoint, ProxyRequest request) {
        super(endpoint);
        this.request = request;
    }

    @Override
    public void connect(HttpClient httpClient, int port, String host, String uri, Handler<Void> connectionHandler, Handler<Void> tracker) {
        // Remove HOP-by-HOP headers
        for (CharSequence header : HOP_HEADERS) {
            request.headers().remove(header.toString());
        }

        if (!endpoint.getHttpClientOptions().isPropagateClientAcceptEncoding()) {
            // Let the API Owner choose the Accept-Encoding between the gateway and the backend
            request.headers().remove(io.gravitee.common.http.HttpHeaders.ACCEPT_ENCODING);
        }

        Future<HttpClientRequest> requestFuture = prepareUpstreamRequest(httpClient, port, host, uri);
        requestFuture.onComplete(
            new io.vertx.core.Handler<>() {
                @Override
                public void handle(AsyncResult<HttpClientRequest> event) {
                    cancelHandler(tracker);

                    if (event.succeeded()) {
                        httpClientRequest = event.result();

                        httpClientRequest.response(response -> {
                            // Prepare upstream response
                            handleUpstreamResponse(response, tracker);
                        });

                        httpClientRequest
                            .connection()
                            .exceptionHandler(t -> {
                                LOGGER.debug(
                                    "Exception occurs during HTTP connection for api [{}] & request id [{}]: {}",
                                    request.metrics().getApi(),
                                    request.metrics().getRequestId(),
                                    t.getMessage()
                                );
                                request.metrics().setMessage(t.getMessage());
                            });

                        httpClientRequest.exceptionHandler(exEvent -> {
                            if (!isCanceled() && !isTransmitted()) {
                                handleException(event.cause());
                                tracker.handle(null);
                            }
                        });
                        connectionHandler.handle(null);
                    } else {
                        connectionHandler.handle(null);
                        handleException(event.cause());
                        tracker.handle(null);
                    }
                }
            }
        );
    }

    private void handleException(Throwable cause) {
        if (!isCanceled() && !isTransmitted()) {
            request.metrics().setMessage(cause.getMessage());

            if (
                timeoutHandler() != null &&
                (cause instanceof ConnectException ||
                    cause instanceof TimeoutException ||
                    cause instanceof NoRouteToHostException ||
                    cause instanceof UnknownHostException)
            ) {
                handleConnectTimeout(cause);
            } else {
                Response clientResponse = ((cause instanceof ConnectTimeoutException) || (cause instanceof TimeoutException))
                    ? new ClientConnectionTimeoutResponse()
                    : new ClientConnectionErrorResponse();

                sendToClient(clientResponse);
            }
        }
    }

    protected Future<HttpClientRequest> prepareUpstreamRequest(HttpClient httpClient, int port, String host, String uri) {
        // Prepare HTTP request
        return httpClient.request(
            new RequestOptions()
                .setHost(host)
                .setMethod(HttpMethod.valueOf(request.method().name()))
                .setPort(port)
                .setURI(uri)
                .setTimeout(endpoint.getHttpClientOptions().getReadTimeout())
                .setFollowRedirects(endpoint.getHttpClientOptions().isFollowRedirects())
        );
    }

    protected T createProxyResponse(HttpClientResponse clientResponse) {
        return (T) new HttpResponse(clientResponse);
    }

    protected T handleUpstreamResponse(final AsyncResult<HttpClientResponse> clientResponseFuture, Handler<Void> tracker) {
        if (clientResponseFuture.succeeded()) {
            HttpClientResponse clientResponse = clientResponseFuture.result();

            response = createProxyResponse(clientResponse);

            if (isSse(request)) {
                request.closeHandler(proxyConnectionClosed -> {
                    clientResponse.exceptionHandler(null);
                    cancel();
                });
            }

            response.pause();

            response.cancelHandler(tracker);

            // Copy body content
            clientResponse.handler(event -> response.bodyHandler().handle(Buffer.buffer(event)));

            // Signal end of the response
            clientResponse.endHandler(event -> {
                response.endHandler().handle(null);
                tracker.handle(null);
            });

            clientResponse.exceptionHandler(throwable -> {
                LOGGER.error(
                    "Unexpected error while handling backend response for request {} {} - {}",
                    httpClientRequest.getMethod(),
                    httpClientRequest.absoluteURI(),
                    throwable.getMessage()
                );

                response.endHandler().handle(null);
                tracker.handle(null);
            });

            clientResponse.customFrameHandler(
                frame -> response.writeCustomFrame(HttpFrame.create(frame.type(), frame.flags(), Buffer.buffer(frame.payload())))
            );

            // And send it to the client
            sendToClient(response);
        } else {
            handleException(clientResponseFuture.cause());
            tracker.handle(null);
        }

        return response;
    }

    @Override
    public Connection cancel() {
        this.canceled = true;
        this.httpClientRequest.reset();
        cancelHandler.handle(null);
        if (response != null) {
            response.bodyHandler(null);
        }
        return this;
    }

    private boolean isCanceled() {
        return this.canceled;
    }

    private boolean isTransmitted() {
        return transmitted;
    }

    @Override
    public Connection exceptionHandler(Handler<Throwable> timeoutHandler) {
        this.timeoutHandler = timeoutHandler;
        return this;
    }

    @Override
    protected void sendToClient(Response response) {
        transmitted = true;
        super.sendToClient(response);
    }

    private void handleConnectTimeout(Throwable throwable) {
        this.timeoutHandler.handle(throwable);
    }

    private Handler<Throwable> timeoutHandler() {
        return this.timeoutHandler;
    }

    @Override
    public HttpConnection<T> write(Buffer chunk) {
        // There is some request content, set the flag to true
        content = true;

        if (!headersWritten) {
            this.writeHeaders();
        }

        httpClientRequest.write(io.vertx.core.buffer.Buffer.buffer(chunk.getNativeBuffer()));

        return this;
    }

    @Override
    public WriteStream<Buffer> drainHandler(Handler<Void> drainHandler) {
        httpClientRequest.drainHandler(aVoid -> drainHandler.handle(null));
        return this;
    }

    @Override
    public boolean writeQueueFull() {
        return httpClientRequest.writeQueueFull();
    }

    private void writeHeaders() {
        writeUpstreamHeaders();

        headersWritten = true;
    }

    protected void writeUpstreamHeaders() {
        HttpHeaders headers = request.headers();

        // Check chunk flag on the request if there are some content to push and if transfer_encoding is set
        // with chunk value
        if (content) {
            String encoding = headers.getFirst(io.vertx.core.http.HttpHeaders.TRANSFER_ENCODING);
            if (encoding != null && encoding.contains(HttpHeadersValues.TRANSFER_ENCODING_CHUNKED)) {
                httpClientRequest.setChunked(true);
            }
        } else {
            request.headers().remove(io.vertx.core.http.HttpHeaders.TRANSFER_ENCODING);
        }

        // Copy headers to upstream
        request
            .headers()
            .names()
            .forEach(name -> {
                httpClientRequest.headers().set(name, request.headers().getAll(name));
            });
    }

    @Override
    public void end() {
        // Request can be null in case of connectivity issue with the upstream
        if (httpClientRequest != null) {
            if (!headersWritten) {
                this.writeHeaders();
            }

            if (!canceled) {
                httpClientRequest.end();
            }
        }
    }

    @Override
    public Connection writeCustomFrame(HttpFrame frame) {
        httpClientRequest.writeCustomFrame(
            frame.type(),
            frame.flags(),
            io.vertx.core.buffer.Buffer.buffer(frame.payload().getNativeBuffer())
        );

        return this;
    }

    private boolean isSse(ProxyRequest request) {
        return HttpHeaderValues.TEXT_EVENT_STREAM.contentEqualsIgnoreCase(request.headers().get(HttpHeaderNames.ACCEPT));
    }
}
