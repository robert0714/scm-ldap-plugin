/*
 * MIT License
 *
 * Copyright (c) 2020-present Cloudogu GmbH and Contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package sonia.scm.auth.ldap;

//~--- non-JDK imports --------------------------------------------------------

import com.google.common.annotations.VisibleForTesting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sonia.scm.util.Util;

import javax.naming.Context;
import javax.naming.NamingException;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;
import javax.naming.ldap.InitialLdapContext;
import javax.naming.ldap.LdapContext;
import javax.naming.ldap.StartTlsRequest;
import javax.naming.ldap.StartTlsResponse;
import javax.net.ssl.SSLContext;
import java.io.Closeable;
import java.io.IOException;
import java.util.Hashtable;
import java.util.Optional;

//~--- JDK imports ------------------------------------------------------------

/**
 * @author Sebastian Sdorra
 */
class LdapConnection implements Closeable {

  /**
   * property for ldap connect timeout
   */
  private static final String PROPERTY_TIMEOUT_CONNECT =
    "com.sun.jndi.ldap.connect.timeout";

  /**
   * property for ldap read timeout
   */
  private static final String PROPERTY_TIMEOUT_READ =
    "com.sun.jndi.ldap.read.timeout";

  private static final String PROPERTY_SSL_SOCKET_FACTORY = "java.naming.ldap.factory.socket";

  /**
   * connect timeout: 2min
   */
  private static final String TIMEOUT_CONNECT = Optional.ofNullable(System.getenv("TIMEOUT_CONNECT"))
				.orElse(Optional.ofNullable(System.getProperty("TIMEOUT_CONNECT")).orElse("120000"));

  /**
   * read timeout: 6min
   */
  private static final String TIMEOUT_READ = Optional.ofNullable(System.getenv("TIMEOUT_READ"))
				.orElse(Optional.ofNullable(System.getProperty("TIMEOUT_READ")).orElse("720000")); ;

  /**
   * the logger for LDAPConnection
   */
  private static final Logger logger =
    LoggerFactory.getLogger(LdapConnection.class);

  private final LdapContext context;
  private StartTlsResponse tls;
  private final SSLContext sslContext;

  @VisibleForTesting
  LdapConnection(LdapConfig config, SSLContext sslContext, String userDN, String password) throws NamingException, IOException {
    this.sslContext = sslContext;
    ThreadLocalSocketFactory.setDelegate(sslContext.getSocketFactory());
    // JNDI uses the context classloader to instantiate the socket factory
    ClassLoader classLoader = Thread.currentThread().getContextClassLoader();

    Thread.currentThread().setContextClassLoader(LdapConnection.class.getClassLoader());
    try {
      context = new InitialLdapContext(createConnectionProperties(config, userDN, password), null);

      if (config.isEnableStartTls()) {
        startTLS(config, userDN, password);
      }
    } finally {
      Thread.currentThread().setContextClassLoader(classLoader);
    }
  }

  private void startTLS(LdapConfig config, String userDN, String password) throws NamingException, IOException {
    logger.debug("send starttls request");

    tls = (StartTlsResponse) context.extendedOperation(new StartTlsRequest());

    if (sslContext != null) {
      tls.negotiate(sslContext.getSocketFactory());
    } else {
      tls.negotiate();
    }

    // authenticate after bind
    if (userDN != null) {
      logger.debug("set bind credentials for dn {}", userDN);

      context.addToEnvironment(Context.SECURITY_AUTHENTICATION, "simple");
      context.addToEnvironment(Context.SECURITY_PRINCIPAL, userDN);

      if (password != null) {
        context.addToEnvironment(Context.SECURITY_CREDENTIALS, password);
      } else {
        logger.debug("try to bind user {} without password", userDN);
      }

      // force bind
      logger.trace("fetch dn of {} to force bind", config.getBaseDn());
      context.getAttributes(config.getBaseDn(), new String[]{"dn"});
    }
  }

  @SuppressWarnings("squid:S1149") // we have to use hashtable, because it is required by jndi
  private Hashtable<String, Object> createConnectionProperties(LdapConfig config, String userDN, String password) {
    Hashtable<String, Object> ldapProperties = new Hashtable<>(12);

    ldapProperties.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
    ldapProperties.put(Context.PROVIDER_URL, config.getHostUrl());

    // apply timeout for read and connect
    // see https://groups.google.com/d/topic/scmmanager/QTimDQM2Wfw/discussion
    ldapProperties.put(PROPERTY_TIMEOUT_CONNECT, TIMEOUT_CONNECT);
    ldapProperties.put(PROPERTY_TIMEOUT_READ, TIMEOUT_READ);

    if (Util.isNotEmpty(userDN) && Util.isNotEmpty(password) && !config.isEnableStartTls()) {
      logger.debug("create context for dn {}", userDN);

      ldapProperties.put(Context.SECURITY_AUTHENTICATION, "simple");
      ldapProperties.put(Context.SECURITY_PRINCIPAL, userDN);
      ldapProperties.put(Context.SECURITY_CREDENTIALS, password);
    } else {
      logger.debug("create anonymous context");
    }

    String referral = config.getReferralStrategy().getContextValue();

    logger.debug("use {} as referral strategy", referral);

    ldapProperties.put(Context.REFERRAL, referral);
    ldapProperties.put("java.naming.ldap.version", "3");

    if (config.getHostUrl().startsWith("ldaps")) {
      ldapProperties.put(Context.SECURITY_PROTOCOL, "ssl");
      ldapProperties.put(PROPERTY_SSL_SOCKET_FACTORY, ThreadLocalSocketFactory.class.getName());
    }

    return ldapProperties;
  }

  AutoCloseableNamingEnumeration<SearchResult> search(String name, String filter, SearchControls cons)
    throws NamingException {
    return new AutoCloseableNamingEnumeration<>(context.search(name, filter, cons));
  }

  @Override
  public void close() {
    LdapUtil.close(tls);
    LdapUtil.close(context);
    ThreadLocalSocketFactory.clearDelegate();
  }
}
