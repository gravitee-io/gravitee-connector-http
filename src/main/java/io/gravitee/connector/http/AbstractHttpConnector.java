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

import io.gravitee.common.util.MultiValueMap;
import io.gravitee.connector.api.AbstractConnector;
import io.gravitee.connector.api.Connection;
import io.gravitee.connector.api.EndpointException;
import io.gravitee.connector.http.endpoint.HttpClientSslOptions;
import io.gravitee.connector.http.endpoint.HttpEndpoint;
import io.gravitee.connector.http.endpoint.HttpProxy;
import io.gravitee.connector.http.endpoint.ProtocolVersion;
import io.gravitee.connector.http.endpoint.jks.JKSKeyStore;
import io.gravitee.connector.http.endpoint.jks.JKSTrustStore;
import io.gravitee.connector.http.endpoint.pem.PEMKeyStore;
import io.gravitee.connector.http.endpoint.pem.PEMTrustStore;
import io.gravitee.connector.http.endpoint.pkcs12.PKCS12KeyStore;
import io.gravitee.connector.http.endpoint.pkcs12.PKCS12TrustStore;
import io.gravitee.connector.http.ws.WebSocketConnection;
import io.gravitee.gateway.api.ExecutionContext;
import io.gravitee.gateway.api.handler.Handler;
import io.gravitee.gateway.api.http.HttpHeaderNames;
import io.gravitee.gateway.api.proxy.ProxyRequest;
import io.gravitee.node.api.configuration.Configuration;
import io.gravitee.node.vertx.proxy.VertxProxyOptionsUtils;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.http.PoolOptions;
import io.vertx.core.http.WebSocketClient;
import io.vertx.core.http.WebSocketClientOptions;
import io.vertx.core.net.ClientOptionsBase;
import io.vertx.core.net.JksOptions;
import io.vertx.core.net.OpenSSLEngineOptions;
import io.vertx.core.net.PemKeyCertOptions;
import io.vertx.core.net.PemTrustOptions;
import io.vertx.core.net.PfxOptions;
import io.vertx.core.net.ProxyOptions;
import io.vertx.core.net.ProxyType;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.util.Base64;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author GraviteeSource Team
 */
public abstract class AbstractHttpConnector<E extends HttpEndpoint> extends AbstractConnector<Connection, ProxyRequest> {

    private final Logger LOGGER = LoggerFactory.getLogger(AbstractConnector.class);

    private static final String URI_PARAM_SEPARATOR = "&";
    private static final char URI_PARAM_SEPARATOR_CHAR = '&';
    private static final char URI_PARAM_VALUE_SEPARATOR_CHAR = '=';
    private static final char URI_QUERY_DELIMITER_CHAR = '?';
    private static final CharSequence URI_QUERY_DELIMITER_CHAR_SEQUENCE = "?";

    protected static final int UNSECURE_PORT = 80;
    protected static final int SECURE_PORT = 443;

    protected final E endpoint;
    private final Configuration configuration;

    private HttpClientOptions httpClientOptions;
    private WebSocketClientOptions webSocketOptions;
    private PoolOptions poolOptions;

    /**
     * Dummy {@link URLStreamHandler} implementation to avoid unknown protocol issue with default implementation
     * (which knows how to handle only http and https protocol).
     */
    private final URLStreamHandler URL_HANDLER = new URLStreamHandler() {
        @Override
        protected URLConnection openConnection(URL u) {
            return null;
        }
    };

    public AbstractHttpConnector(E endpoint, Configuration configuration) {
        this.endpoint = endpoint;
        this.configuration = configuration;
    }

    protected final Map<Thread, HttpClient> httpClients = new ConcurrentHashMap<>();

    protected final Map<Thread, WebSocketClient> webSocketClients = new ConcurrentHashMap<>();

    private final AtomicInteger requestTracker = new AtomicInteger(0);

