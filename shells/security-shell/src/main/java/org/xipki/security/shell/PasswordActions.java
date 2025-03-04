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

package org.xipki.security.shell;

import org.apache.karaf.shell.api.action.Command;
import org.apache.karaf.shell.api.action.Completion;
import org.apache.karaf.shell.api.action.Option;
import org.apache.karaf.shell.api.action.lifecycle.Service;
import org.apache.karaf.shell.support.completers.FileCompleter;
import org.xipki.password.OBFPasswordService;
import org.xipki.password.PBEAlgo;
import org.xipki.password.PBEPasswordService;
import org.xipki.security.shell.Actions.SecurityAction;
import org.xipki.shell.IllegalCmdParamException;
import org.xipki.util.Args;
import org.xipki.util.IoUtil;
import org.xipki.util.StringUtil;

/**
 * Security actions to protect the password.
 *
 * @author Lijun Liao
 */

public class PasswordActions {

  @Command(scope = "xi", name = "deobfuscate", description = "deobfuscate password")
  @Service
  public static class Deobfuscate extends SecurityAction {

    @Option(name = "--password", description = "obfuscated password, starts with "
            + OBFPasswordService.PROTOCOL_OBF + ":\n"
            + "exactly one of password and password-file must be specified")
    private String passwordHint;

    @Option(name = "--password-file", description = "file containing the obfuscated password")
    @Completion(FileCompleter.class)
    private String passwordFile;

    @Option(name = "--out", description = "where to save the password")
    @Completion(FileCompleter.class)
    private String outFile;

    @Override
    protected Object execute0() throws Exception {
      if ((passwordHint == null) == (passwordFile == null)) {
        throw new IllegalCmdParamException("exactly one of password and password-file must be specified");
      }

      if (passwordHint == null) {
        passwordHint = StringUtil.toUtf8String(IoUtil.read(passwordFile));
      }

      if (!StringUtil.startsWithIgnoreCase(passwordHint, OBFPasswordService.PROTOCOL_OBF + ":")) {
        throw new IllegalCmdParamException("encrypted password '" + passwordHint + "' does not start with OBF:");
      }

      String password = OBFPasswordService.deobfuscate(passwordHint);
      if (outFile != null) {
        saveVerbose("saved the password to file", outFile, StringUtil.toUtf8Bytes(password));
      } else {
        println("the password is: '" + password + "'");
      }
      return null;
    }

  } // class Deobfuscate

  @Command(scope = "xi", name = "obfuscate", description = "obfuscate password")
  @Service
  public static class Obfuscate extends SecurityAction {

    @Option(name = "--out", description = "where to save the encrypted password")
    @Completion(FileCompleter.class)
    private String outFile;

    @Option(name = "-k", description = "quorum of the password parts")
    private Integer quorum = 1;

    @Override
    protected Object execute0() throws Exception {
      Args.range(quorum, "k", 1, 10);

      char[] password;
      if (quorum == 1) {
        password = readPassword("Password");
      } else {
        char[][] parts = new char[quorum][];
        for (int i = 0; i < quorum; i++) {
          parts[i] = readPassword("Password " + (i + 1) + "/" + quorum);
        }
        password = StringUtil.merge(parts);
      }

      String passwordHint = OBFPasswordService.obfuscate(new String(password));
      if (outFile != null) {
        saveVerbose("saved the obfuscated password to file", outFile, StringUtil.toUtf8Bytes(passwordHint));
      } else {
        println("the obfuscated password is: '" + passwordHint + "'");
      }
      return null;
    }

  } // class Obfuscate

  @Command(scope = "xi", name = "pbe-dec", description = "decrypt password with master password")
  @Service
  public static class PbeDec extends SecurityAction {

    @Option(name = "--password", description = "encrypted password, starts with PBE:\n"
            + "exactly one of password and password-file must be specified")
    private String passwordHint;

    @Option(name = "--password-file", description = "file containing the encrypted password")
    @Completion(FileCompleter.class)
    private String passwordFile;

    @Option(name = "--mpassword-file", description = "file containing the (obfuscated) master password")
    @Completion(FileCompleter.class)
    private String masterPasswordFile;

    @Option(name = "--mk", description = "quorum of the master password parts")
    private Integer mquorum = 1;

    @Option(name = "--out", description = "where to save the password")
    @Completion(FileCompleter.class)
    private String outFile;

