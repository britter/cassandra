/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.cassandra.auth;

import org.junit.BeforeClass;
import org.junit.Test;

import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.cql3.CQLTester;
import org.apache.cassandra.transport.ProtocolVersion;

public class AuthorizeForAndRestrictTest extends CQLTester
{
    @BeforeClass
    public static void setup()
    {
        requireAuthentication();
        DatabaseDescriptor.setPermissionsValidity(9999);
        DatabaseDescriptor.setPermissionsUpdateInterval(9999);
        requireNetwork();
    }
    @Test
    public void testWarnings() throws Throwable
    {
        useSuperUser();

        executeNet("CREATE KEYSPACE revoke_yeah WITH replication = {'class': 'SimpleStrategy', 'replication_factor': '1'}");
        executeNet("CREATE TABLE revoke_yeah.t1 (id int PRIMARY KEY, val text)");
        executeNet("CREATE USER revoked WITH PASSWORD 'pass1'");

        // verify that noop REVOKEs & UNRESTRICTs error out (APOLLO-1083)

        assertClientWarning(ProtocolVersion.CURRENT,
                            "Role 'revoked' was not granted CREATE on <keyspace revoke_yeah>",
                            "REVOKE CREATE ON KEYSPACE revoke_yeah FROM revoked");
        assertClientWarning(ProtocolVersion.CURRENT,
                            "Role 'revoked' was not granted AUTHORIZE FOR CREATE on <keyspace revoke_yeah>",
                            "REVOKE AUTHORIZE FOR CREATE ON KEYSPACE revoke_yeah FROM revoked");
        assertClientWarning(ProtocolVersion.CURRENT,
                            "Role 'revoked' was not restricted SELECT on <table revoke_yeah.t1>",
                            "UNRESTRICT SELECT ON TABLE revoke_yeah.t1 FROM revoked");

        executeNet("GRANT SELECT ON KEYSPACE revoke_yeah TO revoked");
        executeNet("GRANT AUTHORIZE FOR SELECT ON KEYSPACE revoke_yeah TO revoked");
        executeNet("RESTRICT SELECT ON KEYSPACE revoke_yeah TO revoked");

        assertClientWarning("Role 'revoked' was already granted SELECT on <keyspace revoke_yeah>",
                            "GRANT SELECT ON KEYSPACE revoke_yeah TO revoked");
        assertClientWarning("Role 'revoked' was already granted AUTHORIZE FOR SELECT on <keyspace revoke_yeah>",
                            "GRANT AUTHORIZE FOR SELECT, UPDATE ON KEYSPACE revoke_yeah TO revoked");
        assertClientWarning("Role 'revoked' was already granted AUTHORIZE FOR SELECT, UPDATE on <keyspace revoke_yeah>",
                            "GRANT AUTHORIZE FOR UPDATE, SELECT, DROP ON KEYSPACE revoke_yeah TO revoked");
        assertClientWarning("Role 'revoked' was already restricted SELECT on <keyspace revoke_yeah>",
                            "RESTRICT SELECT ON KEYSPACE revoke_yeah TO revoked");
        assertNoClientWarning("RESTRICT ALL ON KEYSPACE revoke_yeah TO revoked");

        assertClientWarning("Role 'revoked' was not granted SELECT on <table revoke_yeah.t1>",
                            "REVOKE SELECT ON TABLE revoke_yeah.t1 FROM revoked");
        assertClientWarning("Role 'revoked' was not granted AUTHORIZE FOR SELECT on <table revoke_yeah.t1>",
                            "REVOKE AUTHORIZE FOR SELECT ON TABLE revoke_yeah.t1 FROM revoked");
        assertClientWarning("Role 'revoked' was not restricted SELECT on <table revoke_yeah.t1>",
                            "UNRESTRICT SELECT ON TABLE revoke_yeah.t1 FROM revoked");

        // revert all the stuff above
        assertClientWarning("Role 'revoked' was not granted UPDATE on <keyspace revoke_yeah>",
                            "REVOKE SELECT, UPDATE ON KEYSPACE revoke_yeah FROM revoked");
        assertNoClientWarning("REVOKE AUTHORIZE FOR ALL ON KEYSPACE revoke_yeah FROM revoked");
        assertNoClientWarning("UNRESTRICT ALL ON KEYSPACE revoke_yeah FROM revoked");
        assertClientWarning("Role 'revoked' was not restricted SELECT, UPDATE on <keyspace revoke_yeah>",
                            "UNRESTRICT SELECT, UPDATE ON KEYSPACE revoke_yeah FROM revoked");
    }


