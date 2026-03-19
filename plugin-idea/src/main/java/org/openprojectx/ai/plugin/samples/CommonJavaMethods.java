package org.openprojectx.ai.plugin.samples;

import java.util.Objects;

/**
 * A small utility class with common Java methods.
 *
 * <p>Used as sample input when manually testing test-generation flows.</p>
 */
public class CommonJavaMethods {

    public int add(int a, int b) {
        return a + b;
    }

    public int subtract(int a, int b) {
        return a - b;
    }

    public int multiply(int a, int b) {
        return a * b;
    }

    public double divide(double a, double b) {
        if (b == 0) {
            throw new IllegalArgumentException("Divider cannot be zero");
        }
        return a / b;
    }

    public boolean isEven(int number) {
        return number % 2 == 0;
    }

    public String reverse(String input) {
        if (input == null) {
            return null;
        }
        return new StringBuilder(input).reverse().toString();
    }

    public long factorial(int n) {
        if (n < 0) {
            throw new IllegalArgumentException("n must be >= 0");
        }

        long result = 1;
        for (int i = 2; i <= n; i++) {
            result *= i;
        }
        return result;
    }

    public boolean isPalindrome(String input) {
        if (input == null) {
            return false;
        }
        String normalized = input.replaceAll("\\s+", "").toLowerCase();
        return normalized.equals(new StringBuilder(normalized).reverse().toString());
    }

    public int max(int[] numbers) {
        if (numbers == null || numbers.length == 0) {
            throw new IllegalArgumentException("numbers must not be empty");
        }

        int maxValue = numbers[0];
        for (int i = 1; i < numbers.length; i++) {
            if (numbers[i] > maxValue) {
                maxValue = numbers[i];
            }
        }
        return maxValue;
    }

    public String safeTrim(String input) {
        return Objects.requireNonNullElse(input, "").trim();
    }
}
