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

package org.xipki.ca.server.mgmt;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509CRLHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xipki.audit.AuditLevel;
import org.xipki.audit.AuditStatus;
import org.xipki.audit.Audits;
import org.xipki.audit.PciAuditEvent;
import org.xipki.ca.api.NameId;
import org.xipki.ca.api.mgmt.*;
import org.xipki.ca.api.mgmt.entry.*;
import org.xipki.ca.api.profile.CertprofileFactoryRegister;
import org.xipki.ca.api.publisher.CertPublisherFactoryRegister;
import org.xipki.ca.sdk.CertprofileInfoResponse;
import org.xipki.ca.server.*;
import org.xipki.ca.server.db.CaManagerQueryExecutor;
import org.xipki.ca.server.db.CertStore;
import org.xipki.ca.server.db.CertStore.SystemEvent;
import org.xipki.datasource.DataAccessException;
import org.xipki.datasource.DataSourceConf;
import org.xipki.datasource.DataSourceFactory;
import org.xipki.datasource.DataSourceWrapper;
import org.xipki.license.api.CmLicense;
import org.xipki.password.PasswordResolverException;
import org.xipki.security.*;
import org.xipki.security.pkcs11.P11CryptServiceFactory;
import org.xipki.util.*;
import org.xipki.util.exception.OperationException;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.SocketException;
import java.sql.Connection;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.xipki.util.Args.notNull;
import static org.xipki.util.Args.toNonBlankLower;
import static org.xipki.util.StringUtil.concat;

/**
 * Manages the CA system.
 *
 * @author Lijun Liao
 * @since 2.0.0
 */

public class CaManagerImpl implements CaManager, Closeable {

  private class CertsInQueuePublisher implements Runnable {

    private boolean inProcess;

    @Override
    public void run() {
      if (inProcess || !caSystemSetuped) {
        return;
      }

      inProcess = true;
      try {
        ca2Manager.pulishCertsInQueue();
      } finally {
        inProcess = false;
      }
    } // method run

  } // class CertsInQueuePublisher

  private class CaRestarter implements Runnable {

    private boolean inProcess;

    @Override
    public void run() {
      if (inProcess) {
        return;
      }

      inProcess = true;
      try {
        SystemEvent event = queryExecutor.getSystemEvent(EVENT_CACHAGNE);
        long caChangedTime = (event == null) ? 0 : event.getEventTime();

        LOG.info("check the restart CA system event: changed at={}, lastStartTime={}",
            new Date(caChangedTime * 1000L), lastStartTime);

        if (caChangedTime > lastStartTime.getTime() / 1000L) {
          LOG.info("received event to restart CA");
          restartCaSystem();
        } else {
          LOG.debug("received no event to restart CA");
        }
      } catch (Throwable th) {
        LogUtil.error(LOG, th, "ScheduledCaRestarter");
      } finally {
        inProcess = false;
      }
    } // method run

  } // class CaRestarter

  private static final Logger LOG = LoggerFactory.getLogger(CaManagerImpl.class);

  private static final String version;

  private static final String EVENT_LOCK = "LOCK";

  private static final String EVENT_CACHAGNE = "CA_CHANGE";

  final CaIdNameMap idNameMap = new CaIdNameMap();

  final Map<String, CaInfo> caInfos = new ConcurrentHashMap<>();

  final Map<String, SignerEntryWrapper> signers = new ConcurrentHashMap<>();

  final Map<String, SignerEntry> signerDbEntries = new ConcurrentHashMap<>();

  final Map<String, IdentifiedCertprofile> certprofiles = new ConcurrentHashMap<>();

  final Map<String, CertprofileEntry> certprofileDbEntries = new ConcurrentHashMap<>();

  final Map<String, IdentifiedCertPublisher> publishers = new ConcurrentHashMap<>();

  final Map<String, PublisherEntry> publisherDbEntries = new ConcurrentHashMap<>();

  final Map<String, RequestorEntryWrapper> requestors = new ConcurrentHashMap<>();

  final Map<String, RequestorEntry> requestorDbEntries = new ConcurrentHashMap<>();

  final Map<String, KeypairGenEntryWrapper> keypairGens = new ConcurrentHashMap<>();

  final Map<String, KeypairGenEntry> keypairGenDbEntries = new ConcurrentHashMap<>();

  final Map<String, Set<String>> caHasProfiles = new ConcurrentHashMap<>();

  final Map<String, Set<String>> caHasPublishers = new ConcurrentHashMap<>();

  final Map<String, Set<CaHasRequestorEntry>> caHasRequestors = new ConcurrentHashMap<>();

  final Map<String, Integer> caAliases = new ConcurrentHashMap<>();

  final Map<String, X509Ca> x509cas = new ConcurrentHashMap<>();

  RequestorInfo byCaRequestor;

  boolean masterMode;

  boolean noLock;

  int shardId;

  Map<String, FileOrValue> datasourceNameConfFileMap;

