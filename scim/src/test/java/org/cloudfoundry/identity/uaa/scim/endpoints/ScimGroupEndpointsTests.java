/*******************************************************************************
 *     Cloud Foundry 
 *     Copyright (c) [2009-2014] Pivotal Software, Inc. All Rights Reserved.
 *
 *     This product is licensed to you under the Apache License, Version 2.0 (the "License").
 *     You may not use this product except in compliance with the License.
 *
 *     This product includes a number of subcomponents with
 *     separate copyright notices and license terms. Your use of these
 *     subcomponents is subject to the terms and conditions of the
 *     subcomponent's license, as noted in the LICENSE file.
 *******************************************************************************/
package org.cloudfoundry.identity.uaa.scim.endpoints;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.cloudfoundry.identity.uaa.error.ExceptionReportHttpMessageConverter;
import org.cloudfoundry.identity.uaa.rest.SearchResults;
import org.cloudfoundry.identity.uaa.rest.jdbc.DefaultLimitSqlAdapter;
import org.cloudfoundry.identity.uaa.rest.jdbc.JdbcPagingListFactory;
import org.cloudfoundry.identity.uaa.scim.ScimGroup;
import org.cloudfoundry.identity.uaa.scim.ScimGroupMember;
import org.cloudfoundry.identity.uaa.scim.ScimUser;
import org.cloudfoundry.identity.uaa.scim.bootstrap.ScimExternalGroupBootstrap;
import org.cloudfoundry.identity.uaa.scim.exception.InvalidScimResourceException;
import org.cloudfoundry.identity.uaa.scim.exception.ScimException;
import org.cloudfoundry.identity.uaa.scim.exception.ScimResourceAlreadyExistsException;
import org.cloudfoundry.identity.uaa.scim.exception.ScimResourceNotFoundException;
import org.cloudfoundry.identity.uaa.scim.jdbc.JdbcScimGroupExternalMembershipManager;
import org.cloudfoundry.identity.uaa.scim.jdbc.JdbcScimGroupMembershipManager;
import org.cloudfoundry.identity.uaa.scim.jdbc.JdbcScimGroupProvisioning;
import org.cloudfoundry.identity.uaa.scim.jdbc.JdbcScimUserProvisioning;
import org.cloudfoundry.identity.uaa.scim.test.TestUtils;
import org.cloudfoundry.identity.uaa.scim.validate.NullPasswordValidator;
import org.cloudfoundry.identity.uaa.security.SecurityContextAccessor;
import org.cloudfoundry.identity.uaa.test.JdbcTestBase;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.HttpMediaTypeException;
import org.springframework.web.servlet.View;

public class ScimGroupEndpointsTests extends JdbcTestBase {

    Log logger = LogFactory.getLog(getClass());

    private volatile JdbcScimGroupProvisioning dao;

    private volatile JdbcScimUserProvisioning udao;

    private volatile JdbcScimGroupMembershipManager mm;

    private volatile JdbcScimGroupExternalMembershipManager em;

    private volatile ScimExternalGroupBootstrap externalGroupBootstrap;

    private volatile ScimGroupEndpoints endpoints;

    private volatile ScimUserEndpoints userEndpoints;

    private volatile List<String> groupIds;

    private volatile List<String> userIds;

    private static final String SQL_INJECTION_FIELDS = "displayName,version,created,lastModified";

    @Rule
    public ExpectedException expectedEx = ExpectedException.none();

