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

package org.xipki.ca.server;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.GeneralName;
import org.xipki.ca.api.mgmt.entry.SignerEntry;
import org.xipki.security.ConcurrentContentSigner;
import org.xipki.security.SecurityFactory;
import org.xipki.security.SignerConf;
import org.xipki.security.X509Cert;
import org.xipki.util.exception.ObjectCreationException;

import static org.xipki.util.Args.notNull;

/**
 * Wrapper of signer database entry.
 *
 * @author Lijun Liao
 * @since 2.0.0
 */

public class SignerEntryWrapper {

  private SignerEntry dbEntry;

  private ConcurrentContentSigner signer;

  private X500Name subject;

  private GeneralName subjectAsGeneralName;

  public SignerEntryWrapper() {
  }

  public void setDbEntry(SignerEntry dbEntry) {
    this.dbEntry = notNull(dbEntry, "dbEntry");
    signer = null;
    if (dbEntry.getCertificate() != null) {
      subject = dbEntry.getCertificate().getSubject();
      subjectAsGeneralName = new GeneralName(subject);
    }
  }

  public ConcurrentContentSigner getSigner() {
    return signer;
  }

  public void initSigner(SecurityFactory securityFactory) throws ObjectCreationException {
    notNull(securityFactory, "securityFactory");
    if (signer != null) {
      return;
    }

    if (dbEntry == null) {
      throw new ObjectCreationException("dbEntry is null");
    }

    X509Cert responderCert = dbEntry.getCertificate();
    dbEntry.setConfFaulty(true);
    signer = securityFactory.createSigner(dbEntry.getType(), new SignerConf(dbEntry.getConf()), responderCert);
    if (signer.getCertificate() == null) {
      throw new ObjectCreationException("signer without certificate is not allowed");
    }
    dbEntry.setConfFaulty(false);

    if (dbEntry.getBase64Cert() == null) {
      dbEntry.setCertificate(signer.getCertificate());
      subject = signer.getCertificate().getSubject();
      subjectAsGeneralName = new GeneralName(subject);
    }
  } // method initSigner

  public SignerEntry getDbEntry() {
    return dbEntry;
  }

  public boolean isHealthy() {
    return signer != null && signer.isHealthy();
  }

  public GeneralName getSubjectAsGeneralName() {
    return subjectAsGeneralName;
  }

  public X500Name getSubject() {
    return subject;
  }

}