  CaServerConf caServerConf;

  CertprofileFactoryRegister certprofileFactoryRegister;

  CertPublisherFactoryRegister certPublisherFactoryRegister;

  CertStore certstore;

  SecurityFactory securityFactory;

  P11CryptServiceFactory p11CryptServiceFactory;

  CaManagerQueryExecutor queryExecutor;

  private final CmLicense license;

  private DataSourceWrapper datasource;

  private final String lockInstanceId;

  private boolean caLockedByMe;

  private ScheduledThreadPoolExecutor persistentScheduledThreadPoolExecutor;

  private ScheduledThreadPoolExecutor scheduledThreadPoolExecutor;

  private final DataSourceFactory datasourceFactory;

  private CtLogPublicKeyFinder ctLogPublicKeyFinder;

  private boolean caSystemSetuped;

  private Date lastStartTime;

  private boolean initializing;

  private final Ca2Manager ca2Manager;

  private final CertprofileManager certprofileManager;

  private final ConfLoader confLoader;

  private final PublisherManager publisherManager;

  private final RequestorManager requestorManager;

  private final SignerManager signerManager;

  private final KeypairGenManager keypairGenManager;

  static {
    version = StringUtil.getVersion(CaManagerImpl.class);
  }

  public CaManagerImpl(CmLicense license) {
    LOG.info("XiPKI CA version {}", version);

    this.license = Args.notNull(license, "license");
    this.datasourceFactory = new DataSourceFactory();
    String calockId = null;
    File calockFile = new File("calock");
    if (calockFile.exists()) {
      try {
        calockId = StringUtil.toUtf8String(IoUtil.read(calockFile));
      } catch (IOException ex) {
        LOG.error("could not read {}: {}", calockFile.getName(), ex.getMessage());
      }
    }

    if (calockId == null) {
      calockId = UUID.randomUUID().toString();
      try {
        IoUtil.save(calockFile, StringUtil.toUtf8Bytes(calockId));
      } catch (IOException ex) {
        LOG.error("could not save {}: {}", calockFile.getName(), ex.getMessage());
      }
    }

    String hostAddress = null;
    try {
      hostAddress = IoUtil.getHostAddress();
    } catch (SocketException ex) {
      LOG.warn("could not get host address: {}", ex.getMessage());
    }

    this.lockInstanceId = (hostAddress == null) ? calockId : hostAddress + "/" + calockId;

    this.ca2Manager = new Ca2Manager(this);
    this.certprofileManager = new CertprofileManager(this);
    this.confLoader = new ConfLoader(this);
    this.publisherManager = new PublisherManager(this);
    this.requestorManager = new RequestorManager(this);
    this.signerManager = new SignerManager(this);
    this.keypairGenManager = new KeypairGenManager(this);
  } // constructor

  public SecurityFactory getSecurityFactory() {
    return securityFactory;
  }

  public void setSecurityFactory(SecurityFactory securityFactory) {
    this.securityFactory = securityFactory;
  }

  public P11CryptServiceFactory getP11CryptServiceFactory() {
    return p11CryptServiceFactory;
  }

  public void setP11CryptServiceFactory(P11CryptServiceFactory p11CryptServiceFactory) {
    this.p11CryptServiceFactory = p11CryptServiceFactory;
  }

  public boolean isMasterMode() {
    return masterMode;
  }

  @Override
  public Set<String> getSupportedSignerTypes() {
    return securityFactory.getSupportedSignerTypes();
  }

  @Override
  public Set<String> getSupportedCertprofileTypes() {
    return certprofileFactoryRegister.getSupportedTypes();
  }

  @Override
  public Set<String> getSupportedPublisherTypes() {
    return certPublisherFactoryRegister.getSupportedTypes();
  }

  @Override
  public String getTokenInfoP11(String moduleName, Integer slotIndex, boolean verbose) throws CaMgmtException {
    return signerManager.getTokenInfoP11(moduleName, slotIndex, verbose);
  }