    @Before
    public  void initScimGroupEndpointsTests() throws Exception {
        TestUtils.deleteFrom(dataSource, "users", "groups", "group_membership");
        JdbcTemplate template = jdbcTemplate;
        JdbcPagingListFactory pagingListFactory = new JdbcPagingListFactory(template, new DefaultLimitSqlAdapter());
        dao = new JdbcScimGroupProvisioning(template, pagingListFactory);
        udao = new JdbcScimUserProvisioning(template, pagingListFactory);
        udao.setPasswordValidator(new NullPasswordValidator());
        mm = new JdbcScimGroupMembershipManager(template, pagingListFactory);
        mm.setScimGroupProvisioning(dao);
        mm.setScimUserProvisioning(udao);
        mm.setDefaultUserGroups(Collections.singleton("uaa.user"));

        em = new JdbcScimGroupExternalMembershipManager(template, pagingListFactory);
        em.setScimGroupProvisioning(dao);

        endpoints = new ScimGroupEndpoints(dao, mm);
        endpoints.setExternalMembershipManager(em);

        userEndpoints = new ScimUserEndpoints();
        userEndpoints.setScimUserProvisioning(udao);
        userEndpoints.setScimGroupMembershipManager(mm);

        groupIds = new ArrayList<String>();
        userIds = new ArrayList<String>();
        groupIds.add(addGroup("uaa.resource",
                        Arrays.asList(createMember(ScimGroupMember.Type.USER, ScimGroupMember.GROUP_ADMIN),
                                        createMember(ScimGroupMember.Type.GROUP, ScimGroupMember.GROUP_MEMBER),
                                        createMember(ScimGroupMember.Type.USER, ScimGroupMember.GROUP_ADMIN)))
                        );
        groupIds.add(addGroup("uaa.admin", Collections.<ScimGroupMember> emptyList()));
        groupIds.add(addGroup("uaa.none",
                        Arrays.asList(createMember(ScimGroupMember.Type.USER, ScimGroupMember.GROUP_MEMBER),
                                        createMember(ScimGroupMember.Type.GROUP, ScimGroupMember.GROUP_ADMIN)))
                        );

        externalGroupBootstrap = new ScimExternalGroupBootstrap(dao, em);
        externalGroupBootstrap.setAddNonExistingGroups(true);
        Set<String> externalGroups = new LinkedHashSet<>();
        externalGroups.add("organizations.acme|cn=test_org,ou=people,o=springsource,o=org");
        externalGroups.add("internal.read|cn=developers,ou=scopes,dc=test,dc=com");
        externalGroups.add("internal.write|cn=operators,ou=scopes,dc=test,dc=com");
        externalGroups.add("internal.everything|cn=superusers,ou=scopes,dc=test,dc=com");
        externalGroups.add("internal.superuser|cn=superusers,ou=scopes,dc=test,dc=com");
        externalGroupBootstrap.setExternalGroupMap(externalGroups);
        externalGroupBootstrap.afterPropertiesSet();
    }
    private String addGroup(String name, List<ScimGroupMember> m) {
        ScimGroup g = new ScimGroup("", name);
        g = dao.create(g);
        for (ScimGroupMember member : m) {
            mm.addMember(g.getId(), member);
        }
        return g.getId();
    }

    private ScimGroupMember createMember(ScimGroupMember.Type t, List<ScimGroupMember.Role> a) {
        String id = UUID.randomUUID().toString();
        if (t == ScimGroupMember.Type.USER) {
            id = userEndpoints.createUser(TestUtils.scimUserInstance(id), new MockHttpServletResponse()).getId();
            userIds.add(id);
        } else {
            id = dao.create(new ScimGroup("", id)).getId();
            groupIds.add(id);
        }
        return new ScimGroupMember(id, t, a);
    }

    private void deleteGroup(String name) {
        for (ScimGroup g : dao.query("displayName eq \"" + name + "\"")) {
            dao.delete(g.getId(), g.getVersion());
            mm.removeMembersByGroupId(g.getId());
        }
    }

    private void validateSearchResults(SearchResults<?> results, int expectedSize) {
        assertNotNull(results);
        assertNotNull(results.getResources());
        assertEquals(expectedSize, results.getResources().size());
    }

    private void validateGroup(ScimGroup g, String expectedName, int expectedMemberCount) {
        assertNotNull(g);
        assertNotNull(g.getId());
        assertNotNull(g.getVersion());
        assertEquals(expectedName, g.getDisplayName());
        assertNotNull(g.getMembers());
        assertEquals(expectedMemberCount, g.getMembers().size());
    }

