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

package org.xipki.ca.sdk;

import com.alibaba.fastjson.JSON;

/**
 *
 * @author Lijun Liao
 * @since 6.0.0
 */

public class CertprofileInfoRequest extends SdkRequest {

  private String profile;

  public String getProfile() {
    return profile;
  }

  public void setProfile(String profile) {
    this.profile = profile;
  }

  public static CertprofileInfoRequest decode(byte[] encoded) {
    return JSON.parseObject(encoded, CertprofileInfoRequest.class);
  }

}