  private void init() throws CaMgmtException {
    if (securityFactory == null) {
      throw new IllegalStateException("securityFactory is not set");
    }
    if (datasourceFactory == null) {
      throw new IllegalStateException("datasourceFactory is not set");
    }
    if (certprofileFactoryRegister == null) {
      throw new IllegalStateException("certprofileFactoryRegister is not set");
    }
    if (certPublisherFactoryRegister == null) {
      throw new IllegalStateException("certPublisherFactoryRegister is not set");
    }
    if (caServerConf == null) {
      throw new IllegalStateException("caServerConf is not set");
    }

    masterMode = caServerConf.isMaster();
    LOG.info("ca.masterMode: {}", masterMode);

    noLock = caServerConf.isNoLock();
    LOG.info("ca.noLock: {}", noLock);

    shardId = caServerConf.getShardId();
    LOG.info("ca.shardId: {}", shardId);

    caServerConf.initSsl();

    if (caServerConf.getCtLog() != null) {
      try {
        ctLogPublicKeyFinder = new CtLogPublicKeyFinder(caServerConf.getCtLog());
      } catch (Exception ex) {
        throw new CaMgmtException("could not load CtLogPublicKeyFinder: " + ex.getMessage(), ex);
      }
    }

    if (this.datasourceNameConfFileMap == null) {
      this.datasourceNameConfFileMap = new ConcurrentHashMap<>();
      List<DataSourceConf> datasourceList = caServerConf.getDatasources();
      for (DataSourceConf datasource : datasourceList) {
        String name = datasource.getName();
        FileOrValue conf = datasource.getConf();
        this.datasourceNameConfFileMap.put(name, conf);
        if (conf.getFile() != null) {
          LOG.info("associate datasource {} to the file {}", name, conf.getFile());
        } else {
          LOG.info("associate datasource {} to text value", name);
        }
      }

      FileOrValue caDatasourceConf = datasourceNameConfFileMap.remove("ca");
      if (caDatasourceConf == null) {
        throw new CaMgmtException("no datasource named 'ca' configured");
      }

      this.datasource = loadDatasource("ca", caDatasourceConf);
    }

    this.queryExecutor = new CaManagerQueryExecutor(this.datasource);

    if (masterMode) {
      if (!noLock) {
        lockCa();
      }

      List<String> names = queryExecutor.namesFromTable("REQUESTOR");
      final String[] embeddedNames = {RequestorInfo.NAME_BY_CA};
      for (String embeddedName : embeddedNames) {
        boolean contained = false;
        for (String name : names) {
          if (embeddedName.equalsIgnoreCase(name)) {
            contained = true;
            break;
          }
        }

        if (!contained) {
          queryExecutor.addEmbeddedRequestor(embeddedName);
        }
      }
    }

    final long epoch = DateUtil.parseUtcTimeyyyyMMdd("20100101").getTime();
    UniqueIdGenerator idGen = new UniqueIdGenerator(epoch, shardId);

    boolean initSucc = true;
    try {
      this.certstore = new CertStore(datasource, idGen, securityFactory.getPasswordResolver());
    } catch (DataAccessException ex) {
      initSucc = false;
      LogUtil.error(LOG, ex, "error constructing CertStore");
    }

    try {
      ca2Manager.initCaAliases();
    } catch (CaMgmtException ex) {
      initSucc = false;
      LogUtil.error(LOG, ex, "error initCaAliases");
    }

    try {
      certprofileManager.initCertprofiles();
    } catch (CaMgmtException ex) {
      initSucc = false;
      LogUtil.error(LOG, ex, "error initCertprofiles");
    }

    try {
      publisherManager.initPublishers();
    } catch (CaMgmtException ex) {
      initSucc = false;
      LogUtil.error(LOG, ex, "error initPublishers");
    }

    try {
      requestorManager.initRequestors();
    } catch (CaMgmtException ex) {
      initSucc = false;
      LogUtil.error(LOG, ex, "error initRequestors");
    }

    try {
      signerManager.initSigners();
    } catch (CaMgmtException ex) {
      initSucc = false;
      LogUtil.error(LOG, ex, "error initSigners");
    }

    try {
      keypairGenManager.initKeypairGens();
    } catch (CaMgmtException ex) {
      initSucc = false;
      LogUtil.error(LOG, ex, "error initKeypairGens");
    }

    try {
      ca2Manager.initCas();
    } catch (CaMgmtException ex) {
      initSucc = false;
      LogUtil.error(LOG, ex, "error initCas");
    }

    if (!initSucc) {
      throw new CaMgmtException("error initializing CA system");
    }
  } // method init

  public int getDbSchemaVersion() {
    return queryExecutor.getDbSchemaVersion();
  }

  private DataSourceWrapper loadDatasource(String datasourceName, FileOrValue datasourceConf)
      throws CaMgmtException {
    try {
      DataSourceWrapper datasource = datasourceFactory.createDataSource(
          datasourceName, datasourceConf, securityFactory.getPasswordResolver());

      // test the datasource
      Connection conn = datasource.getConnection();
      datasource.returnConnection(conn);

      LOG.info("loaded datasource.{}", datasourceName);
      return datasource;
    } catch (DataAccessException | PasswordResolverException | IOException | RuntimeException ex) {
      throw new CaMgmtException(concat(ex.getClass().getName(),
          " while parsing datasource ", datasourceName, ": ", ex.getMessage()), ex);
    }
  } // method loadDatasource

  @Override
  public CaSystemStatus getCaSystemStatus() {
    if (caSystemSetuped) {
      return masterMode ? CaSystemStatus.STARTED_AS_MASTER : CaSystemStatus.STARTED_AS_SLAVE;
    } else if (initializing) {
      return CaSystemStatus.INITIALIZING;
    } else if (!caLockedByMe) {
      return CaSystemStatus.LOCK_FAILED;
    } else {
      return CaSystemStatus.ERROR;
    }
  } // method getCaSystemStatus

