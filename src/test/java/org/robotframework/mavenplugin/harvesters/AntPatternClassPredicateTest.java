package org.robotframework.mavenplugin.harvesters;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AntPatternClassPredicateTest {

    @Test
    public void testApplySimple() throws Exception {
        // test data
        AntPatternClassPredicate predicate = new AntPatternClassPredicate("*.List");

        // do the test
        assertTrue(predicate.apply("java.util.List"));
        assertFalse(predicate.apply("java.awt.Window"));
    }

    @Test
    public void testApplySimple1() throws Exception {
        // test data
        AntPatternClassPredicate predicate = new AntPatternClassPredicate("java.**.A");

        // do the test
        assertTrue(predicate.apply("java.util.butil.A"));
        assertFalse(predicate.apply("java.awt.Window"));
    }

    @Test
    public void testApplySimplePattern2() throws Exception {
        // test data
        AntPatternClassPredicate predicate = new AntPatternClassPredicate("java.util.*List");

        // do the test
        assertTrue(predicate.apply("java.util.ArrayList"));
        assertFalse(predicate.apply("java.awt.Window"));
    }

    @Test
    public void testApplyNoPattern() throws Exception {
        // test data
        AntPatternClassPredicate predicate = new AntPatternClassPredicate("java.util.List");

        // do the test
        assertTrue(predicate.apply("java.util.List"));
        assertFalse(predicate.apply("java.awt.Window"));
    }

    @Test
    public void testApplyRecursive() throws Exception {
        // test data
        AntPatternClassPredicate predicate = new AntPatternClassPredicate("java.**.List");

        // do the test
        assertTrue(predicate.apply("java.util.List"));
        assertTrue(predicate.apply("java.bla.bla.bla.List"));
    }

    @Test
    public void testAppliesIgnoresSuffixed() throws Exception {
        // test data
        AntPatternClassPredicate predicate = new AntPatternClassPredicate("java.**.List");

        // do the test
        assertTrue(predicate.apply("java.util.List.class"));
        assertFalse(predicate.apply("java.bla.bla.bla.List.classs"));
    }
}
