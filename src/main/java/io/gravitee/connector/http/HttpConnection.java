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
import io.netty.channel.ConnectTimeoutException;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.vertx.core.AsyncResult;
import io.vertx.core.Context;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author GraviteeSource Team
 */
public class HttpConnection<T extends HttpResponse> extends AbstractHttpConnection<HttpEndpoint> {

    private final Logger LOGGER = LoggerFactory.getLogger(this.getClass());

    private static final Set<CharSequence> HOP_HEADERS;
    private static final String SERVER_NULL_PATTERN = " for server null";

    // How often the drain samples for new arrivals. Only a sampling rate: what ends the drain is
    // the quiet threshold below, so this can be tightened or loosened without changing the point
    // at which the exchange is considered quiescent.
    private static final long CLOSED_RESPONSE_DRAIN_CHECK_DELAY_MS = 15;

    // Real elapsed time since the last chunk actually arrived before the response is considered
    // quiescent. Only a guess, and only used when the upstream framing gives us no better signal
    // (no Content-Length, not chunked) - see hasKnownFraming(). Deliberately measured against the
    // last arrival rather than against "did a chunk arrive since the previous sample": under
    // event-loop contention a sample lands in a window where the next chunk is only milliseconds
    // away, and treating that single miss as quiescence finalized the exchange and silently
    // dropped the rest of the body (APIM-15055, reproduced at 40 concurrent clients). Sized at
    // several multiples of the sampling rate so ordinary scheduling jitter cannot trip it.
    private static final long CLOSED_RESPONSE_DRAIN_QUIET_THRESHOLD_MS = 60;

    // Ceiling on the drain, protecting against a backend that never stops sending. Counts only the
    // time the downstream was actually able to take bytes, not raw elapsed time: the gateway pauses
    // the response whenever downstream demand hits zero (FlowableProxyResponse.handleChunk), and a
    // slow client holds that pause for well over a second on a large download. Charging paused time
    // to this budget ran it out while the downstream was asking for nothing, and the bytes already
    // received and still queued behind the pause were then discarded (APIM-15055).
    private static final long CLOSED_RESPONSE_DRAIN_MAX_BUDGET_MS = 1_000;

    // Ceiling on a downstream that pauses and never comes back, so it cannot hold the exchange -
    // and the in-flight accounting that goes with it - open for good. Counted from the last byte
    // actually handed downstream, so a client that keeps consuming is never cut off by it however
    // long it takes; only one that has taken nothing at all for this long is treated as gone. Sized
    // far past any pause a consuming client produces, and at the order of the gateway's own overall
    // proxy request timeout, past which the gateway cancels the connection itself and the drain
    // ends on the isCanceled() check instead.
    private static final long CLOSED_RESPONSE_DRAIN_MAX_PAUSE_MS = 30_000;

    // Chunks that arrive before the downstream body handler is attached are buffered here instead
    // of discarded, up to this size; past it, further chunks are discarded and logged once instead
    // of buffered without bound, since pausing on overflow would need the downstream to resume us
    // and nothing guarantees it will once we never told it we were paused in the first place.
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
    private T response;
    private Handler<Throwable> timeoutHandler;
    private boolean canceled = false;
    private boolean transmitted = false;
    private boolean headersWritten = false;
    private boolean content = false;
    private boolean missingBodyHandlerLogged = false;
    private String targetServer;
    private final Deque<byte[]> bufferedUpstreamChunks = new ArrayDeque<>();
    private int bufferedUpstreamBytes = 0;
    private boolean bufferThresholdExceededLogged = false;
    private boolean requestOneChunkAtATime;
    // Whether upstream chunks arriving before the downstream body handler is attached are buffered
    // locally instead of dropped. Keyed on keep-alive alone, not on the protocol: a non-keep-alive
    // connection is closed by the backend as soon as the response completes, so a chunk dropped
    // while the handler is still being attached is never retransmitted - that is true of a
    // multiplexed protocol just as it is of HTTP/1.x. Keep-alive endpoints retain the original
    // drop-and-log behavior.
    private boolean bufferChunksUntilBodyHandlerAttached;
    // Declared upstream framing, captured when the response headers arrive. Only a Content-Length
    // gives the connector a completion target it can check for itself; chunked framing is complete
    // when Vert.x decodes the terminating chunk and fires its endHandler, which the connector
    // observes but cannot derive from a byte count - see hasKnownFraming() and
    // isUpstreamResponseFullyDelivered().
    private long declaredContentLength = -1;
    private boolean chunkedUpstreamResponse;
    // Counted where a chunk reaches the downstream body handler, not where it arrives from
    // upstream: a received chunk can still be discarded (past LOCAL_UPSTREAM_BUFFER_CAP, or with
    // no body handler registered on a keep-alive endpoint), and counting it on arrival would let
    // isUpstreamResponseFullyDelivered() report the body complete while the client got less.
    private long deliveredByteCount;
    // Guards against ending the exchange twice: once the close-recovery path is waiting on
    // awaitDrainQuiescence, Vert.x's own endHandler is still live and can still fire on its own
    // (e.g. once it finishes decoding a chunked terminator that was already fully received), racing
    // with our own completion check.
    private boolean upstreamResponseEnded;
    // Whether the downstream has been told the response ended. Kept apart from
    // upstreamResponseEnded because the two can happen far apart: the upstream can complete before
    // the downstream has attached any handler at all, and the body and the end signal it is owed
    // then have to wait for that attach instead of being dropped on the floor (APIM-15055).
    private boolean downstreamEndSignalled;
    // Drain bookkeeping, meaningful only between startDrainBookkeeping() and the end of the
    // close-recovery drain it belongs to.
    private long drainDeliveredByteCountAtLastCheck;
    private long drainLastDeliveryAtNanos;
    private long drainLastCheckAtNanos;
    private long drainUnpausedNanos;