    @Override
    public void request(ExecutionContext context, ProxyRequest request, Handler<Connection> connectionHandler) {
        // For Vertx HTTP client query parameters have to be passed along the URI
        final String uri = appendQueryParameters(request.uri(), request.parameters());

        // Add the endpoint reference in metrics to know which endpoint has been invoked while serving the request
        request.metrics().setEndpoint(uri);

        try {
            final URL url = new URL(null, uri, URL_HANDLER);

            final int defaultPort = isSecureProtocol(url.getProtocol()) ? SECURE_PORT : UNSECURE_PORT;
            final int port = url.getPort() != -1 ? url.getPort() : defaultPort;

            final String host = (port == UNSECURE_PORT || port == SECURE_PORT) ? url.getHost() : url.getHost() + ':' + port;

            request.headers().set(HttpHeaderNames.HOST, host);

            // Enhance proxy request with endpoint configuration
            if (endpoint.getHeaders() != null && !endpoint.getHeaders().isEmpty()) {
                endpoint
                    .getHeaders()
                    .forEach(header -> {
                        request.headers().set(header.getName(), header.getValue());
                    });
            }

            // Create the connector to the upstream
            final AbstractHttpConnection<HttpEndpoint> connection = create(request);

            if (connection instanceof WebSocketConnection) {
                final WebSocketClient webSocketClient = webSocketClients.computeIfAbsent(Thread.currentThread(), createWebSocketClient());
                requestTracker.incrementAndGet();

                // Connect to the upstream
                connection.connect(
                    context,
                    webSocketClient,
                    port,
                    url.getHost(),
                    (url.getQuery() == null) ? url.getPath() : url.getPath() + URI_QUERY_DELIMITER_CHAR + url.getQuery(),
                    connect -> connectionHandler.handle(connection),
                    result -> requestTracker.decrementAndGet()
                );
            } else {
                // Grab an instance of the HTTP client
                final HttpClient client = httpClients.computeIfAbsent(Thread.currentThread(), createHttpClient());
                requestTracker.incrementAndGet();

                // Connect to the upstream
                connection.connect(
                    context,
                    client,
                    port,
                    url.getHost(),
                    (url.getQuery() == null) ? url.getPath() : url.getPath() + URI_QUERY_DELIMITER_CHAR + url.getQuery(),
                    connect -> connectionHandler.handle(connection),
                    result -> requestTracker.decrementAndGet()
                );
            }
        } catch (MalformedURLException ex) {
            throw new IllegalArgumentException();
        }
    }

    protected abstract AbstractHttpConnection<HttpEndpoint> create(ProxyRequest request);

    @Override
    protected void doStart() throws Exception {
        this.httpClientOptions = this.createHttpClientOptions();
        this.poolOptions = this.createPoolOptions();
        this.webSocketOptions = this.createWebSocketOptions();
        printHttpClientConfiguration();
    }

    /**
     * Creates PoolOptions for Vert.x 5 HTTP client.
     * In Vert.x 5, pool configuration moved from HttpClientOptions to PoolOptions.
     */
    protected PoolOptions createPoolOptions() {
        PoolOptions poolOptions = new PoolOptions();
        int maxConnections = endpoint.getHttpClientOptions().getMaxConcurrentConnections();

        // Set pool sizes based on protocol version
        if (endpoint.getHttpClientOptions().getVersion() == ProtocolVersion.HTTP_2) {
            poolOptions.setHttp2MaxSize(maxConnections);
        } else {
            poolOptions.setHttp1MaxSize(maxConnections);
        }

        return poolOptions;
    }

    private String appendQueryParameters(String uri, MultiValueMap<String, String> parameters) {
        if (parameters != null && !parameters.isEmpty()) {
            StringJoiner parametersAsString = new StringJoiner(URI_PARAM_SEPARATOR);
            parameters.forEach((paramName, paramValues) -> {
                if (paramValues != null) {
                    for (String paramValue : paramValues) {
                        if (paramValue == null) {
                            parametersAsString.add(paramName);
                        } else {
                            parametersAsString.add(paramName + URI_PARAM_VALUE_SEPARATOR_CHAR + paramValue);
                        }
                    }
                }
            });

            if (uri.contains(URI_QUERY_DELIMITER_CHAR_SEQUENCE)) {
                return uri + URI_PARAM_SEPARATOR_CHAR + parametersAsString.toString();
            } else {
                return uri + URI_QUERY_DELIMITER_CHAR + parametersAsString.toString();
            }
        } else {
            return uri;
        }
    }

