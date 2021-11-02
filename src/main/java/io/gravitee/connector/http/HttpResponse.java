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

import io.gravitee.common.http.HttpHeaders;
import io.gravitee.connector.api.Response;
import io.gravitee.connector.api.response.AbstractResponse;
import io.gravitee.gateway.api.buffer.Buffer;
import io.gravitee.gateway.api.handler.Handler;
import io.gravitee.gateway.api.http2.HttpFrame;
import io.gravitee.gateway.api.stream.ReadStream;
import io.vertx.core.http.HttpClientResponse;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author GraviteeSource Team
 */
public class HttpResponse extends AbstractResponse {

    private Handler<HttpFrame> frameHandler;

    private final HttpHeaders httpHeaders = new HttpHeaders();
    private HttpHeaders trailers;
    private final HttpClientResponse httpClientResponse;

    public HttpResponse(final HttpClientResponse httpClientResponse) {
        this.httpClientResponse = httpClientResponse;
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
        httpClientResponse.pause();
        return this;
    }

    @Override
    public ReadStream<Buffer> resume() {
        httpClientResponse.resume();
        return this;
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
        if (trailers == null) {
            trailers = new HttpHeaders();
        }

        return trailers;
    }
}
