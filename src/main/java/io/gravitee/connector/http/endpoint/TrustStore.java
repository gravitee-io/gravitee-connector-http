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
package io.gravitee.connector.http.endpoint;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.gravitee.connector.http.endpoint.jks.JKSTrustStore;
import io.gravitee.connector.http.endpoint.pem.PEMTrustStore;
import io.gravitee.connector.http.endpoint.pkcs12.PKCS12TrustStore;
import java.io.Serializable;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author GraviteeSource Team
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "type")
@JsonSubTypes(
    {
        @JsonSubTypes.Type(name = "JKS", value = JKSTrustStore.class),
        @JsonSubTypes.Type(name = "PEM", value = PEMTrustStore.class),
        @JsonSubTypes.Type(name = "PKCS12", value = PKCS12TrustStore.class),
        // legacy support
        @JsonSubTypes.Type(name = "jks", value = JKSTrustStore.class),
        @JsonSubTypes.Type(name = "pem", value = PEMTrustStore.class),
        @JsonSubTypes.Type(name = "pkcs12", value = PKCS12TrustStore.class),
    }
)
public abstract class TrustStore implements Serializable {

    @JsonProperty("type")
    private final TrustStoreType type;

    public TrustStore(TrustStoreType type) {
        this.type = type;
    }

    public TrustStoreType getType() {
        return type;
    }
}
