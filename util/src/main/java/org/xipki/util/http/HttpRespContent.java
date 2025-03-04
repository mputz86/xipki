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

package org.xipki.util.http;

/**
 * HTTP response.
 *
 * @author Lijun Liao
 */

public class HttpRespContent {

  private final String contentType;

  private final byte[] content;

  private final boolean base64;

  private final int statusCode;

  private HttpRespContent(int statusCode, String contentType, boolean base64, byte[] content) {
    this.contentType = contentType;
    this.content = content;
    this.base64 = base64;
    this.statusCode = statusCode;
  }

  public static HttpRespContent ofOk(String contentType, byte[] content) {
    return new HttpRespContent(200, contentType, false, content);
  }

  public static HttpRespContent ofOk(String contentType, boolean base64, byte[] content) {
    return new HttpRespContent(200, contentType, base64, content);
  }

  public static HttpRespContent ofError(int statusCode, String contentType, byte[] content) {
    return new HttpRespContent(statusCode, contentType, false, content);
  }

  public static HttpRespContent ofError(int statusCode, String contentType, boolean base64, byte[] content) {
    return new HttpRespContent(statusCode, contentType, base64, content);
  }

  public boolean isOK() {
    return statusCode == 200;
  }

  public int getStatusCode() {
    return statusCode;
  }

  public String getContentType() {
    return contentType;
  }

  public boolean isBase64() {
    return base64;
  }

  public byte[] getContent() {
    return content;
  }
}