    private void validateUserGroups(String id, String... gnm) {
        ScimUser user = userEndpoints.getUser(id, new MockHttpServletResponse());
        Set<String> expectedAuthorities = new HashSet<String>();
        expectedAuthorities.addAll(Arrays.asList(gnm));
        expectedAuthorities.add("uaa.user");
        assertNotNull(user.getGroups());
        logger.debug("user's groups: " + user.getGroups() + ", expecting: " + expectedAuthorities);
        assertEquals(expectedAuthorities.size(), user.getGroups().size());
        for (ScimUser.Group g : user.getGroups()) {
            assertTrue(expectedAuthorities.contains(g.getDisplay()));
        }
    }

    private SecurityContextAccessor mockSecurityContextAccessor(String userId) {
        SecurityContextAccessor sca = mock(SecurityContextAccessor.class);
        when(sca.getUserId()).thenReturn(userId);
        when(sca.isUser()).thenReturn(true);
        return sca;
    }

    @Test
    public void testListGroups() throws Exception {
        validateSearchResults(endpoints.listGroups("id,displayName", "id pr", "created", "ascending", 1, 100), 11);
    }

    @Test
    public void testListExternalGroups() throws Exception {
        validateSearchResults(endpoints.getExternalGroups(1, 100,""), 5);
    }

    @Test
    public void testListExternalGroupsInvalidFilter() throws Exception {
        try {
            endpoints.getExternalGroups(1, 100, "dasda dasdas dasdas");
        }catch (ScimException x) {
            assertTrue(x.getMessage().startsWith("Invalid filter"));
        }
    }

    @Test
    public void testFindPageOfIds() {
        SearchResults<?> results = endpoints.listGroups("id", "id pr", null, "ascending", 1, 1);
        assertEquals(11, results.getTotalResults());
        assertEquals(1, results.getResources().size());
    }

    @Test
    public void testFindMultiplePagesOfIds() {
        int pageSize = dao.getPageSize();
        dao.setPageSize(1);
        try {
            SearchResults<?> results = endpoints.listGroups("id", "id pr", null, "ascending", 1, 100);
            assertEquals(11, results.getTotalResults());
            assertEquals(11, results.getResources().size());
        } finally {
            dao.setPageSize(pageSize);
        }
    }

    @Test
    public void testListGroupsWithNameEqFilter() {
        validateSearchResults(endpoints.listGroups("id,displayName", "displayName eq \"uaa.user\"", "created",
                        "ascending", 1, 100), 1);
    }

    @Test
    public void testListGroupsWithNameCoFilter() {
        validateSearchResults(endpoints.listGroups("id,displayName", "displayName co \"admin\"", "created", "ascending",
                        1, 100), 1);
    }

    @Test
    public void testListGroupsWithInvalidFilterFails() {
        expectedEx.expect(ScimException.class);
        expectedEx.expectMessage("Invalid filter expression");
        endpoints.listGroups("id,displayName", "displayName cr \"admin\"", "created", "ascending", 1, 100);
    }

    @Test
    public void testListGroupsWithInvalidAttributesFails() {
        expectedEx.expect(ScimException.class);
        expectedEx.expectMessage("Invalid attributes");
        endpoints.listGroups("id,display", "displayName co \"admin\"", "created", "ascending", 1, 100);
    }

    @Test
    public void testListGroupsWithNullAttributes() {
        validateSearchResults(endpoints.listGroups(null, "displayName co \"admin\"", "created", "ascending", 1, 100), 1);
    }

    @Test
    public void testSqlInjectionAttackFailsCorrectly() {
        expectedEx.expect(ScimException.class);
        expectedEx.expectMessage("Invalid filter expression");
        endpoints.listGroups("id,display", "displayName='something'; select " + SQL_INJECTION_FIELDS
                        + " from groups where displayName='something'", "created", "ascending", 1, 100);
    }

    @Test
    public void legacyTestListGroupsWithNameEqFilter() {
        validateSearchResults(endpoints.listGroups("id,displayName", "displayName eq 'uaa.user'", "created",
                "ascending", 1, 100), 1);
    }

