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

import org.xipki.ca.api.mgmt.entry.KeypairGenEntry;
import org.xipki.ca.server.keypool.KeypoolKeypairGenerator;
import org.xipki.security.KeypairGenerator;
import org.xipki.security.SecurityFactory;
import org.xipki.security.XiSecurityException;
import org.xipki.util.FileOrValue;
import org.xipki.util.exception.ObjectCreationException;

import java.util.Map;

import static org.xipki.util.Args.notNull;

/**
 * Wrapper of keypair generation database entry.
 *
 * @author Lijun Liao
 * @since 6.0.0
 */
public class KeypairGenEntryWrapper {

  private KeypairGenEntry dbEntry;

  private KeypairGenerator generator;

  public KeypairGenEntryWrapper() {
  }

  public void setDbEntry(KeypairGenEntry dbEntry) {
    this.dbEntry = notNull(dbEntry, "dbEntry");
  }

  public void init(SecurityFactory securityFactory, int shardId, Map<String, FileOrValue> datasourceConfs)
      throws ObjectCreationException {
    notNull(securityFactory, "securityFactory");
    dbEntry.setFaulty(true);
    if ("KEYPOOL".equalsIgnoreCase(dbEntry.getType())) {
      generator = new KeypoolKeypairGenerator();

      ((KeypoolKeypairGenerator) generator).setShardId(shardId);
      ((KeypoolKeypairGenerator) generator).setDatasourceConfs(datasourceConfs);

      try {
        generator.initialize(dbEntry.getConf(), securityFactory.getPasswordResolver());
      } catch (XiSecurityException ex) {
        throw new ObjectCreationException("error initializing keypair generator " + dbEntry.getName(), ex);
      }
    } else {
      generator = securityFactory.createKeypairGenerator(dbEntry.getType(), dbEntry.getConf());
    }

    generator.setName(dbEntry.getName());
    dbEntry.setFaulty(false);
  }

  public KeypairGenEntry getDbEntry() {
    return dbEntry;
  }

  public KeypairGenerator getGenerator() {
    return generator;
  }

  public boolean isHealthy() {
    return generator != null && generator.isHealthy();
  }

}
