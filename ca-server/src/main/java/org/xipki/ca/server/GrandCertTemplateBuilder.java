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

import org.bouncycastle.asn1.*;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.x500.RDN;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xipki.ca.api.BadCertTemplateException;
import org.xipki.ca.api.NameId;
import org.xipki.ca.api.OperationException;
import org.xipki.ca.api.mgmt.RequestorInfo;
import org.xipki.ca.api.mgmt.ValidityMode;
import org.xipki.ca.api.profile.Certprofile;
import org.xipki.ca.api.profile.CertprofileException;
import org.xipki.ca.api.profile.KeypairGenControl;
import org.xipki.ca.api.profile.NotAfterMode;
import org.xipki.ca.server.X509Ca.GrantedCertTemplate;
import org.xipki.ca.server.db.CertStore;
import org.xipki.security.*;
import org.xipki.security.util.RSABrokenKey;
import org.xipki.security.util.X509Util;
import org.xipki.util.LogUtil;
import org.xipki.util.Validity;

import java.io.IOException;
import java.math.BigInteger;
import java.security.spec.InvalidKeySpecException;
import java.util.Date;
import java.util.List;

import static org.xipki.ca.api.OperationException.ErrorCode.*;

/**
 * X509CA GrandCertTemplate builder.
 *
 * @author Lijun Liao
 */

class GrandCertTemplateBuilder {

  private static final Logger LOG = LoggerFactory.getLogger(GrandCertTemplateBuilder.class);

  private static final long MAX_CERT_TIME_MS = 253402300799982L; //9999-12-31-23-59-59

  private static final Date MAX_CERT_TIME = new Date(MAX_CERT_TIME_MS);

  private static final long MS_PER_10MINUTES = 300000L;

  private final ASN1ObjectIdentifier keyAlgOidByImplicitCA;
  private final String keyspecByImplictCA;

  private final CertStore certstore;

  private final CaInfo caInfo;

  GrandCertTemplateBuilder(CaInfo caInfo, CertStore certstore) {
    this.caInfo = caInfo;
    this.certstore = certstore;

    this.keyspecByImplictCA = caInfo.getCaKeyspec();
    this.keyAlgOidByImplicitCA = caInfo.getCaKeyAlgId().getAlgorithm();
  }

