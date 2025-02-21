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

package org.xipki.scep.message;

import org.bouncycastle.asn1.ASN1Encodable;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.cms.*;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cms.*;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.util.CollectionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xipki.scep.util.ScepUtil;
import org.xipki.security.HashAlgo;
import org.xipki.security.SignAlgo;
import org.xipki.security.X509Cert;
import org.xipki.util.Args;
import org.xipki.util.CollectionUtil;
import org.xipki.util.LogUtil;

import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;

/**
 * Decoded {@link NextCaMessage}.
 *
 * @author Lijun Liao
 */

public class DecodedNextCaMessage {

  private static final Logger LOG = LoggerFactory.getLogger(DecodedNextCaMessage.class);

  private AuthorityCertStore authorityCertStore;

  private X509Cert signatureCert;

  private HashAlgo digestAlgorithm;

  private Boolean signatureValid;

  private Date signingTime;

  private String failureMessage;

  public DecodedNextCaMessage() {
  }

  public AuthorityCertStore getAuthorityCertStore() {
    return authorityCertStore;
  }

  public void setAuthorityCertStore(AuthorityCertStore authorityCertStore) {
    this.authorityCertStore = authorityCertStore;
  }

  public X509Cert getSignatureCert() {
    return signatureCert;
  }

  public void setSignatureCert(X509Cert signatureCert) {
    this.signatureCert = signatureCert;
  }

  public HashAlgo getDigestAlgorithm() {
    return digestAlgorithm;
  }

  public void setDigestAlgorithm(HashAlgo digestAlgorithm) {
    this.digestAlgorithm = digestAlgorithm;
  }

  public Boolean isSignatureValid() {
    return signatureValid;
  }

  public void setSignatureValid(Boolean signatureValid) {
    this.signatureValid = signatureValid;
  }

  public String getFailureMessage() {
    return failureMessage;
  }

  public void setFailureMessage(String failureMessage) {
    this.failureMessage = failureMessage;
  }

  public Date getSigningTime() {
    return signingTime;
  }

  public void setSigningTime(Date signingTime) {
    this.signingTime = signingTime;
  }

  @SuppressWarnings("unchecked")
  public static DecodedNextCaMessage decode(CMSSignedData pkiMessage, CollectionStore<X509CertificateHolder> certStore)
      throws MessageDecodingException {
    Args.notNull(pkiMessage, "pkiMessage");

    SignerInformationStore signerStore = pkiMessage.getSignerInfos();
    Collection<SignerInformation> signerInfos = signerStore.getSigners();
    if (signerInfos.size() != 1) {
      throw new MessageDecodingException("number of signerInfos is not 1, but " + signerInfos.size());
    }

    SignerInformation signerInfo = signerInfos.iterator().next();

    SignerId sid = signerInfo.getSID();

    Collection<?> signedDataCerts = null;
    if (certStore != null) {
      signedDataCerts = certStore.getMatches(sid);
    }

    if (CollectionUtil.isEmpty(signedDataCerts)) {
      signedDataCerts = pkiMessage.getCertificates().getMatches(signerInfo.getSID());
    }

    if (signedDataCerts == null || signedDataCerts.size() != 1) {
      throw new MessageDecodingException("could not find embedded certificate to verify the signature");
    }

    AttributeTable signedAttrs = signerInfo.getSignedAttributes();
    if (signedAttrs == null) {
      throw new MessageDecodingException("missing signed attributes");
    }

    Date signingTime = null;
    // signingTime
    ASN1Encodable attrValue = ScepUtil.getFirstAttrValue(signedAttrs, CMSAttributes.signingTime);
    if (attrValue != null) {
      signingTime = ScepUtil.getTime(attrValue);
    }

    DecodedNextCaMessage ret = new DecodedNextCaMessage();
    if (signingTime != null) {
      ret.setSigningTime(signingTime);
    }

    try {
      HashAlgo digestAlgo = HashAlgo.getInstance(signerInfo.getDigestAlgorithmID());
      ret.setDigestAlgorithm(digestAlgo);

      String sigAlgOid = signerInfo.getEncryptionAlgOID();
      if (!PKCSObjectIdentifiers.rsaEncryption.getId().equals(sigAlgOid)) {
        SignAlgo signAlgo = SignAlgo.getInstance(signerInfo.toASN1Structure().getDigestEncryptionAlgorithm());

        if (digestAlgo != signAlgo.getHashAlgo()) {
          ret.setFailureMessage("digestAlgorithm and encryptionAlgorithm do not use the same digestAlgorithm");
          return ret;
        }
      } // end if
    } catch (NoSuchAlgorithmException ex) {
      LogUtil.error(LOG, ex);
      ret.setFailureMessage(ex.getMessage());
      return ret;
    }

    X509CertificateHolder signerCert = (X509CertificateHolder) signedDataCerts.iterator().next();
    ret.setSignatureCert(new X509Cert(signerCert));

    // validate the signature
    SignerInformationVerifier verifier;
    try {
      verifier = new JcaSimpleSignerInfoVerifierBuilder().build(signerCert);
    } catch (OperatorCreationException | CertificateException ex) {
      final String msg = "could not build signature verifier";
      LogUtil.error(LOG, ex, msg);
      ret.setFailureMessage(msg + ": " +  ex.getMessage());
      return ret;
    }

    boolean signatureValid;
    try {
      signatureValid = signerInfo.verify(verifier);
    } catch (CMSException ex) {
      final String msg = "could not verify the signature";
      LogUtil.error(LOG, ex, msg);
      ret.setFailureMessage(msg + ": " +  ex.getMessage());
      return ret;
    }

    ret.setSignatureValid(signatureValid);
    if (!signatureValid) {
      return ret;
    }

    // MessageData
    CMSTypedData signedContent = pkiMessage.getSignedContent();
    ASN1ObjectIdentifier signedContentType = signedContent.getContentType();
    if (!CMSObjectIdentifiers.signedData.equals(signedContentType)) {
      // fall back: some SCEP client use id-data
      if (!CMSObjectIdentifiers.data.equals(signedContentType)) {
        ret.setFailureMessage("either id-signedData or id-data is excepted, but not '" + signedContentType.getId());
        return ret;
      }
    }

    ContentInfo contentInfo = ContentInfo.getInstance(signedContent.getContent());
    SignedData signedData = SignedData.getInstance(contentInfo.getContent());

    List<X509Cert> certs;
    try {
      certs = ScepUtil.getCertsFromSignedData(signedData);
    } catch (CertificateException ex) {
      final String msg = "could not extract Certificates from the message";
      LogUtil.error(LOG, ex, msg);
      ret.setFailureMessage(msg + ": " +  ex.getMessage());
      return ret;
    }

    X509Cert caCert = null;
    List<X509Cert> raCerts = new LinkedList<>();
    for (X509Cert cert : certs) {
      if (cert.getBasicConstraints() > -1) {
        if (caCert != null) {
          final String msg = "multiple CA certificates is returned, but exactly 1 is expected";
          LOG.error(msg);
          ret.setFailureMessage(msg);
          return ret;
        }

        caCert = cert;
      } else {
        raCerts.add(cert);
      }
    } // end for

    if (caCert == null) {
      final String msg = "no CA certificate is returned";
      LOG.error(msg);
      ret.setFailureMessage(msg);
      return ret;
    }

    X509Cert[] locaRaCerts = raCerts.isEmpty() ? null : raCerts.toArray(new X509Cert[0]);

    AuthorityCertStore authorityCertStore = AuthorityCertStore.getInstance(caCert, locaRaCerts);
    ret.setAuthorityCertStore(authorityCertStore);

    return ret;
  } // method decode

}