  private void lockCa() throws CaMgmtException {
    SystemEvent lockInfo = queryExecutor.getSystemEvent(EVENT_LOCK);

    if (lockInfo != null) {
      String lockedBy = lockInfo.getOwner();
      Date lockedAt = new Date(lockInfo.getEventTime() * 1000L);

      if (!this.lockInstanceId.equals(lockedBy)) {
        String msg = concat("could not lock CA, it has been locked by ", lockedBy, " since ",
            lockedAt.toString(),  ". In general this indicates that another CA software in master mode is "
                + "accessing the database or the last shutdown of CA software in master mode is abnormal. "
                + "If you know what you do, you can unlock it executing the ca:unlock command.");
        throw logAndCreateException(msg);
      }

      LOG.info("CA has been locked by me since {}, re-lock it", lockedAt);
    }

    SystemEvent newLockInfo = new SystemEvent(EVENT_LOCK, lockInstanceId, System.currentTimeMillis() / 1000L);
    queryExecutor.changeSystemEvent(newLockInfo);
    caLockedByMe = true;
  } // method lockCa

  @Override
  public void unlockCa() throws CaMgmtException {
    if (!masterMode) {
      throw logAndCreateException("could not unlock CA in slave mode");
    }

    boolean succ = false;
    try {
      queryExecutor.unlockCa();
      LOG.info("unlocked CA");
      succ = true;
    } finally {
      auditLogPciEvent(succ, "UNLOCK");
    }
  } // method unlockCa

  private void reset() {
    caSystemSetuped = false;
    ctLogPublicKeyFinder = null;

    signerManager.reset();
    requestorManager.reset();
    ca2Manager.reset();
    certprofileManager.reset();
    publisherManager.reset();
    keypairGenManager.reset();

    shutdownScheduledThreadPoolExecutor();
  } // method reset

  @Override
  public void restartCa(String name) throws CaMgmtException {
    ca2Manager.restartCa(name);
  }

  @Override
  public void restartCaSystem() throws CaMgmtException {
    reset();
    boolean caSystemStarted = startCaSystem0();
    auditLogPciEvent(caSystemStarted, "CA_CHANGE");

    if (!caSystemStarted) {
      throw logAndCreateException("could not restart CA system");
    }
  } // method restartCaSystem

  @Override
  public void notifyCaChange() throws CaMgmtException {
    try {
      SystemEvent systemEvent = new SystemEvent(EVENT_CACHAGNE, lockInstanceId, System.currentTimeMillis() / 1000L);
      queryExecutor.changeSystemEvent(systemEvent);
      LOG.info("notified the change of CA system");
    } catch (CaMgmtException ex) {
      LogUtil.warn(LOG, ex, "could not notify slave CAs to restart");
      throw ex;
    }
  } // method notifyCaChange

  @Override
  public void addDbSchema(String name, String value) throws CaMgmtException {
    checkModificationOfDbSchema(name);
    queryExecutor.addDbSchema(name, value);
    try {
      certstore.updateDbInfo(securityFactory.getPasswordResolver());
    } catch (DataAccessException ex) {
      throw new CaMgmtException(ex);
    }
  }

  @Override
  public void changeDbSchema(String name, String value) throws CaMgmtException {
    checkModificationOfDbSchema(name);
    queryExecutor.changeDbSchema(name, value);
    try {
      certstore.updateDbInfo(securityFactory.getPasswordResolver());
    } catch (DataAccessException ex) {
      throw new CaMgmtException(ex);
    }
  }

  @Override
  public void removeDbSchema(String name) throws CaMgmtException {
    checkModificationOfDbSchema(name);
    queryExecutor.removeDbSchema(name);
    try {
      certstore.updateDbInfo(securityFactory.getPasswordResolver());
    } catch (DataAccessException ex) {
      throw new CaMgmtException(ex);
    }
  }

  @Override
  public Map<String, String> getDbSchemas() throws CaMgmtException {
    Map<String, String> all = queryExecutor.getDbSchemas();
    Map<String, String> noReserved = new HashMap<>(all.size() * 5 / 4);
    for (Entry<String, String> entry : all.entrySet()) {
      switch (entry.getKey()) {
        case "VERSION":
        case "VENDOR":
        case "X500NAME_MAXLEN":
          break;
        default:
          noReserved.put(entry.getKey(), entry.getValue());
      }
    }
    return noReserved;
  }

  private static void checkModificationOfDbSchema(String name) throws CaMgmtException {
    if (StringUtil.orEqualsIgnoreCase(name, "VERSION", "VENDOR", "X500NAME_MAXLEN")) {
      throw new CaMgmtException("modification of reserved DBSCHEMA " + name + " is not allowed");
    }
  }

  public void startCaSystem() {
    boolean caSystemStarted = false;
    try {
      caSystemStarted = startCaSystem0();
    } catch (Throwable th) {
      LogUtil.error(LOG, th, "could not start CA system");
    }

    if (!caSystemStarted) {
      LOG.error("could not start CA system");
    }

    auditLogPciEvent(caSystemStarted, "START");
  } // method startCaSystem

