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
import io.gravitee.connector.http.endpoint.HttpEndpoint;
import io.gravitee.gateway.api.handler.Handler;
import io.vertx.core.http.HttpClient;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author GraviteeSource Team
 */
public abstract class AbstractHttpConnection<E extends HttpEndpoint> extends io.gravitee.connector.api.AbstractConnection {

    protected final E endpoint;

    public AbstractHttpConnection(E endpoint) {
        this.endpoint = endpoint;
    }

    public abstract void connect(
        HttpClient httpClient,
        int port,
        String host,
        String uri,
        Handler<Void> connectionHandler,
        Handler<Void> tracker
    );

    protected void sendToClient(Response response) {
        if (this.responseHandler != null) {
            this.responseHandler.handle(response);
        }
    }
}