    @Test
    public void testAuthorizeFor() throws Throwable
    {
        useSuperUser();

        executeNet("CREATE USER authfor1 WITH PASSWORD 'pass1'");
        executeNet("CREATE USER authfor2 WITH PASSWORD 'pass2'");
        executeNet("CREATE ROLE authfor_role1");
        executeNet("GRANT authfor_role1 TO authfor1");

        executeNet("CREATE KEYSPACE authfor_test WITH replication = {'class': 'SimpleStrategy', 'replication_factor': '1'}");
        executeNet("CREATE TABLE authfor_test.t1 (id int PRIMARY KEY, val text)");
        executeNet("CREATE TABLE authfor_test.t2 (id int PRIMARY KEY, val text)");

        executeNet("GRANT AUTHORIZE FOR SELECT ON KEYSPACE authfor_test TO authfor1");
        executeNet("GRANT UPDATE ON TABLE authfor_test.t1 TO authfor1");
        executeNet("GRANT AUTHORIZE FOR UPDATE ON TABLE authfor_test.t2 TO authfor2");
        executeNet("GRANT UPDATE ON TABLE authfor_test.t2 TO authfor2");


        useUser("authfor1", "pass1");

        // SELECT on table t1 must not work, authfor1 only has the privilege to grant the SELECT permission
        assertUnauthorizedQuery("User authfor1 has no SELECT permission on <table authfor_test.t1> or any of its parents",
                                "SELECT * FROM authfor_test.t1");

        // authfor1 must not be able to grant the SELECT permission to himself
        assertUnauthorizedQuery("User authfor1 has grant privilege for SELECT permission(s) on <table authfor_test.t1> but must not grant/revoke for him/herself",
                                "GRANT SELECT ON TABLE authfor_test.t1 TO authfor1");

        // authfor1 must not be able to grant the SELECT permission to himself - even via a role
        assertUnauthorizedQuery("User authfor1 has grant privilege for SELECT permission(s) on <table authfor_test.t1> but must not grant/revoke for him/herself",
                                "GRANT SELECT ON TABLE authfor_test.t1 TO authfor_role1");

        // authfor1 has MODIFIY permission on t1 but not the privilege to grant the UPDATE permission
        assertUnauthorizedQuery("User authfor1 has no AUTHORIZE permission nor AUTHORIZE FOR UPDATE permission on <table authfor_test.t1> or any of its parents",
                                "GRANT UPDATE ON TABLE authfor_test.t1 to authfor2");

        assertUnauthorizedQuery("User authfor1 must not grant AUTHORIZE FOR AUTHORIZE permission on <keyspace authfor_test>",
                                "GRANT AUTHORIZE FOR SELECT ON KEYSPACE authfor_test TO authfor2");

        // authfor1 can grant the SELECT privilege - all that must work (although the GRANT on the keyspace is technically sufficient)
        executeNet("GRANT SELECT ON KEYSPACE authfor_test TO authfor2");
        executeNet("GRANT SELECT ON TABLE authfor_test.t1 TO authfor2");
        executeNet("GRANT SELECT ON TABLE authfor_test.t2 TO authfor2");

        // authfor1 has no MODIFIY permission on t2
        assertUnauthorizedQuery("User authfor1 has no UPDATE permission on <table authfor_test.t2> or any of its parents",
                                "INSERT INTO authfor_test.t2 (id, val) VALUES (1, 'foo')");

        useUser("authfor2", "pass2");

        // authfor2 has SELECT permission on t1
        executeNet("SELECT * FROM authfor_test.t1");

        // authfor2 has no UPDATE permission on t1
        assertUnauthorizedQuery("User authfor2 has no UPDATE permission on <table authfor_test.t1> or any of its parents",
                                "INSERT INTO authfor_test.t1 (id, val) VALUES (1, 'foo')");

        // authfor2 has the privilege to grant the UPDATE permission
        executeNet("GRANT UPDATE ON TABLE authfor_test.t2 TO authfor1");

        // authfor2 has UPDATE permission on t2
        executeNet("INSERT INTO authfor_test.t2 (id, val) VALUES (1, 'foo')");

        // authfor2 has SElECT permission on t2
        assertRowsNet(executeNet("SELECT id, val FROM authfor_test.t2"),
                      new Object[] { 1, "foo" });

        useUser("authfor1", "pass1");

        // now the CQL works

        executeNet("INSERT INTO authfor_test.t2 (id, val) VALUES (2, 'bar')");

        useUser("authfor2", "pass2");

        assertRowsNet(executeNet("SELECT id, val FROM authfor_test.t2"),
                      row(1, "foo"),
                      row(2, "bar"));

        useSuperUser();
        assertRowsNet(executeNet("LIST PERMISSIONS OF authfor1"),
                      row("authfor1", "authfor1", "<keyspace authfor_test>", "SELECT", false, false, true),
                      row("authfor1", "authfor1", "<table authfor_test.t1>", "UPDATE", true, false, false),
                      row("authfor1", "authfor1", "<table authfor_test.t2>", "UPDATE", true, false, false));
        assertRowsNet(executeNet("LIST PERMISSIONS OF authfor2"),
                      row("authfor2", "authfor2", "<keyspace authfor_test>", "SELECT", true, false, false),
                      row("authfor2", "authfor2", "<table authfor_test.t1>", "SELECT", true, false, false),
                      row("authfor2", "authfor2", "<table authfor_test.t2>", "SELECT", true, false, false),
                      row("authfor2", "authfor2", "<table authfor_test.t2>", "UPDATE", true, false, true));

        // all permissions and grant options must have been removed
        executeNet("DROP ROLE authfor1");
        executeNet("CREATE USER authfor1 WITH PASSWORD 'pass1'");

        assertRowsNet(executeNet("LIST PERMISSIONS OF authfor1"));
        assertRowsNet(executeNet("LIST PERMISSIONS OF authfor2"),
                      row("authfor2", "authfor2", "<keyspace authfor_test>", "SELECT", true, false, false),
                      row("authfor2", "authfor2", "<table authfor_test.t1>", "SELECT", true, false, false),
                      row("authfor2", "authfor2", "<table authfor_test.t2>", "SELECT", true, false, false),
                      row("authfor2", "authfor2", "<table authfor_test.t2>", "UPDATE", true, false, true));

        // all permissions and grant options must have been removed
        executeNet("DROP ROLE authfor2");
        executeNet("CREATE USER authfor2 WITH PASSWORD 'pass2'");

        assertRowsNet(executeNet("LIST PERMISSIONS OF authfor1"));
        assertRowsNet(executeNet("LIST PERMISSIONS OF authfor2"));

    }