  private boolean startCaSystem0() {
    if (caSystemSetuped) {
      return true;
    }

    initializing = true;
    shutdownScheduledThreadPoolExecutor();

    try {
      LOG.info("starting CA system");
      try {
        init();
      } catch (Exception ex) {
        LogUtil.error(LOG, ex, "error initializing CA system");
        return false;
      }

      this.lastStartTime = new Date();

      x509cas.clear();

      scheduledThreadPoolExecutor = new ScheduledThreadPoolExecutor(10);
      scheduledThreadPoolExecutor.setRemoveOnCancelPolicy(true);

      List<String> failedCaNames = new LinkedList<>();

      // Add the CAs to the store
      for (Entry<String, CaInfo> entry : caInfos.entrySet()) {
        String caName = entry.getKey();
        CaStatus status = entry.getValue().getStatus();
        if (CaStatus.ACTIVE != status) {
          continue;
        }

        if (ca2Manager.startCa(caName)) {
          LOG.info("started CA {}", caName);
        } else {
          failedCaNames.add(caName);
          LOG.error("could not start CA {}", caName);
        }
      }

      caSystemSetuped = true;
      StringBuilder sb = new StringBuilder();
      sb.append("started CA system");

      Set<String> caAliasNames = getCaAliasNames();
      Set<String> names = new HashSet<>(getCaNames());

      if (names.size() > 0) {
        sb.append(" with following CAs: ");
        for (String aliasName : caAliasNames) {
          String name = getCaNameForAlias(aliasName);
          if (name != null) {
            names.remove(name);
            sb.append(name).append(" (alias ").append(aliasName).append("), ");
          }
        }

        for (String name : names) {
          sb.append(name).append(", ");
        }

        int len = sb.length();
        sb.delete(len - 2, len);

        scheduledThreadPoolExecutor.scheduleAtFixedRate(new CertsInQueuePublisher(), 120, 120, SECONDS);
      } else {
        sb.append(": no CA is configured");
      }

      if (!failedCaNames.isEmpty()) {
        sb.append(", and following CAs could not be started: ");
        for (String aliasName : caAliasNames) {
          String name = getCaNameForAlias(aliasName);
          if (failedCaNames.remove(name)) {
            sb.append(name).append(" (alias ").append(aliasName).append("), ");
          }
        }

        for (String name : failedCaNames) {
          sb.append(name).append(", ");
        }

        int len = sb.length();
        sb.delete(len - 2, len);
      }

      LOG.info("{}", sb);
    } finally {
      initializing = false;
      if (!masterMode && persistentScheduledThreadPoolExecutor == null) {
        persistentScheduledThreadPoolExecutor = new ScheduledThreadPoolExecutor(1);
        persistentScheduledThreadPoolExecutor.setRemoveOnCancelPolicy(true);
        persistentScheduledThreadPoolExecutor.scheduleAtFixedRate(new CaRestarter(), 300, 300, SECONDS);
      }
    }

    return true;
  } // method startCaSystem0

  @Override
  public void close() {
    LOG.info("stopping CA system");
    shutdownScheduledThreadPoolExecutor();

    if (persistentScheduledThreadPoolExecutor != null) {
      persistentScheduledThreadPoolExecutor.shutdown();
      while (!persistentScheduledThreadPoolExecutor.isTerminated()) {
        try {
          Thread.sleep(100);
        } catch (InterruptedException ex) {
          LOG.error("interrupted: {}", ex.getMessage());
        }
      }
      persistentScheduledThreadPoolExecutor = null;
    }

    ca2Manager.close();

    if (caLockedByMe) {
      try {
        unlockCa();
      } catch (Throwable th) {
        LogUtil.error(LOG, th, "could not unlock CA system");
      }
    }

    if (datasource != null) {
      try {
        datasource.close();
      } catch (Exception ex) {
        LogUtil.warn(LOG, ex, concat("could not close datasource ca"));
      }
    }

    publisherManager.close();
    certprofileManager.close();

    File caLockFile = new File("calock");
    if (caLockFile.exists()) {
      caLockFile.delete();
    }

    auditLogPciEvent(true, "SHUTDOWN");
    LOG.info("stopped CA system");
    try {
      Audits.getAuditService().close();
    } catch (Exception ex) {
      LogUtil.warn(LOG, ex, concat("could not close audit service"));
    }
  } // method close

  public ScheduledThreadPoolExecutor getScheduledThreadPoolExecutor() {
    return scheduledThreadPoolExecutor;
  }

  @Override
  public Set<String> getCertprofileNames() {
    return certprofileDbEntries.keySet();
  }

  @Override
  public Set<String> getKeypairGenNames() {
    return keypairGenDbEntries.keySet();
  }

  @Override
  public Set<String> getPublisherNames() {
    return publisherDbEntries.keySet();
  }

