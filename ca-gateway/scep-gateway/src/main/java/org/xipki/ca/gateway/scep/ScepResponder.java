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

package org.xipki.ca.gateway.scep;

import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.cms.CMSObjectIdentifiers;
import org.bouncycastle.asn1.cms.ContentInfo;
import org.bouncycastle.asn1.cms.IssuerAndSerialNumber;
import org.bouncycastle.asn1.cms.SignedData;
import org.bouncycastle.asn1.pkcs.CertificationRequest;
import org.bouncycastle.asn1.pkcs.CertificationRequestInfo;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.Certificate;
import org.bouncycastle.asn1.x509.CertificateList;
import org.bouncycastle.asn1.x509.Extensions;
import org.bouncycastle.cert.X509CRLHolder;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cms.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xipki.audit.*;
import org.xipki.ca.gateway.*;
import org.xipki.ca.sdk.*;
import org.xipki.scep.message.*;
import org.xipki.scep.transaction.*;
import org.xipki.scep.util.ScepConstants;
import org.xipki.security.HashAlgo;
import org.xipki.security.SecurityFactory;
import org.xipki.security.SignAlgo;
import org.xipki.security.X509Cert;
import org.xipki.security.util.HttpRequestMetadataRetriever;
import org.xipki.security.util.X509Util;
import org.xipki.util.LogUtil;
import org.xipki.util.PermissionConstants;
import org.xipki.util.exception.ErrorCode;
import org.xipki.util.exception.OperationException;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import static org.xipki.util.Args.notNull;
import static org.xipki.util.exception.ErrorCode.*;

/**
 * SCEP responder.
 *
 * @author Lijun Liao
 * @since 2.0.0
 *
 */
public class ScepResponder {

  private static final String NAME_decryption = "decryption";

  private static final String NAME_fail_info = "fail_info";

  private static final String NAME_failure_message = "failure_message";

  private static final String NAME_message_type = "message_type";

  private static final String NAME_pki_status = "pki_status";

  private static final String NAME_signature = "signature";

  private static class FailInfoException extends Exception {

    public static final FailInfoException BAD_CERTID = new FailInfoException(FailInfo.badCertId);

    public static final FailInfoException BAD_MESSAGE_CHECK = new FailInfoException(FailInfo.badMessageCheck);

    public static final FailInfoException BAD_REQUEST = new FailInfoException(FailInfo.badRequest);

    private final FailInfo failInfo;

    private FailInfoException(FailInfo failInfo) {
      super(notNull(failInfo, "failInfo").name());
      this.failInfo = failInfo;
    }

    public FailInfo getFailInfo() {
      return failInfo;
    }

  } // method FailInfoException

  private static final Logger LOG = LoggerFactory.getLogger(ScepResponder.class);

  private static final String CGI_PROGRAM = "/pkiclient.exe";

  private static final int CGI_PROGRAM_LEN = CGI_PROGRAM.length();

  private static final String CT_RESPONSE = ScepConstants.CT_PKI_MESSAGE;

  private final ScepControl control;

  private final SdkClient sdk;

  private final PopControl popControl;

  private final CaCaps caCaps;

  private final SecurityFactory securityFactory;

  private final RequestorAuthenticator authenticator;

  private final CaNameScepSigners signers;

  public ScepResponder(ScepControl control, SdkClient sdk, SecurityFactory securityFactory, CaNameScepSigners signers,
                       RequestorAuthenticator authenticator, PopControl popControl) {
    this.control = notNull(control, "control");
    this.sdk = notNull(sdk, "sdk");
    this.securityFactory = notNull(securityFactory, "securityFactory");
    this.authenticator = notNull(authenticator, "authenticator");
    this.popControl = notNull(popControl, "popControl");

    // CACaps
    CaCaps caps = new CaCaps();
    caps.addCapabilities(CaCapability.SCEPStandard, CaCapability.AES, CaCapability.DES3, CaCapability.POSTPKIOperation,
        CaCapability.Renewal, CaCapability.SHA1, CaCapability.SHA256, CaCapability.SHA512);
    this.caCaps = caps;
    this.signers = signers;
  } // constructor

  private CaCaps getCaCaps() {
    return caCaps;
  }