    protected HttpClientOptions createHttpClientOptions() throws EndpointException {
        HttpClientOptions options = new HttpClientOptions();
        configureCommonOptions(options);

        options.setPipelining(endpoint.getHttpClientOptions().isPipelining());
        options.setKeepAlive(endpoint.getHttpClientOptions().isKeepAlive());
        options.setKeepAliveTimeout((int) (endpoint.getHttpClientOptions().getKeepAliveTimeout() / 1000));
        // In Vert.x 5, setTryUseCompression was renamed to setDecompressionSupported
        options.setDecompressionSupported(endpoint.getHttpClientOptions().isUseCompression());
        options.setMaxHeaderSize(endpoint.getHttpClientOptions().getMaxHeaderSize());
        options.setMaxChunkSize(endpoint.getHttpClientOptions().getMaxChunkSize());
        if (endpoint.getHttpClientOptions().getVersion() == ProtocolVersion.HTTP_2) {
            options.setProtocolVersion(HttpVersion.HTTP_2);
            options.setHttp2ClearTextUpgrade(endpoint.getHttpClientOptions().isClearTextUpgrade());
        }

        // setVerifyHost is not on ClientOptionsBase, set it on the concrete type
        HttpClientSslOptions sslOptions = endpoint.getHttpClientSslOptions();
        if (options.isSsl() && sslOptions != null) {
            options.setVerifyHost(sslOptions.isHostnameVerifier());
        }

        return options;
    }

    private WebSocketClientOptions createWebSocketOptions() throws EndpointException {
        WebSocketClientOptions options = new WebSocketClientOptions();
        configureCommonOptions(options);

        int maxConnections = endpoint.getHttpClientOptions().getMaxConcurrentConnections();
        options.setMaxConnections(maxConnections);

        // setVerifyHost is not on ClientOptionsBase, set it on the concrete type
        HttpClientSslOptions sslOptions = endpoint.getHttpClientSslOptions();
        if (options.isSsl() && sslOptions != null) {
            options.setVerifyHost(sslOptions.isHostnameVerifier());
        }

        return options;
    }

