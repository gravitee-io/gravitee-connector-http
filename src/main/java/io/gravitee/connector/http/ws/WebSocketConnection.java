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
package io.gravitee.connector.http.ws;

import io.gravitee.common.http.HttpStatusCode;
import io.gravitee.connector.api.response.StatusResponse;
import io.gravitee.connector.http.AbstractHttpConnection;
import io.gravitee.connector.http.endpoint.HttpEndpoint;
import io.gravitee.gateway.api.buffer.Buffer;
import io.gravitee.gateway.api.handler.Handler;
import io.gravitee.gateway.api.proxy.ProxyRequest;
import io.gravitee.gateway.api.proxy.ws.WebSocketProxyRequest;
import io.gravitee.gateway.api.stream.WriteStream;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.UpgradeRejectedException;
import io.vertx.core.http.WebSocketConnectOptions;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author GraviteeSource Team
 */
public class WebSocketConnection extends AbstractHttpConnection<HttpEndpoint> {

    private static final Set<CharSequence> WS_HOP_HEADERS;

    static {
        Set<CharSequence> wsHopHeaders = new HashSet<>();

        // Hop-by-hop headers Websocket
        wsHopHeaders.add(HttpHeaderNames.KEEP_ALIVE);
        wsHopHeaders.add(HttpHeaderNames.PROXY_AUTHORIZATION);
        wsHopHeaders.add(HttpHeaderNames.PROXY_AUTHENTICATE);
        wsHopHeaders.add(HttpHeaderNames.PROXY_CONNECTION);
        wsHopHeaders.add(HttpHeaderNames.TE);
        wsHopHeaders.add(HttpHeaderNames.TRAILER);

        WS_HOP_HEADERS = Collections.unmodifiableSet(wsHopHeaders);
    }

    private final WebSocketProxyRequest wsProxyRequest;

    public WebSocketConnection(HttpEndpoint endpoint, ProxyRequest request) {
        super(endpoint);
        this.wsProxyRequest = (WebSocketProxyRequest) request;
    }

    @Override
    public void connect(HttpClient httpClient, int port, String host, String uri, Handler<Void> connectionHandler, Handler<Void> tracker) {
        // Remove hop-by-hop headers.
        for (CharSequence header : WS_HOP_HEADERS) {
            wsProxyRequest.headers().remove(header);
        }

        WebSocketConnectOptions options = new WebSocketConnectOptions().setHost(host).setPort(port).setURI(uri);

        // Add subprotocols based on the ones specified in the request headers
        if (wsProxyRequest.headers().contains(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL)) {
            wsProxyRequest.headers().getAll(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL).forEach(options::addSubProtocol);
        }

        wsProxyRequest.headers().forEach(entry -> options.addHeader(entry.getKey(), entry.getValue()));

        httpClient.webSocket(options, event -> {
            if (event.succeeded()) {
              event.result().pause();
                // The client -> gateway connection must be upgraded now that the one between gateway -> upstream
                // has been accepted
                wsProxyRequest
                    .upgrade()
                    .thenAccept(webSocketProxyRequest -> {
                        // From server to client
                        wsProxyRequest.frameHandler(frame -> {
                            if (frame.type() == io.gravitee.gateway.api.ws.WebSocketFrame.Type.BINARY) {
                                event
                                    .result()
                                    .writeFrame(
                                        io.vertx.core.http.WebSocketFrame.binaryFrame(
                                            io.vertx.core.buffer.Buffer.buffer(frame.data().getNativeBuffer()),
                                            frame.isFinal()
                                        )
                                    );
                            } else if (frame.type() == io.gravitee.gateway.api.ws.WebSocketFrame.Type.TEXT) {
                                event
                                    .result()
                                    .writeFrame(io.vertx.core.http.WebSocketFrame.textFrame(frame.data().toString(), frame.isFinal()));
                            } else if (frame.type() == io.gravitee.gateway.api.ws.WebSocketFrame.Type.CONTINUATION) {
                                event
                                    .result()
                                    .writeFrame(
                                        io.vertx.core.http.WebSocketFrame.continuationFrame(
                                            io.vertx.core.buffer.Buffer.buffer(frame.data().toString()),
                                            frame.isFinal()
                                        )
                                    );
                            } else if (frame.type() == io.gravitee.gateway.api.ws.WebSocketFrame.Type.PING) {
                                event
                                    .result()
                                    .writeFrame(
                                        io.vertx.core.http.WebSocketFrame.pingFrame(
                                            io.vertx.core.buffer.Buffer.buffer(frame.data().toString())
                                        )
                                    );
                            } else if (frame.type() == io.gravitee.gateway.api.ws.WebSocketFrame.Type.PONG) {
                                event
                                    .result()
                                    .writeFrame(
                                        io.vertx.core.http.WebSocketFrame.pongFrame(
                                            io.vertx.core.buffer.Buffer.buffer(frame.data().toString())
                                        )
                                    );
                            }
                        });

                        wsProxyRequest.closeHandler(result -> event.result().close());

                        // From client to server
                        event.result().frameHandler(frame -> wsProxyRequest.write(new WebSocketFrame(frame)));

                        event
                            .result()
                            .closeHandler(event1 -> {
                                wsProxyRequest.close();
                                tracker.handle(null);
                            });

                        event
                            .result()
                            .exceptionHandler(throwable -> {
                                wsProxyRequest.reject(HttpStatusCode.BAD_REQUEST_400);
                                sendToClient(new StatusResponse(HttpStatusCode.BAD_REQUEST_400));
                                tracker.handle(null);
                            });

                        connectionHandler.handle(null);

                        // Tell the reactor that the request has been handled by the HTTP client
                        sendToClient(new SwitchProtocolProxyResponse());

                        event.result().resume();
                    });
            } else {
                connectionHandler.handle(null);

                if (event.cause() instanceof UpgradeRejectedException) {
                    wsProxyRequest.reject(((UpgradeRejectedException) event.cause()).getStatus());
                    sendToClient(new StatusResponse(((UpgradeRejectedException) event.cause()).getStatus()));
                } else {
                    wsProxyRequest.reject(HttpStatusCode.BAD_GATEWAY_502);
                    sendToClient(new StatusResponse(HttpStatusCode.BAD_GATEWAY_502));
                }

                tracker.handle(null);
            }
        });
    }

    @Override
    public WriteStream<Buffer> write(Buffer content) {
        return this;
    }

    @Override
    public void end() {}
}