    @Override
    protected Object execute0() throws Exception {
      Args.range(mquorum, "mk", 1, 10);
      if ((passwordHint == null) == (passwordFile == null)) {
        throw new IllegalCmdParamException("exactly one of password and password-file must be specified");
      }

      if (passwordHint == null) {
        passwordHint = StringUtil.toUtf8String(IoUtil.read(passwordFile));
      }

      if (!StringUtil.startsWithIgnoreCase(passwordHint, PBEPasswordService.PROTOCOL_PBE + ":")) {
        throw new IllegalCmdParamException("encrypted password '" + passwordHint + "' does not start with PBE:");
      }

      char[] masterPassword;
      if (masterPasswordFile != null) {
        String str = StringUtil.toUtf8String(IoUtil.read(masterPasswordFile));
        if (StringUtil.startsWithIgnoreCase(str, OBFPasswordService.PROTOCOL_OBF + ":")) {
          str = OBFPasswordService.deobfuscate(str);
        }
        masterPassword = str.toCharArray();
      } else {
        if (mquorum == 1) {
          masterPassword = readPassword("Master password");
        } else {
          char[][] parts = new char[mquorum][];
          for (int i = 0; i < mquorum; i++) {
            parts[i] = readPassword("Master password (part " + (i + 1) + "/" + mquorum + ")");
          }
          masterPassword = StringUtil.merge(parts);
        }
      }
      char[] password = PBEPasswordService.decryptPassword(masterPassword, passwordHint);

      if (outFile != null) {
        saveVerbose("saved the password to file", outFile, StringUtil.toUtf8Bytes(new String(password)));
      } else {
        println("the password is: '" + new String(password) + "'");
      }
      return null;
    } // method execute0

  } // class PbeDec

  @Command(scope = "xi", name = "pbe-enc", description = "encrypt password with master password")
  @Service
  public static class PbeEnc extends SecurityAction {

    @Option(name = "--iteration-count", aliases = "-n", description = "iteration count, between 1 and 65535")
    private int iterationCount = 2000;

    @Option(name = "--out", description = "where to save the encrypted password")
    @Completion(FileCompleter.class)
    private String outFile;

    @Option(name = "-k", description = "quorum of the password parts")
    private Integer quorum = 1;

    @Option(name = "--mpassword-file", description = "file containing the (obfuscated) master password")
    @Completion(FileCompleter.class)
    private String masterPasswordFile;

    @Option(name = "--mk", description = "quorum of the master password parts")
    private Integer mquorum = 1;

    @Override
    protected Object execute0() throws Exception {
      Args.range(iterationCount, "iterationCount", 1, 65535);
      Args.range(quorum, "k", 1, 10);
      Args.range(mquorum, "mk", 1, 10);

      char[] masterPassword;
      if (masterPasswordFile != null) {
        String str = StringUtil.toUtf8String(IoUtil.read(masterPasswordFile));
        if (StringUtil.startsWithIgnoreCase(str, OBFPasswordService.PROTOCOL_OBF + ":")) {
          str = OBFPasswordService.deobfuscate(str);
        }
        masterPassword = str.toCharArray();
      } else {
        if (mquorum == 1) {
          masterPassword = readPassword("Master password");
        } else {
          char[][] parts = new char[mquorum][];
          for (int i = 0; i < mquorum; i++) {
            parts[i] = readPassword("Master password (part " + (i + 1) + "/" + mquorum + ")");
          }
          masterPassword = StringUtil.merge(parts);
        }
      }

      char[] password;
      if (quorum == 1) {
        password = readPassword("Password");
      } else {
        char[][] parts = new char[quorum][];
        for (int i = 0; i < quorum; i++) {
          parts[i] = readPassword("Password (part " + (i + 1) + "/" + quorum + ")");
        }
        password = StringUtil.merge(parts);
      }

      String passwordHint = PBEPasswordService.encryptPassword(PBEAlgo.PBEWithHmacSHA256AndAES_256,
          iterationCount, masterPassword, password);
      if (outFile != null) {
        saveVerbose("saved the encrypted password to file", outFile, StringUtil.toUtf8Bytes(passwordHint));
      } else {
        println("the encrypted password is: '" + passwordHint + "'");
      }
      return null;
    } // method execute0

  } // class PbeEnc

}