    public HttpConnection(HttpEndpoint endpoint, ProxyRequest request) {
        super(endpoint);
        this.request = request;
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
            response.handlersAttachedHandler(attached -> completeDownstreamHandoff());
            chunkedUpstreamResponse = isChunkedTransferEncoding(clientResponse);
            declaredContentLength = chunkedUpstreamResponse ? -1 : parseDeclaredContentLength(clientResponse);

            if (isSse(request)) {
                request.closeHandler(proxyConnectionClosed -> {
                    clientResponse.exceptionHandler(null);
                    cancel();
                });
            }

            response.pause();

            response.cancelHandler(tracker);

            // Endpoints without keep-alive request one chunk at a time (see below) instead of
            // waiting for the downstream to call resume() itself: that wait is what leaves demand
            // at zero long enough for Vert.x's internal queue to back up past its threshold and
            // disable Netty's socket-level autoRead - and if the backend then closes the
            // connection, whatever is still sitting in the kernel receive buffer is lost for good.
            // A steady one-at-a-time demand keeps that queue from ever backing up that far, without
            // ever granting the unbounded demand that let every concurrent connection's reads
            // compete unthrottled for the same event-loop threads.
            //
            // This mechanism is HTTP/1.x-specific: a paused HTTP/2 stream (VertxHttp2Stream.doPause())
            // only pauses that stream's own queue and never touches the shared connection's
            // socket-level autoRead, since one HTTP/2 connection multiplexes many streams. So an
            // HTTP/2 endpoint - even one explicitly configured with keep-alive off - keeps the
            // original behavior instead of taking a pacing change it has no bug to be fixed by.
            // Gated positively on HTTP/1.x rather than negatively on "not HTTP/2", so a future
            // multiplexed protocol added to HttpVersion defaults to the original behavior too.
            //
            // That HTTP/1.x gate scopes the pacing mechanism only. Buffering chunks that arrive
            // before the downstream body handler is attached is a separate concern with no
            // protocol dependency - nothing about it relies on socket-level autoRead - so it
            // applies to every non-keep-alive endpoint whatever version was negotiated.
            boolean isHttp1 = httpClientRequest.version() == HttpVersion.HTTP_1_0 || httpClientRequest.version() == HttpVersion.HTTP_1_1;
            requestOneChunkAtATime = isKeepAliveDisabled() && isHttp1;
            bufferChunksUntilBodyHandlerAttached = isKeepAliveDisabled();

            // Tracks when upstream activity was last observed, so a connection-close can wait out
            // re-reads still in flight (e.g. autoRead being re-armed by resume()) instead of ending
            // before they land. Seeded with the moment the response headers arrived, so a close on
            // a response that never produced a chunk is still given the same quiet period.
            AtomicLong lastChunkReceivedAtNanos = new AtomicLong(System.nanoTime());

            // Copy body content. Kept as two fully separate registrations rather than one
            // handler with an inline check, so the keep-alive (legacy) path is verifiable by
            // reading the else-branch alone, with nothing from the one-chunk-at-a-time mode
            // mixed into it.
            if (requestOneChunkAtATime) {
                clientResponse.handler(event -> {
                    lastChunkReceivedAtNanos.set(System.nanoTime());
                    deliverUpstreamChunk(event.getBytes());
                    clientResponse.fetch(1);
                });
                clientResponse.fetch(1);
            } else {
                clientResponse.handler(event -> {
                    lastChunkReceivedAtNanos.set(System.nanoTime());
                    deliverUpstreamChunk(event.getBytes());
                });
            }

            // Signal end of the response
            clientResponse.endHandler(event -> endUpstreamResponse(tracker));

            clientResponse.exceptionHandler(throwable -> {
                var vertxContext = Vertx.currentContext();
                if (throwable instanceof HttpClosedException && !isCanceled() && vertxContext != null) {
                    // TCP orders the FIN after all data, and the kernel reports EOF only once the
                    // receive buffer is drained, so every byte the backend sent has necessarily
                    // been read into userspace by the time this fires - it is queued in the paused
                    // read-stream, waiting on demand. Draining it is purely a matter of staying
                    // alive long enough for it to flow through, so wait until we know it has -
                    // see awaitDrainQuiescence - before signalling the end of the exchange.
                    response.resume();
                    startDrainBookkeeping();
                    awaitDrainQuiescence(vertxContext, clientResponse, lastChunkReceivedAtNanos, tracker);
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
            tracker.handle(null);
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
            deliveredByteCount += chunkBytes.length;
        } else {
            logMissingBodyHandlerOnce();
        }
    }

    private void deliverUpstreamChunkWithLocalBuffering(byte[] chunkBytes) {
        Handler<Buffer> bodyHandler = response.bodyHandler();
        if (bodyHandler != null) {
            flushBufferedUpstreamChunks(bodyHandler);
            bodyHandler.handle(Buffer.buffer(chunkBytes));
            deliveredByteCount += chunkBytes.length;
        } else {
            bufferUpstreamChunk(chunkBytes);
        }
    }

    private void bufferUpstreamChunk(byte[] chunkBytes) {
        if (bufferedUpstreamBytes >= LOCAL_UPSTREAM_BUFFER_CAP) {
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
        bufferedUpstreamBytes += chunkBytes.length;
    }

    private void flushBufferedUpstreamChunks(Handler<Buffer> bodyHandler) {
        if (bufferedUpstreamChunks.isEmpty()) {
            return;
        }

        byte[] chunk;
        while ((chunk = bufferedUpstreamChunks.poll()) != null) {
            bodyHandler.handle(Buffer.buffer(chunk));
            deliveredByteCount += chunk.length;
        }
        bufferedUpstreamBytes = 0;
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

    private void logDrainBudgetExhausted() {
        logDrainGaveUp(CLOSED_RESPONSE_DRAIN_MAX_BUDGET_MS + "ms of draining with the downstream able to take bytes");
    }

    private void logDrainAbandonedPausedDownstream(long pausedForMs) {
        logDrainGaveUp("the downstream took no byte for " + pausedForMs + "ms");
    }

    // Only a declared Content-Length lets the connector prove a body was cut short. Chunked and
    // close-delimited framing leave it nothing to compare the delivered byte count against, so a
    // body handed over in full is indistinguishable from one cut short and calling it truncated is
    // a guess - a guess that was wrong every time QA measured it, 99 warnings over 120 chunked 46MB
    // downloads whose bodies were all byte-exact (APIM-15055). With no length to check against the
    // line reports only what was observed, and at debug: a chunked response whose backend closes
    // the connection reaches this ordinarily rather than exceptionally, and a warning apiece would
    // flood production.
    private void logDrainGaveUp(String gaveUpAfter) {
        if (isUpstreamResponseShortOfDeclaredLength()) {
            LOGGER.warn(
                "Ending upstream response for request {} {} after {} - {} of the {} declared bytes reached the downstream, so the response body is truncated",
                httpClientRequest.getMethod(),
                httpClientRequest.absoluteURI(),
                gaveUpAfter,
                deliveredByteCount,
                declaredContentLength
            );
        } else {
            LOGGER.debug(
                "Ending upstream response for request {} {} after {} - {} bytes reached the downstream, and the upstream declared no length to check that against",
                httpClientRequest.getMethod(),
                httpClientRequest.absoluteURI(),
                gaveUpAfter,
                deliveredByteCount
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
        // upstreamResponseEnded guards the close-recovery race described on that field.
        if (isCanceled() || upstreamResponseEnded) {
            return;
        }
        upstreamResponseEnded = true;

        completeDownstreamHandoff();

        // Released even when the hand-off is still waiting on the downstream to attach: the tracker
        // is the connector's own in-flight accounting, and the upstream exchange is over whether or
        // not anyone downstream ever comes to collect the response.
        tracker.handle(null);
    }

    private void completeDownstreamHandoff() {
        if (!upstreamResponseEnded || isCanceled() || downstreamEndSignalled) {
            return;
        }

        Handler<Buffer> bodyHandler = response.bodyHandler();
        if (bodyHandler != null) {
            flushBufferedUpstreamChunks(bodyHandler);
        }

        Handler<Void> endHandler = response.endHandler();
        if (endHandler != null) {
            downstreamEndSignalled = true;
            endHandler.handle(null);
        }
    }

    private void startDrainBookkeeping() {
        drainDeliveredByteCountAtLastCheck = deliveredByteCount;
        drainLastDeliveryAtNanos = System.nanoTime();
        drainLastCheckAtNanos = drainLastDeliveryAtNanos;
        drainUnpausedNanos = 0;
    }

    private void awaitDrainQuiescence(
        Context vertxContext,
        HttpClientResponse clientResponse,
        AtomicLong lastChunkReceivedAtNanos,
        Handler<Void> tracker
    ) {
        vertxContext
            .owner()
            .setTimer(CLOSED_RESPONSE_DRAIN_CHECK_DELAY_MS, timerId -> {
                // Vert.x's endHandler has already finalized the exchange, so there is nothing left
                // to detach or end. This is the only completion signal a chunked response has:
                // declaredContentLength is -1 for chunked framing, so the byte-count check below
                // can never become true and a chunked response whose terminator never arrives runs
                // the drain to the deadline however complete its body is. The same holds for a
                // bodyless response (HEAD, 204, 304), which declares a length whose bytes never
                // arrive.
                if (upstreamResponseEnded) {
                    return;
                }

                if (isCanceled() || isUpstreamResponseFullyDelivered()) {
                    endUpstreamResponseAfterClose(clientResponse, tracker);
                    return;
                }

                long now = System.nanoTime();
                boolean downstreamPaused = response.isPaused();

                if (deliveredByteCount > drainDeliveredByteCountAtLastCheck) {
                    drainDeliveredByteCountAtLastCheck = deliveredByteCount;
                    drainLastDeliveryAtNanos = now;
                }
                if (!downstreamPaused) {
                    drainUnpausedNanos += now - drainLastCheckAtNanos;
                }
                drainLastCheckAtNanos = now;

                if (downstreamPaused) {
                    long pausedForMs = TimeUnit.NANOSECONDS.toMillis(now - drainLastDeliveryAtNanos);
                    if (pausedForMs >= CLOSED_RESPONSE_DRAIN_MAX_PAUSE_MS) {
                        logDrainAbandonedPausedDownstream(pausedForMs);
                        endUpstreamResponseAfterClose(clientResponse, tracker);
                        return;
                    }
                    awaitDrainQuiescence(vertxContext, clientResponse, lastChunkReceivedAtNanos, tracker);
                    return;
                }

                // A response with known framing (Content-Length or chunked) is only ever ended here
                // by isUpstreamResponseFullyDelivered() above or by the budget below: an inactivity
                // guess can fire in a gap between two chunks that are both still to come, which is
                // exactly how a declared-length response lost its tail (APIM-15055). Only a
                // close-delimited response - no declared length, RFC 9112 §6.3 - has no signal other
                // than that guess. Reached only while the downstream is unpaused, because arrivals
                // necessarily stop while it is paused and their silence would then say nothing about
                // whether anything is left to hand over.
                if (!hasKnownFraming()) {
                    long quietForMs = TimeUnit.NANOSECONDS.toMillis(now - lastChunkReceivedAtNanos.get());
                    if (quietForMs >= CLOSED_RESPONSE_DRAIN_QUIET_THRESHOLD_MS) {
                        endUpstreamResponseAfterClose(clientResponse, tracker);
                        return;
                    }
                }

                if (TimeUnit.NANOSECONDS.toMillis(drainUnpausedNanos) >= CLOSED_RESPONSE_DRAIN_MAX_BUDGET_MS) {
                    logDrainBudgetExhausted();
                    endUpstreamResponseAfterClose(clientResponse, tracker);
                } else {
                    awaitDrainQuiescence(vertxContext, clientResponse, lastChunkReceivedAtNanos, tracker);
                }
            });
    }

    private boolean hasKnownFraming() {
        return chunkedUpstreamResponse || declaredContentLength >= 0;
    }

    private boolean isUpstreamResponseFullyDelivered() {
        return declaredContentLength >= 0 && deliveredByteCount >= declaredContentLength;
    }

    private boolean isUpstreamResponseShortOfDeclaredLength() {
        return declaredContentLength >= 0 && deliveredByteCount < declaredContentLength;
    }

    private boolean isChunkedTransferEncoding(HttpClientResponse clientResponse) {
        String transferEncoding = clientResponse.getHeader(HttpHeaderNames.TRANSFER_ENCODING);
        return transferEncoding != null && transferEncoding.contains(HttpHeadersValues.TRANSFER_ENCODING_CHUNKED);
    }

    private long parseDeclaredContentLength(HttpClientResponse clientResponse) {
        String contentLength = clientResponse.getHeader(HttpHeaderNames.CONTENT_LENGTH);
        if (contentLength == null) {
            return -1;
        }
        try {
            return Long.parseLong(contentLength.trim());
        } catch (NumberFormatException e) {
            return -1;
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
}
