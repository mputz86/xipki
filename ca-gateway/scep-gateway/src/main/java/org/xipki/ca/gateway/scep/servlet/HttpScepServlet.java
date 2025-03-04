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

package org.xipki.ca.gateway.scep.servlet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xipki.ca.gateway.RestResponse;
import org.xipki.ca.gateway.scep.ScepResponder;
import org.xipki.ca.gateway.servlet.HttpRequestMetadataRetrieverImpl;
import org.xipki.ca.gateway.servlet.ServletHelper;
import org.xipki.util.Args;
import org.xipki.util.Base64;
import org.xipki.util.IoUtil;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * SCEP servlet.
 *
 * <p>URL http://host:port/scep/&lt;name&gt;/&lt;profile-alias&gt;/pkiclient.exe
 *
 * @author Lijun Liao
 * @since 6.0.0
 */

public class HttpScepServlet extends HttpServlet {

  private static final Logger LOG = LoggerFactory.getLogger(HttpScepServlet.class);

  private boolean logReqResp;

  private ScepResponder responder;

  public void setLogReqResp(boolean logReqResp) {
    this.logReqResp = logReqResp;
  }

  public void setResponder(ScepResponder responder) {
    this.responder = Args.notNull(responder, "responder");
  }

  @Override
  protected void doGet(HttpServletRequest req, HttpServletResponse resp)
      throws ServletException, IOException {
    service0(req, resp, false);
  }

  @Override
  protected void doPost(HttpServletRequest req, HttpServletResponse resp)
      throws ServletException, IOException {
    service0(req, resp, true);
  }

  private void service0(HttpServletRequest req, HttpServletResponse resp, boolean viaPost)
      throws IOException {
    String path = req.getServletPath();

    byte[] requestBytes = viaPost ? IoUtil.read(req.getInputStream())
        : Base64.decode(req.getParameter("message"));

    RestResponse restResp = responder.service(path, requestBytes, new HttpRequestMetadataRetrieverImpl(req));
    restResp.fillResponse(resp);

    ServletHelper.logReqResp("SCEP Gateway", LOG, logReqResp, viaPost, req, requestBytes, restResp.getBody());
  }

}