    @Test
    public void legacyTestListGroupsWithNameCoFilter() {
        validateSearchResults(endpoints.listGroups("id,displayName", "displayName co 'admin'", "created", "ascending",
                1, 100), 1);
    }

    @Test
    public void legacyTestListGroupsWithInvalidFilterFails() {
        expectedEx.expect(ScimException.class);
        expectedEx.expectMessage("Invalid filter expression");
        endpoints.listGroups("id,displayName", "displayName cr 'admin'", "created", "ascending", 1, 100);
    }

    @Test
    public void legacyTestListGroupsWithInvalidAttributesFails() {
        expectedEx.expect(ScimException.class);
        expectedEx.expectMessage("Invalid attributes");
        endpoints.listGroups("id,display", "displayName co 'admin'", "created", "ascending", 1, 100);
    }

    @Test
    public void legacyTestListGroupsWithNullAttributes() {
        validateSearchResults(endpoints.listGroups(null, "displayName co 'admin'", "created", "ascending", 1, 100), 1);
    }

    @Test
    public void testGetGroup() throws Exception {
        MockHttpServletResponse httpServletResponse = new MockHttpServletResponse();
        ScimGroup g = endpoints.getGroup(groupIds.get(groupIds.size() - 1), httpServletResponse);
        validateGroup(g, "uaa.none", 2);
        assertEquals("\"0\"", httpServletResponse.getHeader("ETag"));
    }

    @Test
    public void testGetNonExistentGroupFails() {
        expectedEx.expect(ScimResourceNotFoundException.class);
        endpoints.getGroup("wrongid", new MockHttpServletResponse());
    }

    @Test
    public void testCreateGroup() throws Exception {
        ScimGroup g = new ScimGroup("", "clients.read");
        g.setMembers(Arrays.asList(createMember(ScimGroupMember.Type.USER, ScimGroupMember.GROUP_ADMIN)));
        MockHttpServletResponse httpServletResponse = new MockHttpServletResponse();
        ScimGroup g1 = endpoints.createGroup(g, httpServletResponse);
        assertEquals("\"0\"", httpServletResponse.getHeader("ETag"));

        validateGroup(g1, "clients.read", 1);
        validateUserGroups(g.getMembers().get(0).getMemberId(), "clients.read");

        deleteGroup("clients.read");
    }

    @Test
    public void testCreateExistingGroupFails() {
        ScimGroup g = new ScimGroup("", "clients.read");
        g.setMembers(Arrays.asList(createMember(ScimGroupMember.Type.USER, ScimGroupMember.GROUP_ADMIN)));
        endpoints.createGroup(g, new MockHttpServletResponse());
        try {
            endpoints.createGroup(g, new MockHttpServletResponse());
            fail("must have thrown exception");
        } catch (ScimResourceAlreadyExistsException ex) {
            validateSearchResults(endpoints.listGroups("id", "displayName eq \"clients.read\"", "id", "ASC", 1, 100), 1);
        }

        deleteGroup("clients.read");
    }

    @Test
    public void testCreateGroupWithInvalidMemberFails() {
        ScimGroup g = new ScimGroup("", "clients.read");
        g.setMembers(Arrays.asList(new ScimGroupMember("non-existent id", ScimGroupMember.Type.USER,
                        ScimGroupMember.GROUP_ADMIN)));

        try {
            endpoints.createGroup(g, new MockHttpServletResponse());
            fail("must have thrown exception");
        } catch (InvalidScimResourceException ex) {
            // ensure that the group was not created
            validateSearchResults(endpoints.listGroups("id", "displayName eq \"clients.read\"", "id", "ASC", 1, 100), 0);
        }
    }