    @Test
    public void testRestrict() throws Throwable
    {
        // Test story:
        // - one table
        // - one security admin user (restrict1), which must not gain access to a resource (the table)
        // - one role that has legit access to that table
        // - security admin user is granted the role
        // - security admin user must still not be able to access that table
        // - security admin user must not be able to "unrestrict"

        useSuperUser();

        executeNet("CREATE KEYSPACE restrict_test WITH replication = {'class': 'SimpleStrategy', 'replication_factor': '1'}");
        executeNet("CREATE TABLE restrict_test.t1 (id int PRIMARY KEY, val text)");

        executeNet("CREATE ROLE role_restrict");
        executeNet("CREATE USER restrict1 WITH PASSWORD 'restrict1'");

        executeNet("GRANT AUTHORIZE FOR SELECT ON KEYSPACE restrict_test TO restrict1");


        useUser("restrict1", "restrict1");

        assertUnauthorizedQuery("User restrict1 has no SELECT permission on <table restrict_test.t1> or any of its parents",
                                "SELECT * FROM restrict_test.t1");

        executeNet("GRANT SELECT ON KEYSPACE restrict_test TO role_restrict");

        assertUnauthorizedQuery("User restrict1 has no SELECT permission on <table restrict_test.t1> or any of its parents",
                                "SELECT * FROM restrict_test.t1");

        useSuperUser();
        executeNet("GRANT role_restrict TO restrict1");

        useUser("restrict1", "restrict1");
        assertRowsNet(executeNet("SELECT * FROM restrict_test.t1"));

        useSuperUser();
        executeNet("RESTRICT SELECT ON TABLE restrict_test.t1 TO restrict1");

        useUser("restrict1", "restrict1");
        assertUnauthorizedQuery("Access for user restrict1 on <table restrict_test.t1> or any of its parents with SELECT permission is restricted",
                                "SELECT * FROM restrict_test.t1");

        assertUnauthorizedQuery("Only superusers are allowed to RESTRICT/UNRESTRICT",
                                "UNRESTRICT SELECT ON TABLE restrict_test.t1 FROM restrict1");

        assertUnauthorizedQuery("Access for user restrict1 on <table restrict_test.t1> or any of its parents with SELECT permission is restricted",
                                "SELECT * FROM restrict_test.t1");
    }
}