  GrantedCertTemplate create(
      IdentifiedCertprofile certprofile,
      CertTemplateData certTemplate,
      RequestorInfo requestor,
      List<KeypairGenerator> keypairGenerators,
      boolean update)
      throws OperationException {
    if (caInfo.getRevocationInfo() != null) {
      throw new OperationException(NOT_PERMITTED, "CA is revoked");
    }

    if (certprofile == null) {
      throw new OperationException(UNKNOWN_CERT_PROFILE,
          "unknown cert profile " + certTemplate.getCertprofileName());
    }

    ConcurrentContentSigner signer = caInfo.getSigner(certprofile.getSignatureAlgorithms());
    if (signer == null) {
      throw new OperationException(SYSTEM_FAILURE,
          "CA does not support any signature algorithm restricted by the cert profile");
    }

    final NameId certprofileIdent = certprofile.getIdent();
    if (certprofile.getVersion() != Certprofile.X509CertVersion.v3) {
      throw new OperationException(SYSTEM_FAILURE,
          "unknown cert version " + certprofile.getVersion());
    }

    if (certprofile.isOnlyForRa()) {
      if (requestor == null || !requestor.isRa()) {
        throw new OperationException(NOT_PERMITTED,
            "profile " + certprofileIdent + " not applied to non-RA");
      }
    }

    switch (certprofile.getCertLevel()) {
      case RootCA:
        throw new OperationException(NOT_PERMITTED,
            "CA is not allowed to generate Root CA certificate");
      case SubCA:
        Integer reqPathlen = certprofile.getPathLenBasicConstraint();
        int caPathLen = caInfo.getPathLenConstraint();
        boolean allowed = (reqPathlen == null && caPathLen == Integer.MAX_VALUE)
                            || (reqPathlen != null && reqPathlen < caPathLen);
        if (!allowed) {
          throw new OperationException(NOT_PERMITTED, "invalid BasicConstraint.pathLenConstraint");
        }
        break;
      default:
    }

    X500Name requestedSubject = CaUtil.removeEmptyRdns(certTemplate.getSubject());

    if (!certprofile.isSerialNumberInReqPermitted()) {
      RDN[] rdns = requestedSubject.getRDNs(ObjectIdentifiers.DN.SN);
      if (rdns != null && rdns.length > 0) {
        throw new OperationException(BAD_CERT_TEMPLATE,
            "subjectDN SerialNumber in request is not permitted");
      }
    }

    Date reqNotBefore = certTemplate.getNotBefore();

    Date grantedNotBefore = certprofile.getNotBefore(reqNotBefore);
    // notBefore in the past is not permitted (due to the fact that some clients may not have
    // accurate time, we allow max. 5 minutes in the past)
    long currentMillis = System.currentTimeMillis();
    if (currentMillis - grantedNotBefore.getTime() > MS_PER_10MINUTES) {
      grantedNotBefore = new Date(currentMillis - MS_PER_10MINUTES);
    }

    long time = caInfo.getNoNewCertificateAfter();
    if (grantedNotBefore.getTime() > time) {
      throw new OperationException(NOT_PERMITTED,
          "CA is not permitted to issue certificate after " + new Date(time));
    }

    if (grantedNotBefore.before(caInfo.getNotBefore())) {
      // notBefore may not be before CA's notBefore
      grantedNotBefore = caInfo.getNotBefore();
    }

    PrivateKeyInfo privateKey = null;
    SubjectPublicKeyInfo grantedPublicKeyInfo = certTemplate.getPublicKeyInfo();

    if (grantedPublicKeyInfo != null) {
      try {
        grantedPublicKeyInfo = X509Util.toRfc3279Style(certTemplate.getPublicKeyInfo());
      } catch (InvalidKeySpecException ex) {
        LogUtil.warn(LOG, ex, "invalid SubjectPublicKeyInfo");
        throw new OperationException(BAD_CERT_TEMPLATE, "invalid SubjectPublicKeyInfo");
      }

      // CHECK weak public key, like RSA key (ROCA)
      if (grantedPublicKeyInfo.getAlgorithm().getAlgorithm().equals(
          PKCSObjectIdentifiers.rsaEncryption)) {
        try {
          ASN1Sequence seq = ASN1Sequence.getInstance(
              grantedPublicKeyInfo.getPublicKeyData().getOctets());
          if (seq.size() != 2) {
            throw new OperationException(BAD_CERT_TEMPLATE, "invalid format of RSA public key");
          }

          BigInteger modulus = ASN1Integer.getInstance(seq.getObjectAt(0)).getPositiveValue();
          if (RSABrokenKey.isAffected(modulus)) {
            throw new OperationException(BAD_CERT_TEMPLATE, "RSA public key is too weak");
          }
        } catch (IllegalArgumentException ex) {
          throw new OperationException(BAD_CERT_TEMPLATE, "invalid format of RSA public key");
        }
      }
    } else if (certTemplate.isCaGenerateKeypair()) {
      KeypairGenControl kg = certprofile.getKeypairGenControl();

      ASN1ObjectIdentifier keyAlgOid;
      String keyspec;

      if (kg == null || kg instanceof KeypairGenControl.ForbiddenKeypairGenControl) {
        throw new OperationException(BAD_CERT_TEMPLATE, "no public key is specified");
      } else if (kg instanceof KeypairGenControl.InheritCAKeypairGenControl) {
        keyspec = keyspecByImplictCA;
        keyAlgOid = keyAlgOidByImplicitCA;
      } else {
        keyspec = kg.getKeyspec();
        keyAlgOid = kg.getKeyAlgorithmOid();
      }

      KeypairGenerator keypairGenerator = null;
      if (keypairGenerators != null) {
        for (KeypairGenerator m : keypairGenerators) {
          if (m.supports(keyspec)) {
            keypairGenerator = m;
            break;
          }
        }
      }

      if (keypairGenerator == null) {
        throw new OperationException(SYSTEM_FAILURE, "cannot generate keypair " + keyspec);
      }

      KeypairGenResult keypair = null;
      try {
        keypair = keypairGenerator.generateKeypair(keyspec);
      } catch (XiSecurityException ex) {
        String msg = "error generating keypair " + keyspec;
        LogUtil.error(LOG, ex, msg);
        throw new OperationException(SYSTEM_FAILURE, msg);
      }

      // adapt the algorithm identifier in private key and public key
      privateKey = keypair.getPrivateKey();
      grantedPublicKeyInfo = keypair.getPublicKey();
      if (!privateKey.getPrivateKeyAlgorithm().getAlgorithm().equals(keyAlgOid)) {
        ASN1BitString asn1PublicKeyData = privateKey.getPublicKeyData();
        try {
          privateKey = new PrivateKeyInfo(
              new AlgorithmIdentifier(keyAlgOid,
                  privateKey.getPrivateKeyAlgorithm().getParameters()),
              privateKey.getPrivateKey().toASN1Primitive(),
              privateKey.getAttributes(),
              asn1PublicKeyData == null ? null : asn1PublicKeyData.getOctets());
        } catch (IOException ex) {
          throw new OperationException(SYSTEM_FAILURE, ex);
        }
      }

      if (!grantedPublicKeyInfo.getAlgorithm().getAlgorithm().equals(keyAlgOid)) {
        grantedPublicKeyInfo = new SubjectPublicKeyInfo(
            new AlgorithmIdentifier(keyAlgOid, grantedPublicKeyInfo.getAlgorithm().getParameters()),
            grantedPublicKeyInfo.getPublicKeyData().getBytes());
      }
    } else {
      // show not reach here
      throw new OperationException(BAD_CERT_TEMPLATE, "no public key is specified");
    }

    // public key
    try {
      grantedPublicKeyInfo = certprofile.checkPublicKey(grantedPublicKeyInfo);
    } catch (CertprofileException ex) {
      throw new OperationException(SYSTEM_FAILURE, "exception in cert profile " + certprofileIdent);
    } catch (BadCertTemplateException ex) {
      throw new OperationException(BAD_CERT_TEMPLATE, ex);
    }

    // subject
    Certprofile.SubjectInfo subjectInfo;
    try {
      subjectInfo = certprofile.getSubject(requestedSubject, grantedPublicKeyInfo);
    } catch (CertprofileException ex) {
      throw new OperationException(SYSTEM_FAILURE, "exception in cert profile " + certprofileIdent);
    } catch (BadCertTemplateException ex) {
      throw new OperationException(BAD_CERT_TEMPLATE, ex);
    }

    X500Name grantedSubject = subjectInfo.getGrantedSubject();

    // make sure that empty subject is not permitted
    ASN1ObjectIdentifier[] attrTypes = grantedSubject.getAttributeTypes();
    if (attrTypes == null || attrTypes.length == 0) {
      throw new OperationException(BAD_CERT_TEMPLATE, "empty subject is not permitted");
    }

    // make sure that the grantedSubject does not equal the CA's subject
    if (X509Util.canonicalizName(grantedSubject).equals(
        caInfo.getPublicCaInfo().getC14nSubject())) {
      throw new OperationException(ALREADY_ISSUED,
          "certificate with the same subject as CA is not allowed");
    }

    if (update) {
      CertStore.CertStatus certStatus =
          certstore.getCertStatusForSubject(caInfo.getIdent(), grantedSubject);
      if (certStatus == CertStore.CertStatus.REVOKED) {
        throw new OperationException(CERT_REVOKED);
      } else if (certStatus == CertStore.CertStatus.UNKNOWN) {
        throw new OperationException(UNKNOWN_CERT);
      }
    } // end if(update)

    StringBuilder msgBuilder = new StringBuilder();

    if (subjectInfo.getWarning() != null) {
      msgBuilder.append(", ").append(subjectInfo.getWarning());
    }

    Validity validity = certprofile.getValidity();

    if (validity == null) {
      validity = caInfo.getMaxValidity();
    } else if (validity.compareTo(caInfo.getMaxValidity()) > 0) {
      validity = caInfo.getMaxValidity();
    }

    Date maxNotAfter = validity.add(grantedNotBefore);
    // maxNotAfter not after 99991231-235959
    if (maxNotAfter.getTime() > MAX_CERT_TIME_MS) {
      maxNotAfter = MAX_CERT_TIME;
    }

    Date grantedNotAfter = certTemplate.getNotAfter();
    if (grantedNotAfter != null) {
      if (grantedNotAfter.after(maxNotAfter)) {
        grantedNotAfter = maxNotAfter;
        msgBuilder.append(", notAfter modified");
      }
    } else {
      grantedNotAfter = maxNotAfter;
    }

    if (grantedNotAfter.after(caInfo.getNotAfter())) {
      ValidityMode caMode = caInfo.getValidityMode();
      NotAfterMode profileMode = certprofile.getNotAfterMode();
      if (profileMode == null) {
        profileMode = NotAfterMode.BY_CA;
      }

      if (profileMode == NotAfterMode.STRICT) {
        throw new OperationException(NOT_PERMITTED,
                "notAfter outside of CA's validity is not permitted by the CertProfile");
      }

      if (caMode == ValidityMode.STRICT) {
        throw new OperationException(NOT_PERMITTED,
                "notAfter outside of CA's validity is not permitted by the CA");
      }

      if (caMode == ValidityMode.CUTOFF) {
        grantedNotAfter = caInfo.getNotAfter();
      } else if (caMode == ValidityMode.LAX) {
        if (profileMode == NotAfterMode.CUTOFF) {
          grantedNotAfter = caInfo.getNotAfter();
        }
      } else {
        throw new IllegalStateException("should not reach here, CA ValidityMode " + caMode
                + " CertProfile NotAfterMode " + profileMode);
      } // end if (mode)
    } // end if (notAfter)

    String warning = null;
    if (msgBuilder.length() > 2) {
      warning = msgBuilder.substring(2);
    }
    GrantedCertTemplate gct = new GrantedCertTemplate(certTemplate.getExtensions(), certprofile,
        grantedNotBefore, grantedNotAfter, requestedSubject, grantedPublicKeyInfo,
        privateKey, signer, warning);
    gct.setGrantedSubject(grantedSubject);
    return gct;

  } // method createGrantedCertTemplate

}
