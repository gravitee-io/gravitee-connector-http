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
package io.gravitee.connector.http.endpoint;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.gravitee.common.http.HttpHeader;
import io.gravitee.connector.api.AbstractEndpoint;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author GraviteeSource Team
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class HttpEndpoint extends AbstractEndpoint {

    private final Logger LOGGER = LoggerFactory.getLogger(HttpEndpoint.class);

    @JsonProperty("proxy")
    private HttpProxy httpProxy;

    @JsonProperty("http")
    private HttpClientOptions httpClientOptions = new HttpClientOptions();

    @JsonProperty("ssl")
    private HttpClientSslOptions httpClientSslOptions;

    @JsonProperty("headers")
    private List<HttpHeader> headers;

    @JsonCreator
    public HttpEndpoint(
        @JsonProperty(value = "type") String type,
        @JsonProperty(value = "name", required = true) String name,
        @JsonProperty(value = "target", required = true) String target
    ) {
        super(type != null ? type : "http", name, target);
    }

    public HttpClientOptions getHttpClientOptions() {
        return httpClientOptions;
    }

    public List<HttpHeader> getHeaders() {
        return headers;
    }

    public HttpProxy getHttpProxy() {
        return httpProxy;
    }

    public HttpClientSslOptions getHttpClientSslOptions() {
        return httpClientSslOptions;
    }
}
