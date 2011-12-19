/**
 * Copyright (c) 2010, Sebastian Sdorra
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 * 3. Neither the name of SCM-Manager; nor the names of its
 *    contributors may be used to endorse or promote products derived from this
 *    software without specific prior written permission.
 *
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
 *
 * http://bitbucket.org/sdorra/scm-manager
 *
 */



package sonia.scm.auth.ldap;

//~--- non-JDK imports --------------------------------------------------------

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import sonia.scm.user.User;
import sonia.scm.util.Util;
import sonia.scm.web.security.AuthenticationResult;

//~--- JDK imports ------------------------------------------------------------

import java.text.MessageFormat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.List;
import java.util.Set;

import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;

/**
 *
 * @author Sebastian Sdorra
 */
public class LDAPContext
{

  /** Field description */
  public static final String ATTRIBUTE_GROUP_NAME = "cn";

  /** Field description */
  public static final String SEARCHTYPE_GROUP = "group";

  /** Field description */
  public static final String SEARCHTYPE_USER = "user";

  /** the logger for LDAPContext */
  private static final Logger logger =
    LoggerFactory.getLogger(LDAPContext.class);

  //~--- constructors ---------------------------------------------------------

  /**
   * Constructs ...
   *
   *
   * @param config
   */
  public LDAPContext(LDAPConfig config)
  {
    this.config = config;
    this.state = new LDAPAuthenticationState();
    buildLdapProperties();
  }

  //~--- methods --------------------------------------------------------------

  /**
   * Method description
   *
   *
   * @param username
   * @param password
   *
   * @return
   */
  public AuthenticationResult authenticate(String username, String password)
  {
    AuthenticationResult result = AuthenticationResult.NOT_FOUND;
    DirContext bindContext = null;

    try
    {
      bindContext = createBindContext();

      if (bindContext != null)
      {
        SearchResult searchResult = getUserSearchResult(bindContext, username);

        if (searchResult != null)
        {
          result = AuthenticationResult.FAILED;

          String userDN = searchResult.getNameInNamespace();

          if (authenticateUser(userDN, password))
          {
            Attributes attributes = searchResult.getAttributes();
            User user = createUser(attributes);
            Set<String> groups = new HashSet<String>();

            fetchGroups(bindContext, groups, userDN, user.getId(),
                        user.getMail());
            getGroups(attributes, groups);
            result = new AuthenticationResult(user, groups);
          }    // password wrong ?
        }      // user not found
      }        // no bind context available
    }
    finally
    {
      LDAPUtil.close(bindContext);
    }

    return result;
  }

  //~--- get methods ----------------------------------------------------------

  /**
   * Method description
   *
   *
   * @return
   */
  public LDAPAuthenticationState getState()
  {
    return state;
  }

  //~--- methods --------------------------------------------------------------

  /**
   * Method description
   *
   *
   * @param list
   * @param attribute
   */
  private void appendAttribute(List<String> list, String attribute)
  {
    if (Util.isNotEmpty(attribute))
    {
      list.add(attribute);
    }
  }

  /**
   * Method description
   *
   *
   * @param userDN
   * @param password
   *
   * @return
   */
  private boolean authenticateUser(String userDN, String password)
  {
    boolean authenticated = false;
    Hashtable<String, String> userProperties = new Hashtable<String,
                                                 String>(ldapProperties);

    userProperties.put(Context.SECURITY_PRINCIPAL, userDN);
    userProperties.put(Context.SECURITY_CREDENTIALS, password);

    DirContext userContext = null;

    try
    {
      userContext = new InitialDirContext(userProperties);
      authenticated = true;
      state.setAuthenticateUser(true);

      if (logger.isDebugEnabled())
      {
        logger.debug("user {} successfully authenticated", userDN);
      }
    }
    catch (NamingException ex)
    {
      state.setAuthenticateUser(false);
      state.setException(ex);

      if (logger.isTraceEnabled())
      {
        logger.trace("authentication failed for user ".concat(userDN), ex);
      }
      else if (logger.isWarnEnabled())
      {
        logger.debug("authentication failed for user {}", userDN);
      }
    }
    finally
    {
      LDAPUtil.close(userContext);
    }

    return authenticated;
  }

