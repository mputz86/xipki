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

package org.xipki.security.util;

import org.xipki.security.X509Cert;

import java.io.IOException;

/**
 * Interface to retrieve the metadata of HTTP request.
 *
 * @author Lijun Liao
 * @since 3.0.1
 */

public interface HttpRequestMetadataRetriever {

  String getHeader(String headerName);

  String getParameter(String paramName);

  X509Cert getTlsClientCert() throws IOException;

}