    @Test
    public void testUpdateGroup() throws Exception {
        ScimGroup g = new ScimGroup("", "clients.read");
        g.setMembers(Arrays.asList(createMember(ScimGroupMember.Type.USER, ScimGroupMember.GROUP_ADMIN)));
        g = endpoints.createGroup(g, new MockHttpServletResponse());
        validateUserGroups(g.getMembers().get(0).getMemberId(), "clients.read");

        g.setDisplayName("superadmin");
        g.getMembers().get(0).setRoles(ScimGroupMember.GROUP_MEMBER);
        MockHttpServletResponse httpServletResponse = new MockHttpServletResponse();
        ScimGroup g1 = endpoints.updateGroup(g, g.getId(), "*", httpServletResponse);
        assertEquals("\"1\"", httpServletResponse.getHeader("ETag"));

        validateGroup(g1, "superadmin", 1);
        assertEquals(ScimGroupMember.GROUP_MEMBER, g1.getMembers().get(0).getRoles());
        validateUserGroups(g.getMembers().get(0).getMemberId(), "superadmin");
    }

    @Test
    public void testUpdateGroupQuotedEtag() throws Exception {
        ScimGroup g = new ScimGroup("", "clients.read");
        g.setMembers(Arrays.asList(createMember(ScimGroupMember.Type.USER, ScimGroupMember.GROUP_ADMIN)));
        g = endpoints.createGroup(g, new MockHttpServletResponse());
        validateUserGroups(g.getMembers().get(0).getMemberId(), "clients.read");

        g.setDisplayName("superadmin");
        g.getMembers().get(0).setRoles(ScimGroupMember.GROUP_MEMBER);
        MockHttpServletResponse httpServletResponse = new MockHttpServletResponse();
        ScimGroup g1 = endpoints.updateGroup(g, g.getId(), "\"*\"", httpServletResponse);
        assertEquals("\"1\"", httpServletResponse.getHeader("ETag"));

        validateGroup(g1, "superadmin", 1);
        assertEquals(ScimGroupMember.GROUP_MEMBER, g1.getMembers().get(0).getRoles());
        validateUserGroups(g.getMembers().get(0).getMemberId(), "superadmin");
    }

    @Test
    public void testUpdateGroupRemoveMembers() throws Exception {
        ScimGroup g = new ScimGroup("", "clients.read");
        g.setMembers(Arrays.asList(createMember(ScimGroupMember.Type.USER, ScimGroupMember.GROUP_ADMIN)));
        g = endpoints.createGroup(g, new MockHttpServletResponse());
        validateUserGroups(g.getMembers().get(0).getMemberId(), "clients.read");

        g.setDisplayName("superadmin");
        g.setMembers(new ArrayList<ScimGroupMember>());
        MockHttpServletResponse httpServletResponse = new MockHttpServletResponse();
        ScimGroup g1 = endpoints.updateGroup(g, g.getId(), "*", httpServletResponse);
        assertEquals("\"1\"", httpServletResponse.getHeader("ETag"));

        validateGroup(g1, "superadmin", 0);
    }

    @Test(expected = ScimException.class)
    public void testUpdateGroupNullEtag() throws Exception {
        ScimGroup g = new ScimGroup("", "clients.read");
        g.setMembers(Arrays.asList(createMember(ScimGroupMember.Type.USER, ScimGroupMember.GROUP_ADMIN)));
        g = endpoints.createGroup(g, new MockHttpServletResponse());
        validateUserGroups(g.getMembers().get(0).getMemberId(), "clients.read");

        g.setDisplayName("superadmin");
        g.getMembers().get(0).setRoles(ScimGroupMember.GROUP_MEMBER);
        MockHttpServletResponse httpServletResponse = new MockHttpServletResponse();
        endpoints.updateGroup(g, g.getId(), null, httpServletResponse);
    }

    @Test(expected = ScimException.class)
    public void testUpdateGroupNoEtag() throws Exception {
        ScimGroup g = new ScimGroup("", "clients.read");
        g.setMembers(Arrays.asList(createMember(ScimGroupMember.Type.USER, ScimGroupMember.GROUP_ADMIN)));
        g = endpoints.createGroup(g, new MockHttpServletResponse());
        validateUserGroups(g.getMembers().get(0).getMemberId(), "clients.read");

        g.setDisplayName("superadmin");
        g.getMembers().get(0).setRoles(ScimGroupMember.GROUP_MEMBER);
        MockHttpServletResponse httpServletResponse = new MockHttpServletResponse();
        endpoints.updateGroup(g, g.getId(), "", httpServletResponse);
    }

