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

import org.xipki.util.LruCache;
import org.xipki.util.StringUtil;

import java.util.regex.Pattern;

/**
 * Text validator.
 *
 * @author Lijun Liao
 */

public abstract class TextVadidator {

  private static class RegexValidator extends TextVadidator {

    private final Pattern pattern;

    RegexValidator(String regex) {
      this.pattern = Pattern.compile(regex);
    }

    @Override
    public boolean isValid(String value) {
      return pattern.matcher(value).matches();
    }

    @Override
    public String pattern() {
      return pattern.pattern();
    }

  } // class RegexValidator

  private static class FQDNValidator extends TextVadidator {

    @Override
    public boolean isValid(String value) {
      return DomainValidator.getInstance().isValid(value);
    }

    @Override
    public String pattern() {
      return ":FQDN";
    }

  } // class FQDNValidator

  public static final TextVadidator COUNTRY = new RegexValidator("[A-Za-z]{2}");

  public static final TextVadidator NUMBER = new RegexValidator("[\\d]{1,}");

  public static final TextVadidator DATE_OF_BIRTH =
      new RegexValidator("^(19|20)\\d\\d(0[1-9]|1[012])(0[1-9]|[12][0-9]|3[01])120000Z");

  public static final TextVadidator GENDER = new RegexValidator("M|m|F|f");

  public static final TextVadidator FQDN = new FQDNValidator();

  private static final LruCache<String, TextVadidator> cache = new LruCache<>(200);

  private TextVadidator() {
  }

  public abstract boolean isValid(String value);

  public abstract String pattern();

  public static TextVadidator compile(String regex) {
    if (StringUtil.orEqualsIgnoreCase(regex, ":COUNTRY", "COUNTRY")) {
      return COUNTRY;
    } else if (StringUtil.orEqualsIgnoreCase(regex, ":DATE_OF_BIRTH", "DATE_OF_BIRTH")) {
      return DATE_OF_BIRTH;
    } else if (StringUtil.orEqualsIgnoreCase(regex, ":FQDN", "FQDN")) {
      return FQDN;
    } else if (StringUtil.orEqualsIgnoreCase(regex, ":GENDER", "GENDER")) {
      return GENDER;
    } else if (StringUtil.orEqualsIgnoreCase(regex, ":NUMBER", "NUMBER")) {
      return NUMBER;
    } else {
      TextVadidator validator = cache.get(regex);
      if (validator == null) {
        validator = new RegexValidator(regex);
        cache.put(regex, validator);
      }
      return validator;
    }
  } // method compile

}