  @Override
  public Set<String> getRequestorNames() {
    return requestorDbEntries.keySet();
  }

  @Override
  public Set<String> getSignerNames() {
    return signerDbEntries.keySet();
  }

  @Override
  public Set<String> getCaNames() {
    return caInfos.keySet();
  }

  @Override
  public Set<String> getSuccessfulCaNames() {
    return ca2Manager.getSuccessfulCaNames();
  }

  @Override
  public Set<String> getFailedCaNames() {
    return ca2Manager.getFailedCaNames();
  }

  @Override
  public Set<String> getInactiveCaNames() {
    return ca2Manager.getInactiveCaNames();
  }

  public void commitNextCrlNo(NameId ca, long nextCrlNo) throws OperationException {
    ca2Manager.commitNextCrlNo(ca, nextCrlNo);
  }

  @Override
  public void addCa(CaEntry caEntry) throws CaMgmtException {
    ca2Manager.addCa(caEntry);
  }

  @Override
  public CaEntry getCa(String name) {
    CaInfo caInfo = caInfos.get(toNonBlankLower(name, "name"));
    return (caInfo == null) ? null : caInfo.getCaEntry();
  }

  @Override
  public void changeCa(ChangeCaEntry entry) throws CaMgmtException {
    ca2Manager.changeCa(entry);
  }

  @Override
  public void removeCertprofileFromCa(String profileName, String caName) throws CaMgmtException {
    certprofileManager.removeCertprofileFromCa(profileName, caName);
  }

  @Override
  public void addCertprofileToCa(String profileName, String caName) throws CaMgmtException {
    certprofileManager.addCertprofileToCa(profileName, caName);
  }

  @Override
  public void removePublisherFromCa(String publisherName, String caName) throws CaMgmtException {
    publisherManager.removePublisherFromCa(publisherName, caName);
  }

  @Override
  public void addPublisherToCa(String publisherName, String caName) throws CaMgmtException {
    publisherManager.addPublisherToCa(publisherName, caName);
  }

  @Override
  public Set<String> getCertprofilesForCa(String caName) {
    return caHasProfiles.get(toNonBlankLower(caName, "caName"));
  }

  @Override
  public Set<CaHasRequestorEntry> getRequestorsForCa(String caName) {
    return caHasRequestors.get(toNonBlankLower(caName, "caName"));
  }

  @Override
  public RequestorEntry getRequestor(String name) {
    return requestorDbEntries.get(toNonBlankLower(name, "name"));
  }

  public RequestorEntryWrapper getRequestorWrapper(String name) {
    return requestors.get(toNonBlankLower(name, "name"));
  }

  @Override
  public void addRequestor(RequestorEntry requestorEntry) throws CaMgmtException {
    requestorManager.addRequestor(requestorEntry);
  }

  @Override
  public void removeRequestor(String name) throws CaMgmtException {
    requestorManager.removeRequestor(name);
  }

  @Override
  public void changeRequestor(String name, String type, String conf) throws CaMgmtException {
    requestorManager.changeRequestor(name, type, conf);
  }

  @Override
  public void removeRequestorFromCa(String requestorName, String caName) throws CaMgmtException {
    requestorManager.removeRequestorFromCa(requestorName, caName);
  }

  @Override
  public void addRequestorToCa(CaHasRequestorEntry requestor, String caName) throws CaMgmtException {
    requestorManager.addRequestorToCa(requestor, caName);
  }

  @Override
  public CertprofileEntry getCertprofile(String name) {
    return certprofileDbEntries.get(name.toLowerCase());
  }

  @Override
  public void removeCertprofile(String name) throws CaMgmtException {
    certprofileManager.removeCertprofile(name);
  }

  @Override
  public void changeCertprofile(String name, String type, String conf) throws CaMgmtException {
    certprofileManager.changeCertprofile(name, type, conf);
  }

  @Override
  public void addCertprofile(CertprofileEntry certprofileEntry) throws CaMgmtException {
    certprofileManager.addCertprofile(certprofileEntry);
  }

  public CertprofileInfoResponse getCertprofileInfo(String profileName) throws OperationException {
    return certprofileManager.getCertprofileInfo(profileName);
  }

  @Override
  public KeypairGenEntry getKeypairGen(String name) throws CaMgmtException {
    return keypairGenDbEntries.get(name);
  }

  @Override
  public void removeKeypairGen(String name) throws CaMgmtException {
    keypairGenManager.removeKeypairGen(name);
  }

  @Override
  public void changeKeypairGen(String name, String type, String conf) throws CaMgmtException {
    keypairGenManager.changeKeypairGen(name, type, conf);
  }

  @Override
  public void addKeypairGen(KeypairGenEntry keypairGenEntry) throws CaMgmtException {
    keypairGenManager.addKeypairGen(keypairGenEntry);
  }

  @Override
  public void addSigner(SignerEntry signerEntry) throws CaMgmtException {
    signerManager.addSigner(signerEntry);
  }

