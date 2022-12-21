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
package io.gravitee.connector.http.stub;

import io.vertx.codegen.annotations.Nullable;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.GoAway;
import io.vertx.core.http.Http2Settings;
import io.vertx.core.http.HttpConnection;
import io.vertx.core.net.SocketAddress;
import java.security.cert.Certificate;
import java.util.List;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import javax.security.cert.X509Certificate;

/**
 * @author Yann TAVERNIER (yann.tavernier at graviteesource.com)
 * @author GraviteeSource Team
 */
public class ThrowingOnGoAwayHttpConnection implements HttpConnection {

    private Handler<Throwable> handler;

    @Override
    public HttpConnection goAway(long errorCode, int lastStreamId, Buffer debugData) {
        handler.handle(new RuntimeException(debugData.toString()));
        return this;
    }

    @Override
    public HttpConnection goAwayHandler(@Nullable Handler<GoAway> handler) {
        return null;
    }

    @Override
    public HttpConnection shutdownHandler(@Nullable Handler<Void> handler) {
        return null;
    }

    @Override
    public void shutdown(long timeout, Handler<AsyncResult<Void>> handler) {
        System.out.println("");
    }

    @Override
    public Future<Void> shutdown(long timeoutMs) {
        return null;
    }

    @Override
    public HttpConnection closeHandler(Handler<Void> handler) {
        return null;
    }

    @Override
    public Future<Void> close() {
        return null;
    }

    @Override
    public Http2Settings settings() {
        return null;
    }

    @Override
    public Future<Void> updateSettings(Http2Settings settings) {
        return null;
    }

    @Override
    public HttpConnection updateSettings(Http2Settings settings, Handler<AsyncResult<Void>> completionHandler) {
        return null;
    }

    @Override
    public Http2Settings remoteSettings() {
        return null;
    }

    @Override
    public HttpConnection remoteSettingsHandler(Handler<Http2Settings> handler) {
        return null;
    }

    @Override
    public HttpConnection ping(Buffer data, Handler<AsyncResult<Buffer>> pongHandler) {
        return null;
    }

    @Override
    public Future<Buffer> ping(Buffer data) {
        return null;
    }

    @Override
    public HttpConnection pingHandler(@Nullable Handler<Buffer> handler) {
        return null;
    }

    @Override
    public HttpConnection exceptionHandler(Handler<Throwable> handler) {
        this.handler = handler;
        return this;
    }

    @Override
    public SocketAddress remoteAddress() {
        return null;
    }

    @Override
    public SocketAddress localAddress() {
        return null;
    }

    @Override
    public boolean isSsl() {
        return false;
    }

    @Override
    public SSLSession sslSession() {
        return null;
    }

    @Override
    public X509Certificate[] peerCertificateChain() throws SSLPeerUnverifiedException {
        return new X509Certificate[0];
    }

    @Override
    public List<Certificate> peerCertificates() throws SSLPeerUnverifiedException {
        return null;
    }

    @Override
    public String indicatedServerName() {
        return null;
    }
}
