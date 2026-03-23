package examples.stringprocessor;

public class StringProcessor {

    /**
     * Truncates text to at most maxLength characters.
     * If the text is longer than maxLength, it is cut and "..." is appended
     * (the result length will be maxLength + 3).
     * Returns the original text unchanged if it fits within maxLength.
     *
     * @param text      the input string (may be null or empty)
     * @param maxLength maximum number of characters before truncation (must be >= 0)
     * @return truncated string, original string, or empty string if text is null/empty
     * @throws IllegalArgumentException if maxLength is negative
     */
    public String truncate(String text, int maxLength) {
        if (maxLength < 0) {
            throw new IllegalArgumentException("maxLength must be >= 0, got: " + maxLength);
        }
        if (text == null || text.isEmpty()) {
            return "";
        }
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }

    /**
     * Converts a space-separated phrase into camelCase.
     * The first word is lowercased; each subsequent word is capitalised.
     * Multiple consecutive spaces are treated as one delimiter.
     *
     * @param phrase the input phrase (may be null or empty)
     * @return camelCase string, or empty string if phrase is null/empty
     */
    public String toCamelCase(String phrase) {
        if (phrase == null || phrase.isBlank()) {
            return "";
        }
        String[] words = phrase.trim().split("\\s+");
        StringBuilder sb = new StringBuilder(words[0].toLowerCase());
        for (int i = 1; i < words.length; i++) {
            if (!words[i].isEmpty()) {
                sb.append(Character.toUpperCase(words[i].charAt(0)))
                  .append(words[i].substring(1).toLowerCase());
            }
        }
        return sb.toString();
    }

    /**
     * Returns true if text is a palindrome, ignoring case and all
     * non-alphanumeric characters (spaces, punctuation, etc.).
     * Null and empty inputs are considered palindromes.
     *
     * @param text the string to test (may be null or empty)
     * @return true if the cleaned text reads the same forwards and backwards
     */
    public boolean isPalindrome(String text) {
        if (text == null || text.isEmpty()) {
            return true;
        }
        String cleaned = text.toLowerCase().replaceAll("[^a-z0-9]", "");
        if (cleaned.isEmpty()) {
            return true;
        }
        int left = 0, right = cleaned.length() - 1;
        while (left < right) {
            if (cleaned.charAt(left) != cleaned.charAt(right)) {
                return false;
            }
            left++;
            right--;
        }
        return true;
    }
}