  @Override
  public void removeSigner(String name) throws CaMgmtException {
    signerManager.removeSigner(name);
  }

  @Override
  public void changeSigner(String name, String type, String conf, String base64Cert) throws CaMgmtException {
    signerManager.changeSigner(name, type, conf, base64Cert);
  }

  @Override
  public SignerEntry getSigner(String name) {
    return signerDbEntries.get(toNonBlankLower(name, "name"));
  }

  public SignerEntryWrapper getSignerWrapper(String name) {
    return signers.get(toNonBlankLower(name, "name"));
  }

  @Override
  public void addPublisher(PublisherEntry entry) throws CaMgmtException {
    publisherManager.addPublisher(entry);
  }

  @Override
  public List<PublisherEntry> getPublishersForCa(String caName) {
    return publisherManager.getPublishersForCa(caName);
  }

  @Override
  public PublisherEntry getPublisher(String name) {
    return publisherDbEntries.get(toNonBlankLower(name, "name"));
  }

  @Override
  public void removePublisher(String name) throws CaMgmtException {
    publisherManager.removePublisher(name);
  }

  @Override
  public void changePublisher(String name, String type, String conf) throws CaMgmtException {
    publisherManager.changePublisher(name, type, conf);
  }

  public void setCaServerConf(CaServerConf caServerConf) {
    this.caServerConf = notNull(caServerConf, "caServerConf");
  }

  @Override
  public void addCaAlias(String aliasName, String caName) throws CaMgmtException {
    ca2Manager.addCaAlias(aliasName, caName);
  }

  @Override
  public void removeCaAlias(String name) throws CaMgmtException {
    ca2Manager.removeCaAlias(name);
  }

  @Override
  public String getCaNameForAlias(String aliasName) {
    return ca2Manager.getCaNameForAlias(aliasName);
  }

  @Override
  public Set<String> getAliasesForCa(String caName) {
    return ca2Manager.getAliasesForCa(caName);
  }

  @Override
  public Set<String> getCaAliasNames() {
    return caAliases.keySet();
  }

  @Override
  public void removeCa(String name) throws CaMgmtException {
    ca2Manager.removeCa(name);
  }

  @Override
  public void republishCertificates(String caName, List<String> publisherNames, int numThreads)
      throws CaMgmtException {
    publisherManager.republishCertificates(caName, publisherNames, numThreads);
  }

  @Override
  public void revokeCa(String caName, CertRevocationInfo revocationInfo) throws CaMgmtException {
    ca2Manager.revokeCa(caName, revocationInfo);
  }

  @Override
  public void unrevokeCa(String caName) throws CaMgmtException {
    ca2Manager.unrevokeCa(caName);
  }

  public void setCertprofileFactoryRegister(CertprofileFactoryRegister register) {
    this.certprofileFactoryRegister = register;
  }

  public void setCertPublisherFactoryRegister(CertPublisherFactoryRegister register) {
    this.certPublisherFactoryRegister = register;
  }

  static void auditLogPciEvent(boolean successful, String eventType) {
    PciAuditEvent event = new PciAuditEvent(new Date());
    event.setUserId("CA-SYSTEM");
    event.setEventType(eventType);
    event.setAffectedResource("CORE");
    event.setStatus((successful ? AuditStatus.SUCCESSFUL : AuditStatus.FAILED).name());
    event.setLevel(successful ? AuditLevel.INFO : AuditLevel.ERROR);
    Audits.getAuditService().logEvent(event);
  }

  @Override
  public void clearPublishQueue(String caName, List<String> publisherNames) throws CaMgmtException {
    ca2Manager.clearPublishQueue(caName, publisherNames);
  }

  private void shutdownScheduledThreadPoolExecutor() {
    if (scheduledThreadPoolExecutor == null) {
      return;
    }

    scheduledThreadPoolExecutor.shutdown();
    scheduledThreadPoolExecutor = null;
  } // method shutdownScheduledThreadPoolExecutor

  @Override
  public void revokeCertificate(String caName, BigInteger serialNumber, CrlReason reason, Date invalidityTime)
      throws CaMgmtException {
    ca2Manager.revokeCertificate(caName, serialNumber, reason, invalidityTime);
  }

  @Override
  public void unsuspendCertificate(String caName, BigInteger serialNumber) throws CaMgmtException {
    ca2Manager.unsuspendCertificate(caName, serialNumber);
  }

  @Override
  public void removeCertificate(String caName, BigInteger serialNumber) throws CaMgmtException {
    ca2Manager.removeCertificate(caName, serialNumber);
  }

  @Override
  public X509Cert generateCertificate(
      String caName, String profileName, byte[] encodedCsr, Date notBefore, Date notAfter)
      throws CaMgmtException {
    return ca2Manager.generateCertificate(caName, profileName, encodedCsr, notBefore, notAfter);
  }