  /**
   * Method description
   *
   */
  private void buildLdapProperties()
  {
    ldapProperties = new Hashtable<String, String>();
    ldapProperties.put(Context.INITIAL_CONTEXT_FACTORY,
                       "com.sun.jndi.ldap.LdapCtxFactory");
    ldapProperties.put(Context.PROVIDER_URL, config.getHostUrl());

    String connectionDN = config.getConnectionDn();
    String connectionPassword = config.getConnectionPassword();

    if (Util.isNotEmpty(connectionDN) && Util.isNotEmpty(connectionPassword))
    {
      if (logger.isDebugEnabled())
      {
        logger.debug("create bind context for dn {}", connectionDN);
      }

      ldapProperties.put(Context.SECURITY_AUTHENTICATION, "simple");
      ldapProperties.put(Context.SECURITY_PRINCIPAL, connectionDN);
      ldapProperties.put(Context.SECURITY_CREDENTIALS, connectionPassword);
    }
    else if (logger.isDebugEnabled())
    {
      logger.debug("create anonymous bind context");
    }

    ldapProperties.put("java.naming.ldap.version", "3");
  }

  /**
   * Method description
   *
   *
   * @return
   */
  private DirContext createBindContext()
  {
    DirContext context = null;

    try
    {
      context = new InitialDirContext(ldapProperties);
      state.setBind(true);
    }
    catch (NamingException ex)
    {
      state.setBind(false);
      state.setException(ex);
      logger.error(
          "could not bind to ldap with dn ".concat(config.getConnectionDn()),
          ex);
    }

    return context;
  }

  /**
   * Method description
   *
   *
   * @return
   */
  private String createGroupSearchBaseDN()
  {
    return createSearchBaseDN(SEARCHTYPE_GROUP, config.getUnitGroup());
  }

  /**
   * Method description
   *
   *
   * @param userDN
   * @param uid
   * @param mail
   *
   * @return
   */
  private String createGroupSearchFilter(String userDN, String uid, String mail)
  {
    String filter = null;

    if (Util.isNotEmpty(config.getSearchFilterGroup()))
    {
      if (mail == null)
      {
        mail = "";
      }

      filter = MessageFormat.format(config.getSearchFilterGroup(), userDN, uid,
                                    mail);

      if (logger.isDebugEnabled())
      {
        logger.debug("search-filter for group search: {}", filter);
      }
    }
    else
    {
      logger.warn("search-filter for groups not defined");
    }

    return filter;
  }

  /**
   * Method description
   *
   *
   * @param type
   * @param prefix
   *
   * @return
   */
  private String createSearchBaseDN(String type, String prefix)
  {
    String dn = null;

    if (Util.isNotEmpty(config.getBaseDn()))
    {
      if (Util.isNotEmpty(prefix))
      {
        dn = prefix.concat(",").concat(config.getBaseDn());
      }
      else
      {
        if (logger.isDebugEnabled())
        {
          logger.debug("no prefix for {} defined, using basedn for search",
                       type);
        }

        dn = config.getBaseDn();
      }

      if (logger.isDebugEnabled())
      {
        logger.debug("saarch base for {} search: {}", type, dn);
      }
    }
    else
    {
      logger.error("no basedn defined");
    }

    return dn;
  }

  /**
   * Method description
   *
   *
   * @param attributes
   *
   * @return
   */
  private User createUser(Attributes attributes)
  {
    User user = new User();

    user.setName(LDAPUtil.getAttribute(attributes,
                                       config.getAttributeNameId()));
    user.setDisplayName(LDAPUtil.getAttribute(attributes,
            config.getAttributeNameFullname()));
    user.setMail(LDAPUtil.getAttribute(attributes,
                                       config.getAttributeNameMail()));
    user.setType(LDAPAuthenticationHandler.TYPE);

    return user;
  }

  /**
   * Method description
   *
   *
   * @return
   */
  private String createUserSearchBaseDN()
  {
    return createSearchBaseDN(SEARCHTYPE_USER, config.getUnitPeople());
  }

  /**
   * Method description
   *
   *
   * @param username
   *
   * @return
   */
  private String createUserSearchFilter(String username)
  {
    String filter = null;

    if (Util.isNotEmpty(config.getSearchFilter()))
    {
      filter = MessageFormat.format(config.getSearchFilter(), username);

      if (logger.isDebugEnabled())
      {
        logger.debug("search-filter for user search: {}", filter);
      }
    }
    else
    {
      logger.error("search filter not defined");
    }

    return filter;
  }

