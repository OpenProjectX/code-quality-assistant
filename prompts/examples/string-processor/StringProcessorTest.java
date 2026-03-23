package examples.stringprocessor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class StringProcessorTest {

    private StringProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new StringProcessor();
    }

    // -------------------------------------------------------------------------
    // truncate(String text, int maxLength)
    // -------------------------------------------------------------------------

    @ParameterizedTest
    @NullAndEmptySource
    void truncate_nullOrEmptyText_returnsEmptyString(String text) {
        assertEquals("", processor.truncate(text, 5), "input: " + text);
    }

    @Test
    void truncate_negativeMaxLength_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> processor.truncate("hello", -1));
    }

    @ParameterizedTest
    @CsvSource({
        // shorter than limit → original returned
        "'hi',          10, 'hi'",
        // exact boundary (length == limit) → original returned
        "'hello',        5, 'hello'",
        // one over boundary (length == limit + 1) → truncated
        "'hello!',       5, 'hello...'",
        // well over limit
        "'hello world',  3, 'hel...'",
        // limit zero → ellipsis only
        "'hello',        0, '...'"
    })
    void truncate_nonEmptyText_returnExpectedResult(String text, int maxLength, String expected) {
        assertEquals(expected, processor.truncate(text, maxLength), "input: '" + text + "', maxLength: " + maxLength);
    }

    // -------------------------------------------------------------------------
    // toCamelCase(String phrase)
    // -------------------------------------------------------------------------

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void toCamelCase_nullEmptyOrBlankPhrase_returnsEmptyString(String phrase) {
        assertEquals("", processor.toCamelCase(phrase), "input: '" + phrase + "'");
    }

    @ParameterizedTest
    @CsvSource({
        // single word — lowercased
        "'Hello',               'hello'",
        // two words
        "'hello world',         'helloWorld'",
        // multiple words
        "'the quick brown fox', 'theQuickBrownFox'",
        // extra spaces treated as one delimiter
        "'hello   world',       'helloWorld'",
        // all caps input
        "'HELLO WORLD',         'helloWorld'",
        // single lowercase word unchanged
        "'one',                 'one'"
    })
    void toCamelCase_validPhrases_returnExpectedCamelCase(String phrase, String expected) {
        assertEquals(expected, processor.toCamelCase(phrase), "input: '" + phrase + "'");
    }

    // -------------------------------------------------------------------------
    // isPalindrome(String text)
    // -------------------------------------------------------------------------

    @ParameterizedTest
    @NullAndEmptySource
    void isPalindrome_nullOrEmpty_returnsTrue(String text) {
        assertTrue(processor.isPalindrome(text), "input: '" + text + "'");
    }

    @ParameterizedTest
    @CsvSource({
        // classic palindromes
        "racecar, true",
        "madam,   true",
        "level,   true",
        "noon,    true",
        // mixed case — case-insensitive
        "Racecar, true",
        // single character
        "a,       true",
        // two-char palindrome
        "aa,      true",
        // numeric palindrome
        "121,     true",
        // non-palindromes
        "hello,   false",
        "world,   false",
        "java,    false",
        // two-char non-palindrome
        "ab,      false"
    })
    void isPalindrome_alphanumericInputs_returnExpectedResult(String text, boolean expected) {
        if (expected) {
            assertTrue(processor.isPalindrome(text), "expected palindrome for: '" + text + "'");
        } else {
            assertFalse(processor.isPalindrome(text), "expected non-palindrome for: '" + text + "'");
        }
    }

    @ParameterizedTest
    @CsvSource({
        // phrase with spaces and punctuation
        "'A man, a plan, a canal: Panama', true",
        // only non-alphanumeric chars → cleaned string is empty → palindrome
        "'!!!',                            true"
    })
    void isPalindrome_phrasesWithPunctuation_returnExpectedResult(String text, boolean expected) {
        if (expected) {
            assertTrue(processor.isPalindrome(text), "expected palindrome for: '" + text + "'");
        } else {
            assertFalse(processor.isPalindrome(text), "expected non-palindrome for: '" + text + "'");
        }
    }
}