    @Test(expected = ScimException.class)
    public void testUpdateGroupInvalidEtag() throws Exception {
        ScimGroup g = new ScimGroup("", "clients.read");
        g.setMembers(Arrays.asList(createMember(ScimGroupMember.Type.USER, ScimGroupMember.GROUP_ADMIN)));
        g = endpoints.createGroup(g, new MockHttpServletResponse());
        validateUserGroups(g.getMembers().get(0).getMemberId(), "clients.read");

        g.setDisplayName("superadmin");
        g.getMembers().get(0).setRoles(ScimGroupMember.GROUP_MEMBER);
        MockHttpServletResponse httpServletResponse = new MockHttpServletResponse();
        endpoints.updateGroup(g, g.getId(), "abc", httpServletResponse);
    }

    @Test
    public void testUpdateNonUniqueDisplayNameFails() {
        ScimGroup g1 = new ScimGroup("", "clients.read");
        g1.setMembers(Arrays.asList(createMember(ScimGroupMember.Type.USER, ScimGroupMember.GROUP_ADMIN)));
        g1 = endpoints.createGroup(g1, new MockHttpServletResponse());

        ScimGroup g2 = new ScimGroup("", "clients.write");
        g2.setMembers(Arrays.asList(createMember(ScimGroupMember.Type.USER, ScimGroupMember.GROUP_ADMIN)));
        g2 = endpoints.createGroup(g2, new MockHttpServletResponse());

        g1.setDisplayName("clients.write");
        try {
            endpoints.updateGroup(g1, g1.getId(), "*", new MockHttpServletResponse());
            fail("must have thrown exception");
        } catch (InvalidScimResourceException ex) {
            validateSearchResults(endpoints.listGroups("id", "displayName eq \"clients.write\"", "id", "ASC", 1, 100), 1);
            validateSearchResults(endpoints.listGroups("id", "displayName eq \"clients.read\"", "id", "ASC", 1, 100), 1);
        }

        deleteGroup("clients.read");
        deleteGroup("clients.write");
    }

    @Test
    public void testUpdateWithInvalidMemberFails() {
        ScimGroup g1 = new ScimGroup("", "clients.read");
        g1.setMembers(Arrays.asList(createMember(ScimGroupMember.Type.USER, ScimGroupMember.GROUP_ADMIN)));
        g1 = endpoints.createGroup(g1, new MockHttpServletResponse());

        g1.setMembers(Arrays.asList(new ScimGroupMember("non-existent id", ScimGroupMember.Type.USER,
                        ScimGroupMember.GROUP_ADMIN)));
        g1.setDisplayName("clients.write");

        try {
            endpoints.updateGroup(g1, g1.getId(), "*", new MockHttpServletResponse());
            fail("must have thrown exception");
        } catch (ScimException ex) {
            // ensure that displayName was not updated
            g1 = endpoints.getGroup(g1.getId(), new MockHttpServletResponse());
            validateGroup(g1, "clients.read", 0);
            validateSearchResults(endpoints.listGroups("id", "displayName eq \"clients.write\"", "id", "ASC", 1, 100), 0);
        }

        deleteGroup("clients.read");
    }

    @Test
    public void testUpdateInvalidVersionFails() {
        ScimGroup g1 = new ScimGroup("", "clients.read");
        g1.setMembers(Arrays.asList(createMember(ScimGroupMember.Type.USER, ScimGroupMember.GROUP_ADMIN)));
        g1 = endpoints.createGroup(g1, new MockHttpServletResponse());

        g1.setDisplayName("clients.write");

        try {
            endpoints.updateGroup(g1, g1.getId(), "version", new MockHttpServletResponse());
        } catch (ScimException ex) {
            assertTrue("Wrong exception message", ex.getMessage().contains("Invalid version"));
            validateSearchResults(endpoints.listGroups("id", "displayName eq \"clients.write\"", "id", "ASC", 1, 100), 0);
            validateSearchResults(endpoints.listGroups("id", "displayName eq \"clients.read\"", "id", "ASC", 1, 100), 1);
        }

        deleteGroup("clients.read");
    }

