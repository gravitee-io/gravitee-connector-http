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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.gravitee.connector.http.endpoint.HttpEndpoint;
import io.gravitee.node.api.configuration.Configuration;
import io.netty.handler.ssl.OpenSsl;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.WebSocketClientOptions;
import io.vertx.core.net.ClientOptionsBase;
import io.vertx.core.net.PemKeyCertOptions;
import io.vertx.core.net.PemTrustOptions;
import java.security.cert.CertificateException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.X509ExtendedTrustManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * With {@code trustAll}, the connector must accept any certificate through an {@link X509ExtendedTrustManager}, and not
 * verify the hostname: Netty 4.2.17+ refuses the Vert.x trust-all trust manager with OpenSSL on Java 25.
 */
class AbstractHttpConnectorTrustAllTest {

    private static Vertx vertx;
    private static HttpServer server;

    @BeforeAll
    static void startServer() throws Exception {
        vertx = Vertx.vertx();
        server = vertx
            .createHttpServer(
                new HttpServerOptions()
                    .setSsl(true)
                    .setKeyCertOptions(
                        new PemKeyCertOptions().setCertPath(sslFile("server-localhost.crt")).setKeyPath(sslFile("server-localhost.key"))
                    )
            )
            .requestHandler(request -> request.response().end("ok"))
            .listen(0)
            .await(10, TimeUnit.SECONDS);
    }

    @AfterAll
    static void stopServer() throws Exception {
        vertx.close().await(10, TimeUnit.SECONDS);
    }

    @Test
    void trust_all_uses_an_extended_trust_manager_without_hostname_verification() throws Exception {
        // Given / When
        HttpClientOptions options = connector("https://localhost:8443/", true, false).createHttpClientOptions();

        // Then
        assertThat(options.isVerifyHost()).isFalse();
        assertTrustsAllWithExtendedTrustManager(options);
    }

    @Test
    void hostname_is_verified_without_trust_all() throws Exception {
        // Given / When
        HttpClientOptions options = connector("https://localhost:8443/", false, false).createHttpClientOptions();

        // Then
        assertThat(options.isVerifyHost()).isTrue();
        assertThat(options.getTrustOptions()).isNull();
    }

    @Test
    void websocket_trust_all_uses_an_extended_trust_manager_without_hostname_verification() throws Exception {
        // Given / When
        WebSocketClientOptions options = connector("wss://localhost:8443/", true, false).createWebSocketOptions();

        // Then
        assertThat(options.isVerifyHost()).isFalse();
        assertTrustsAllWithExtendedTrustManager(options);
    }

    @Test
    void websocket_hostname_is_verified_without_trust_all() throws Exception {
        // Given / When
        WebSocketClientOptions options = connector("wss://localhost:8443/", false, false).createWebSocketOptions();

        // Then
        assertThat(options.isVerifyHost()).isTrue();
        assertThat(options.getTrustOptions()).isNull();
    }

    @ParameterizedTest(name = "openssl={0}")
    @ValueSource(booleans = { false, true })
    void trust_all_connects_to_a_host_absent_from_the_certificate(boolean openssl) throws Exception {
        // Given: the certificate is issued for "localhost" only
        HttpClientOptions options = connector("https://127.0.0.1:" + server.actualPort() + "/", true, openssl).createHttpClientOptions();

        // When
        String body = get(options, "127.0.0.1");

        // Then
        assertThat(body).isEqualTo("ok");
    }

    @ParameterizedTest(name = "openssl={0}")
    @ValueSource(booleans = { false, true })
    void hostname_verification_still_rejects_a_host_absent_from_the_certificate(boolean openssl) throws Exception {
        // Given: the server certificate is trusted, so only the hostname check can fail
        HttpClientOptions options = connector("https://127.0.0.1:" + server.actualPort() + "/", false, openssl).createHttpClientOptions();
        options.setTrustOptions(new PemTrustOptions().addCertPath(sslFile("server-localhost.crt")));

        // When / Then
        assertThatThrownBy(() -> get(options, "127.0.0.1"))
            .rootCause()
            .isInstanceOf(CertificateException.class)
            .hasMessageContaining("No subject alternative names matching IP address 127.0.0.1");
    }

    /**
     * Not the Vert.x trustAll flag: its trust manager is not an X509ExtendedTrustManager, which Netty 4.2.17+ refuses
     * with OpenSSL.
     */
    private static void assertTrustsAllWithExtendedTrustManager(ClientOptionsBase options) throws Exception {
        assertThat(options.isTrustAll()).isFalse();
        assertThat(options.getTrustOptions().getTrustManagerFactory(vertx).getTrustManagers())
            .singleElement()
            .isInstanceOf(X509ExtendedTrustManager.class);
    }

    private static String get(HttpClientOptions options, String host) throws Exception {
        HttpClient client = vertx.createHttpClient(options);
        try {
            return client
                .request(HttpMethod.GET, server.actualPort(), host, "/")
                .compose(request -> request.send().compose(HttpClientResponse::body))
                .await(10, TimeUnit.SECONDS)
                .toString();
        } finally {
            client.close();
        }
    }

    private static TestHttpConnector connector(String target, boolean trustAll, boolean openssl) throws Exception {
        assumeTrue(!openssl || OpenSsl.isAvailable(), "OpenSSL is not available");
        String json =
            "{\"name\":\"default\",\"target\":\"" + target + "\",\"ssl\":{\"trustAll\":" + trustAll + ",\"hostnameVerifier\":true}}";
        HttpEndpoint endpoint = new ObjectMapper().readValue(json, HttpEndpoint.class);
        return new TestHttpConnector(
            endpoint,
            // "http.ssl.openssl" follows the test parameter, every other property takes its default value.
            mock(Configuration.class, invocation -> {
                if ("http.ssl.openssl".equals(invocation.getArgument(0))) {
                    return openssl;
                }
                return invocation.getArguments().length == 3 ? invocation.getArgument(2) : null;
            })
        );
    }

    private static String sslFile(String file) {
        return Objects.requireNonNull(AbstractHttpConnectorTrustAllTest.class.getResource("/ssl/" + file)).getPath();
    }
}
