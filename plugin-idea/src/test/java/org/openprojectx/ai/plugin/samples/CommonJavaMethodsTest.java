package org.openprojectx.ai.plugin.samples;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CommonJavaMethodsTest {

    private CommonJavaMethods commonJavaMethods;

    @BeforeEach
    void setUp() {
        commonJavaMethods = new CommonJavaMethods();
    }

    @Test
    void testAdd() {
        assertEquals(5, commonJavaMethods.add(2, 3));
        assertEquals(-1, commonJavaMethods.add(-2, 1));
        assertEquals(0, commonJavaMethods.add(0, 0));
        assertEquals(Integer.MAX_VALUE, commonJavaMethods.add(Integer.MAX_VALUE, 0));
    }

    @Test
    void testSubtract() {
        assertEquals(1, commonJavaMethods.subtract(3, 2));
        assertEquals(-3, commonJavaMethods.subtract(2, 5));
        assertEquals(0, commonJavaMethods.subtract(0, 0));
        assertEquals(Integer.MIN_VALUE, commonJavaMethods.subtract(Integer.MIN_VALUE, 0));
    }

    @Test
    void testMultiply() {
        assertEquals(6, commonJavaMethods.multiply(2, 3));
        assertEquals(-6, commonJavaMethods.multiply(-2, 3));
        assertEquals(0, commonJavaMethods.multiply(0, 5));
        assertEquals(Integer.MAX_VALUE, commonJavaMethods.multiply(Integer.MAX_VALUE, 1));
    }

    @Test
    void testDivide() {
        assertEquals(2.0, commonJavaMethods.divide(6.0, 3.0), 1e-9);
        assertEquals(-2.0, commonJavaMethods.divide(-6.0, 3.0), 1e-9);
        assertEquals(Double.POSITIVE_INFINITY, commonJavaMethods.divide(1.0, 0.0), 1e-9);
        assertThrows(IllegalArgumentException.class, () -> commonJavaMethods.divide(1.0, 0));
    }

    @Test
    void testIsEven() {
        assertTrue(commonJavaMethods.isEven(2));
        assertTrue(commonJavaMethods.isEven(0));
        assertFalse(commonJavaMethods.isEven(1));
        assertFalse(commonJavaMethods.isEven(-1));
    }

    @Test
    void testReverse() {
        assertNull(commonJavaMethods.reverse(null));
        assertEquals("", commonJavaMethods.reverse(""));
        assertEquals("olleh", commonJavaMethods.reverse("hello"));
        assertEquals("a", commonJavaMethods.reverse("a"));
    }

    @Test
    void testFactorial() {
        assertEquals(1L, commonJavaMethods.factorial(0));
        assertEquals(1L, commonJavaMethods.factorial(1));
        assertEquals(120L, commonJavaMethods.factorial(5));
        assertThrows(IllegalArgumentException.class, () -> commonJavaMethods.factorial(-1));
    }

    @Test
    void testIsPalindrome() {
        assertFalse(commonJavaMethods.isPalindrome(null));
        assertTrue(commonJavaMethods.isPalindrome(""));
        assertTrue(commonJavaMethods.isPalindrome("A man a plan a canal Panama"));
        assertTrue(commonJavaMethods.isPalindrome("racecar"));
        assertFalse(commonJavaMethods.isPalindrome("hello"));
    }

    @Test
    void testMax() {
        assertEquals(5, commonJavaMethods.max(new int[]{1, 2, 3, 4, 5}));
        assertEquals(-1, commonJavaMethods.max(new int[]{-5, -1, -3}));
        assertEquals(0, commonJavaMethods.max(new int[]{0}));
        assertThrows(IllegalArgumentException.class, () -> commonJavaMethods.max(null));
        assertThrows(IllegalArgumentException.class, () -> commonJavaMethods.max(new int[]{}));
    }

    @Test
    void testSafeTrim() {
        assertEquals("", commonJavaMethods.safeTrim(null));
        assertEquals("", commonJavaMethods.safeTrim(""));
        assertEquals("hello", commonJavaMethods.safeTrim("  hello  "));
        assertEquals("hello world", commonJavaMethods.safeTrim("hello world"));
    }

    public static void main(String[] args) {
        org.junit.platform.console.ConsoleLauncher.main(new String[]{
            "--select-class", "org.openprojectx.ai.plugin.samples.CommonJavaMethodsTest"
        });
    }
}