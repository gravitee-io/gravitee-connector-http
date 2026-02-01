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
package io.gravitee.connector.http.stub;

import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.ClientForm;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpConnection;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.http.StreamPriority;
import io.vertx.core.net.HostAndPort;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Dummy implementation of {@link HttpClientRequest} for testing purpose
 * @author Yann TAVERNIER (yann.tavernier at graviteesource.com)
 * @author GraviteeSource Team
 */
public class DummyHttpClientRequest implements HttpClientRequest {

    private final MultiMap headers;
    private final HttpConnection connection;

    public DummyHttpClientRequest() {
        this.headers = HttpHeaders.headers();
        this.connection = new ThrowingOnGoAwayHttpConnection();
    }

    public DummyHttpClientRequest(RequestOptions options) {
        this.headers = HttpHeaders.headers().addAll(options.getHeaders());
        this.connection = new ThrowingOnGoAwayHttpConnection();
    }

    @Override
    public HttpClientRequest exceptionHandler(Handler<Throwable> handler) {
        return this;
    }

    @Override
    public Future<Void> write(Buffer data) {
        return Future.succeededFuture();
    }

    @Override
    public HttpClientRequest setWriteQueueMaxSize(int maxSize) {
        return null;
    }

    @Override
    public boolean writeQueueFull() {
        return false;
    }

    @Override
    public HttpClientRequest drainHandler(Handler<Void> handler) {
        return this;
    }

    @Override
    public HttpClientRequest authority(HostAndPort hostAndPort) {
        return this;
    }

    @Override
    public HttpClientRequest setFollowRedirects(boolean followRedirects) {
        return this;
    }

    @Override
    public boolean isFollowRedirects() {
        return false;
    }

    @Override
    public HttpClientRequest setMaxRedirects(int maxRedirects) {
        return this;
    }

    @Override
    public int getMaxRedirects() {
        return 0;
    }

    @Override
    public int numberOfRedirections() {
        return 0;
    }

    @Override
    public HttpClientRequest setChunked(boolean chunked) {
        return this;
    }

    @Override
    public boolean isChunked() {
        return false;
    }

    @Override
    public io.vertx.core.http.HttpMethod getMethod() {
        return null;
    }

    @Override
    public HttpClientRequest setMethod(io.vertx.core.http.HttpMethod method) {
        return this;
    }

    @Override
    public String absoluteURI() {
        return null;
    }

    @Override
    public String getURI() {
        return null;
    }

    @Override
    public HttpClientRequest setURI(String uri) {
        return this;
    }

    @Override
    public String path() {
        return null;
    }

    @Override
    public String query() {
        return null;
    }

    @Override
    public MultiMap headers() {
        return headers;
    }

    @Override
    public HttpClientRequest putHeader(String name, String value) {
        this.headers.set(name, value);
        return this;
    }

    @Override
    public HttpClientRequest putHeader(CharSequence name, CharSequence value) {
        this.headers.set(name, value);
        return this;
    }

    @Override
    public HttpClientRequest putHeader(String name, Iterable<String> values) {
        this.headers.set(name, values);
        return this;
    }

    @Override
    public HttpClientRequest putHeader(CharSequence name, Iterable<CharSequence> values) {
        this.headers.set(name, values);
        return this;
    }

    @Override
    public HttpClientRequest traceOperation(String s) {
        return this;
    }

    @Override
    public String traceOperation() {
        return null;
    }

    @Override
    public HttpVersion version() {
        return null;
    }

    @Override
    public Future<Void> write(String chunk) {
        return Future.succeededFuture();
    }

    @Override
    public Future<Void> write(String chunk, String enc) {
        return Future.succeededFuture();
    }

    @Override
    public HttpClientRequest continueHandler(Handler<Void> handler) {
        return this;
    }

    @Override
    public HttpClientRequest earlyHintsHandler(@Nullable Handler<MultiMap> handler) {
        return this;
    }

    @Override
    public HttpClientRequest redirectHandler(@Nullable Function<HttpClientResponse, Future<HttpClientRequest>> function) {
        return this;
    }

    @Override
    public Future<Void> sendHead() {
        return Future.succeededFuture();
    }

    @Override
    public Future<HttpClientResponse> connect() {
        return Future.succeededFuture();
    }

    @Override
    public Future<HttpClientResponse> response() {
        // Return a pending future that never completes - tests don't need response handling
        return Promise.<HttpClientResponse>promise().future();
    }

    @Override
    public Future<HttpClientResponse> send(ClientForm clientForm) {
        return Future.succeededFuture();
    }

    @Override
    public Future<Void> end(String chunk) {
        return Future.succeededFuture();
    }

    @Override
    public Future<Void> end(String chunk, String enc) {
        return Future.succeededFuture();
    }

    @Override
    public Future<Void> end(Buffer chunk) {
        return Future.succeededFuture();
    }

    @Override
    public Future<Void> end() {
        return Future.succeededFuture();
    }

    @Override
    public HttpClientRequest idleTimeout(long l) {
        return this;
    }

    @Override
    public HttpClientRequest pushHandler(Handler<HttpClientRequest> handler) {
        return this;
    }

    @Override
    public Future<Void> reset(long code) {
        return Future.succeededFuture();
    }

    @Override
    public Future<Void> reset(long code, Throwable cause) {
        return Future.succeededFuture();
    }

    @Override
    public io.vertx.core.http.HttpConnection connection() {
        return connection;
    }

    @Override
    public Future<Void> writeCustomFrame(int type, int flags, Buffer payload) {
        return Future.succeededFuture();
    }

    @Override
    public StreamPriority getStreamPriority() {
        return null;
    }
}
