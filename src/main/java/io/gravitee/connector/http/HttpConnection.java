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

import io.gravitee.common.http.HttpHeadersValues;
import io.gravitee.connector.api.Connection;
import io.gravitee.connector.api.Response;
import io.gravitee.connector.api.response.ClientConnectionErrorResponse;
import io.gravitee.connector.api.response.ClientConnectionTimeoutResponse;
import io.gravitee.connector.http.endpoint.HttpEndpoint;
import io.gravitee.gateway.api.ExecutionContext;
import io.gravitee.gateway.api.buffer.Buffer;
import io.gravitee.gateway.api.handler.Handler;
import io.gravitee.gateway.api.http.HttpHeaders;
import io.gravitee.gateway.api.http2.HttpFrame;
import io.gravitee.gateway.api.proxy.ProxyRequest;
import io.gravitee.gateway.api.stream.WriteStream;
import io.gravitee.node.api.opentelemetry.Span;
import io.gravitee.node.api.opentelemetry.http.ObservableHttpClientRequest;
import io.gravitee.node.api.opentelemetry.http.ObservableHttpClientResponse;
import io.gravitee.node.logging.NodeLoggerFactory;
import io.netty.channel.ConnectTimeoutException;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.*;
import io.vertx.core.internal.buffer.BufferInternal;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author GraviteeSource Team
 */
public class HttpConnection<T extends HttpResponse> extends AbstractHttpConnection<HttpEndpoint> {

    private final Logger LOGGER = NodeLoggerFactory.getLogger(this.getClass());

    private static final Set<CharSequence> HOP_HEADERS;
    private static final String SERVER_NULL_PATTERN = " for server null";

    // Past this, chunks are discarded and logged once rather than buffered without bound: pausing
    // on overflow would need the downstream to resume us, and it was never told we paused.
    private static final int LOCAL_UPSTREAM_BUFFER_CAP = 16 * 1024;

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
    private final CloseRecoveryDrain.DrainTimings drainTimings;
    private T response;
    private CloseRecoveryDrain drain;
    private Handler<Throwable> timeoutHandler;
    private boolean canceled = false;
    private boolean transmitted = false;
    private boolean headersWritten = false;
    private boolean content = false;
    private boolean missingBodyHandlerLogged = false;
    private String targetServer;
    private final BufferedUpstreamChunks bufferedUpstreamChunks = new BufferedUpstreamChunks();
    private boolean bufferThresholdExceededLogged = false;
    // Keyed on keep-alive, not protocol: a non-keep-alive backend closes as soon as the response
    // completes, so a chunk dropped while the handler is still being attached is never resent.
    private boolean bufferChunksUntilBodyHandlerAttached;

    public HttpConnection(HttpEndpoint endpoint, ProxyRequest request) {
        this(endpoint, request, CloseRecoveryDrain.DrainTimings.DEFAULT);
    }

    HttpConnection(HttpEndpoint endpoint, ProxyRequest request, CloseRecoveryDrain.DrainTimings drainTimings) {
        super(endpoint);
        this.request = request;
        this.drainTimings = drainTimings;
    }

