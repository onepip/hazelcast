/*
 * Copyright 2025 Hazelcast Inc.
 *
 * Licensed under the Hazelcast Community License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://hazelcast.com/hazelcast-community-license
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.sql.impl.expression.predicate;

import com.hazelcast.sql.impl.expression.ExpressionEvalContext;
import com.hazelcast.sql.impl.row.Row;
import com.hazelcast.test.HazelcastParallelClassRunner;
import com.hazelcast.test.annotation.ParallelJVMTest;
import com.hazelcast.test.annotation.QuickTest;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;

import static com.hazelcast.sql.impl.expression.ConstantExpression.FALSE;
import static com.hazelcast.sql.impl.expression.ConstantExpression.NULL;
import static com.hazelcast.sql.impl.expression.ConstantExpression.TRUE;
import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;

@RunWith(HazelcastParallelClassRunner.class)
@Category({QuickTest.class, ParallelJVMTest.class})
public class TernaryLogicTest {

    private static final Row ROW = new MockRow();
    private static final ExpressionEvalContext CONTEXT = mock(ExpressionEvalContext.class);

    @SuppressWarnings("SimplifiableJUnitAssertion")
    @Test
    public void testAnd() {
        assertEquals(true, TernaryLogic.and(ROW, CONTEXT));

        assertEquals(false, TernaryLogic.and(ROW, CONTEXT, FALSE, FALSE));
        assertEquals(false, TernaryLogic.and(ROW, CONTEXT, FALSE, TRUE));
        assertEquals(false, TernaryLogic.and(ROW, CONTEXT, TRUE, FALSE));
        assertEquals(true, TernaryLogic.and(ROW, CONTEXT, TRUE, TRUE));

        assertEquals(false, TernaryLogic.and(ROW, CONTEXT, NULL, FALSE));
        assertEquals(false, TernaryLogic.and(ROW, CONTEXT, FALSE, NULL));

        assertEquals(null, TernaryLogic.and(ROW, CONTEXT, NULL, TRUE));
        assertEquals(null, TernaryLogic.and(ROW, CONTEXT, TRUE, NULL));

        assertEquals(null, TernaryLogic.and(ROW, CONTEXT, NULL, NULL));
    }

    @SuppressWarnings("SimplifiableJUnitAssertion")
    @Test
    public void testOr() {
        assertEquals(false, TernaryLogic.or(ROW, CONTEXT));

        assertEquals(false, TernaryLogic.or(ROW, CONTEXT, FALSE, FALSE));
        assertEquals(true, TernaryLogic.or(ROW, CONTEXT, FALSE, TRUE));
        assertEquals(true, TernaryLogic.or(ROW, CONTEXT, TRUE, FALSE));
        assertEquals(true, TernaryLogic.or(ROW, CONTEXT, TRUE, TRUE));

        assertEquals(null, TernaryLogic.or(ROW, CONTEXT, NULL, FALSE));
        assertEquals(null, TernaryLogic.or(ROW, CONTEXT, FALSE, NULL));

        assertEquals(true, TernaryLogic.or(ROW, CONTEXT, NULL, TRUE));
        assertEquals(true, TernaryLogic.or(ROW, CONTEXT, TRUE, NULL));

        assertEquals(null, TernaryLogic.or(ROW, CONTEXT, NULL, NULL));
    }

    @SuppressWarnings({"ConstantConditions", "SimplifiableJUnitAssertion"})
    @Test
    public void testNot() {
        assertEquals(true, TernaryLogic.not(false));
        assertEquals(false, TernaryLogic.not(true));
        assertEquals(null, TernaryLogic.not(null));
    }

    @SuppressWarnings({"ConstantConditions", "SimplifiableJUnitAssertion"})
    @Test
    public void isNull() {
        assertEquals(false, TernaryLogic.isNull(false));
        assertEquals(false, TernaryLogic.isNull(true));
        assertEquals(true, TernaryLogic.isNull(null));
        assertEquals(false, TernaryLogic.isNull(new Object()));
    }

    @SuppressWarnings({"ConstantConditions", "SimplifiableJUnitAssertion"})
    @Test
    public void isNotNull() {
        assertEquals(true, TernaryLogic.isNotNull(false));
        assertEquals(true, TernaryLogic.isNotNull(true));
        assertEquals(false, TernaryLogic.isNotNull(null));
        assertEquals(true, TernaryLogic.isNotNull(new Object()));
    }

    @SuppressWarnings({"ConstantConditions", "SimplifiableJUnitAssertion"})
    @Test
    public void testIsTrue() {
        assertEquals(false, TernaryLogic.isTrue(false));
        assertEquals(true, TernaryLogic.isTrue(true));
        assertEquals(false, TernaryLogic.isTrue(null));
    }

    @SuppressWarnings({"ConstantConditions", "SimplifiableJUnitAssertion"})
    @Test
    public void testIsNotTrue() {
        assertEquals(true, TernaryLogic.isNotTrue(false));
        assertEquals(false, TernaryLogic.isNotTrue(true));
        assertEquals(true, TernaryLogic.isNotTrue(null));
    }

    @SuppressWarnings({"ConstantConditions", "SimplifiableJUnitAssertion"})
    @Test
    public void testIsFalse() {
        assertEquals(true, TernaryLogic.isFalse(false));
        assertEquals(false, TernaryLogic.isFalse(true));
        assertEquals(false, TernaryLogic.isFalse(null));
    }

    @SuppressWarnings({"ConstantConditions", "SimplifiableJUnitAssertion"})
    @Test
    public void testIsNotFalse() {
        assertEquals(false, TernaryLogic.isNotFalse(false));
        assertEquals(true, TernaryLogic.isNotFalse(true));
        assertEquals(true, TernaryLogic.isNotFalse(null));
    }

    private static class MockRow implements Row {

        @Override
        public <T> T get(int index) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int getColumnCount() {
            throw new UnsupportedOperationException();
        }

    }
}
