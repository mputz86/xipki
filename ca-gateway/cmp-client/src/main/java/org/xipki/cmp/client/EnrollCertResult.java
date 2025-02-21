/*
 *
 * Copyright (c) 2013 - 2020 Lijun Liao
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.xipki.cmp.client;

import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.xipki.cmp.PkiStatusInfo;
import org.xipki.security.X509Cert;
import org.xipki.util.Args;

import java.util.Map;
import java.util.Set;

/**
 * Certificate enrollment result.
 *
 * @author Lijun Liao
 * @since 2.0.0
 */

public class EnrollCertResult {

  public static class CertifiedKeyPairOrError {

    private final X509Cert certificate;

    private final PrivateKeyInfo privateKeyInfo;

    private final PkiStatusInfo error;

    public CertifiedKeyPairOrError(X509Cert certificate, PrivateKeyInfo privateKeyInfo) {
      this.certificate = Args.notNull(certificate, "certificate");
      this.privateKeyInfo = privateKeyInfo;
      this.error = null;
    }

    public CertifiedKeyPairOrError(PkiStatusInfo error) {
      this.certificate = null;
      this.privateKeyInfo = null;
      this.error = Args.notNull(error, "error");
    }

    public X509Cert getCertificate() {
      return certificate;
    }

    public PrivateKeyInfo getPrivateKeyInfo() {
      return privateKeyInfo;
    }

    public PkiStatusInfo getError() {
      return error;
    }

  } // class CertifiedKeyPairOrError

  private final X509Cert[] caCertChain;

  private final Map<String, CertifiedKeyPairOrError> certsOrErrors;

  public EnrollCertResult(X509Cert[] caCertChain, Map<String, CertifiedKeyPairOrError> certsOrErrors) {
    this.certsOrErrors = Args.notEmpty(certsOrErrors, "certsOrErrors");
    this.caCertChain = caCertChain;
  }

  public X509Cert[] getCaCertChain() {
    return caCertChain;
  }

  public CertifiedKeyPairOrError getCertOrError(String id) {
    return certsOrErrors.get(Args.notBlank(id, "id"));
  }

  public Set<String> getAllIds() {
    return certsOrErrors.keySet();
  }

}
