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

package org.apache.cassandra.cql3;

import com.datastax.driver.core.exceptions.InvalidQueryException;
import org.apache.cassandra.concurrent.SEPExecutor;
import org.apache.cassandra.concurrent.Stage;
import org.apache.cassandra.db.Keyspace;
import org.apache.cassandra.db.SystemKeyspace;
import org.apache.cassandra.transport.ProtocolVersion;
import org.apache.cassandra.utils.FBUtilities;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ViewFilteringTest2 extends CQLTester
{
    ProtocolVersion protocolVersion = ProtocolVersion.V4;
    private final List<String> views = new ArrayList<>();

    @BeforeClass
    public static void startup()
    {
        requireNetwork();
        System.setProperty("cassandra.mv.allow_filtering_nonkey_columns_unsafe", "true");
    }

    @AfterClass
    public static void TearDown()
    {
        System.setProperty("cassandra.mv.allow_filtering_nonkey_columns_unsafe", "false");
    }

    @Before
    public void begin()
    {
        views.clear();
    }

    @After
    public void end() throws Throwable
    {
        for (String viewName : views)
            executeNet(protocolVersion, "DROP MATERIALIZED VIEW " + viewName);
    }

    private void createView(String name, String query) throws Throwable
    {
        executeNet(protocolVersion, String.format(query, name));
        // If exception is thrown, the view will not be added to the list; since it shouldn't have been created, this is
        // the desired behavior
        views.add(name);
    }

    private void updateView(String query, Object... params) throws Throwable
    {
        executeNet(protocolVersion, query, params);
        while (!(((SEPExecutor) Stage.VIEW_MUTATION.executor()).getPendingTaskCount() == 0
                 && ((SEPExecutor) Stage.VIEW_MUTATION.executor()).getActiveTaskCount() == 0))
        {
            Thread.sleep(1);
        }
    }

    private void dropView(String name) throws Throwable
    {
        executeNet(protocolVersion, "DROP MATERIALIZED VIEW " + name);
        views.remove(name);
    }

    @Test
    public void testClusteringKeyEQRestrictions() throws Throwable
    {
        List<String> mvPrimaryKeys = Arrays.asList("((a, b), c)", "((b, a), c)", "(a, b, c)", "(c, b, a)", "((c, a), b)");
        for (int i = 0; i < mvPrimaryKeys.size(); i++)
        {
            createTable("CREATE TABLE %s (a int, b int, c int, d int, PRIMARY KEY (a, b, c))");

            execute("USE " + keyspace());
            executeNet(protocolVersion, "USE " + keyspace());

            execute("INSERT INTO %s (a, b, c, d) VALUES (?, ?, ?, ?)", 0, 0, 0, 0);
            execute("INSERT INTO %s (a, b, c, d) VALUES (?, ?, ?, ?)", 0, 0, 1, 0);
            execute("INSERT INTO %s (a, b, c, d) VALUES (?, ?, ?, ?)", 0, 1, 0, 0);
            execute("INSERT INTO %s (a, b, c, d) VALUES (?, ?, ?, ?)", 0, 1, 1, 0);
            execute("INSERT INTO %s (a, b, c, d) VALUES (?, ?, ?, ?)", 1, 0, 0, 0);
            execute("INSERT INTO %s (a, b, c, d) VALUES (?, ?, ?, ?)", 1, 0, 1, 0);
            execute("INSERT INTO %s (a, b, c, d) VALUES (?, ?, ?, ?)", 1, 1, 0, 0);
            execute("INSERT INTO %s (a, b, c, d) VALUES (?, ?, ?, ?)", 1, 1, 1, 0);

            logger.info("Testing MV primary key: {}", mvPrimaryKeys.get(i));

            // only accept rows where b = 1
            createView("mv_test" + i, "CREATE MATERIALIZED VIEW %s AS SELECT * FROM %%s WHERE a IS NOT NULL AND b = 1 AND c IS NOT NULL PRIMARY KEY " + mvPrimaryKeys.get(i));

            while (!SystemKeyspace.isViewBuilt(keyspace(), "mv_test" + i))
                Thread.sleep(10);

            assertRowsIgnoringOrder(execute("SELECT a, b, c, d FROM mv_test" + i),
                                    row(0, 1, 0, 0),
                                    row(0, 1, 1, 0),
                                    row(1, 1, 0, 0),
                                    row(1, 1, 1, 0)
            );

            // insert new rows that do not match the filter
            execute("INSERT INTO %s (a, b, c, d) VALUES (?, ?, ?, ?)", 2, 0, 0, 0);
            execute("INSERT INTO %s (a, b, c, d) VALUES (?, ?, ?, ?)", 2, 2, 0, 0);
            assertRowsIgnoringOrder(execute("SELECT a, b, c, d FROM mv_test" + i),
                                    row(0, 1, 0, 0),
                                    row(0, 1, 1, 0),
                                    row(1, 1, 0, 0),
                                    row(1, 1, 1, 0)
            );

            // insert new row that does match the filter
            execute("INSERT INTO %s (a, b, c, d) VALUES (?, ?, ?, ?)", 1, 1, 2, 0);
            assertRowsIgnoringOrder(execute("SELECT a, b, c, d FROM mv_test" + i),
                                    row(0, 1, 0, 0),
                                    row(0, 1, 1, 0),
                                    row(1, 1, 0, 0),
                                    row(1, 1, 1, 0),
                                    row(1, 1, 2, 0)
            );

            // update rows that don't match the filter
            execute("UPDATE %s SET d = ? WHERE a = ? AND b = ? AND c = ?", 1, 2, 0, 0);
            execute("UPDATE %s SET d = ? WHERE a = ? AND b = ? AND c = ?", 1, 2, 2, 0);
            assertRowsIgnoringOrder(execute("SELECT a, b, c, d FROM mv_test" + i),
                                    row(0, 1, 0, 0),
                                    row(0, 1, 1, 0),
                                    row(1, 1, 0, 0),
                                    row(1, 1, 1, 0),
                                    row(1, 1, 2, 0)
            );

            // update a row that does match the filter
            execute("UPDATE %s SET d = ? WHERE a = ? AND b = ? AND c = ?", 1, 1, 1, 0);
            assertRowsIgnoringOrder(execute("SELECT a, b, c, d FROM mv_test" + i),
                                    row(0, 1, 0, 0),
                                    row(0, 1, 1, 0),
                                    row(1, 1, 0, 1),
                                    row(1, 1, 1, 0),
                                    row(1, 1, 2, 0)
            );

            // delete rows that don't match the filter
            execute("DELETE FROM %s WHERE a = ? AND b = ? AND c = ?", 2, 0, 0);
            execute("DELETE FROM %s WHERE a = ? AND b = ? AND c = ?", 2, 2, 0);
            execute("DELETE FROM %s WHERE a = ? AND b = ?", 0, 0);
            assertRowsIgnoringOrder(execute("SELECT a, b, c, d FROM mv_test" + i),
                                    row(0, 1, 0, 0),
                                    row(0, 1, 1, 0),
                                    row(1, 1, 0, 1),
                                    row(1, 1, 1, 0),
                                    row(1, 1, 2, 0)
            );

            // delete a row that does match the filter
            execute("DELETE FROM %s WHERE a = ? AND b = ? AND c = ?", 1, 1, 0);
            assertRowsIgnoringOrder(execute("SELECT a, b, c, d FROM mv_test" + i),
                                    row(0, 1, 0, 0),
                                    row(0, 1, 1, 0),
                                    row(1, 1, 1, 0),
                                    row(1, 1, 2, 0)
            );

            // delete a partition that matches the filter
            execute("DELETE FROM %s WHERE a = ?", 1);
            assertRowsIgnoringOrder(execute("SELECT a, b, c, d FROM mv_test" + i),
                                    row(0, 1, 0, 0),
                                    row(0, 1, 1, 0)
            );

            dropView("mv_test" + i);
            dropTable("DROP TABLE %s");
        }
    }

    @Test
    public void testClusteringKeySliceRestrictions() throws Throwable
    {
        List<String> mvPrimaryKeys = Arrays.asList("((a, b), c)", "((b, a), c)", "(a, b, c)", "(c, b, a)", "((c, a), b)");
        for (int i = 0; i < mvPrimaryKeys.size(); i++)
        {
            createTable("CREATE TABLE %s (a int, b int, c int, d int, PRIMARY KEY (a, b, c))");

            execute("USE " + keyspace());
            executeNet(protocolVersion, "USE " + keyspace());

            execute("INSERT INTO %s (a, b, c, d) VALUES (?, ?, ?, ?)", 0, 0, 0, 0);
            execute("INSERT INTO %s (a, b, c, d) VALUES (?, ?, ?, ?)", 0, 0, 1, 0);
            execute("INSERT INTO %s (a, b, c, d) VALUES (?, ?, ?, ?)", 0, 1, 0, 0);
            execute("INSERT INTO %s (a, b, c, d) VALUES (?, ?, ?, ?)", 0, 1, 1, 0);
            execute("INSERT INTO %s (a, b, c, d) VALUES (?, ?, ?, ?)", 1, 0, 0, 0);
            execute("INSERT INTO %s (a, b, c, d) VALUES (?, ?, ?, ?)", 1, 0, 1, 0);
            execute("INSERT INTO %s (a, b, c, d) VALUES (?, ?, ?, ?)", 1, 1, 0, 0);
            execute("INSERT INTO %s (a, b, c, d) VALUES (?, ?, ?, ?)", 1, 1, 1, 0);

            logger.info("Testing MV primary key: {}", mvPrimaryKeys.get(i));

            createView("mv_test" + i, "CREATE MATERIALIZED VIEW %s AS SELECT * FROM %%s WHERE a IS NOT NULL AND b >= 1 AND c IS NOT NULL PRIMARY KEY " + mvPrimaryKeys.get(i));

            while (!SystemKeyspace.isViewBuilt(keyspace(), "mv_test" + i))
                Thread.sleep(10);

            assertRowsIgnoringOrder(execute("SELECT a, b, c, d FROM mv_test" + i),
                                    row(0, 1, 0, 0),
                                    row(0, 1, 1, 0),
                                    row(1, 1, 0, 0),
                                    row(1, 1, 1, 0)
            );

            // insert new rows that do not match the filter
            execute("INSERT INTO %s (a, b, c, d) VALUES (?, ?, ?, ?)", 2, -1, 0, 0);
            execute("INSERT INTO %s (a, b, c, d) VALUES (?, ?, ?, ?)", 2, 0, 0, 0);
            assertRowsIgnoringOrder(execute("SELECT a, b, c, d FROM mv_test" + i),
                                    row(0, 1, 0, 0),
                                    row(0, 1, 1, 0),
                                    row(1, 1, 0, 0),
                                    row(1, 1, 1, 0)
            );

            // insert new row that does match the filter
            execute("INSERT INTO %s (a, b, c, d) VALUES (?, ?, ?, ?)", 1, 1, 2, 0);
            assertRowsIgnoringOrder(execute("SELECT a, b, c, d FROM mv_test" + i),
                                    row(0, 1, 0, 0),
                                    row(0, 1, 1, 0),
                                    row(1, 1, 0, 0),
                                    row(1, 1, 1, 0),
                                    row(1, 1, 2, 0)
            );

            // update rows that don't match the filter
            execute("UPDATE %s SET d = ? WHERE a = ? AND b = ? AND c = ?", 1, 2, -1, 0);
            execute("UPDATE %s SET d = ? WHERE a = ? AND b = ? AND c = ?", 1, 2, 0, 0);
            assertRowsIgnoringOrder(execute("SELECT a, b, c, d FROM mv_test" + i),
                                    row(0, 1, 0, 0),
                                    row(0, 1, 1, 0),
                                    row(1, 1, 0, 0),
                                    row(1, 1, 1, 0),
                                    row(1, 1, 2, 0)
            );

            // update a row that does match the filter
            execute("UPDATE %s SET d = ? WHERE a = ? AND b = ? AND c = ?", 1, 1, 1, 0);
            assertRowsIgnoringOrder(execute("SELECT a, b, c, d FROM mv_test" + i),
                                    row(0, 1, 0, 0),
                                    row(0, 1, 1, 0),
                                    row(1, 1, 0, 1),
                                    row(1, 1, 1, 0),
                                    row(1, 1, 2, 0)
            );

            // delete rows that don't match the filter
            execute("DELETE FROM %s WHERE a = ? AND b = ? AND c = ?", 2, -1, 0);
            execute("DELETE FROM %s WHERE a = ? AND b = ? AND c = ?", 2, 0, 0);
            execute("DELETE FROM %s WHERE a = ? AND b = ?", 0, 0);
            assertRowsIgnoringOrder(execute("SELECT a, b, c, d FROM mv_test" + i),
                                    row(0, 1, 0, 0),
                                    row(0, 1, 1, 0),
                                    row(1, 1, 0, 1),
                                    row(1, 1, 1, 0),
                                    row(1, 1, 2, 0)
            );

            // delete a row that does match the filter
            execute("DELETE FROM %s WHERE a = ? AND b = ? AND c = ?", 1, 1, 0);
            assertRowsIgnoringOrder(execute("SELECT a, b, c, d FROM mv_test" + i),
                                    row(0, 1, 0, 0),
                                    row(0, 1, 1, 0),
                                    row(1, 1, 1, 0),
                                    row(1, 1, 2, 0)
            );

            // delete a partition that matches the filter
            execute("DELETE FROM %s WHERE a = ?", 1);
            assertRowsIgnoringOrder(execute("SELECT a, b, c, d FROM mv_test" + i),
                                    row(0, 1, 0, 0),
                                    row(0, 1, 1, 0)
            );

            dropView("mv_test" + i);
            dropTable("DROP TABLE %s");
        }
    }

    @Test
    public void testClusteringKeyINRestrictions() throws Throwable
    {
        List<String> mvPrimaryKeys = Arrays.asList("((a, b), c)", "((b, a), c)", "(a, b, c)", "(c, b, a)", "((c, a), b)");
        for (int i = 0; i < mvPrimaryKeys.size(); i++)
        {
            createTable("CREATE TABLE %s (a int, b int, c int, d int, PRIMARY KEY (a, b, c))");

            execute("USE " + keyspace());
            executeNet(protocolVersion, "USE " + keyspace());

            execute("INSERT INTO %s (a, b, c, d) VALUES (?, ?, ?, ?)", 0, 0, 0, 0);
            execute("INSERT INTO %s (a, b, c, d) VALUES (?, ?, ?, ?)", 0, 0, 1, 0);
            execute("INSERT INTO %s (a, b, c, d) VALUES (?, ?, ?, ?)", 0, 1, 0, 0);
            execute("INSERT INTO %s (a, b, c, d) VALUES (?, ?, ?, ?)", 0, 1, 1, 0);
            execute("INSERT INTO %s (a, b, c, d) VALUES (?, ?, ?, ?)", 1, 0, 0, 0);
            execute("INSERT INTO %s (a, b, c, d) VALUES (?, ?, ?, ?)", 1, 0, 1, 0);
            execute("INSERT INTO %s (a, b, c, d) VALUES (?, ?, ?, ?)", 1, 1, 0, 0);
            execute("INSERT INTO %s (a, b, c, d) VALUES (?, ?, ?, ?)", 1, 1, 1, 0);
            execute("INSERT INTO %s (a, b, c, d) VALUES (?, ?, ?, ?)", 1, 2, 1, 0);

            logger.info("Testing MV primary key: {}", mvPrimaryKeys.get(i));

            // only accept rows where b = 1
            createView("mv_test" + i, "CREATE MATERIALIZED VIEW %s AS SELECT * FROM %%s WHERE a IS NOT NULL AND b IN (1, 2) AND c IS NOT NULL PRIMARY KEY " + mvPrimaryKeys.get(i));

            while (!SystemKeyspace.isViewBuilt(keyspace(), "mv_test" + i))
                Thread.sleep(10);

            assertRowsIgnoringOrder(execute("SELECT a, b, c, d FROM mv_test" + i),
                                    row(0, 1, 0, 0),
                                    row(0, 1, 1, 0),
                                    row(1, 1, 0, 0),
                                    row(1, 1, 1, 0),
                                    row(1, 2, 1, 0)
            );

            // insert new rows that do not match the filter
            execute("INSERT INTO %s (a, b, c, d) VALUES (?, ?, ?, ?)", 2, -1, 0, 0);
            execute("INSERT INTO %s (a, b, c, d) VALUES (?, ?, ?, ?)", 2, 0, 0, 0);
            assertRowsIgnoringOrder(execute("SELECT a, b, c, d FROM mv_test" + i),
                                    row(0, 1, 0, 0),
                                    row(0, 1, 1, 0),
                                    row(1, 1, 0, 0),
                                    row(1, 1, 1, 0),
                                    row(1, 2, 1, 0)
            );

            // insert new row that does match the filter
            execute("INSERT INTO %s (a, b, c, d) VALUES (?, ?, ?, ?)", 1, 1, 2, 0);
            assertRowsIgnoringOrder(execute("SELECT a, b, c, d FROM mv_test" + i),
                                    row(0, 1, 0, 0),
                                    row(0, 1, 1, 0),
                                    row(1, 1, 0, 0),
                                    row(1, 1, 1, 0),
                                    row(1, 1, 2, 0),
                                    row(1, 2, 1, 0)
            );

            // update rows that don't match the filter
            execute("UPDATE %s SET d = ? WHERE a = ? AND b = ? AND c = ?", 1, 2, -1, 0);
            execute("UPDATE %s SET d = ? WHERE a = ? AND b = ? AND c = ?", 1, 2, 0, 0);
            assertRowsIgnoringOrder(execute("SELECT a, b, c, d FROM mv_test" + i),
                                    row(0, 1, 0, 0),
                                    row(0, 1, 1, 0),
                                    row(1, 1, 0, 0),
                                    row(1, 1, 1, 0),
                                    row(1, 1, 2, 0),
                                    row(1, 2, 1, 0)
            );

            // update a row that does match the filter
            execute("UPDATE %s SET d = ? WHERE a = ? AND b = ? AND c = ?", 1, 1, 1, 0);
            assertRowsIgnoringOrder(execute("SELECT a, b, c, d FROM mv_test" + i),
                                    row(0, 1, 0, 0),
                                    row(0, 1, 1, 0),
                                    row(1, 1, 0, 1),
                                    row(1, 1, 1, 0),
                                    row(1, 1, 2, 0),
                                    row(1, 2, 1, 0)
            );

            // delete rows that don't match the filter
            execute("DELETE FROM %s WHERE a = ? AND b = ? AND c = ?", 2, -1, 0);
            execute("DELETE FROM %s WHERE a = ? AND b = ? AND c = ?", 2, 0, 0);
            execute("DELETE FROM %s WHERE a = ? AND b = ?", 0, 0);
            assertRowsIgnoringOrder(execute("SELECT a, b, c, d FROM mv_test" + i),
                                    row(0, 1, 0, 0),
                                    row(0, 1, 1, 0),
                                    row(1, 1, 0, 1),
                                    row(1, 1, 1, 0),
                                    row(1, 1, 2, 0),
                                    row(1, 2, 1, 0)
            );

            // delete a row that does match the filter
            execute("DELETE FROM %s WHERE a = ? AND b = ? AND c = ?", 1, 1, 0);
            assertRowsIgnoringOrder(execute("SELECT a, b, c, d FROM mv_test" + i),
                                    row(0, 1, 0, 0),
                                    row(0, 1, 1, 0),
                                    row(1, 1, 1, 0),
                                    row(1, 1, 2, 0),
                                    row(1, 2, 1, 0)
            );

            // delete a partition that matches the filter
            execute("DELETE FROM %s WHERE a = ?", 1);
            assertRowsIgnoringOrder(execute("SELECT a, b, c, d FROM mv_test" + i),
                                    row(0, 1, 0, 0),
                                    row(0, 1, 1, 0)
            );

            dropView("mv_test" + i);
            dropTable("DROP TABLE %s");
        }
    }

    @Test
    public void testClusteringKeyMultiColumnRestrictions() throws Throwable
    {
        List<String> mvPrimaryKeys = Arrays.asList("((a, b), c)", "((b, a), c)", "(a, b, c)", "(c, b, a)", "((c, a), b)");
        for (int i = 0; i < mvPrimaryKeys.size(); i++)
        {
            createTable("CREATE TABLE %s (a int, b int, c int, d int, PRIMARY KEY (a, b, c))");

            execute("USE " + keyspace());
            executeNet(protocolVersion, "USE " + keyspace());

            execute("INSERT INTO %s (a, b, c, d) VALUES (?, ?, ?, ?)", 0, 0, 0, 0);
            execute("INSERT INTO %s (a, b, c, d) VALUES (?, ?, ?, ?)", 0, 0, 1, 0);
            execute("INSERT INTO %s (a, b, c, d) VALUES (?, ?, ?, ?)", 0, 1, 0, 0);
            execute("INSERT INTO %s (a, b, c, d) VALUES (?, ?, ?, ?)", 0, 1, 1, 0);
            execute("INSERT INTO %s (a, b, c, d) VALUES (?, ?, ?, ?)", 1, 0, 0, 0);
            execute("INSERT INTO %s (a, b, c, d) VALUES (?, ?, ?, ?)", 1, 0, 1, 0);
            execute("INSERT INTO %s (a, b, c, d) VALUES (?, ?, ?, ?)", 1, 1, -1, 0);
            execute("INSERT INTO %s (a, b, c, d) VALUES (?, ?, ?, ?)", 1, 1, 0, 0);
            execute("INSERT INTO %s (a, b, c, d) VALUES (?, ?, ?, ?)", 1, 1, 1, 0);

            logger.info("Testing MV primary key: {}", mvPrimaryKeys.get(i));

            // only accept rows where b = 1
            createView("mv_test" + i, "CREATE MATERIALIZED VIEW %s AS SELECT * FROM %%s WHERE a IS NOT NULL AND (b, c) >= (1, 0) PRIMARY KEY " + mvPrimaryKeys.get(i));

            while (!SystemKeyspace.isViewBuilt(keyspace(), "mv_test" + i))
                Thread.sleep(10);

            assertRowsIgnoringOrder(execute("SELECT a, b, c, d FROM mv_test" + i),
                                    row(0, 1, 0, 0),
                                    row(0, 1, 1, 0),
                                    row(1, 1, 0, 0),
                                    row(1, 1, 1, 0)
            );

            // insert new rows that do not match the filter
            execute("INSERT INTO %s (a, b, c, d) VALUES (?, ?, ?, ?)", 2, -1, 0, 0);
            execute("INSERT INTO %s (a, b, c, d) VALUES (?, ?, ?, ?)", 2, 0, 0, 0);
            execute("INSERT INTO %s (a, b, c, d) VALUES (?, ?, ?, ?)", 2, 1, -1, 0);
            assertRowsIgnoringOrder(execute("SELECT a, b, c, d FROM mv_test" + i),
                                    row(0, 1, 0, 0),
                                    row(0, 1, 1, 0),
                                    row(1, 1, 0, 0),
                                    row(1, 1, 1, 0)
            );

            // insert new row that does match the filter
            execute("INSERT INTO %s (a, b, c, d) VALUES (?, ?, ?, ?)", 1, 1, 2, 0);
            assertRowsIgnoringOrder(execute("SELECT a, b, c, d FROM mv_test" + i),
                                    row(0, 1, 0, 0),
                                    row(0, 1, 1, 0),
                                    row(1, 1, 0, 0),
                                    row(1, 1, 1, 0),
                                    row(1, 1, 2, 0)
            );

            // update rows that don't match the filter
            execute("UPDATE %s SET d = ? WHERE a = ? AND b = ? AND c = ?", 1, 1, -1, 0);
            execute("UPDATE %s SET d = ? WHERE a = ? AND b = ? AND c = ?", 1, 2, -1, 0);
            execute("UPDATE %s SET d = ? WHERE a = ? AND b = ? AND c = ?", 1, 2, 0, 0);
            assertRowsIgnoringOrder(execute("SELECT a, b, c, d FROM mv_test" + i),
                                    row(0, 1, 0, 0),
                                    row(0, 1, 1, 0),
                                    row(1, 1, 0, 0),
                                    row(1, 1, 1, 0),
                                    row(1, 1, 2, 0)
            );

            // update a row that does match the filter
            execute("UPDATE %s SET d = ? WHERE a = ? AND b = ? AND c = ?", 1, 1, 1, 0);
            assertRowsIgnoringOrder(execute("SELECT a, b, c, d FROM mv_test" + i),
                                    row(0, 1, 0, 0),
                                    row(0, 1, 1, 0),
                                    row(1, 1, 0, 1),
                                    row(1, 1, 1, 0),
                                    row(1, 1, 2, 0)
            );

            // delete rows that don't match the filter
            execute("DELETE FROM %s WHERE a = ? AND b = ? AND c = ?", 1, 1, -1);
            execute("DELETE FROM %s WHERE a = ? AND b = ? AND c = ?", 2, -1, 0);
            execute("DELETE FROM %s WHERE a = ? AND b = ? AND c = ?", 2, 0, 0);
            execute("DELETE FROM %s WHERE a = ? AND b = ?", 0, 0);
            assertRowsIgnoringOrder(execute("SELECT a, b, c, d FROM mv_test" + i),
                                    row(0, 1, 0, 0),
                                    row(0, 1, 1, 0),
                                    row(1, 1, 0, 1),
                                    row(1, 1, 1, 0),
                                    row(1, 1, 2, 0)
            );

            // delete a row that does match the filter
            execute("DELETE FROM %s WHERE a = ? AND b = ? AND c = ?", 1, 1, 0);
            assertRowsIgnoringOrder(execute("SELECT a, b, c, d FROM mv_test" + i),
                                    row(0, 1, 0, 0),
                                    row(0, 1, 1, 0),
                                    row(1, 1, 1, 0),
                                    row(1, 1, 2, 0)
            );

            // delete a partition that matches the filter
            execute("DELETE FROM %s WHERE a = ?", 1);
            assertRowsIgnoringOrder(execute("SELECT a, b, c, d FROM mv_test" + i),
                                    row(0, 1, 0, 0),
                                    row(0, 1, 1, 0)
            );

            dropView("mv_test" + i);
            dropTable("DROP TABLE %s");
        }
    }

    @Test
    public void testClusteringKeyFilteringRestrictions() throws Throwable
    {
        List<String> mvPrimaryKeys = Arrays.asList("((a, b), c)", "((b, a), c)", "(a, b, c)", "(c, b, a)", "((c, a), b)");
        for (int i = 0; i < mvPrimaryKeys.size(); i++)
        {
            createTable("CREATE TABLE %s (a int, b int, c int, d int, PRIMARY KEY (a, b, c))");

            execute("USE " + keyspace());
            executeNet(protocolVersion, "USE " + keyspace());

            execute("INSERT INTO %s (a, b, c, d) VALUES (?, ?, ?, ?)", 0, 0, 0, 0);
            execute("INSERT INTO %s (a, b, c, d) VALUES (?, ?, ?, ?)", 0, 0, 1, 0);
            execute("INSERT INTO %s (a, b, c, d) VALUES (?, ?, ?, ?)", 0, 1, 0, 0);
            execute("INSERT INTO %s (a, b, c, d) VALUES (?, ?, ?, ?)", 0, 1, 1, 0);
            execute("INSERT INTO %s (a, b, c, d) VALUES (?, ?, ?, ?)", 1, 0, 0, 0);
            execute("INSERT INTO %s (a, b, c, d) VALUES (?, ?, ?, ?)", 1, 0, 1, 0);
            execute("INSERT INTO %s (a, b, c, d) VALUES (?, ?, ?, ?)", 1, 1, -1, 0);
            execute("INSERT INTO %s (a, b, c, d) VALUES (?, ?, ?, ?)", 1, 1, 0, 0);
            execute("INSERT INTO %s (a, b, c, d) VALUES (?, ?, ?, ?)", 1, 1, 1, 0);

            logger.info("Testing MV primary key: {}", mvPrimaryKeys.get(i));

            // only accept rows where b = 1
            createView("mv_test" + i, "CREATE MATERIALIZED VIEW %s AS SELECT * FROM %%s WHERE a IS NOT NULL AND b IS NOT NULL AND c = 1 PRIMARY KEY " + mvPrimaryKeys.get(i));

            while (!SystemKeyspace.isViewBuilt(keyspace(), "mv_test" + i))
                Thread.sleep(10);

            assertRowsIgnoringOrder(execute("SELECT a, b, c, d FROM mv_test" + i),
                                    row(0, 0, 1, 0),
                                    row(0, 1, 1, 0),
                                    row(1, 0, 1, 0),
                                    row(1, 1, 1, 0)
            );

            // insert new rows that do not match the filter
            execute("INSERT INTO %s (a, b, c, d) VALUES (?, ?, ?, ?)", 2, 0, 0, 0);
            execute("INSERT INTO %s (a, b, c, d) VALUES (?, ?, ?, ?)", 2, 1, -1, 0);
            assertRowsIgnoringOrder(execute("SELECT a, b, c, d FROM mv_test" + i),
                                    row(0, 0, 1, 0),
                                    row(0, 1, 1, 0),
                                    row(1, 0, 1, 0),
                                    row(1, 1, 1, 0)
            );

            // insert new row that does match the filter
            execute("INSERT INTO %s (a, b, c, d) VALUES (?, ?, ?, ?)", 1, 2, 1, 0);
            assertRowsIgnoringOrder(execute("SELECT a, b, c, d FROM mv_test" + i),
                                    row(0, 0, 1, 0),
                                    row(0, 1, 1, 0),
                                    row(1, 0, 1, 0),
                                    row(1, 1, 1, 0),
                                    row(1, 2, 1, 0)
            );

            // update rows that don't match the filter
            execute("UPDATE %s SET d = ? WHERE a = ? AND b = ? AND c = ?", 1, 1, -1, 0);
            execute("UPDATE %s SET d = ? WHERE a = ? AND b = ? AND c = ?", 1, 2, 0, 0);
            assertRowsIgnoringOrder(execute("SELECT a, b, c, d FROM mv_test" + i),
                                    row(0, 0, 1, 0),
                                    row(0, 1, 1, 0),
                                    row(1, 0, 1, 0),
                                    row(1, 1, 1, 0),
                                    row(1, 2, 1, 0)
            );

            // update a row that does match the filter
            execute("UPDATE %s SET d = ? WHERE a = ? AND b = ? AND c = ?", 2, 1, 1, 1);
            assertRowsIgnoringOrder(execute("SELECT a, b, c, d FROM mv_test" + i),
                                    row(0, 0, 1, 0),
                                    row(0, 1, 1, 0),
                                    row(1, 0, 1, 0),
                                    row(1, 1, 1, 2),
                                    row(1, 2, 1, 0)
            );

            // delete rows that don't match the filter
            execute("DELETE FROM %s WHERE a = ? AND b = ? AND c = ?", 1, 1, -1);
            execute("DELETE FROM %s WHERE a = ? AND b = ? AND c = ?", 2, -1, 0);
            execute("DELETE FROM %s WHERE a = ? AND b = ? AND c = ?", 2, 0, 0);
            execute("DELETE FROM %s WHERE a = ? AND b = ?", 0, -1);
            assertRowsIgnoringOrder(execute("SELECT a, b, c, d FROM mv_test" + i),
                                    row(0, 0, 1, 0),
                                    row(0, 1, 1, 0),
                                    row(1, 0, 1, 0),
                                    row(1, 1, 1, 2),
                                    row(1, 2, 1, 0)
            );

            // delete a row that does match the filter
            execute("DELETE FROM %s WHERE a = ? AND b = ? AND c = ?", 1, 1, 1);
            assertRowsIgnoringOrder(execute("SELECT a, b, c, d FROM mv_test" + i),
                                    row(0, 0, 1, 0),
                                    row(0, 1, 1, 0),
                                    row(1, 0, 1, 0),
                                    row(1, 2, 1, 0)
            );

            // delete a partition that matches the filter
            execute("DELETE FROM %s WHERE a = ?", 1);
            assertRowsIgnoringOrder(execute("SELECT a, b, c, d FROM mv_test" + i),
                                    row(0, 0, 1, 0),
                                    row(0, 1, 1, 0)
            );

            // insert a partition with one matching and one non-matching row using a batch (CASSANDRA-10614)
            String tableName = KEYSPACE + "." + currentTable();
            execute("BEGIN BATCH " +
                    "INSERT INTO " + tableName + " (a, b, c, d) VALUES (?, ?, ?, ?); " +
                    "INSERT INTO " + tableName + " (a, b, c, d) VALUES (?, ?, ?, ?); " +
                    "APPLY BATCH",
                    4, 4, 0, 0,
                    4, 4, 1, 1);
            assertRowsIgnoringOrder(execute("SELECT a, b, c, d FROM mv_test" + i),
                                    row(0, 0, 1, 0),
                                    row(0, 1, 1, 0),
                                    row(4, 4, 1, 1)
            );

            dropView("mv_test" + i);
            dropTable("DROP TABLE %s");
        }
    }

    @Test
    public void testPartitionKeyAndClusteringKeyFilteringRestrictions() throws Throwable
    {
        List<String> mvPrimaryKeys = Arrays.asList("((a, b), c)", "((b, a), c)", "(a, b, c)", "(c, b, a)", "((c, a), b)");
        for (int i = 0; i < mvPrimaryKeys.size(); i++)
        {
            createTable("CREATE TABLE %s (a int, b int, c int, d int, PRIMARY KEY (a, b, c))");

            execute("USE " + keyspace());
            executeNet(protocolVersion, "USE " + keyspace());

            execute("INSERT INTO %s (a, b, c, d) VALUES (?, ?, ?, ?)", 0, 0, 0, 0);
            execute("INSERT INTO %s (a, b, c, d) VALUES (?, ?, ?, ?)", 0, 0, 1, 0);
            execute("INSERT INTO %s (a, b, c, d) VALUES (?, ?, ?, ?)", 0, 1, 0, 0);
            execute("INSERT INTO %s (a, b, c, d) VALUES (?, ?, ?, ?)", 0, 1, 1, 0);
            execute("INSERT INTO %s (a, b, c, d) VALUES (?, ?, ?, ?)", 1, 0, 0, 0);
            execute("INSERT INTO %s (a, b, c, d) VALUES (?, ?, ?, ?)", 1, 0, 1, 0);
            execute("INSERT INTO %s (a, b, c, d) VALUES (?, ?, ?, ?)", 1, 1, -1, 0);
            execute("INSERT INTO %s (a, b, c, d) VALUES (?, ?, ?, ?)", 1, 1, 0, 0);
            execute("INSERT INTO %s (a, b, c, d) VALUES (?, ?, ?, ?)", 1, 1, 1, 0);

            logger.info("Testing MV primary key: {}", mvPrimaryKeys.get(i));

            // only accept rows where b = 1
            createView("mv_test" + i, "CREATE MATERIALIZED VIEW %s AS SELECT * FROM %%s WHERE a = 1 AND b IS NOT NULL AND c = 1 PRIMARY KEY " + mvPrimaryKeys.get(i));

            while (!SystemKeyspace.isViewBuilt(keyspace(), "mv_test" + i))
                Thread.sleep(10);

            assertRowsIgnoringOrder(execute("SELECT a, b, c, d FROM mv_test" + i),
                                    row(1, 0, 1, 0),
                                    row(1, 1, 1, 0)
            );

            // insert new rows that do not match the filter
            execute("INSERT INTO %s (a, b, c, d) VALUES (?, ?, ?, ?)", 0, 0, 1, 0);
            execute("INSERT INTO %s (a, b, c, d) VALUES (?, ?, ?, ?)", 1, 1, 0, 0);
            assertRowsIgnoringOrder(execute("SELECT a, b, c, d FROM mv_test" + i),
                                    row(1, 0, 1, 0),
                                    row(1, 1, 1, 0)
            );

            // insert new row that does match the filter
            execute("INSERT INTO %s (a, b, c, d) VALUES (?, ?, ?, ?)", 1, 2, 1, 0);
            assertRowsIgnoringOrder(execute("SELECT a, b, c, d FROM mv_test" + i),
                                    row(1, 0, 1, 0),
                                    row(1, 1, 1, 0),
                                    row(1, 2, 1, 0)
            );

            // update rows that don't match the filter
            execute("UPDATE %s SET d = ? WHERE a = ? AND b = ? AND c = ?", 1, 1, -1, 0);
            execute("UPDATE %s SET d = ? WHERE a = ? AND b = ? AND c = ?", 0, 1, 1, 0);
            assertRowsIgnoringOrder(execute("SELECT a, b, c, d FROM mv_test" + i),
                                    row(1, 0, 1, 0),
                                    row(1, 1, 1, 0),
                                    row(1, 2, 1, 0)
            );

            // update a row that does match the filter
            execute("UPDATE %s SET d = ? WHERE a = ? AND b = ? AND c = ?", 2, 1, 1, 1);
            assertRowsIgnoringOrder(execute("SELECT a, b, c, d FROM mv_test" + i),
                                    row(1, 0, 1, 0),
                                    row(1, 1, 1, 2),
                                    row(1, 2, 1, 0)
            );

            // delete rows that don't match the filter
            execute("DELETE FROM %s WHERE a = ? AND b = ? AND c = ?", 1, 1, -1);
            execute("DELETE FROM %s WHERE a = ? AND b = ? AND c = ?", 2, 0, 1);
            execute("DELETE FROM %s WHERE a = ?", 0);
            assertRowsIgnoringOrder(execute("SELECT a, b, c, d FROM mv_test" + i),
                                    row(1, 0, 1, 0),
                                    row(1, 1, 1, 2),
                                    row(1, 2, 1, 0)
            );

            // delete a row that does match the filter
            execute("DELETE FROM %s WHERE a = ? AND b = ? AND c = ?", 1, 1, 1);
            assertRowsIgnoringOrder(execute("SELECT a, b, c, d FROM mv_test" + i),
                                    row(1, 0, 1, 0),
                                    row(1, 2, 1, 0)
            );

            // delete a partition that matches the filter
            execute("DELETE FROM %s WHERE a = ?", 1);
            assertEmpty(execute("SELECT a, b, c, d FROM mv_test" + i));

            dropView("mv_test" + i);
            dropTable("DROP TABLE %s");
        }
    }

    @Test
    public void testAllTypes() throws Throwable
    {
        String myType = createType("CREATE TYPE %s (a int, b uuid, c set<text>)");
        String columnNames = "asciival, " +
                             "bigintval, " +
                             "blobval, " +
                             "booleanval, " +
                             "dateval, " +
                             "decimalval, " +
                             "doubleval, " +
                             "floatval, " +
                             "inetval, " +
                             "intval, " +
                             "textval, " +
                             "timeval, " +
                             "timestampval, " +
                             "timeuuidval, " +
                             "uuidval," +
                             "varcharval, " +
                             "varintval, " +
                             "frozenlistval, " +
                             "frozensetval, " +
                             "frozenmapval, " +
                             "tupleval, " +
                             "udtval";

        createTable(
        "CREATE TABLE %s (" +
        "asciival ascii, " +
        "bigintval bigint, " +
        "blobval blob, " +
        "booleanval boolean, " +
        "dateval date, " +
        "decimalval decimal, " +
        "doubleval double, " +
        "floatval float, " +
        "inetval inet, " +
        "intval int, " +
        "textval text, " +
        "timeval time, " +
        "timestampval timestamp, " +
        "timeuuidval timeuuid, " +
        "uuidval uuid," +
        "varcharval varchar, " +
        "varintval varint, " +
        "frozenlistval frozen<list<int>>, " +
        "frozensetval frozen<set<uuid>>, " +
        "frozenmapval frozen<map<ascii, int>>," +
        "tupleval frozen<tuple<int, ascii, uuid>>," +
        "udtval frozen<" + myType + ">, " +
        "PRIMARY KEY (" + columnNames + "))");

        execute("USE " + keyspace());
        executeNet(protocolVersion, "USE " + keyspace());


        createView(
        "mv_test",
        "CREATE MATERIALIZED VIEW %s AS SELECT * FROM %%s WHERE " +
        "asciival = 'abc' AND " +
        "bigintval = 123 AND " +
        "blobval = 0xfeed AND " +
        "booleanval = true AND " +
        "dateval = '1987-03-23' AND " +
        "decimalval = 123.123 AND " +
        "doubleval = 123.123 AND " +
        "floatval = 123.123 AND " +
        "inetval = '127.0.0.1' AND " +
        "intval = 123 AND " +
        "textval = 'abc' AND " +
        "timeval = '07:35:07.000111222' AND " +
        "timestampval = 123123123 AND " +
        "timeuuidval = 6BDDC89A-5644-11E4-97FC-56847AFE9799 AND " +
        "uuidval = 6BDDC89A-5644-11E4-97FC-56847AFE9799 AND " +
        "varcharval = 'abc' AND " +
        "varintval = 123123123 AND " +
        "frozenlistval = [1, 2, 3] AND " +
        "frozensetval = {6BDDC89A-5644-11E4-97FC-56847AFE9799} AND " +
        "frozenmapval = {'a': 1, 'b': 2} AND " +
        "tupleval = (1, 'foobar', 6BDDC89A-5644-11E4-97FC-56847AFE9799) AND " +
        "udtval = {a: 1, b: 6BDDC89A-5644-11E4-97FC-56847AFE9799, c: {'foo', 'bar'}} " +
        "PRIMARY KEY (" + columnNames + ")");

        execute("INSERT INTO %s (" + columnNames + ") VALUES (" +
                "'abc'," +
                "123," +
                "0xfeed," +
                "true," +
                "'1987-03-23'," +
                "123.123," +
                "123.123," +
                "123.123," +
                "'127.0.0.1'," +
                "123," +
                "'abc'," +
                "'07:35:07.000111222'," +
                "123123123," +
                "6BDDC89A-5644-11E4-97FC-56847AFE9799," +
                "6BDDC89A-5644-11E4-97FC-56847AFE9799," +
                "'abc'," +
                "123123123," +
                "[1, 2, 3]," +
                "{6BDDC89A-5644-11E4-97FC-56847AFE9799}," +
                "{'a': 1, 'b': 2}," +
                "(1, 'foobar', 6BDDC89A-5644-11E4-97FC-56847AFE9799)," +
                "{a: 1, b: 6BDDC89A-5644-11E4-97FC-56847AFE9799, c: {'foo', 'bar'}})");

        assert !execute("SELECT * FROM mv_test").isEmpty();

        executeNet(protocolVersion, "ALTER TABLE %s RENAME inetval TO foo");
        assert !execute("SELECT * FROM mv_test").isEmpty();
    }

    @Test
    public void testMVCreationWithNonPrimaryRestrictions() throws Throwable
    {
        createTable("CREATE TABLE %s (a int, b int, c int, d int, PRIMARY KEY (a, b))");

        execute("USE " + keyspace());
        executeNet(protocolVersion, "USE " + keyspace());

        try {
            createView("mv_test", "CREATE MATERIALIZED VIEW %s AS SELECT * FROM %%s WHERE a IS NOT NULL AND b IS NOT NULL AND c IS NOT NULL AND d = 1 PRIMARY KEY (a, b, c)");
            dropView("mv_test");
        } catch(Exception e) {
            throw new RuntimeException("MV creation with non primary column restrictions failed.", e);
        }

        dropTable("DROP TABLE %s");
    }

    @Test
    public void testNonPrimaryRestrictions() throws Throwable
    {
        createTable("CREATE TABLE %s (a int, b int, c int, d int, PRIMARY KEY (a, b))");

        execute("USE " + keyspace());
        executeNet(protocolVersion, "USE " + keyspace());

        execute("INSERT INTO %s (a, b, c, d) VALUES (?, ?, ?, ?)", 0, 0, 0, 0);
        execute("INSERT INTO %s (a, b, c, d) VALUES (?, ?, ?, ?)", 0, 0, 1, 0);
        execute("INSERT INTO %s (a, b, c, d) VALUES (?, ?, ?, ?)", 0, 1, 0, 0);
        execute("INSERT INTO %s (a, b, c, d) VALUES (?, ?, ?, ?)", 0, 1, 1, 0);
        execute("INSERT INTO %s (a, b, c, d) VALUES (?, ?, ?, ?)", 1, 0, 0, 0);
        execute("INSERT INTO %s (a, b, c, d) VALUES (?, ?, ?, ?)", 1, 0, 1, 0);
        execute("INSERT INTO %s (a, b, c, d) VALUES (?, ?, ?, ?)", 1, 1, 0, 0);
        execute("INSERT INTO %s (a, b, c, d) VALUES (?, ?, ?, ?)", 1, 1, 1, 0);

        // only accept rows where c = 1
        createView("mv_test", "CREATE MATERIALIZED VIEW %s AS SELECT * FROM %%s WHERE a IS NOT NULL AND b IS NOT NULL AND c IS NOT NULL AND c = 1 PRIMARY KEY (a, b, c)");

        while (!SystemKeyspace.isViewBuilt(keyspace(), "mv_test"))
            Thread.sleep(10);

        assertRowsIgnoringOrder(execute("SELECT a, b, c, d FROM mv_test"),
                                row(0, 0, 1, 0),
                                row(0, 1, 1, 0),
                                row(1, 0, 1, 0),
                                row(1, 1, 1, 0)
        );

        // insert new rows that do not match the filter
        execute("INSERT INTO %s (a, b, c, d) VALUES (?, ?, ?, ?)", 2, 0, 0, 0);
        execute("INSERT INTO %s (a, b, c, d) VALUES (?, ?, ?, ?)", 2, 1, 2, 0);
        assertRowsIgnoringOrder(execute("SELECT a, b, c, d FROM mv_test"),
                                row(0, 0, 1, 0),
                                row(0, 1, 1, 0),
                                row(1, 0, 1, 0),
                                row(1, 1, 1, 0)
        );

        // insert new row that does match the filter
        execute("INSERT INTO %s (a, b, c, d) VALUES (?, ?, ?, ?)", 1, 2, 1, 0);
        assertRowsIgnoringOrder(execute("SELECT a, b, c, d FROM mv_test"),
                                row(0, 0, 1, 0),
                                row(0, 1, 1, 0),
                                row(1, 0, 1, 0),
                                row(1, 1, 1, 0),
                                row(1, 2, 1, 0)
        );

        // update rows that don't match the filter
        execute("UPDATE %s SET d = ? WHERE a = ? AND b = ?", 2, 2, 0);
        execute("UPDATE %s SET d = ? WHERE a = ? AND b = ?", 1, 2, 1);
        assertRowsIgnoringOrder(execute("SELECT a, b, c, d FROM mv_test"),
                                row(0, 0, 1, 0),
                                row(0, 1, 1, 0),
                                row(1, 0, 1, 0),
                                row(1, 1, 1, 0),
                                row(1, 2, 1, 0)
        );

        // update a row that does match the filter
        execute("UPDATE %s SET d = ? WHERE a = ? AND b = ?", 1, 1, 0);
        assertRowsIgnoringOrder(execute("SELECT a, b, c, d FROM mv_test"),
                                row(0, 0, 1, 0),
                                row(0, 1, 1, 0),
                                row(1, 0, 1, 1),
                                row(1, 1, 1, 0),
                                row(1, 2, 1, 0)
        );

        // delete rows that don't match the filter
        execute("DELETE FROM %s WHERE a = ? AND b = ?", 2, 0);
        assertRowsIgnoringOrder(execute("SELECT a, b, c, d FROM mv_test"),
                                row(0, 0, 1, 0),
                                row(0, 1, 1, 0),
                                row(1, 0, 1, 1),
                                row(1, 1, 1, 0),
                                row(1, 2, 1, 0)
        );

        // delete a row that does match the filter
        execute("DELETE FROM %s WHERE a = ? AND b = ?", 1, 2);
        assertRowsIgnoringOrder(execute("SELECT a, b, c, d FROM mv_test"),
                                row(0, 0, 1, 0),
                                row(0, 1, 1, 0),
                                row(1, 0, 1, 1),
                                row(1, 1, 1, 0)
        );

        // delete a partition that matches the filter
        execute("DELETE FROM %s WHERE a = ?", 1);
        assertRowsIgnoringOrder(execute("SELECT a, b, c, d FROM mv_test"),
                                row(0, 0, 1, 0),
                                row(0, 1, 1, 0)
        );

        dropView("mv_test");
        dropTable("DROP TABLE %s");
    }

    @Test
    public void complexRestrictedTimestampUpdateTestWithFlush() throws Throwable
    {
        complexRestrictedTimestampUpdateTest(true);
    }

    @Test
    public void complexRestrictedTimestampUpdateTestWithoutFlush() throws Throwable
    {
        complexRestrictedTimestampUpdateTest(false);
    }

    public void complexRestrictedTimestampUpdateTest(boolean flush) throws Throwable
    {
        createTable("CREATE TABLE %s (a int, b int, c int, d int, e int, PRIMARY KEY (a, b))");

        execute("USE " + keyspace());
        executeNet(protocolVersion, "USE " + keyspace());
        Keyspace ks = Keyspace.open(keyspace());

        createView("mv", "CREATE MATERIALIZED VIEW %s AS SELECT * FROM %%s WHERE a IS NOT NULL AND b IS NOT NULL AND c IS NOT NULL AND c = 1 PRIMARY KEY (c, a, b)");
        ks.getColumnFamilyStore("mv").disableAutoCompaction();

        //Set initial values TS=0, matching the restriction and verify view
        executeNet(protocolVersion, "INSERT INTO %s (a, b, c, d) VALUES (0, 0, 1, 0) USING TIMESTAMP 0");
        assertRows(execute("SELECT d from mv WHERE c = ? and a = ? and b = ?", 1, 0, 0), row(0));

        if (flush)
            FBUtilities.waitOnFutures(ks.flush());

        //update c's timestamp TS=2
        executeNet(protocolVersion, "UPDATE %s USING TIMESTAMP 2 SET c = ? WHERE a = ? and b = ? ", 1, 0, 0);
        assertRows(execute("SELECT d from mv WHERE c = ? and a = ? and b = ?", 1, 0, 0), row(0));

        if (flush)
            FBUtilities.waitOnFutures(ks.flush());

        //change c's value and TS=3, tombstones c=1 and adds c=0 record
        executeNet(protocolVersion, "UPDATE %s USING TIMESTAMP 3 SET c = ? WHERE a = ? and b = ? ", 0, 0, 0);
        assertRows(execute("SELECT d from mv WHERE c = ? and a = ? and b = ?", 0, 0, 0));

        if(flush)
        {
            ks.getColumnFamilyStore("mv").forceMajorCompaction();
            FBUtilities.waitOnFutures(ks.flush());
        }

        //change c's value back to 1 with TS=4, check we can see d
        executeNet(protocolVersion, "UPDATE %s USING TIMESTAMP 4 SET c = ? WHERE a = ? and b = ? ", 1, 0, 0);
        if (flush)
        {
            ks.getColumnFamilyStore("mv").forceMajorCompaction();
            FBUtilities.waitOnFutures(ks.flush());
        }

        assertRows(execute("SELECT d, e from mv WHERE c = ? and a = ? and b = ?", 1, 0, 0), row(0, null));


        //Add e value @ TS=1
        executeNet(protocolVersion, "UPDATE %s USING TIMESTAMP 1 SET e = ? WHERE a = ? and b = ? ", 1, 0, 0);
        assertRows(execute("SELECT d, e from mv WHERE c = ? and a = ? and b = ?", 1, 0, 0), row(0, 1));

        if (flush)
            FBUtilities.waitOnFutures(ks.flush());


        //Change d value @ TS=2
        executeNet(protocolVersion, "UPDATE %s USING TIMESTAMP 2 SET d = ? WHERE a = ? and b = ? ", 2, 0, 0);
        assertRows(execute("SELECT d from mv WHERE c = ? and a = ? and b = ?", 1, 0, 0), row(2));

        if (flush)
            FBUtilities.waitOnFutures(ks.flush());


        //Change d value @ TS=3
        executeNet(protocolVersion, "UPDATE %s USING TIMESTAMP 3 SET d = ? WHERE a = ? and b = ? ", 1, 0, 0);
        assertRows(execute("SELECT d from mv WHERE c = ? and a = ? and b = ?", 1, 0, 0), row(1));


        //Tombstone c
        executeNet(protocolVersion, "DELETE FROM %s WHERE a = ? and b = ?", 0, 0);
        assertRowsIgnoringOrder(execute("SELECT d from mv"));
        assertRows(execute("SELECT d from mv"));

        //Add back without D
        executeNet(protocolVersion, "INSERT INTO %s (a, b, c) VALUES (0, 0, 1)");

        //Make sure D doesn't pop back in.
        assertRows(execute("SELECT d from mv WHERE c = ? and a = ? and b = ?", 1, 0, 0), row((Object) null));


        //New partition
        // insert a row with timestamp 0
        executeNet(protocolVersion, "INSERT INTO %s (a, b, c, d, e) VALUES (?, ?, ?, ?, ?) USING TIMESTAMP 0", 1, 0, 1, 0, 0);

        // overwrite pk and e with timestamp 1, but don't overwrite d
        executeNet(protocolVersion, "INSERT INTO %s (a, b, c, e) VALUES (?, ?, ?, ?) USING TIMESTAMP 1", 1, 0, 1, 0);

        // delete with timestamp 0 (which should only delete d)
        executeNet(protocolVersion, "DELETE FROM %s USING TIMESTAMP 0 WHERE a = ? AND b = ?", 1, 0);
        assertRows(execute("SELECT a, b, c, d, e from mv WHERE c = ? and a = ? and b = ?", 1, 1, 0),
                   row(1, 0, 1, null, 0)
        );

        executeNet(protocolVersion, "UPDATE %s USING TIMESTAMP 2 SET c = ? WHERE a = ? AND b = ?", 1, 1, 1);
        executeNet(protocolVersion, "UPDATE %s USING TIMESTAMP 3 SET c = ? WHERE a = ? AND b = ?", 1, 1, 0);
        assertRows(execute("SELECT a, b, c, d, e from mv WHERE c = ? and a = ? and b = ?", 1, 1, 0),
                   row(1, 0, 1, null, 0)
        );

        executeNet(protocolVersion, "UPDATE %s USING TIMESTAMP 3 SET d = ? WHERE a = ? AND b = ?", 0, 1, 0);
        assertRows(execute("SELECT a, b, c, d, e from mv WHERE c = ? and a = ? and b = ?", 1, 1, 0),
                   row(1, 0, 1, 0, 0)
        );
    }

    @Test
    public void testRestrictedRegularColumnTimestampUpdates() throws Throwable
    {
        // Regression test for CASSANDRA-10910

        createTable("CREATE TABLE %s (" +
                    "k int PRIMARY KEY, " +
                    "c int, " +
                    "val int)");

        execute("USE " + keyspace());
        executeNet(protocolVersion, "USE " + keyspace());

        createView("mv_rctstest", "CREATE MATERIALIZED VIEW %s AS SELECT * FROM %%s WHERE k IS NOT NULL AND c IS NOT NULL AND c = 1 PRIMARY KEY (k,c)");

        updateView("UPDATE %s SET c = ?, val = ? WHERE k = ?", 0, 0, 0);
        updateView("UPDATE %s SET val = ? WHERE k = ?", 1, 0);
        updateView("UPDATE %s SET c = ? WHERE k = ?", 1, 0);
        assertRows(execute("SELECT c, k, val FROM mv_rctstest"), row(1, 0, 1));

        updateView("TRUNCATE %s");

        updateView("UPDATE %s USING TIMESTAMP 1 SET c = ?, val = ? WHERE k = ?", 0, 0, 0);
        updateView("UPDATE %s USING TIMESTAMP 3 SET c = ? WHERE k = ?", 1, 0);
        updateView("UPDATE %s USING TIMESTAMP 2 SET val = ? WHERE k = ?", 1, 0);
        updateView("UPDATE %s USING TIMESTAMP 4 SET c = ? WHERE k = ?", 1, 0);
        updateView("UPDATE %s USING TIMESTAMP 3 SET val = ? WHERE k = ?", 2, 0);
        assertRows(execute("SELECT c, k, val FROM mv_rctstest"), row(1, 0, 2));
    }

    @Test
    public void testOldTimestampsWithRestrictions() throws Throwable
    {
        createTable("CREATE TABLE %s (" +
                    "k int, " +
                    "c int, " +
                    "val text, " + "" +
                    "PRIMARY KEY(k, c))");

        execute("USE " + keyspace());
        executeNet(protocolVersion, "USE " + keyspace());

        createView("mv_tstest", "CREATE MATERIALIZED VIEW %s AS SELECT * FROM %%s WHERE val IS NOT NULL AND k IS NOT NULL AND c IS NOT NULL AND val = 'baz' PRIMARY KEY (val,k,c)");

        for (int i = 0; i < 100; i++)
            updateView("INSERT into %s (k,c,val)VALUES(?,?,?)", 0, i % 2, "baz");

        Keyspace.open(keyspace()).getColumnFamilyStore(currentTable()).forceBlockingFlush();

        Assert.assertEquals(2, execute("select * from %s").size());
        Assert.assertEquals(2, execute("select * from mv_tstest").size());

        assertRows(execute("SELECT val from %s where k = 0 and c = 0"), row("baz"));
        assertRows(execute("SELECT c from mv_tstest where k = 0 and val = ?", "baz"), row(0), row(1));

        //Make sure an old TS does nothing
        updateView("UPDATE %s USING TIMESTAMP 100 SET val = ? where k = ? AND c = ?", "bar", 0, 1);
        assertRows(execute("SELECT val from %s where k = 0 and c = 1"), row("baz"));
        assertRows(execute("SELECT c from mv_tstest where k = 0 and val = ?", "baz"), row(0), row(1));
        assertRows(execute("SELECT c from mv_tstest where k = 0 and val = ?", "bar"));

        //Latest TS
        updateView("UPDATE %s SET val = ? where k = ? AND c = ?", "bar", 0, 1);
        assertRows(execute("SELECT val from %s where k = 0 and c = 1"), row("bar"));
        assertRows(execute("SELECT c from mv_tstest where k = 0 and val = ?", "bar"));
        assertRows(execute("SELECT c from mv_tstest where k = 0 and val = ?", "baz"), row(0));
    }
}