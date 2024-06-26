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

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.gravitee.connector.http.endpoint.jks.JKSKeyStore;
import io.gravitee.connector.http.endpoint.none.NoneKeyStore;
import io.gravitee.connector.http.endpoint.pem.PEMKeyStore;
import io.gravitee.connector.http.endpoint.pkcs12.PKCS12KeyStore;
import java.io.Serializable;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author GraviteeSource Team
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes(
    {
        @JsonSubTypes.Type(name = "JKS", value = JKSKeyStore.class),
        @JsonSubTypes.Type(name = "PEM", value = PEMKeyStore.class),
        @JsonSubTypes.Type(name = "PKCS12", value = PKCS12KeyStore.class),
        @JsonSubTypes.Type(value = NoneKeyStore.class),
        // legacy support
        @JsonSubTypes.Type(name = "jks", value = JKSKeyStore.class),
        @JsonSubTypes.Type(name = "pem", value = PEMKeyStore.class),
        @JsonSubTypes.Type(name = "pkcs12", value = PKCS12KeyStore.class),
    }
)
public abstract class KeyStore implements Serializable {

    @JsonProperty("type")
    private final KeyStoreType type;

    public KeyStore(KeyStoreType type) {
        this.type = type;
    }

    public KeyStoreType getType() {
        return type;
    }
}