    @Test
    public void testUpdateGroupWithNullEtagFails() {
        ScimGroup g1 = new ScimGroup("", "clients.read");
        g1.setMembers(Arrays.asList(createMember(ScimGroupMember.Type.USER, ScimGroupMember.GROUP_ADMIN)));
        g1 = endpoints.createGroup(g1, new MockHttpServletResponse());

        g1.setDisplayName("clients.write");

        try {
            endpoints.updateGroup(g1, g1.getId(), null, new MockHttpServletResponse());
        } catch (ScimException ex) {
            assertTrue("Wrong exception message", ex.getMessage().contains("Missing If-Match"));
            validateSearchResults(endpoints.listGroups("id", "displayName eq \"clients.write\"", "id", "ASC", 1, 100), 0);
            validateSearchResults(endpoints.listGroups("id", "displayName eq \"clients.read\"", "id", "ASC", 1, 100), 1);
        }

        deleteGroup("clients.read");
    }

    @Test
    public void testUpdateWithQuotedVersionSucceeds() {
        ScimGroup g1 = new ScimGroup("", "clients.read");
        g1.setMembers(Arrays.asList(createMember(ScimGroupMember.Type.USER, ScimGroupMember.GROUP_ADMIN)));
        g1 = endpoints.createGroup(g1, new MockHttpServletResponse());

        g1.setDisplayName("clients.write");

        endpoints.updateGroup(g1, g1.getId(), "\"*", new MockHttpServletResponse());
        endpoints.updateGroup(g1, g1.getId(), "*\"", new MockHttpServletResponse());
        validateSearchResults(endpoints.listGroups("id", "displayName eq \"clients.write\"", "id", "ASC", 1, 100), 1);
        validateSearchResults(endpoints.listGroups("id", "displayName eq \"clients.read\"", "id", "ASC", 1, 100), 0);

        deleteGroup("clients.write");
    }

    @Test
    public void testUpdateWrongVersionFails() {
        ScimGroup g1 = new ScimGroup("", "clients.read");
        g1.setMembers(Arrays.asList(createMember(ScimGroupMember.Type.USER, ScimGroupMember.GROUP_ADMIN)));
        g1 = endpoints.createGroup(g1, new MockHttpServletResponse());

        g1.setDisplayName("clients.write");

        try {
            endpoints.updateGroup(g1, g1.getId(), String.valueOf(g1.getVersion() + 23), new MockHttpServletResponse());
        } catch (ScimException ex) {
            validateSearchResults(endpoints.listGroups("id", "displayName eq \"clients.write\"", "id", "ASC", 1, 100), 0);
            validateSearchResults(endpoints.listGroups("id", "displayName eq \"clients.read\"", "id", "ASC", 1, 100), 1);
        }

        deleteGroup("clients.read");
    }

    @Test
    public void testUpdateGroupWithNoMembers() {
        ScimGroup g = new ScimGroup("", "clients.read");
        g.setMembers(Arrays.asList(createMember(ScimGroupMember.Type.USER, ScimGroupMember.GROUP_ADMIN)));
        g = endpoints.createGroup(g, new MockHttpServletResponse());
        validateUserGroups(g.getMembers().get(0).getMemberId(), "clients.read");

        g.setDisplayName("someadmin");
        g.setMembers(null);
        ScimGroup g1 = endpoints.updateGroup(g, g.getId(), "*", new MockHttpServletResponse());
        validateGroup(g1, "someadmin", 0);

        deleteGroup("clients.read");
    }

