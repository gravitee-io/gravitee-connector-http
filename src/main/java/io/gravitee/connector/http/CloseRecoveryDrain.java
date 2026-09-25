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
import io.gravitee.node.logging.NodeLoggerFactory;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.vertx.core.Context;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;

/**
 * Tracks one upstream exchange from active to upstream-ended to downstream-signalled, and decides
 * when a response whose upstream connection closed mid-body has handed downstream everything it
 * ever will. Every method runs on the event loop that owns the exchange.
 */
final class CloseRecoveryDrain {

    private static final Logger LOGGER = NodeLoggerFactory.getLogger(CloseRecoveryDrain.class);

    // Only a sampling rate: the quiet threshold, not this, decides when the exchange is quiescent.
    private static final long CHECK_DELAY_MS = 15;

    // Measured from the last arrival rather than "no chunk since the previous sample": under
    // event-loop contention a single sample can land just before the next chunk, and treating that
    // miss as quiescence drops the rest of the body. Several multiples of the sampling rate so
    // scheduling jitter cannot trip it.
    private static final long QUIET_THRESHOLD_MS = 60;

    // Counts only time the downstream could take bytes: the gateway pauses the response whenever
    // downstream demand hits zero, and charging that pause to the budget discarded bytes still
    // queued behind it.
    private static final long MAX_BUDGET_MS = 1_000;

    // Counted from the last byte handed downstream, so a client that keeps consuming is never cut
    // off; sized at the order of the gateway's own proxy request timeout.
    private static final long MAX_PAUSE_MS = 30_000;

    record DrainTimings(long checkDelayMs, long quietThresholdMs, long maxBudgetMs, long maxPauseMs) {
        static final DrainTimings DEFAULT = new DrainTimings(CHECK_DELAY_MS, QUIET_THRESHOLD_MS, MAX_BUDGET_MS, MAX_PAUSE_MS);
    }

    private final DrainTimings timings;
    private final HttpClientRequest clientRequest;
    private final BooleanSupplier canceled;
    private final BooleanSupplier downstreamPaused;
    // Only a Content-Length gives a completion target the connector can check for itself; chunked
    // framing completes when Vert.x decodes the terminating chunk and fires its endHandler.
    private final long declaredContentLength;
    private final boolean chunked;
    // Counted where a chunk reaches the downstream body handler, not where it arrives: a received
    // chunk can still be discarded, and counting it on arrival would report a short body complete.
    private long deliveredByteCount;
    // Seeded with the moment the headers arrived, so a close on a response that never produced a
    // chunk still gets the same quiet period.
    private long lastChunkReceivedAtNanos = System.nanoTime();
    // Moves forward one step at a time. UPSTREAM_ENDED guards against ending the exchange twice:
    // Vert.x's own endHandler stays live during the drain and can race with it.
    // DOWNSTREAM_SIGNALLED is separate because the upstream can end before the downstream has
    // attached any handler, and the end signal then has to wait for that attach.
    private ExchangeState exchangeState = ExchangeState.ACTIVE;

    CloseRecoveryDrain(
        DrainTimings timings,
        HttpClientRequest clientRequest,
        HttpClientResponse clientResponse,
        BooleanSupplier canceled,
        BooleanSupplier downstreamPaused
    ) {
        this.timings = timings;
        this.clientRequest = clientRequest;
        this.canceled = canceled;
        this.downstreamPaused = downstreamPaused;
        this.chunked = isChunkedTransferEncoding(clientResponse);
        this.declaredContentLength = chunked ? -1 : parseDeclaredContentLength(clientResponse);
    }

    void chunkReceived() {
        lastChunkReceivedAtNanos = System.nanoTime();
    }

    void chunkDelivered(int byteCount) {
        deliveredByteCount += byteCount;
    }

    boolean markUpstreamEnded() {
        return transitionTo(ExchangeState.UPSTREAM_ENDED);
    }

    boolean isUpstreamEndedAwaitingDownstream() {
        return exchangeState == ExchangeState.UPSTREAM_ENDED;
    }

    void markDownstreamSignalled() {
        transitionTo(ExchangeState.DOWNSTREAM_SIGNALLED);
    }

    // TCP orders the FIN after all data, so every byte the backend sent is already queued in the
    // paused read-stream when the close is reported; it flows as the downstream resumes on its own.
    void awaitQuiescence(Context vertxContext, Runnable onQuiescent) {
        scheduleCheck(vertxContext, new DrainBookkeeping(deliveredByteCount, System.nanoTime()), onQuiescent);
    }

    private void scheduleCheck(Context vertxContext, DrainBookkeeping drain, Runnable onQuiescent) {
        vertxContext
            .owner()
            .setTimer(timings.checkDelayMs(), timerId -> {
                // Vert.x's endHandler already finalized the exchange. For chunked and bodyless
                // responses this is the only completion signal the drain gets.
                if (exchangeState != ExchangeState.ACTIVE) {
                    return;
                }

                if (shouldEnd(drain)) {
                    onQuiescent.run();
                } else {
                    scheduleCheck(vertxContext, drain, onQuiescent);
                }
            });
    }