    private void configureCommonOptions(ClientOptionsBase options) throws EndpointException {
        options.setIdleTimeout((int) (endpoint.getHttpClientOptions().getIdleTimeout() / 1000));
        options.setConnectTimeout((int) endpoint.getHttpClientOptions().getConnectTimeout());

        URL target;
        try {
            target = new URL(null, endpoint.target(), URL_HANDLER);
        } catch (MalformedURLException e) {
            throw new EndpointException("Endpoint target is not valid " + endpoint.target());
        }

        // Configure proxy
        HttpProxy proxy = endpoint.getHttpProxy();

        if (proxy != null && proxy.isEnabled()) {
            if (proxy.isUseSystemProxy()) {
                setSystemProxy(options);
            } else {
                ProxyOptions proxyOptions;
                proxyOptions = new ProxyOptions();
                proxyOptions.setHost(proxy.getHost());
                proxyOptions.setPort(proxy.getPort());
                proxyOptions.setUsername(proxy.getUsername());
                proxyOptions.setPassword(proxy.getPassword());
                proxyOptions.setType(ProxyType.valueOf(proxy.getType().name()));
                options.setProxyOptions(proxyOptions);
            }
        }

        HttpClientSslOptions sslOptions = endpoint.getHttpClientSslOptions();

        if (isSecureProtocol(target.getProtocol())) {
            // Configure SSL
            options.setSsl(true);
            options.setUseAlpn(true);

            if (configuration.getProperty("http.ssl.openssl", Boolean.class, false)) {
                options.setSslEngineOptions(new OpenSSLEngineOptions());
            }

            if (sslOptions != null) {
                options.setTrustAll(sslOptions.isTrustAll());

                // Client trust configuration
                if (!sslOptions.isTrustAll() && sslOptions.getTrustStore() != null) {
                    switch (sslOptions.getTrustStore().getType()) {
                        case PEM:
                            PEMTrustStore pemTrustStore = (PEMTrustStore) sslOptions.getTrustStore();
                            PemTrustOptions pemTrustOptions = new PemTrustOptions();
                            if (pemTrustStore.getPath() != null && !pemTrustStore.getPath().isEmpty()) {
                                pemTrustOptions.addCertPath(pemTrustStore.getPath());
                            } else if (pemTrustStore.getContent() != null && !pemTrustStore.getContent().isEmpty()) {
                                pemTrustOptions.addCertValue(io.vertx.core.buffer.Buffer.buffer(pemTrustStore.getContent()));
                            } else {
                                throw new EndpointException("Missing PEM certificate value for endpoint " + endpoint.name());
                            }
                            options.setTrustOptions(pemTrustOptions);
                            break;
                        case PKCS12:
                            PKCS12TrustStore pkcs12TrustStore = (PKCS12TrustStore) sslOptions.getTrustStore();
                            PfxOptions pfxOptions = new PfxOptions();
                            pfxOptions.setPassword(pkcs12TrustStore.getPassword());
                            if (pkcs12TrustStore.getPath() != null && !pkcs12TrustStore.getPath().isEmpty()) {
                                pfxOptions.setPath(pkcs12TrustStore.getPath());
                            } else if (pkcs12TrustStore.getContent() != null && !pkcs12TrustStore.getContent().isEmpty()) {
                                byte[] decode = Base64.getDecoder().decode(pkcs12TrustStore.getContent());
                                pfxOptions.setValue(io.vertx.core.buffer.Buffer.buffer(decode));
                            } else {
                                throw new EndpointException("Missing PKCS12 value for endpoint " + endpoint.name());
                            }
                            options.setTrustOptions(pfxOptions);
                            break;
                        case JKS:
                            JKSTrustStore jksTrustStore = (JKSTrustStore) sslOptions.getTrustStore();
                            JksOptions jksOptions = new JksOptions();
                            jksOptions.setPassword(jksTrustStore.getPassword());
                            if (jksTrustStore.getPath() != null && !jksTrustStore.getPath().isEmpty()) {
                                jksOptions.setPath(jksTrustStore.getPath());
                            } else if (jksTrustStore.getContent() != null && !jksTrustStore.getContent().isEmpty()) {
                                byte[] decode = Base64.getDecoder().decode(jksTrustStore.getContent());
                                jksOptions.setValue(io.vertx.core.buffer.Buffer.buffer(decode));
                            } else {
                                throw new EndpointException("Missing JKS value for endpoint " + endpoint.name());
                            }
                            options.setTrustOptions(jksOptions);
                            break;
                    }
                }

                // Client authentication configuration
                if (sslOptions.getKeyStore() != null) {
                    switch (sslOptions.getKeyStore().getType()) {
                        case PEM:
                            PEMKeyStore pemKeyStore = (PEMKeyStore) sslOptions.getKeyStore();
                            PemKeyCertOptions pemKeyCertOptions = new PemKeyCertOptions();
                            if (pemKeyStore.getCertPath() != null && !pemKeyStore.getCertPath().isEmpty()) {
                                pemKeyCertOptions.setCertPath(pemKeyStore.getCertPath());
                            } else if (pemKeyStore.getCertContent() != null && !pemKeyStore.getCertContent().isEmpty()) {
                                pemKeyCertOptions.setCertValue(io.vertx.core.buffer.Buffer.buffer(pemKeyStore.getCertContent()));
                            }
                            if (pemKeyStore.getKeyPath() != null && !pemKeyStore.getKeyPath().isEmpty()) {
                                pemKeyCertOptions.setKeyPath(pemKeyStore.getKeyPath());
                            } else if (pemKeyStore.getKeyContent() != null && !pemKeyStore.getKeyContent().isEmpty()) {
                                pemKeyCertOptions.setKeyValue(io.vertx.core.buffer.Buffer.buffer(pemKeyStore.getKeyContent()));
                            }
                            options.setKeyCertOptions(pemKeyCertOptions);
                            break;
                        case PKCS12:
                            PKCS12KeyStore pkcs12KeyStore = (PKCS12KeyStore) sslOptions.getKeyStore();
                            PfxOptions pfxOptions = new PfxOptions();
                            pfxOptions.setPassword(pkcs12KeyStore.getPassword());
                            if (pkcs12KeyStore.getPath() != null && !pkcs12KeyStore.getPath().isEmpty()) {
                                pfxOptions.setPath(pkcs12KeyStore.getPath());
                            } else if (pkcs12KeyStore.getContent() != null && !pkcs12KeyStore.getContent().isEmpty()) {
                                byte[] decode = Base64.getDecoder().decode(pkcs12KeyStore.getContent());
                                pfxOptions.setValue(io.vertx.core.buffer.Buffer.buffer(decode));
                            }
                            options.setKeyCertOptions(pfxOptions);
                            break;
                        case JKS:
                            JKSKeyStore jksKeyStore = (JKSKeyStore) sslOptions.getKeyStore();
                            JksOptions jksOptions = new JksOptions();
                            jksOptions.setPassword(jksKeyStore.getPassword());
                            if (jksKeyStore.getPath() != null && !jksKeyStore.getPath().isEmpty()) {
                                jksOptions.setPath(jksKeyStore.getPath());
                            } else if (jksKeyStore.getContent() != null && !jksKeyStore.getContent().isEmpty()) {
                                byte[] decode = Base64.getDecoder().decode(jksKeyStore.getContent());
                                jksOptions.setValue(io.vertx.core.buffer.Buffer.buffer(decode));
                            }
                            options.setKeyCertOptions(jksOptions);
                            break;
                    }
                }
            }
        }
    }

