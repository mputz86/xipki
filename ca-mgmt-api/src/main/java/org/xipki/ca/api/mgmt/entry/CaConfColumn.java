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

package org.xipki.ca.api.mgmt.entry;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.annotation.JSONField;
import com.alibaba.fastjson.serializer.SerializerFeature;
import org.xipki.ca.api.CaUris;
import org.xipki.ca.api.mgmt.*;
import org.xipki.util.ConfPairs;
import org.xipki.util.Validity;
import org.xipki.util.exception.InvalidConfException;

import java.util.List;
import java.util.Map;

/**
 * Represent the CONF column in the table CA.
 *
 * @author Lijun Liao
 */
public class CaConfColumn {

  /**
   * Syntax version.
   */
  @JSONField(ordinal = 1)
  private int version = 1;

  /**
   * number of octets of the serial number.
   */
  @JSONField(ordinal = 3)
  private int snSize;

  @JSONField(ordinal = 5)
  private List<String> cacertUris;

  @JSONField(ordinal = 7)
  private List<String> ocspUris;

  @JSONField(ordinal = 9)
  private List<String> crlUris;

  @JSONField(ordinal = 11)
  private List<String> deltaCrlUris;

  /**
   * Not nullable. Maximal validity of the generated certificates.
   */
  @JSONField(ordinal = 13)
  private String maxValidity;

  @JSONField(ordinal = 15)
  private Map<String,String> crlControl;

  /**
   * Certificate Transparency Log Control.
   */
  @JSONField(ordinal = 25)
  private Map<String,String> ctlogControl;

  @JSONField(ordinal = 27)
  private Map<String,String> revokeSuspendedControl;

  @JSONField(ordinal = 29)
  private List<String> keypairGenNames;

  /**
   * Whether to save the certificate, default is true.
   */
  @JSONField(ordinal = 33)
  private boolean saveCert;

  /**
   * Whether the generated keypair should be saved, default is false.
   */
  @JSONField(ordinal = 35)
  private boolean saveKeypair;

  @JSONField(ordinal = 39)
  private String validityMode;

  @JSONField(ordinal = 41)
  private int permission;

  @JSONField(ordinal = 43)
  private int numCrls = 30;

  @JSONField(ordinal = 45)
  private int expirationPeriod = 365;

  /**
   * How long in days should certificates be kept after the expiration.
   * Negative value for kept-for-ever.
   */
  @JSONField(ordinal = 47)
  private int keepExpiredCertDays = -1;

  /**
   * Extra control.
   */
  @JSONField(ordinal = 99)
  private Map<String, String> extraControl;

  public int getVersion() {
    return version;
  }

  public void setVersion(int version) {
    this.version = version;
  }

  public int getSnSize() {
    return snSize;
  }

  public void setSnSize(int snSize) {
    this.snSize = snSize;
  }

  public List<String> getCacertUris() {
    return cacertUris;
  }

  public void setCacertUris(List<String> cacertUris) {
    this.cacertUris = cacertUris;
  }

  public List<String> getOcspUris() {
    return ocspUris;
  }

  public void setOcspUris(List<String> ocspUris) {
    this.ocspUris = ocspUris;
  }

  public List<String> getCrlUris() {
    return crlUris;
  }

  public void setCrlUris(List<String> crlUris) {
    this.crlUris = crlUris;
  }

  public List<String> getDeltaCrlUris() {
    return deltaCrlUris;
  }

  public void setDeltaCrlUris(List<String> deltaCrlUris) {
    this.deltaCrlUris = deltaCrlUris;
  }

  public String getMaxValidity() {
    return maxValidity;
  }

  public void setMaxValidity(String maxValidity) {
    this.maxValidity = maxValidity;
  }

  public Map<String, String> getCrlControl() {
    return crlControl;
  }

  public void setCrlControl(Map<String, String> crlControl) {
    this.crlControl = crlControl;
  }

  public Map<String, String> getCtlogControl() {
    return ctlogControl;
  }

  public void setCtlogControl(Map<String, String> ctlogControl) {
    this.ctlogControl = ctlogControl;
  }

  public Map<String, String> getRevokeSuspendedControl() {
    return revokeSuspendedControl;
  }

  public void setRevokeSuspendedControl(Map<String, String> revokeSuspendedControl) {
    this.revokeSuspendedControl = revokeSuspendedControl;
  }

  public List<String> getKeypairGenNames() {
    return keypairGenNames;
  }

