/*
 * Copyright (c) 2026-present, salesforce.com, inc.
 * All rights reserved.
 * Redistribution and use of this software in source and binary forms, with or
 * without modification, are permitted provided that the following conditions
 * are met:
 * - Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 * - Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 * - Neither the name of salesforce.com, inc. nor the names of its contributors
 * may be used to endorse or promote products derived from this software without
 * specific prior written permission of salesforce.com, inc.
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package com.salesforce.androidsdk.smartstore.store;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.salesforce.androidsdk.smartstore.store.SmartStore.SmartStoreException;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Vector DB spike — pure-unit tests for
 * {@link QuerySpec#buildVectorMatchQuerySpec} and {@link QuerySpec#floatArrayToVecJson}.
 * These don't open a database; they exercise the Smart-SQL generation and arg
 * binding logic only. End-to-end behaviour (`query()` returning the right
 * rows) is covered by {@link SmartStoreVectorSearchTest}.
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class QuerySpecVectorTest {

    private static final String SOUP = "articles";
    private static final String PATH = "embedding";

    // --- floatArrayToVecJson -------------------------------------------

    @Test
    public void floatArrayToVecJsonEmpty() {
        Assert.assertEquals("[]", QuerySpec.floatArrayToVecJson(new float[0]));
    }

    @Test
    public void floatArrayToVecJsonNullReturnsNull() {
        Assert.assertNull(QuerySpec.floatArrayToVecJson(null));
    }

    @Test
    public void floatArrayToVecJsonSingleElement() {
        Assert.assertEquals("[0.5]", QuerySpec.floatArrayToVecJson(new float[] { 0.5f }));
    }

    @Test
    public void floatArrayToVecJsonMultipleElements() {
        String json = QuerySpec.floatArrayToVecJson(new float[] { 0.1f, 0.2f, 0.3f });
        // Don't assert exact Float.toString() output; just verify structure.
        Assert.assertTrue("Should start with [ — got " + json, json.startsWith("["));
        Assert.assertTrue("Should end with ] — got " + json, json.endsWith("]"));
        Assert.assertEquals("Expected 2 commas for 3 elements", 2, json.chars().filter(c -> c == ',').count());
    }

    // --- buildVectorMatchQuerySpec: smartSql shape ----------------------

    @Test
    public void simpleVectorMatchSmartSql() {
        QuerySpec spec = QuerySpec.buildVectorMatchQuerySpec(
                SOUP, PATH, new float[] { 0.1f, 0.2f, 0.3f }, /* k */ 5, /* pageSize */ 5);
        String sql = spec.smartSql;

        // Top-level select is the whole soup (selectPaths = null)
        Assert.assertTrue("Select should project _soup: " + sql, sql.contains("{" + SOUP + ":_soup}"));

        // FROM clause references the main soup table
        Assert.assertTrue("FROM should reference {articles}: " + sql, sql.contains("FROM {" + SOUP + "} "));

        // WHERE clause pulls soupEntryId IN (SELECT rowid FROM {soup:path:vec} ...)
        Assert.assertTrue("WHERE should filter by _soupEntryId IN (...): " + sql,
                sql.contains("{" + SOUP + ":_soupEntryId} IN ("));

        // Vector sub-select references the vec-qualified smart-sql token
        Assert.assertTrue("Subselect should reference {articles:embedding:vec}: " + sql,
                sql.contains("{" + SOUP + ":" + PATH + ":vec}"));

        // Embedding is MATCH'd via vec_f32('[...]') literal
        Assert.assertTrue("Subselect should inline embedding via vec_f32('[...]'): " + sql,
                sql.contains(SmartStore.EMBEDDING_COL + " MATCH vec_f32('[0.1,0.2,0.3]')"));

        // k is bound positionally
        Assert.assertTrue("Subselect should carry 'AND k = ?' for bound k: " + sql,
                sql.contains("AND k = ?"));
    }

    @Test
    public void vectorMatchWithSelectPathsProjectsRequested() {
        QuerySpec spec = QuerySpec.buildVectorMatchQuerySpec(
                SOUP, new String[] { "title", "embedding" }, PATH,
                new float[] { 1f, 2f }, 3, null, QuerySpec.Order.ascending, 3);
        Assert.assertTrue("Select should project title: " + spec.smartSql,
                spec.smartSql.contains("{" + SOUP + ":title}"));
        Assert.assertTrue("Select should project embedding path: " + spec.smartSql,
                spec.smartSql.contains("{" + SOUP + ":" + PATH + "}"));
    }

    @Test
    public void vectorMatchExposesK_AsArg() {
        QuerySpec spec = QuerySpec.buildVectorMatchQuerySpec(
                SOUP, PATH, new float[] { 0.1f, 0.2f }, /* k */ 17, /* pageSize */ 17);
        String[] args = spec.getArgs();
        Assert.assertNotNull("vector_match args should carry k", args);
        Assert.assertEquals("Exactly one positional arg (k)", 1, args.length);
        Assert.assertEquals("k should be serialized as decimal string", "17", args[0]);
    }

    @Test
    public void vectorMatchIsTypeVectorMatch() {
        QuerySpec spec = QuerySpec.buildVectorMatchQuerySpec(
                SOUP, PATH, new float[] { 1f }, 1, 1);
        Assert.assertEquals(QuerySpec.QueryType.vector_match, spec.queryType);
    }

    // --- buildVectorMatchQuerySpec: input validation --------------------

    @Test
    public void nullPathRejected() {
        try {
            QuerySpec.buildVectorMatchQuerySpec(SOUP, null, new float[] { 1f }, 1, 1);
            Assert.fail("Expected SmartStoreException for null path");
        } catch (SmartStoreException expected) {
            Assert.assertTrue("Message should mention path: " + expected.getMessage(),
                    expected.getMessage().toLowerCase().contains("path"));
        }
    }

    @Test
    public void nonPositiveKRejected() {
        try {
            QuerySpec.buildVectorMatchQuerySpec(SOUP, PATH, new float[] { 1f }, 0, 1);
            Assert.fail("Expected SmartStoreException for k <= 0");
        } catch (SmartStoreException expected) {
            Assert.assertTrue(expected.getMessage().contains("k must be > 0"));
        }

        try {
            QuerySpec.buildVectorMatchQuerySpec(SOUP, PATH, new float[] { 1f }, -1, 1);
            Assert.fail("Expected SmartStoreException for k < 0");
        } catch (SmartStoreException expected) {
            Assert.assertTrue(expected.getMessage().contains("k must be > 0"));
        }
    }

    // --- String overload (used by fromJSON) -----------------------------

    @Test
    public void stringOverloadInlinesPreFormattedJson() {
        QuerySpec spec = QuerySpec.buildVectorMatchQuerySpec(
                SOUP, null, PATH, "[9,8,7]", 2, null, QuerySpec.Order.ascending, 2);
        Assert.assertTrue("Should inline exact JSON provided: " + spec.smartSql,
                spec.smartSql.contains("MATCH vec_f32('[9,8,7]')"));
    }
}