    private boolean shouldEnd(DrainBookkeeping drain) {
        if (canceled.getAsBoolean() || isFullyDelivered()) {
            return true;
        }

        long now = System.nanoTime();
        boolean paused = downstreamPaused.getAsBoolean();
        drain.recordSample(deliveredByteCount, now, paused);

        if (paused) {
            return hasPausedDownstreamBeenAbandoned(drain, now);
        }

        // Only a close-delimited response (RFC 9112 §6.3) falls back on the inactivity guess: with
        // known framing, a gap between two chunks still to come would cut the body short. Checked
        // only while unpaused, since arrivals necessarily stop while the downstream is paused.
        if (!hasKnownFraming() && TimeUnit.NANOSECONDS.toMillis(now - lastChunkReceivedAtNanos) >= timings.quietThresholdMs()) {
            return true;
        }

        return isUnpausedBudgetExhausted(drain);
    }

    private boolean hasPausedDownstreamBeenAbandoned(DrainBookkeeping drain, long now) {
        long pausedForMs = drain.millisSinceLastDelivery(now);
        if (pausedForMs < timings.maxPauseMs()) {
            return false;
        }
        logGaveUp("the downstream took no byte for " + pausedForMs + "ms");
        return true;
    }

    private boolean isUnpausedBudgetExhausted(DrainBookkeeping drain) {
        if (drain.unpausedMillis() < timings.maxBudgetMs()) {
            return false;
        }
        logGaveUp(timings.maxBudgetMs() + "ms of draining with the downstream able to take bytes");
        return true;
    }

    // Without a declared length, a body handed over in full is indistinguishable from one cut
    // short, so that case is logged at debug: chunked responses whose backend closes reach this
    // ordinarily, and a warning apiece would flood production.
    private void logGaveUp(String gaveUpAfter) {
        if (isShortOfDeclaredLength()) {
            LOGGER.warn(
                "Ending upstream response for request {} {} after {} - {} of the {} declared bytes reached the downstream, so the response body is truncated",
                clientRequest.getMethod(),
                clientRequest.absoluteURI(),
                gaveUpAfter,
                deliveredByteCount,
                declaredContentLength
            );
        } else {
            LOGGER.debug(
                "Ending upstream response for request {} {} after {} - {} bytes reached the downstream, and the upstream declared no length to check that against",
                clientRequest.getMethod(),
                clientRequest.absoluteURI(),
                gaveUpAfter,
                deliveredByteCount
            );
        }
    }

    private boolean transitionTo(ExchangeState target) {
        if (target.ordinal() != exchangeState.ordinal() + 1) {
            return false;
        }
        exchangeState = target;
        return true;
    }

    private boolean hasKnownFraming() {
        return chunked || declaredContentLength >= 0;
    }

    private boolean isFullyDelivered() {
        return declaredContentLength >= 0 && deliveredByteCount >= declaredContentLength;
    }

    private boolean isShortOfDeclaredLength() {
        return declaredContentLength >= 0 && deliveredByteCount < declaredContentLength;
    }

    private static boolean isChunkedTransferEncoding(HttpClientResponse clientResponse) {
        return clientResponse
            .headers()
            .getAll(HttpHeaderNames.TRANSFER_ENCODING)
            .stream()
            .flatMap(fieldValue -> Arrays.stream(fieldValue.split(",")))
            .map(String::trim)
            .anyMatch(HttpHeadersValues.TRANSFER_ENCODING_CHUNKED::equalsIgnoreCase);
    }

    private static long parseDeclaredContentLength(HttpClientResponse clientResponse) {
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

    private enum ExchangeState {
        ACTIVE,
        UPSTREAM_ENDED,
        DOWNSTREAM_SIGNALLED,
    }

    private static final class DrainBookkeeping {

        private long deliveredByteCountAtLastCheck;
        private long lastDeliveryAtNanos;
        private long lastCheckAtNanos;
        private long unpausedNanos;

        DrainBookkeeping(long deliveredByteCount, long startedAtNanos) {
            this.deliveredByteCountAtLastCheck = deliveredByteCount;
            this.lastDeliveryAtNanos = startedAtNanos;
            this.lastCheckAtNanos = startedAtNanos;
        }

        void recordSample(long deliveredByteCount, long now, boolean downstreamPaused) {
            if (deliveredByteCount > deliveredByteCountAtLastCheck) {
                deliveredByteCountAtLastCheck = deliveredByteCount;
                lastDeliveryAtNanos = now;
            }
            if (!downstreamPaused) {
                unpausedNanos += now - lastCheckAtNanos;
            }
            lastCheckAtNanos = now;
        }

        long millisSinceLastDelivery(long now) {
            return TimeUnit.NANOSECONDS.toMillis(now - lastDeliveryAtNanos);
        }

        long unpausedMillis() {
            return TimeUnit.NANOSECONDS.toMillis(unpausedNanos);
        }
    }
}