    @Override
    public void connect(
        final ExecutionContext ctx,
        HttpClient httpClient,
        int port,
        String host,
        String uri,
        Handler<Void> connectionHandler,
        Handler<Void> tracker
    ) {
        this.targetServer = host + ":" + port;
        // Remove HOP-by-HOP headers
        for (CharSequence header : HOP_HEADERS) {
            request.headers().remove(header.toString());
        }

        if (!endpoint.getHttpClientOptions().isPropagateClientAcceptEncoding()) {
            // Let the API Owner choose the Accept-Encoding between the gateway and the backend
            request.headers().remove(io.gravitee.common.http.HttpHeaders.ACCEPT_ENCODING);
        }

        RequestOptions requestOptions = prepareRequestOptions(port, host, uri);
        Future<HttpClientRequest> requestFuture = prepareUpstreamRequest(httpClient, requestOptions);
        requestFuture.onComplete(event -> {
            //Copy the request options to initialize the observable http client request headers not null
            var observableRequestOptions = new RequestOptions(requestOptions);
            observableRequestOptions.setHeaders(io.vertx.core.http.HttpHeaders.headers());
            ObservableHttpClientRequest observableHttpClientRequest = new ObservableHttpClientRequest(observableRequestOptions);

            Span requestSpan = ctx.getTracer().startSpanFrom(observableHttpClientRequest);

            //Copy the headers from the observable request into the proxy request to ensure that the traceparent header
            //is set since requestOptions is not used here to set the headers but the proxy request is
            observableHttpClientRequest
                .headers()
                .forEach(header -> {
                    request.headers().set(header.getKey(), header.getValue());
                });
            cancelHandler(tracker);

            if (event.succeeded()) {
                httpClientRequest = event.result();
                observableHttpClientRequest.httpClientRequest(httpClientRequest);

                httpClientRequest
                    .response()
                    .onComplete(response -> {
                        // Prepare upstream response
                        handleUpstreamResponse(ctx, response, tracker, requestSpan);
                    });

                httpClientRequest
                    .connection()
                    .exceptionHandler(t -> {
                        ctx.getTracer().endOnError(requestSpan, t);
                        LOGGER.debug(
                            "Exception occurs during HTTP connection for api [{}] & request id [{}]: {}",
                            request.metrics().getApi(),
                            request.metrics().getRequestId(),
                            t.getMessage()
                        );
                        request.metrics().setMessage(t.getMessage());
                    });

                httpClientRequest.exceptionHandler(exEvent -> {
                    ctx.getTracer().endOnError(requestSpan, exEvent.getCause());
                    if (!isCanceled() && !isTransmitted()) {
                        handleException(exEvent.getCause());
                        tracker.handle(null);
                    }
                });
                connectionHandler.handle(null);
            } else {
                ctx.getTracer().endOnError(requestSpan, event.cause());
                connectionHandler.handle(null);
                handleException(event.cause());
                tracker.handle(null);
            }
        });
    }

    @Override
    public void connect(
        ExecutionContext context,
        WebSocketClient webSocketClient,
        int port,
        String host,
        String uri,
        Handler<Void> connectionHandler,
        Handler<Void> tracker
    ) {
        throw new UnsupportedOperationException("Not supported.");
    }