  @Override
  public X509Cert generateCrossCertificate(
      String caName, String profileName, byte[] encodedCsr, byte[] encodedTargetCert, Date notBefore, Date notAfter)
      throws CaMgmtException {
    return ca2Manager.generateCrossCertificate(caName, profileName, encodedCsr, encodedTargetCert, notBefore, notAfter);
  }

  @Override
  public KeyCertBytesPair generateKeyCert(
      String caName, String profileName, String subject, Date notBefore, Date notAfter)
      throws CaMgmtException {
    return ca2Manager.generateKeyCert(caName, profileName, subject, notBefore, notAfter);
  }

  public X509Ca getX509Ca(String name) throws CaMgmtException {
    return ca2Manager.getX509Ca(name);
  }

  public KeypairGenerator getKeypairGenerator(String keypairGenName) {
    keypairGenName = toNonBlankLower(keypairGenName, "keypairGenName");
    KeypairGenEntryWrapper keypairGen = keypairGens.get(keypairGenName);
    return keypairGen == null ? null : keypairGen.getGenerator();
  }

  public IdentifiedCertprofile getIdentifiedCertprofile(String profileName) {
    return certprofiles.get(toNonBlankLower(profileName, "profileName"));
  }

  public List<IdentifiedCertPublisher> getIdentifiedPublishersForCa(String caName) {
    return publisherManager.getIdentifiedPublishersForCa(caName);
  }

  @Override
  public X509Cert generateRootCa(
      CaEntry caEntry, String profileName, String subject, String serialNumber, Date notBefore, Date notAfter)
      throws CaMgmtException {
    return ca2Manager.generateRootCa(caEntry, profileName, subject, serialNumber, notBefore, notAfter);
  }

  void assertMasterMode() throws CaMgmtException {
    if (!masterMode) {
      throw new CaMgmtException("operation not allowed in slave mode");
    }
  }

  void assertMasterModeAndSetuped() throws CaMgmtException {
    assertMasterMode();
    if (!caSystemSetuped) {
      throw new CaMgmtException("CA system is not initialized yet.");
    }
  }

  public SignerEntryWrapper createSigner(SignerEntry entry) throws CaMgmtException {
    return signerManager.createSigner(entry);
  }

  public IdentifiedCertprofile createCertprofile(CertprofileEntry entry) throws CaMgmtException {
    return certprofileManager.createCertprofile(entry);
  }

  public IdentifiedCertPublisher createPublisher(PublisherEntry entry) throws CaMgmtException {
    return publisherManager.createPublisher(entry);
  }

  public KeypairGenEntryWrapper createKeypairGenerator(KeypairGenEntry entry) throws CaMgmtException {
    return keypairGenManager.createKeypairGen(entry);
  }

  public CaIdNameMap idNameMap() {
    return idNameMap;
  }

  @Override
  public X509CRLHolder generateCrlOnDemand(String caName) throws CaMgmtException {
    return ca2Manager.generateCrlOnDemand(caName);
  }

  @Override
  public X509CRLHolder getCrl(String caName, BigInteger crlNumber) throws CaMgmtException {
    return ca2Manager.getCrl(caName, crlNumber);
  }

  @Override
  public X509CRLHolder getCurrentCrl(String caName) throws CaMgmtException {
    return ca2Manager.getCurrentCrl(caName);
  }

  @Override
  public CertWithRevocationInfo getCert(String caName, BigInteger serialNumber) throws CaMgmtException {
    return ca2Manager.getCert(caName, serialNumber);
  }

  @Override
  public CertWithRevocationInfo getCert(X500Name issuer, BigInteger serialNumber) throws CaMgmtException {
    return ca2Manager.getCert(issuer, serialNumber);
  }

  @Override
  public List<CertListInfo> listCertificates(
      String caName, X500Name subjectPattern, Date validFrom, Date validTo, CertListOrderBy orderBy, int numEntries)
      throws CaMgmtException {
    return ca2Manager.listCertificates(caName, subjectPattern, validFrom, validTo, orderBy, numEntries);
  }

  @Override
  public void refreshTokenForSignerType(String signerType) throws CaMgmtException {
    try {
      securityFactory.refreshTokenForSignerType(signerType);
    } catch (XiSecurityException ex) {
      throw new CaMgmtException("could not refresh token for signer type " + signerType + ": " + ex.getMessage(), ex);
    }
  }

  @Override
  public Map<String, X509Cert> loadConf(InputStream zippedConfStream) throws CaMgmtException {
    return confLoader.loadConf(zippedConfStream);
  }

  @Override
  public InputStream exportConf(List<String> caNames) throws CaMgmtException, IOException {
    return confLoader.exportConf(caNames);
  }

  public CtLogPublicKeyFinder getCtLogPublicKeyFinder() {
    return ctLogPublicKeyFinder;
  }

  public CmLicense getLicense() {
    return license;
  }

  CaMgmtException logAndCreateException(String msg) {
    LOG.error(msg);
    return new CaMgmtException(msg);
  }

}