  /**
   * Method description
   *
   *
   * @param context
   * @param groups
   * @param userDN
   * @param uid
   * @param mail
   */
  private void fetchGroups(DirContext context, Set<String> groups,
                           String userDN, String uid, String mail)
  {
    if (Util.isNotEmpty(config.getSearchFilterGroup()))
    {
      NamingEnumeration<SearchResult> searchResultEnm = null;

      try
      {

        // read group of unique names
        SearchControls searchControls = new SearchControls();

        searchControls.setSearchScope(SearchControls.SUBTREE_SCOPE);

        // make group name attribute configurable?
        searchControls.setReturningAttributes(new String[] {
          ATTRIBUTE_GROUP_NAME });

        String filter = createGroupSearchFilter(userDN, uid, mail);

        if (filter != null)
        {
          String searchDN = createGroupSearchBaseDN();

          searchResultEnm = context.search(searchDN, filter, searchControls);

          while (searchResultEnm.hasMore())
          {
            SearchResult searchResult = searchResultEnm.next();
            Attributes groupAttributes = searchResult.getAttributes();
            String name = LDAPUtil.getAttribute(groupAttributes,
                            ATTRIBUTE_GROUP_NAME);

            if (Util.isNotEmpty(name))
            {
              groups.add(name);
            }
          }
        }
      }
      catch (NamingException ex)
      {
        logger.debug("could not find groups", ex);
      }
      finally
      {
        LDAPUtil.close(searchResultEnm);
      }
    }
    else if (logger.isWarnEnabled())
    {
      logger.warn("group filter is empty");
    }
  }

  //~--- get methods ----------------------------------------------------------

  /**
   * Method description
   *
   *
   * @param attributes
   * @param groups
   *
   */
  private void getGroups(Attributes attributes, Set<String> groups)
  {
    NamingEnumeration<?> userGroupsEnm = null;

    try
    {
      Attribute groupsAttribute =
        attributes.get(config.getAttributeNameGroup());

      if (groupsAttribute != null)
      {
        userGroupsEnm = (NamingEnumeration<?>) groupsAttribute.getAll();

        while (userGroupsEnm.hasMore())
        {
          String group = (String) userGroupsEnm.next();

          group = LDAPUtil.getName(group);
          groups.add(group);
        }
      }
      else
      {
        logger.info("user has no group attributes assigned");
      }
    }
    catch (NamingException ex)
    {
      logger.error("could not read group attribute", ex);
    }
    finally
    {
      LDAPUtil.close(userGroupsEnm);
    }
  }

  /**
   * Method description
   *
   *
   * @return
   */
  private String[] getReturnAttributes()
  {
    List<String> list = new ArrayList<String>();

    appendAttribute(list, config.getAttributeNameId());
    appendAttribute(list, config.getAttributeNameFullname());
    appendAttribute(list, config.getAttributeNameMail());
    appendAttribute(list, config.getAttributeNameGroup());

    return list.toArray(new String[list.size()]);
  }

  /**
   * Method description
   *
   *
   * @param bindContext
   * @param username
   *
   * @return
   */
  private SearchResult getUserSearchResult(DirContext bindContext,
          String username)
  {
    SearchResult result = null;

    if (bindContext != null)
    {
      NamingEnumeration<SearchResult> searchResultEnm = null;

      try
      {
        SearchControls searchControls = new SearchControls();
        int scope = LDAPUtil.getSearchScope(config.getSearchScope());

        if (logger.isDebugEnabled())
        {
          logger.debug("using scope {} for user search",
                       LDAPUtil.getSearchScope(scope));
        }

        searchControls.setSearchScope(scope);
        searchControls.setCountLimit(1);
        searchControls.setReturningAttributes(getReturnAttributes());

        String filter = createUserSearchFilter(username);

        if (filter != null)
        {
          String baseDn = createUserSearchBaseDN();

          if (baseDn != null)
          {
            searchResultEnm = bindContext.search(baseDn, filter,
                    searchControls);

            if (searchResultEnm.hasMore())
            {
              result = searchResultEnm.next();
              state.setSearchUser(true);
            }
            else if (logger.isWarnEnabled())
            {
              logger.warn("no user with username {} found", username);
            }
          }
        }
      }
      catch (NamingException ex)
      {
        state.setSearchUser(false);
        state.setException(ex);
        logger.error("exception occured during user search", ex);
      }
      finally
      {
        LDAPUtil.close(searchResultEnm);
      }
    }

    return result;
  }

  //~--- fields ---------------------------------------------------------------

  /** Field description */
  private LDAPConfig config;

  /** Field description */
  private Hashtable<String, String> ldapProperties;

  /** Field description */
  private LDAPAuthenticationState state;
}