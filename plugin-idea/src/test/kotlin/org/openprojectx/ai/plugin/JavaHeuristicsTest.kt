package org.openprojectx.ai.plugin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JavaHeuristicsTest {

    private val base = "C:/Users/dev/IdeaProjects/java-test-fixtures"

    @Test
    fun `derives package for class directly under project-root src-main-java (single module)`() {
        val path = "$base/src/main/java/com/example/fixtures/model/Order.java"
        assertEquals("com.example.fixtures.model", JavaHeuristics.derivePackageNameForJava(path, base))
    }

    @Test
    fun `derives package for class in a multi-module layout`() {
        val path = "$base/moduleA/src/main/java/com/example/foo/Bar.java"
        assertEquals("com.example.foo", JavaHeuristics.derivePackageNameForJava(path, base))
    }

    @Test
    fun `derives test location under project-root (single module)`() {
        val path = "$base/src/main/java/com/example/fixtures/model/Order.java"
        assertEquals("src/test/java", JavaHeuristics.deriveTestLocationForMainJava(path, base))
    }

    @Test
    fun `derives test location preserving module prefix (multi module)`() {
        val path = "$base/moduleA/src/main/java/com/example/foo/Bar.java"
        assertEquals("moduleA/src/test/java", JavaHeuristics.deriveTestLocationForMainJava(path, base))
    }

    @Test
    fun `returns null when file is not under src-main-java`() {
        val path = "$base/src/test/java/com/example/foo/BarTest.java"
        assertNull(JavaHeuristics.derivePackageNameForJava(path, base))
        assertNull(JavaHeuristics.deriveTestLocationForMainJava(path, base))
    }

    @Test
    fun `normalizes windows backslash paths`() {
        val winBase = "C:\\dev\\proj"
        val winPath = "C:\\dev\\proj\\src\\main\\java\\com\\example\\Win.java"
        assertEquals("com.example", JavaHeuristics.derivePackageNameForJava(winPath, winBase))
    }

    @Test
    fun `extracts declared package from generated java code`() {
        val code = """
            package com.example.fixtures.model;

            import org.junit.jupiter.api.Test;

            class OrderTest { }
        """.trimIndent()
        assertEquals("com.example.fixtures.model", JavaHeuristics.extractDeclaredPackage(code))
    }

    @Test
    fun `extractDeclaredPackage returns null when no package declared`() {
        val code = "import org.junit.jupiter.api.Test;\nclass OrderTest { }"
        assertNull(JavaHeuristics.extractDeclaredPackage(code))
    }
}
