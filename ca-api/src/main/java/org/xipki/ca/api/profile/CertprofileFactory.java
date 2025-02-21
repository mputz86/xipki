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

package org.xipki.ca.api.profile;

import org.xipki.util.exception.ObjectCreationException;

import java.util.Set;

/**
 * Certprofile factory.
 *
 * @author Lijun Liao
 * @since 2.0.0
 */

public interface CertprofileFactory {

  /**
   * Retrieves the types of supported certificate profiles.
   * @return types of supported certificate profiles, never {@code null}.
   */
  Set<String> getSupportedTypes();

  /**
   * Whether Certprofile of given type can be created.
   *
   * @param type
   *          Type of the certificate profile. Must not be {@code null}.
   * @return whether certificate profile of this type can be created.
   */
  boolean canCreateProfile(String type);

  /**
   * Create new Certprofile of given type.
   *
   * @param type
   *          Type of the certificate profile. Must not be {@code null}.
   * @return the new created certificate profile.
   * @throws ObjectCreationException
   *           if certificate profile could not be created.
   */
  Certprofile newCertprofile(String type) throws ObjectCreationException;

}
