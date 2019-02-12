/**
 * Copyright (c) 2010, Sebastian Sdorra
 * All rights reserved.
 * <p>
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * <p>
 * 1. Redistributions of source code must retain the above copyright notice,
 * this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 * 3. Neither the name of SCM-Manager; nor the names of its
 * contributors may be used to endorse or promote products derived from this
 * software without specific prior written permission.
 * <p>
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED.  IN NO EVENT SHALL THE REGENTS OR CONTRIBUTORS BE LIABLE FOR ANY
 * DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 * <p>
 * http://bitbucket.org/sdorra/scm-manager
 */


package sonia.scm.auth.ldap;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import sonia.scm.web.security.AuthenticationResult;
import sonia.scm.web.security.AuthenticationState;

import javax.validation.Valid;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;

@Singleton
@Path("v2/config/ldap")
public class LDAPConfigResource {

  private final LDAPAuthenticationHandler authenticationHandler;
  private final LDAPConfigMapper mapper;

  @Inject
  public LDAPConfigResource(LDAPAuthenticationHandler authenticationHandler, LDAPConfigMapper mapper) {
    this.authenticationHandler = authenticationHandler;
    this.mapper = mapper;
  }

  @POST
  @Path("test")
  @Consumes({MediaType.APPLICATION_JSON})
  @Produces({MediaType.APPLICATION_JSON})
  public LDAPAuthenticationState testConfig(LDAPTestConfig testConfig) {
    LDAPConfig config = testConfig.getConfig();
    LDAPAuthenticationContext context = new LDAPAuthenticationContext(config);
    AuthenticationResult ar = context.authenticate(testConfig.getUsername(),
      testConfig.getPassword());
    LDAPAuthenticationState state = context.getState();

    if ((ar != null) && (ar.getState() == AuthenticationState.SUCCESS)) {
      state.setUser(ar.getUser());
      state.setGroups(ar.getGroups());
    }

    return state;
  }

  @GET
  @Produces(MediaType.APPLICATION_JSON)
  public LDAPConfigDto getConfig() {
    return mapper.map(authenticationHandler.getConfig());
  }

  @PUT
  @Consumes(MediaType.APPLICATION_JSON)
  public Response setConfig(@Context UriInfo uriInfo, @Valid LDAPConfigDto config) {
    LDAPConfig newConfig = mapper.map(config, authenticationHandler.getConfig());
    authenticationHandler.setConfig(newConfig);
    authenticationHandler.storeConfig();

    return Response.noContent().build();
  }
}
