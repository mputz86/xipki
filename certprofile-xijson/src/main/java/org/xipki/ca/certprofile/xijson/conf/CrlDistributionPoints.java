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

package org.xipki.ca.certprofile.xijson.conf;

import org.xipki.util.ValidatableConf;
import org.xipki.util.exception.InvalidConfException;

import java.util.Set;

/**
 * Extension CRLDistributionPoints.
 *
 * @author Lijun Liao
 */

public class CrlDistributionPoints extends ValidatableConf {

  private Set<String> protocols;

  public Set<String> getProtocols() {
    return protocols;
  }

  public void setProtocols(Set<String> protocols) {
    this.protocols = protocols;
  }

  @Override
  public void validate() throws InvalidConfException {
  }

} // class CrlDistributionPoints