  public void setKeypairGenNames(List<String> keypairGenNames) {
    this.keypairGenNames = keypairGenNames;
  }

  public boolean isSaveCert() {
    return saveCert;
  }

  public void setSaveCert(boolean saveCert) {
    this.saveCert = saveCert;
  }

  public boolean isSaveKeypair() {
    return saveKeypair;
  }

  public void setSaveKeypair(boolean saveKeypair) {
    this.saveKeypair = saveKeypair;
  }

  public String getValidityMode() {
    return validityMode;
  }

  public void setValidityMode(String validityMode) {
    this.validityMode = validityMode;
  }

  public int getPermission() {
    return permission;
  }

  public void setPermission(int permission) {
    this.permission = permission;
  }

  public int getNumCrls() {
    return numCrls;
  }

  public void setNumCrls(int numCrls) {
    this.numCrls = numCrls;
  }

  public int getExpirationPeriod() {
    return expirationPeriod;
  }

  public void setExpirationPeriod(int expirationPeriod) {
    this.expirationPeriod = expirationPeriod;
  }

  public int getKeepExpiredCertDays() {
    return keepExpiredCertDays;
  }

  public void setKeepExpiredCertDays(int keepExpiredCertDays) {
    this.keepExpiredCertDays = keepExpiredCertDays;
  }

  public Map<String, String> getExtraControl() {
    return extraControl;
  }

  public void setExtraControl(Map<String, String> extraControl) {
    this.extraControl = extraControl;
  }

  @Override
  public CaConfColumn clone() {
    return decode(encode());
  }

  public static CaConfColumn decode(String encoded) {
    return JSON.parseObject(encoded, CaConfColumn.class);
  }

  public void fillCaEntry(CaEntry entry) throws CaMgmtException {
    entry.setRevokeSuspendedControl(revokeSuspendedControl());
    entry.setMaxValidity(maxValidity());
    entry.setKeepExpiredCertInDays(keepExpiredCertDays);
    entry.setKeypairGenNames(keypairGenNames);
    entry.setExtraControl(extraControl());
    entry.setCrlControl(crlControl());
    entry.setCtlogControl(ctlogControl());
    entry.setSaveCert((isSaveCert()));
    entry.setSaveKeypair(isSaveKeypair());
    entry.setPermission(permission);
    entry.setValidityMode(validityMode());
  }

  public String encode() {
    return JSON.toJSONString(this, SerializerFeature.MapSortField,
            SerializerFeature.QuoteFieldNames, SerializerFeature.IgnoreNonFieldGetter);
  }

  public CaUris caUris() {
    return new CaUris(cacertUris, ocspUris, crlUris, deltaCrlUris);
  }

  public int snSize() {
    if (snSize > CaManager.MAX_SERIALNUMBER_SIZE) {
      return CaManager.MAX_SERIALNUMBER_SIZE;
    } else if (snSize < CaManager.MIN_SERIALNUMBER_SIZE) {
      return CaManager.MIN_SERIALNUMBER_SIZE;
    } else {
      return snSize;
    }
  }

  private RevokeSuspendedControl revokeSuspendedControl() {
     return revokeSuspendedControl == null
        ? new RevokeSuspendedControl(false)
        : new RevokeSuspendedControl(new ConfPairs(revokeSuspendedControl));
  }

  private Validity maxValidity() {
    return maxValidity == null ? null : Validity.getInstance(maxValidity);
  }

  private CrlControl crlControl() throws CaMgmtException {
    ConfPairs pairs = new ConfPairs(crlControl);
    try {
      return new CrlControl(pairs);
    } catch (InvalidConfException ex) {
      throw new CaMgmtException("invalid CRL_CONTROL: " + pairs, ex);
    }
  }

  private CtlogControl ctlogControl() throws CaMgmtException {
    if (ctlogControl == null) {
      return null;
    }

    ConfPairs pairs = new ConfPairs(ctlogControl);
    try {
      return new CtlogControl(pairs);
    } catch (InvalidConfException ex) {
      throw new CaMgmtException("invalid CTLOG_CONTROL: " + pairs.getEncoded(), ex);
    }
  }

  private ConfPairs extraControl() {
    return extraControl == null ? null : new ConfPairs(extraControl).unmodifiable();
  }

  private ValidityMode validityMode() {
    return validityMode == null ? ValidityMode.STRICT : ValidityMode.forName(validityMode);
  }

}
