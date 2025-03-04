/*
 *
 * Copyright (c) 2013 - 2022 Lijun Liao
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

package org.xipki.ca.gateway.scep;

import org.xipki.ca.gateway.CaNameSigners;
import org.xipki.security.ConcurrentContentSigner;
import org.xipki.util.Args;
import org.xipki.util.CollectionUtil;
import org.xipki.util.exception.InvalidConfException;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 *
 * @author Lijun Liao
 * @since 6.0.0
 */
public class CaNameScepSigners {

  private final ScepSigner defaultSigner;

  private Map<String, ScepSigner> signers;

  public CaNameScepSigners(CaNameSigners signers) {
    ConcurrentContentSigner signer = signers.getDefaultSigner();
    this.defaultSigner = signer == null ? null : new ScepSigner(signer);

    this.signers = new HashMap<>();
    for (String name : signers.signerNames()) {
      this.signers.put(name, new ScepSigner(signers.getSigner(name)));
    }
  }

  public CaNameScepSigners(ScepSigner defaultSigner, Map<String, ScepSigner> signers)
      throws InvalidConfException {
    if (defaultSigner == null && CollectionUtil.isEmpty(signers)) {
      throw new InvalidConfException("At least one of defaultSigner and signers must be set");
    }

    this.defaultSigner = defaultSigner;
    if (signers == null) {
      this.signers = null;
    } else {
      this.signers = new HashMap<>(signers.size() * 3 / 2);
      for (Map.Entry<String, ScepSigner> m : signers.entrySet()) {
        String name = m.getKey().toLowerCase(Locale.ROOT);
        if (this.signers.containsKey(name)) {
          throw new InvalidConfException("at least two signers for the CA " + name + " are set");
        }
        this.signers.put(m.getKey().toLowerCase(Locale.ROOT), m.getValue());
      }
    }
  }

  public ScepSigner getSigner(String caName) {
    ScepSigner signer = signers.get(Args.toNonBlankLower(caName, "caName"));
    return signer != null ? signer : defaultSigner;
  }

}