    @Test
    public void testDeleteGroup() throws Exception {
        ScimGroup g = new ScimGroup("", "clients.read");
        g.setMembers(Arrays.asList(createMember(ScimGroupMember.Type.USER, ScimGroupMember.GROUP_ADMIN)));
        g = endpoints.createGroup(g, new MockHttpServletResponse());
        validateUserGroups(g.getMembers().get(0).getMemberId(), "clients.read");

        g = endpoints.deleteGroup(g.getId(), "*", new MockHttpServletResponse());
        try {
            endpoints.getGroup(g.getId(), new MockHttpServletResponse());
            fail("group should not exist");
        } catch (ScimResourceNotFoundException ex) {
        }
        validateUserGroups(g.getMembers().get(0).getMemberId(), "uaa.user");
    }

    @Test
    public void testDeleteWrongVersionFails() {
        ScimGroup g = new ScimGroup("", "clients.read");
        g.setMembers(Arrays.asList(createMember(ScimGroupMember.Type.USER, ScimGroupMember.GROUP_ADMIN)));
        g = endpoints.createGroup(g, new MockHttpServletResponse());

        try {
            endpoints.deleteGroup(g.getId(), String.valueOf(g.getVersion() + 3), new MockHttpServletResponse());
        } catch (ScimException ex) {
            validateSearchResults(endpoints.listGroups("id", "displayName eq \"clients.read\"", "id", "ASC", 1, 100), 1);
        }

        deleteGroup("clients.read");
    }

    @Test
    public void testDeleteNonExistentGroupFails() {
        expectedEx.expect(ScimResourceNotFoundException.class);
        endpoints.deleteGroup("some id", "*", new MockHttpServletResponse());
    }

    @Test
    public void testExceptionHandler() {
        Map<Class<? extends Exception>, HttpStatus> map = new HashMap<Class<? extends Exception>, HttpStatus>();
        map.put(IllegalArgumentException.class, HttpStatus.BAD_REQUEST);
        map.put(UnsupportedOperationException.class, HttpStatus.BAD_REQUEST);
        map.put(BadSqlGrammarException.class, HttpStatus.BAD_REQUEST);
        map.put(DataIntegrityViolationException.class, HttpStatus.BAD_REQUEST);
        map.put(HttpMessageConversionException.class, HttpStatus.BAD_REQUEST);
        map.put(HttpMediaTypeException.class, HttpStatus.BAD_REQUEST);
        endpoints.setStatuses(map);
        endpoints.setMessageConverters(new HttpMessageConverter<?>[] { new ExceptionReportHttpMessageConverter() });

        MockHttpServletRequest request = new MockHttpServletRequest();
        validateView(endpoints.handleException(new ScimResourceNotFoundException(""), request), HttpStatus.NOT_FOUND);
        validateView(endpoints.handleException(new UnsupportedOperationException(""), request), HttpStatus.BAD_REQUEST);
        validateView(endpoints.handleException(new BadSqlGrammarException("", "", null), request),
                        HttpStatus.BAD_REQUEST);
        validateView(endpoints.handleException(new IllegalArgumentException(""), request), HttpStatus.BAD_REQUEST);
        validateView(endpoints.handleException(new DataIntegrityViolationException(""), request),
                        HttpStatus.BAD_REQUEST);
    }

    @Test
    public void testListGroupsAsUser() {
        endpoints.setSecurityContextAccessor(mockSecurityContextAccessor(userIds.get(0)));
        try {
            validateSearchResults(endpoints.listGroups("id,displayName", "id pr", "created", "ascending", 1, 100), 1);
        } finally {
            endpoints.setSecurityContextAccessor(null);
        }
    }

    private void validateView(View view, HttpStatus status) {
        MockHttpServletResponse response = new MockHttpServletResponse();
        try {
            view.render(new HashMap<String, Object>(), new MockHttpServletRequest(), response);
            assertNotNull(response.getContentAsString());
        } catch (Exception e) {
            fail("view should render correct status and body");
        }
        assertEquals(status.value(), response.getStatus());
    }
}