    @Override
    protected void doStop() throws Exception {
        LOGGER.debug(
            "Shutdown of HTTP Client for endpoint[{}] target[{}] requests[{}]",
            endpoint.name(),
            endpoint.target(),
            requestTracker.get()
        );

        if (requestTracker.get() > 0) {
            LOGGER.warn("Cancel requests[{}] for endpoint[{}] target[{}]", requestTracker.get(), endpoint.name(), endpoint.target());
        }

        httpClients
            .values()
            .forEach(httpClient -> {
                try {
                    httpClient.close();
                } catch (IllegalStateException ise) {
                    LOGGER.warn(ise.getMessage());
                }
            });
    }

    private Function<Thread, HttpClient> createHttpClient() {
        return thread -> Vertx.currentContext().owner().createHttpClient(httpClientOptions, poolOptions);
    }

    private Function<Thread, WebSocketClient> createWebSocketClient() {
        return thread -> Vertx.currentContext().owner().createWebSocketClient(webSocketOptions);
    }

    /**
     * Check if input protocol is secure or not based on its last character ('s' for secure).
     * Also, to be considered as secure the length of protocol's name the must be at least 2 characters to avoid considering `ws` as secure.
     * ⚠️ This method is implemented using `charAt` and `length` methods for performance reasons. Be very careful when changing this method.
     *
     * @param protocol the protocol to check
     * @return true if protocol is secure, false otherwise
     */
    protected static boolean isSecureProtocol(String protocol) {
        return protocol.charAt(protocol.length() - 1) == 's' && protocol.length() > 2;
    }

    private void printHttpClientConfiguration() {
        LOGGER.debug("Create HTTP connector with configuration: ");
        LOGGER.debug(
            "\t" +
            httpClientOptions.getProtocolVersion() +
            " {" +
            "ConnectTimeout='" +
            httpClientOptions.getConnectTimeout() +
            '\'' +
            ", KeepAlive='" +
            httpClientOptions.isKeepAlive() +
            '\'' +
            ", KeepAliveTimeout='" +
            httpClientOptions.getKeepAliveTimeout() +
            '\'' +
            ", IdleTimeout='" +
            httpClientOptions.getIdleTimeout() +
            '\'' +
            ", MaxChunkSize='" +
            httpClientOptions.getMaxChunkSize() +
            '\'' +
            ", Http1MaxPoolSize='" +
            poolOptions.getHttp1MaxSize() +
            '\'' +
            ", Http2MaxPoolSize='" +
            poolOptions.getHttp2MaxSize() +
            '\'' +
            ", MaxWaitQueueSize='" +
            poolOptions.getMaxWaitQueueSize() +
            '\'' +
            ", Pipelining='" +
            httpClientOptions.isPipelining() +
            '\'' +
            ", PipeliningLimit='" +
            httpClientOptions.getPipeliningLimit() +
            '\'' +
            ", DecompressionSupported='" +
            httpClientOptions.isDecompressionSupported() +
            '\'' +
            '}'
        );

        if (httpClientOptions.isSsl()) {
            LOGGER.debug(
                "\tSSL {" +
                "TrustAll='" +
                httpClientOptions.isTrustAll() +
                '\'' +
                ", VerifyHost='" +
                httpClientOptions.isVerifyHost() +
                '\'' +
                '}'
            );
        }

        if (httpClientOptions.getProxyOptions() != null) {
            LOGGER.debug(
                "\tProxy {" +
                "Type='" +
                httpClientOptions.getProxyOptions().getType() +
                ", Host='" +
                httpClientOptions.getProxyOptions().getHost() +
                '\'' +
                ", Port='" +
                httpClientOptions.getProxyOptions().getPort() +
                '\'' +
                '}'
            );
        }
    }

    private void setSystemProxy(ClientOptionsBase options) {
        try {
            options.setProxyOptions(VertxProxyOptionsUtils.buildProxyOptions(configuration));
        } catch (Exception e) {
            LOGGER.warn(
                "A service endpoint (name[{}] type[{}] target[{}]) requires a system proxy to be defined but some configurations are missing or not well defined: {}",
                endpoint.name(),
                endpoint.type(),
                endpoint.target(),
                e.getMessage()
            );
            LOGGER.warn("Ignoring system proxy");
        }
    }
}
