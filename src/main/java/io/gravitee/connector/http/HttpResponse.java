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

import io.gravitee.connector.api.Response;
import io.gravitee.connector.api.response.AbstractResponse;
import io.gravitee.connector.http.vertx.VertxHttpHeaders;
import io.gravitee.gateway.api.buffer.Buffer;
import io.gravitee.gateway.api.handler.Handler;
import io.gravitee.gateway.api.http.HttpHeaders;
import io.gravitee.gateway.api.http2.HttpFrame;
import io.gravitee.gateway.api.stream.ReadStream;
import io.vertx.core.http.HttpClientResponse;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author GraviteeSource Team
 */
public class HttpResponse extends AbstractResponse {

    private Handler<HttpFrame> frameHandler;
    private Handler<Void> handlersAttachedHandler;
    private boolean paused;

    private final HttpHeaders httpHeaders;
    private final HttpClientResponse httpClientResponse;

    public HttpResponse(final HttpClientResponse httpClientResponse) {
        this.httpClientResponse = httpClientResponse;
        this.httpHeaders = new VertxHttpHeaders(this.httpClientResponse.headers());
    }

    public void handlersAttachedHandler(Handler<Void> handlersAttachedHandler) {
        this.handlersAttachedHandler = handlersAttachedHandler;
    }

    @Override
    public Response bodyHandler(Handler<Buffer> bodyHandler) {
        super.bodyHandler(bodyHandler);
        notifyHandlersAttached(bodyHandler);
        return this;
    }

    @Override
    public Response endHandler(Handler<Void> endHandler) {
        super.endHandler(endHandler);
        notifyHandlersAttached(endHandler);
        return this;
    }

    // The connector defers the body flush and the end signal until this fires, so an upstream
    // response that completes before the downstream attaches is handed over instead of dropped.
    private void notifyHandlersAttached(Handler<?> attachedHandler) {
        if (attachedHandler != null && handlersAttachedHandler != null) {
            handlersAttachedHandler.handle(null);
        }
    }

    @Override
    public int status() {
        return httpClientResponse.statusCode();
    }

    @Override
    public String reason() {
        return httpClientResponse.statusMessage();
    }

    @Override
    public HttpHeaders headers() {
        return httpHeaders;
    }

    @Override
    public ReadStream<Buffer> pause() {
        paused = true;
        httpClientResponse.pause();
        return this;
    }

    @Override
    public ReadStream<Buffer> resume() {
        paused = false;
        httpClientResponse.resume();
        return this;
    }

    // The connector's close-recovery drain reads this to tell a downstream that has stopped asking
    // for bytes apart from an exchange that has genuinely stalled, so a pause it will come back
    // from does not cost the drain its budget.
    public boolean isPaused() {
        return paused;
    }

    @Override
    public Response customFrameHandler(Handler<HttpFrame> frameHandler) {
        this.frameHandler = frameHandler;
        return this;
    }

    public void writeCustomFrame(HttpFrame frame) {
        if (frameHandler != null) {
            frameHandler.handle(frame);
        }
    }

    @Override
    public HttpHeaders trailers() {
        return new VertxHttpHeaders(this.httpClientResponse.trailers());
    }
}
