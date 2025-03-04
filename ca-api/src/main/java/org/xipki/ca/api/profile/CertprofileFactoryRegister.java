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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xipki.util.Args;
import org.xipki.util.exception.ObjectCreationException;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Register of CertprofileFactories.
 *
 * @author Lijun Liao
 * @since 2.0.0
 */

public class CertprofileFactoryRegister {

  private static final Logger LOG = LoggerFactory.getLogger(CertprofileFactoryRegister.class);

  private final ConcurrentLinkedDeque<CertprofileFactory> factories = new ConcurrentLinkedDeque<>();

  /**
   * Retrieves the types of supported certificate profiles.
   * @return types of supported certificate profiles, never {@code null}.
   */
  public Set<String> getSupportedTypes() {
    Set<String> types = new HashSet<>();
    for (CertprofileFactory service : factories) {
      types.addAll(service.getSupportedTypes());
    }
    return Collections.unmodifiableSet(types);
  }

  /**
   * Whether Certprofile of given type can be created.
   *
   * @param type
   *          Type of the certificate profile. Must not be {@code null}.
   * @return whether certificate profile of this type can be created.
   */
  public boolean canCreateProfile(String type) {
    for (CertprofileFactory service : factories) {
      if (service.canCreateProfile(type)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Create new Certprofile of given type.
   *
   * @param type
   *          Type of the certificate. Must not be {@code null}.
   * @return new certificate profile.
   * @throws ObjectCreationException
   *           If certificate profile could not be created.
   */
  public Certprofile newCertprofile(String type) throws ObjectCreationException {
    Args.notBlank(type, "type");

    for (CertprofileFactory service : factories) {
      if (service.canCreateProfile(type)) {
        return service.newCertprofile(type);
      }
    }

    throw new ObjectCreationException(
        "could not find factory to create Certprofile of type '" + type + "'");
  } // method newCertprofile

  public void registFactory(CertprofileFactory factory) {
    //might be null if dependency is optional
    if (factory == null) {
      LOG.info("registFactroy invoked with null.");
      return;
    }

    boolean replaced = factories.remove(factory);
    factories.add(factory);

    String action = replaced ? "replaced" : "added";
    LOG.info("{} CertprofileFactory binding for {}", action, factory);
  } // method registFactory

  public void unregistFactory(CertprofileFactory factory) {
    //might be null if dependency is optional
    if (factory == null) {
      LOG.debug("unregistFactory invoked with null.");
      return;
    }

    if (factories.remove(factory)) {
      LOG.info("removed CertprofileFactory binding for {}", factory);
    } else {
      LOG.info("no CertprofileFactory binding found to remove for '{}'", factory);
    }
  } // method unregistFactory

}