  private Requestor getRequestor(String user) {
    return authenticator.getPasswordRequestorByUser(user);
  }

  private Requestor getRequestor(X509Cert cert) {
    return authenticator.getCertRequestor(cert);
  }

  public RestResponse service(String path, byte[] request, HttpRequestMetadataRetriever metadataRetriever) {
    String caName = null;
    String certprofileName = null;
    if (path.length() > 1) {
      if (path.endsWith(CGI_PROGRAM)) {
        // skip also the first char (which is always '/')
        String tpath = path.substring(1, path.length() - CGI_PROGRAM_LEN);
        String[] tokens = tpath.split("/");
        if (tokens.length == 2) {
          caName = tokens[0];
          certprofileName = tokens[1].toLowerCase();
        }
      } // end if
    } // end if

    if (caName == null || certprofileName == null) {
      return new RestResponse(HttpServletResponse.SC_NOT_FOUND);
    }

    AuditService auditService = Audits.getAuditService();
    AuditEvent event = new AuditEvent(new Date());
    event.setApplicationName("scep-gw");
    event.addEventData("name", caName + "/" + certprofileName);

    AuditLevel auditLevel = AuditLevel.INFO;
    AuditStatus auditStatus = AuditStatus.SUCCESSFUL;
    String auditMessage = null;

    String operation = metadataRetriever.getParameter("operation");
    event.addEventData("operation", operation);

    RestResponse ret;

    try {
      byte[] respBody;
      String contentType;

      if ("PKIOperation".equalsIgnoreCase(operation)) {
        CMSSignedData reqMessage;
        // parse the request
        try {
          reqMessage = new CMSSignedData(request);
        } catch (Exception ex) {
          final String msg = "invalid request";
          LogUtil.error(LOG, ex, msg);
          auditMessage = msg;
          auditStatus = AuditStatus.FAILED;
          return new RestResponse(HttpServletResponse.SC_BAD_REQUEST);
        }

        ScepSigner signer = signers.getSigner(caName);
        if (signer == null) {
          final String msg = "found no signer";
          LOG.error(msg + " for CA {}", caName);
          auditMessage = msg;
          auditStatus = AuditStatus.FAILED;
          return new RestResponse(HttpServletResponse.SC_BAD_REQUEST);
        }

        ContentInfo ci;
        try {
          ci = servicePkiOperation(signer, caName, reqMessage, certprofileName, event);
        } catch (MessageDecodingException ex) {
          final String msg = "could not decrypt and/or verify the request";
          LogUtil.error(LOG, ex, msg);
          auditMessage = msg;
          auditStatus = AuditStatus.FAILED;
          return new RestResponse(HttpServletResponse.SC_BAD_REQUEST);
        } catch (OperationException | SdkErrorResponseException ex) {
          ErrorCode code;
          if (ex instanceof OperationException) {
            auditMessage = ex.getMessage();
            code = ((OperationException) ex).getErrorCode();
          } else {
            ErrorResponse err = ((SdkErrorResponseException) ex).getErrorResponse();
            auditMessage = err.getMessage();
            code = err.getCode();
          }

          int httpCode;
          switch (code) {
            case ALREADY_ISSUED:
            case CERT_REVOKED:
            case CERT_UNREVOKED:
              httpCode = HttpServletResponse.SC_FORBIDDEN;
              break;
            case BAD_CERT_TEMPLATE:
            case BAD_REQUEST:
            case BAD_POP:
            case INVALID_EXTENSION:
            case UNKNOWN_CERT:
            case UNKNOWN_CERT_PROFILE:
              httpCode = HttpServletResponse.SC_BAD_REQUEST;
              break;
            case NOT_PERMITTED:
              httpCode = HttpServletResponse.SC_UNAUTHORIZED;
              break;
            case SYSTEM_UNAVAILABLE:
              httpCode = HttpServletResponse.SC_SERVICE_UNAVAILABLE;
              break;
            case CRL_FAILURE:
            case DATABASE_FAILURE:
            case SYSTEM_FAILURE:
            default:
              httpCode = HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
              break;
          }

          LogUtil.error(LOG, ex, auditMessage);
          auditStatus = AuditStatus.FAILED;
          return new RestResponse(httpCode);
        }

        respBody = ci.getEncoded();
        contentType = CT_RESPONSE;
      } else if (Operation.GetCACaps.getCode().equalsIgnoreCase(operation)) {
        // CA-Ident is ignored
        contentType = ScepConstants.CT_TEXT_PLAIN;
        respBody = getCaCaps().getBytes();
      } else if (Operation.GetCACert.getCode().equalsIgnoreCase(operation)) {
        // CA-Ident is ignored
        contentType = ScepConstants.CT_X509_CA_RA_CERT;
        respBody = getCaCertResp(caName);
      } else if (Operation.GetNextCACert.getCode().equalsIgnoreCase(operation)) {
        auditMessage = "SCEP operation '" + operation + "' is not permitted";
        auditStatus = AuditStatus.FAILED;
        return new RestResponse(HttpServletResponse.SC_FORBIDDEN);
      } else {
        auditMessage = "unknown SCEP operation '" + operation + "'";
        auditStatus = AuditStatus.FAILED;
        return new RestResponse(HttpServletResponse.SC_BAD_REQUEST);
      }
      ret = new RestResponse(HttpServletResponse.SC_OK, contentType, null, respBody);
    } catch (Throwable th) {
      LOG.error("Throwable thrown, this should not happen!", th);

      auditLevel = AuditLevel.ERROR;
      auditStatus = AuditStatus.FAILED;
      auditMessage = "internal error";
      ret = new RestResponse(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
    } finally {
      audit(auditService, event, auditLevel, auditStatus, auditMessage);
    }

    return ret;
  } // method service0

  private static void audit(AuditService auditService, AuditEvent event,
                            AuditLevel auditLevel, AuditStatus auditStatus, String auditMessage) {
    AuditLevel curLevel = event.getLevel();
    if (curLevel == null) {
      event.setLevel(auditLevel);
    } else if (curLevel.getValue() > auditLevel.getValue()) {
      event.setLevel(auditLevel);
    }

    if (auditStatus != null) {
      event.setStatus(auditStatus);
    }

    if (auditMessage != null) {
      event.addEventData(CaAuditConstants.NAME_message, auditMessage);
    }

    event.finish();
    auditService.logEvent(event);

    GatewayUtil.logAuditEvent(LOG, event);
  } // method audit

  private byte[] getCaCertResp(String caName) throws OperationException, SdkErrorResponseException {
    try {
      ScepSigner signer = signers.getSigner(caName);
      if (signer == null) {
        throw new OperationException(PATH_NOT_FOUND, "found na signer for CA " + caName);
      }

      byte[] cacert = sdk.cacert(caName);
      if (cacert == null) {
        throw new OperationException(PATH_NOT_FOUND, "unknown CA " + caName);
      }

      CMSSignedDataGenerator cmsSignedDataGen = new CMSSignedDataGenerator();
      try {
        cmsSignedDataGen.addCertificate(new X509CertificateHolder(Certificate.getInstance(cacert)));
        cmsSignedDataGen.addCertificate(signer.getCert().toBcCert());
        CMSSignedData degenerateSignedData = cmsSignedDataGen.generate(new CMSAbsentContent());
        return degenerateSignedData.getEncoded();
      } catch (IOException ex) {
        throw new CMSException("could not build CMS SignedDta");
      }
    } catch (CMSException | IOException ex) {
      throw new OperationException(SYSTEM_FAILURE, ex.getMessage());
    }
  }

  private ContentInfo servicePkiOperation(
      ScepSigner signer, String caName, CMSSignedData requestContent, String certprofileName, AuditEvent event)
      throws MessageDecodingException, OperationException, SdkErrorResponseException {
    DecodedPkiMessage req = DecodedPkiMessage.decode(requestContent, signer.getDecryptor(), null);
    PkiMessage rep = servicePkiOperation0(caName, requestContent, req, certprofileName, event);
    audit(event, NAME_pki_status, rep.getPkiStatus().toString());
    if (rep.getPkiStatus() == PkiStatus.FAILURE) {
      event.setStatus(AuditStatus.FAILED);
    }
    if (rep.getFailInfo() != null) {
      audit(event, NAME_fail_info, rep.getFailInfo().toString());
    }
    return encodeResponse(signer, rep, req);
  } // method servicePkiOperation

  private PkiMessage servicePkiOperation0(
      String caName, CMSSignedData requestContent, DecodedPkiMessage req, String certprofileName, AuditEvent event)
      throws OperationException, SdkErrorResponseException {
    notNull(requestContent, "requestContent");

    String tid = notNull(req, "req").getTransactionId().getId();
    // verify and decrypt the request
    audit(event, CaAuditConstants.NAME_tid, tid);

    if (req.getFailureMessage() != null) {
      audit(event, NAME_failure_message, req.getFailureMessage());
    }

    if (!dfltTrue(req.isSignatureValid())) {
      audit(event, NAME_signature, "invalid");
    }

    if (!dfltTrue(req.isDecryptionSuccessful())) {
      audit(event, NAME_decryption, "failed");
    }

    PkiMessage rep = new PkiMessage(req.getTransactionId(), MessageType.CertRep, Nonce.randomNonce());
    rep.setRecipientNonce(req.getSenderNonce());

    if (req.getFailureMessage() != null) {
      return fail(rep, FailInfo.badRequest);
    }

    if (!dfltTrue(req.isSignatureValid())) {
      return fail(rep, FailInfo.badMessageCheck);
    }

    if (!dfltTrue(req.isDecryptionSuccessful())) {
      return fail(rep, FailInfo.badRequest);
    }

    Date signingTime = req.getSigningTime();
    long maxSigningTimeBiasInMs = 1000L * control.getMaxSigningTimeBias();
    if (maxSigningTimeBiasInMs > 0) {
      boolean isTimeBad = signingTime == null ? true
          : Math.abs(System.currentTimeMillis() - signingTime.getTime()) > maxSigningTimeBiasInMs;

      if (isTimeBad) {
        return fail(rep, FailInfo.badTime);
      }
    } // end if

    // check the digest algorithm
    HashAlgo hashAlgo = req.getDigestAlgorithm();
    boolean supported = false;
    if (hashAlgo == HashAlgo.SHA1) {
      if (caCaps.supportsSHA1()) {
        supported = true;
      }
    } else if (hashAlgo == HashAlgo.SHA256) {
      if (caCaps.supportsSHA256()) {
        supported = true;
      }
    } else if (hashAlgo == HashAlgo.SHA512) {
      if (caCaps.supportsSHA512()) {
        supported = true;
      }
    }

    if (!supported) {
      LOG.warn("tid={}: unsupported digest algorithm {}", tid, hashAlgo);
      return fail(rep, FailInfo.badAlg);
    }

    // check the content encryption algorithm
    ASN1ObjectIdentifier encOid = req.getContentEncryptionAlgorithm();
    if (CMSAlgorithm.DES_EDE3_CBC.equals(encOid)) {
      if (!caCaps.supportsDES3()) {
        LOG.warn("tid={}: encryption with DES3 algorithm {} is not permitted", tid, encOid);
        return fail(rep, FailInfo.badAlg);
      }
    } else if (CMSAlgorithm.AES128_CBC.equals(encOid)) {
      if (!caCaps.supportsAES()) {
        LOG.warn("tid={}: encryption with AES algorithm {} is not permitted", tid, encOid);
        return fail(rep, FailInfo.badAlg);
      }
    } else {
      LOG.warn("tid={}: encryption with algorithm {} is not permitted", tid, encOid);
      return fail(rep, FailInfo.badAlg);
    }

    try {
      SignedData signedData;

      MessageType mt = req.getMessageType();
      audit(event, NAME_message_type, mt.toString());

      Requestor requestor = null;

      switch (mt) {
        case PKCSReq:
        case RenewalReq: {
          CertificationRequest csr = CertificationRequest.getInstance(req.getMessageData());
          X500Name reqSubject = csr.getCertificationRequestInfo().getSubject();
          if (LOG.isInfoEnabled()) {
            LOG.info("tid={}, subject={}", tid, X509Util.x500NameText(reqSubject));
          }

          event.addEventData(CaAuditConstants.NAME_certprofile, certprofileName);
          event.addEventData(CaAuditConstants.NAME_req_subject, "\"" + X509Util.x500NameText(reqSubject) + "\"");

          if (!GatewayUtil.verifyCsr(csr, securityFactory, popControl)) {
            LOG.warn("tid={} POP verification failed", tid);
            throw FailInfoException.BAD_MESSAGE_CHECK;
          }

          CertificationRequestInfo csrReqInfo = csr.getCertificationRequestInfo();
          X509Cert reqSignatureCert = req.getSignatureCert();

          if (reqSignatureCert.isSelfSigned()) {
            if (!reqSignatureCert.getSubject().equals(csrReqInfo.getSubject())) {
              LOG.warn("tid={}, self-signed identityCert.subject ({}) != csr.subject ({})",
                  tid, reqSignatureCert.getSubject(), csrReqInfo.getSubject());
              throw FailInfoException.BAD_REQUEST;
            }
          }

          if (X509Util.getCommonName(csrReqInfo.getSubject()) == null) {
            throw new OperationException(BAD_CERT_TEMPLATE, "tid=" + tid + ": no CommonName in requested subject");
          }

          String challengePwd = X509Util.getChallengePassword(csrReqInfo);
          if (challengePwd != null) {
            String[] strs = challengePwd.split(":");
            if (strs.length != 2) {
              LOG.warn("tid={}: challengePassword does not have the format <user>:<password>", tid);
              throw FailInfoException.BAD_REQUEST;
            }

            String user = strs[0];
            String password = strs[1];
            requestor = getRequestor(user);
            boolean authorized = requestor != null && requestor.authenticate(password.getBytes(StandardCharsets.UTF_8));
            if (!authorized) {
              LOG.warn("tid={}: could not authenticate user {}", tid, user);
              throw FailInfoException.BAD_REQUEST;
            }
          } // end if

          if (reqSignatureCert.isSelfSigned()) {
            if (MessageType.PKCSReq != mt) {
              LOG.warn("tid={}: self-signed certificate is not permitted for messageType {}", tid, mt);
              throw FailInfoException.BAD_REQUEST;
            }
            if (requestor == null) {
              LOG.warn("tid={}: could not extract user & password from challengePassword"
                  + ", which are required for self-signed signature certificate", tid);
              throw FailInfoException.BAD_REQUEST;
            }
          } else {
            // No challengePassword is sent, try to find out whether the signature
            // certificate is known by the CA
            if (requestor == null) {
              // up to draft-nourse-scep-23 the client sends all messages to enroll
              // certificate via MessageType PKCSReq
              requestor = getRequestor(reqSignatureCert);
              if (requestor == null) {
                LOG.warn("tid={}: signature certificate is not trusted by the CA", tid);
                throw FailInfoException.BAD_REQUEST;
              }
            } // end if
          } // end if

          checkUserPermission(requestor, certprofileName);

          Extensions extensions = X509Util.getExtensions(csrReqInfo);
          // need to remove the password
          EnrollCertRequestEntry template = new EnrollCertRequestEntry();
          template.setCertprofile(certprofileName);
          template.setSubject(new X500NameType(csrReqInfo.getSubject()));

          try {
            template.extensions(extensions);
          } catch (IOException e) {
            LogUtil.warn(LOG, e, "could not encode extensions");
            throw FailInfoException.BAD_REQUEST;
          }

          try {
            template.subjectPublicKey(csrReqInfo.getSubjectPublicKeyInfo());
          } catch (IOException e) {
            LogUtil.warn(LOG, e, "could not encode SubjectPublicKeyInfo");
            throw FailInfoException.BAD_REQUEST;
          }

          EnrollCertsRequest sdkReq = new EnrollCertsRequest();
          sdkReq.setEntries(Collections.singletonList(template));
          sdkReq.setTransactionId(tid);
          sdkReq.setExplicitConfirm(false);
          CertsMode certsMode = control.isIncludeCertChain() ? CertsMode.CHAIN
              : control.isIncludeCaCert() ? CertsMode.CERT : CertsMode.NONE;
          sdkReq.setCaCertMode(certsMode);

          EnrollOrPollCertsResponse sdkResp;
          try {
            sdkResp = sdk.enrollCerts(caName, sdkReq);
          } catch (IOException e) {
            LOG.error("error enrollCerts", e);
            throw new OperationException(SYSTEM_FAILURE, e.getMessage());
          }
          signedData = buildSignedData(sdkResp);
          break;
        }
        case CertPoll: {
          IssuerAndSubject is = IssuerAndSubject.getInstance(req.getMessageData());
          audit(event, CaAuditConstants.NAME_issuer, "\"" + X509Util.x500NameText(is.getIssuer()) + "\"");
          audit(event, CaAuditConstants.NAME_subject, "\"" + X509Util.x500NameText(is.getSubject()) + "\"");
          PollCertRequestEntry template = new PollCertRequestEntry();
          template.setSubject(new X500NameType(is.getSubject()));

          PollCertRequest sdkReq = new PollCertRequest();
          sdkReq.setIssuer(new X500NameType(is.getIssuer()));
          sdkReq.setTransactionId(req.getTransactionId().getId());
          sdkReq.setEntries(Collections.singletonList(template));

          EnrollOrPollCertsResponse sdkResp;
          try {
            sdkResp = sdk.pollCerts(caName, sdkReq);
          } catch (IOException e) {
            LOG.error("error pollCerts", e);
            throw new OperationException(SYSTEM_FAILURE, e.getMessage());
          }

          signedData = buildSignedData(sdkResp);
          break;
        }
        case GetCert:
          IssuerAndSerialNumber isn = IssuerAndSerialNumber.getInstance(req.getMessageData());
          BigInteger serial = isn.getSerialNumber().getPositiveValue();
          audit(event, CaAuditConstants.NAME_issuer, "\"" + X509Util.x500NameText(isn.getName()) + "\"");
          audit(event, CaAuditConstants.NAME_serial, LogUtil.formatCsn(serial));
          signedData = getCert(caName, isn.getName(), serial);
          break;
        case GetCRL:
          isn = IssuerAndSerialNumber.getInstance(req.getMessageData());
          serial = isn.getSerialNumber().getPositiveValue();
          audit(event, CaAuditConstants.NAME_issuer, "\"" + X509Util.x500NameText(isn.getName()) + "\"");
          audit(event, CaAuditConstants.NAME_serial, LogUtil.formatCsn(serial));
          signedData = getCrl(caName, isn.getName(), serial);
          break;
        default:
          LOG.error("unknown SCEP messageType '{}'", req.getMessageType());
          throw FailInfoException.BAD_REQUEST;
      } // end switch

      rep.setMessageData(new ContentInfo(CMSObjectIdentifiers.signedData, signedData));
      rep.setPkiStatus(PkiStatus.SUCCESS);
      return rep;
    } catch (FailInfoException ex) {
      LogUtil.error(LOG, ex);
      return fail(rep, ex.getFailInfo());
    }
  } // method servicePkiOperation0

  private SignedData getCert(String caName, X500Name issuer, BigInteger serialNumber)
      throws FailInfoException, OperationException, SdkErrorResponseException {
    byte[] encodedCert;
    try {
      encodedCert = sdk.getCert(caName, issuer, serialNumber);
    } catch (IOException ex) {
      final String message = "could not get certificate for CA '" + caName
          + "' and serialNumber=" + LogUtil.formatCsn(serialNumber) + ")";
      LogUtil.error(LOG, ex, message);
      throw new OperationException(SYSTEM_FAILURE, ex);
    }

    if (encodedCert == null) {
      throw FailInfoException.BAD_CERTID;
    }

    return buildSignedData(encodedCert, null);
  } // method getCert

  private SignedData buildSignedData(EnrollOrPollCertsResponse sdkResp)
    throws OperationException {
    List<EnrollOrPullCertResponseEntry> entries = sdkResp.getEntries();
    int n = entries == null ? 0 : entries.size();
    if (n != 1) {
      throw new OperationException(SYSTEM_FAILURE, "expected 1 cert, but received " + n);
    }

    EnrollOrPullCertResponseEntry entry = entries.get(0);
    byte[] cert = entry.getCert();
    if (cert == null) {
      throw new OperationException(ErrorCode.ofCode(entry.getError().getCode()), "expected 1 cert, but received none");
    }

    return buildSignedData(cert, sdkResp.getExtraCerts());
  }

  private SignedData buildSignedData(byte[] cert, List<byte[]> extraCerts)
      throws OperationException {
    CMSSignedDataGenerator cmsSignedDataGen = new CMSSignedDataGenerator();
    try {
      cmsSignedDataGen.addCertificate(new X509CertificateHolder(Certificate.getInstance(cert)));
      if (extraCerts != null) {
        for (byte[] c : extraCerts) {
          cmsSignedDataGen.addCertificate(new X509CertificateHolder(Certificate.getInstance(c)));
        }
      }
      return SignedData.getInstance(cmsSignedDataGen.generate(new CMSAbsentContent()).toASN1Structure().getContent());
    } catch (CMSException ex) {
      LogUtil.error(LOG, ex);
      throw new OperationException(SYSTEM_FAILURE, ex);
    }
  } // method buildSignedData

  private SignedData getCrl(String caName, X500Name issuer, BigInteger serialNumber)
      throws FailInfoException, OperationException, SdkErrorResponseException {
    if (!control.isSupportGetCrl()) {
      throw FailInfoException.BAD_REQUEST;
    }

    byte[] crl;
    try {
      crl = sdk.currentCrl(caName);
    } catch (IOException e) {
      throw new OperationException(SYSTEM_FAILURE, e.getMessage());
    }

    if (crl == null) {
      LOG.error("found no CRL");
      throw FailInfoException.BAD_REQUEST;
    }
    CMSSignedDataGenerator cmsSignedDataGen = new CMSSignedDataGenerator();
    cmsSignedDataGen.addCRL(new X509CRLHolder(CertificateList.getInstance(crl)));

    CMSSignedData signedData;
    try {
      signedData = cmsSignedDataGen.generate(new CMSAbsentContent());
    } catch (CMSException ex) {
      LogUtil.error(LOG, ex, "could not generate CMSSignedData");
      throw new OperationException(SYSTEM_FAILURE, ex);
    }
    return SignedData.getInstance(signedData.toASN1Structure().getContent());
  } // method getCrl

  private ContentInfo encodeResponse(
      ScepSigner signer, PkiMessage response, DecodedPkiMessage request)
      throws OperationException {
    notNull(response, "response");
    notNull(request, "request");

    String algorithm = signer.getKey().getAlgorithm();

    if (!"RSA".equalsIgnoreCase(algorithm)) {
      throw new UnsupportedOperationException("getSignatureAlgorithm() for non-RSA is not supported yet.");
    }

    HashAlgo hashAlgo = request.getDigestAlgorithm();

    ContentInfo ci;
    try {
      SignAlgo signatureAlgorithm = SignAlgo.getInstance(hashAlgo.getJceName() + "withRSA");
      X509Cert[] cmsCertSet = control.isIncludeSignerCert() ? new X509Cert[]{signer.getCert()} : null;

      ci = response.encode(signer.getKey(), signatureAlgorithm, signer.getCert(), cmsCertSet,
          request.getSignatureCert(), request.getContentEncryptionAlgorithm());
    } catch (MessageEncodingException | NoSuchAlgorithmException ex) {
      LogUtil.error(LOG, ex, "could not encode response");
      throw new OperationException(SYSTEM_FAILURE, ex);
    }
    return ci;
  } // method encodeResponse

  private static void checkUserPermission(
      Requestor requestor, String certprofile)
      throws OperationException {
    int permission = PermissionConstants.ENROLL_CERT;
    if (!requestor.isPermitted(permission)) {
      throw new OperationException(NOT_PERMITTED,
          PermissionConstants.getTextForCode(permission) + " is not permitted for user " + requestor.getName());
    }

    if (!requestor.isCertprofilePermitted(certprofile)) {
      throw new OperationException(NOT_PERMITTED,
          "Certificate profile " + certprofile + " is not permitted for user " + requestor.getName());
    }
  } // method checkUserPermission

  private static void audit(AuditEvent audit, String name, String value) {
    audit.addEventData(name, (value == null) ? "null" : value);
  }

  private static PkiMessage fail(PkiMessage rep, FailInfo failInfo) {
    rep.setPkiStatus(PkiStatus.FAILURE);
    rep.setFailInfo(FailInfo.badMessageCheck);
    return rep;
  }

  private static boolean dfltTrue(Boolean b) {
    return b == null ? true : b;
  }

}