    private void handleException(Throwable cause) {
        if (!isCanceled() && !isTransmitted()) {
            String errorMessage = rewriteServerNull(cause.getMessage());
            request.metrics().setMessage(errorMessage);

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

    private String rewriteServerNull(String message) {
        return (message != null && message.contains(SERVER_NULL_PATTERN) && targetServer != null)
            ? message.replace(SERVER_NULL_PATTERN, " for server " + targetServer)
            : message;
    }

    protected RequestOptions prepareRequestOptions(int port, String host, String uri) {
        return new RequestOptions()
            .setHost(host)
            .setMethod(HttpMethod.valueOf(request.method().name()))
            .setPort(port)
            .setURI(uri)
            .setSsl(request.uri().split(":")[0].equalsIgnoreCase("https"))
            .setTimeout(endpoint.getHttpClientOptions().getReadTimeout())
            .setFollowRedirects(endpoint.getHttpClientOptions().isFollowRedirects());
    }

    protected Future<HttpClientRequest> prepareUpstreamRequest(HttpClient httpClient, RequestOptions requestOptions) {
        // Prepare HTTP request
        return httpClient.request(requestOptions);
    }

    protected T createProxyResponse(HttpClientResponse clientResponse) {
        return (T) new HttpResponse(clientResponse);
    }

    protected T handleUpstreamResponse(
        final ExecutionContext ctx,
        final AsyncResult<HttpClientResponse> clientResponseFuture,
        Handler<Void> tracker,
        final Span requestSpan
    ) {
        if (clientResponseFuture.succeeded()) {
            HttpClientResponse clientResponse = clientResponseFuture.result();
            ctx.getTracer().endWithResponse(requestSpan, new ObservableHttpClientResponse(clientResponse));
            response = createProxyResponse(clientResponse);
            drain = new CloseRecoveryDrain(drainTimings, httpClientRequest, clientResponse, this::isCanceled, response::isPaused);
            response.handlersAttachedHandler(attached -> completeDownstreamHandoff());

            if (isSse(request)) {
                request.closeHandler(proxyConnectionClosed -> {
                    clientResponse.exceptionHandler(null);
                    cancel();
                });
            }

            response.pause();

            response.cancelHandler(tracker);

            bufferChunksUntilBodyHandlerAttached = isKeepAliveDisabled();

            // Copy body content
            clientResponse.handler(event -> {
                drain.chunkReceived();
                deliverUpstreamChunk(event.getBytes());
            });

            // Signal end of the response
            clientResponse.endHandler(event -> endUpstreamResponse(tracker));

            clientResponse.exceptionHandler(throwable -> {
                var vertxContext = Vertx.currentContext();
                if (throwable instanceof HttpClosedException && !isCanceled() && vertxContext != null) {
                    // No resume() here: it would override a pause the downstream set on purpose
                    // and charge that pause to the drain's unpaused budget.
                    drain.awaitQuiescence(vertxContext, () -> endUpstreamResponseAfterClose(clientResponse, tracker));
                } else {
                    LOGGER.error(
                        "Unexpected error while handling backend response for request {} {} - {}",
                        httpClientRequest.getMethod(),
                        httpClientRequest.absoluteURI(),
                        throwable.getMessage()
                    );
                    endUpstreamResponseAfterClose(clientResponse, tracker);
                }
            });

            clientResponse.customFrameHandler(frame ->
                response.writeCustomFrame(HttpFrame.create(frame.type(), frame.flags(), Buffer.buffer(frame.payload())))
            );

            // And send it to the client
            sendToClient(response);
        } else {
            ctx.getTracer().endWithResponseAndError(requestSpan, clientResponseFuture.result(), clientResponseFuture.cause());
            handleException(clientResponseFuture.cause());
            if (!isCanceled()) {
                tracker.handle(null);
            }
        }

        return response;
    }

    private boolean isKeepAliveDisabled() {
        return !endpoint.getHttpClientOptions().isKeepAlive();
    }

    private void deliverUpstreamChunk(byte[] chunkBytes) {
        if (bufferChunksUntilBodyHandlerAttached) {
            deliverUpstreamChunkWithLocalBuffering(chunkBytes);
        } else {
            deliverUpstreamChunkForKeepAliveEndpoint(chunkBytes);
        }
    }

    private void deliverUpstreamChunkForKeepAliveEndpoint(byte[] chunkBytes) {
        Handler<Buffer> bodyHandler = response.bodyHandler();
        if (bodyHandler != null) {
            bodyHandler.handle(Buffer.buffer(chunkBytes));
            drain.chunkDelivered(chunkBytes.length);
        } else {
            logMissingBodyHandlerOnce();
        }
    }

    private void deliverUpstreamChunkWithLocalBuffering(byte[] chunkBytes) {
        Handler<Buffer> bodyHandler = response.bodyHandler();
        if (bodyHandler != null) {
            flushBufferedUpstreamChunks(bodyHandler);
            bodyHandler.handle(Buffer.buffer(chunkBytes));
            drain.chunkDelivered(chunkBytes.length);
        } else {
            bufferUpstreamChunk(chunkBytes);
        }
    }

    private void bufferUpstreamChunk(byte[] chunkBytes) {
        if (bufferedUpstreamChunks.sizeBytes() >= LOCAL_UPSTREAM_BUFFER_CAP) {
            if (!bufferThresholdExceededLogged) {
                bufferThresholdExceededLogged = true;
                LOGGER.warn(
                    "Discarding upstream response body for request {} {} - exceeded {} bytes buffered locally while waiting for the downstream body handler to be attached",
                    httpClientRequest.getMethod(),
                    httpClientRequest.absoluteURI(),
                    LOCAL_UPSTREAM_BUFFER_CAP
                );
            }
            return;
        }

        bufferedUpstreamChunks.add(chunkBytes);
    }

    private void flushBufferedUpstreamChunks(Handler<Buffer> bodyHandler) {
        bufferedUpstreamChunks.drainTo(chunk -> {
            bodyHandler.handle(Buffer.buffer(chunk));
            drain.chunkDelivered(chunk.length);
        });
    }

    private void logMissingBodyHandlerOnce() {
        if (!missingBodyHandlerLogged) {
            missingBodyHandlerLogged = true;
            LOGGER.warn(
                "Discarding upstream response body for request {} {} - no downstream body handler is registered",
                httpClientRequest.getMethod(),
                httpClientRequest.absoluteURI()
            );
        }
    }

    private void detachUpstreamStream(HttpClientResponse clientResponse) {
        clientResponse.handler(null);
        response.pause();
    }

    private void endUpstreamResponseAfterClose(HttpClientResponse clientResponse, Handler<Void> tracker) {
        detachUpstreamStream(clientResponse);
        endUpstreamResponse(tracker);
    }

    private void endUpstreamResponse(Handler<Void> tracker) {
        // cancel() already released the tracker through the connection-level cancelHandler, so
        // finalizing again here would decrement the in-flight request counter a second time.
        if (isCanceled() || !drain.markUpstreamEnded()) {
            return;
        }

        completeDownstreamHandoff();

        // Released even while the hand-off still waits on the downstream to attach: the upstream
        // exchange is over whether or not anyone downstream collects the response.
        tracker.handle(null);
    }

    private void completeDownstreamHandoff() {
        if (!drain.isUpstreamEndedAwaitingDownstream() || isCanceled()) {
            return;
        }

        Handler<Buffer> bodyHandler = response.bodyHandler();
        if (bodyHandler != null) {
            flushBufferedUpstreamChunks(bodyHandler);
        } else if (!bufferedUpstreamChunks.isEmpty()) {
            return;
        }

        Handler<Void> endHandler = response.endHandler();
        if (endHandler != null) {
            drain.markDownstreamSignalled();
            endHandler.handle(null);
        }
    }

    @Override
    public Connection cancel() {
        // the gateway can cancel an already-canceled connection (a downstream cancel followed by the
        // SSE request.closeHandler), and re-firing cancelHandler would release the in-flight tracker twice.
        if (isCanceled()) {
            return this;
        }

        this.canceled = true;
        if (this.httpClientRequest != null) {
            // Debug rather than warn because the gateway cancels routinely - a client that
            // disconnects mid-download, a response already ended, a policy that interrupts the
            // chain - so a louder level would spam production on ordinary traffic. Logged only
            // here, since a cancel before the upstream request exists resets nothing and leaves no
            // client mid-stream.
            LOGGER.debug(
                "Cancelling upstream request {} {} - the request is reset and the downstream stops receiving the response body",
                httpClientRequest.getMethod(),
                httpClientRequest.absoluteURI()
            );
            this.httpClientRequest.reset();
        }
        if (cancelHandler != null) {
            cancelHandler.handle(null);
        }
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
        if (this.timeoutHandler != null) {
            this.timeoutHandler.handle(throwable);
        }
    }

    private Handler<Throwable> timeoutHandler() {
        return this.timeoutHandler;
    }

    @Override
    public HttpConnection<T> write(Buffer chunk) {
        // There is some request content, set the flag to true
        content = true;
        // Request can be null in case of connectivity issue with the upstream
        if (httpClientRequest != null) {
            if (!headersWritten) {
                this.writeHeaders();
            }

            /*
            When the http connection is upgraded from http1.1 to http2, an empty body is sent, even if the request is a GET.
            And in a GET request the CONTENT-LENGTH header does not exist.
            To avoid any issue in that specific situation, CONTENT-LENGTH header is set to 0.
             */
            HttpHeaders headers = request.headers();
            if (
                chunk.length() == 0 && (headers == null || !headers.contains(io.gravitee.gateway.api.http.HttpHeaderNames.CONTENT_LENGTH))
            ) {
                httpClientRequest.headers().set(io.gravitee.gateway.api.http.HttpHeaderNames.CONTENT_LENGTH, "0");
            }

            httpClientRequest.write(BufferInternal.buffer(chunk.getNativeBuffer()));
        }
        return this;
    }

    @Override
    public WriteStream<Buffer> drainHandler(Handler<Void> drainHandler) {
        if (this.httpClientRequest != null) {
            httpClientRequest.drainHandler(aVoid -> {
                if (drainHandler != null) {
                    drainHandler.handle(null);
                }
            });
        }
        return this;
    }

    @Override
    public boolean writeQueueFull() {
        // Request can be null in case of connectivity issue with the upstream
        if (httpClientRequest != null) {
            return httpClientRequest.writeQueueFull();
        }
        return false;
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
        if (httpClientRequest != null) {
            httpClientRequest.writeCustomFrame(frame.type(), frame.flags(), BufferInternal.buffer(frame.payload().getNativeBuffer()));
        }

        return this;
    }

    private boolean isSse(ProxyRequest request) {
        return HttpHeaderValues.TEXT_EVENT_STREAM.contentEqualsIgnoreCase(request.headers().get(HttpHeaderNames.ACCEPT));
    }

    private static final class BufferedUpstreamChunks {

        private final Deque<byte[]> chunks = new ArrayDeque<>();
        private int sizeBytes;

        void add(byte[] chunk) {
            chunks.add(chunk);
            sizeBytes += chunk.length;
        }

        void drainTo(Handler<byte[]> sink) {
            byte[] chunk;
            while ((chunk = chunks.poll()) != null) {
                sizeBytes -= chunk.length;
                sink.handle(chunk);
            }
        }

        int sizeBytes() {
            return sizeBytes;
        }

        boolean isEmpty() {
            return chunks.isEmpty();
        }
    }
}
