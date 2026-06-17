package org.openprojectx.ai.plugin.testgen

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.JavaRecursiveElementVisitor
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiField
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiModifier

object DependentMethodCollector {

    fun collect(project: Project, sourceFile: VirtualFile): String {
        return ReadAction.compute<String, RuntimeException> {
            val psiFile = PsiManager.getInstance(project).findFile(sourceFile) as? PsiJavaFile
                ?: return@compute ""
            val psiClass = psiFile.classes.firstOrNull() ?: return@compute ""

            val qualifiedName = psiClass.qualifiedName ?: psiClass.name ?: ""

            val classAnnotations = psiClass.annotations
                .mapNotNull { it.qualifiedName?.substringAfterLast('.') }
                .joinToString(", ")

            // Fields that are candidates for mocking (non-primitive, non-JDK, non-self)
            val mockableFields = psiClass.fields.mapNotNull { field ->
                val typeClass = resolveFieldClass(field) ?: return@mapNotNull null
                val typeQName = typeClass.qualifiedName ?: return@mapNotNull null
                if (isSkippable(typeQName)) return@mapNotNull null
                if (typeQName == qualifiedName) return@mapNotNull null
                Triple(field, typeClass, typeQName)
            }

            // Direct method calls from this class's own methods
            val directCallSignatures = linkedSetOf<String>()
            for (method in psiClass.methods) {
                val body = method.body ?: continue
                body.accept(object : JavaRecursiveElementVisitor() {
                    override fun visitMethodCallExpression(call: PsiMethodCallExpression) {
                        super.visitMethodCallExpression(call)
                        val target = call.resolveMethod() ?: return
                        val targetClass = target.containingClass ?: return
                        val targetQName = targetClass.qualifiedName ?: return
                        if (targetQName == qualifiedName) return
                        if (isSkippable(targetQName)) return
                        directCallSignatures.add(formatSignature(target, targetQName))
                    }
                })
            }

            // For each mockable field type: collect ALL public methods (full mock API surface)
            // and nested calls from those methods (one level deeper)
            val mockTypeDetails = mutableListOf<MockTypeDetail>()
            for ((field, typeClass, typeQName) in mockableFields) {
                val fieldAnnotations = field.annotations
                    .mapNotNull { it.qualifiedName?.substringAfterLast('.') }
                    .joinToString(", ")

                val publicMethods = typeClass.allMethods
                    .filter { m ->
                        m.containingClass?.qualifiedName?.let { !isSkippable(it) } == true &&
                        m.hasModifierProperty(PsiModifier.PUBLIC) &&
                        !m.isConstructor
                    }
                    .map { formatSignature(it, typeQName) }
                    .distinct()

                // Nested deps: collect methods called inside the mock type's methods (one level)
                val nestedSignatures = linkedSetOf<String>()
                for (m in typeClass.methods) {
                    val body = m.body ?: continue
                    body.accept(object : JavaRecursiveElementVisitor() {
                        override fun visitMethodCallExpression(call: PsiMethodCallExpression) {
                            super.visitMethodCallExpression(call)
                            val target = call.resolveMethod() ?: return
                            val tc = target.containingClass ?: return
                            val tqn = tc.qualifiedName ?: return
                            if (tqn == typeQName || tqn == qualifiedName) return
                            if (isSkippable(tqn)) return
                            nestedSignatures.add(formatSignature(target, tqn))
                        }
                    })
                }

                mockTypeDetails.add(MockTypeDetail(
                    fieldName = field.name,
                    fieldAnnotations = fieldAnnotations,
                    typeName = typeClass.name ?: typeQName,
                    qualifiedTypeName = typeQName,
                    isInterface = typeClass.isInterface,
                    publicMethods = publicMethods,
                    nestedDependencies = nestedSignatures.toList()
                ))
            }

            if (directCallSignatures.isEmpty() && mockTypeDetails.isEmpty()) return@compute ""

            buildString {
                if (classAnnotations.isNotBlank()) {
                    appendLine("## Source Class Annotations")
                    appendLine("@$classAnnotations")
                    appendLine()
                }

                if (mockTypeDetails.isNotEmpty()) {
                    appendLine("## Injectable Fields (Mock these in tests)")
                    for (detail in mockTypeDetails) {
                        val annotations = if (detail.fieldAnnotations.isNotBlank()) " [@${detail.fieldAnnotations}]" else ""
                        val kind = if (detail.isInterface) "interface" else "class"
                        appendLine("  ${detail.typeName} ${detail.fieldName}$annotations  [$kind: ${detail.qualifiedTypeName}]")
                    }
                    appendLine()

                    appendLine("## Full Mock API — all public methods available for stubbing/verification")
                    for (detail in mockTypeDetails) {
                        appendLine("### ${detail.typeName} (${detail.qualifiedTypeName})")
                        if (detail.publicMethods.isEmpty()) {
                            appendLine("  (no public methods found)")
                        } else {
                            detail.publicMethods.forEach { appendLine("  $it") }
                        }
                        if (detail.nestedDependencies.isNotEmpty()) {
                            appendLine("  -- collaborators of ${detail.typeName} (may also need mocking) --")
                            detail.nestedDependencies.forEach { appendLine("  $it") }
                        }
                        appendLine()
                    }
                }

                if (directCallSignatures.isNotEmpty()) {
                    appendLine("## Methods directly called by this class (confirmed call sites)")
                    directCallSignatures.forEach { appendLine("  $it") }
                }
            }.trimEnd()
        }
    }

    private data class MockTypeDetail(
        val fieldName: String,
        val fieldAnnotations: String,
        val typeName: String,
        val qualifiedTypeName: String,
        val isInterface: Boolean,
        val publicMethods: List<String>,
        val nestedDependencies: List<String>
    )

    private fun resolveFieldClass(field: PsiField): PsiClass? {
        val type = field.type
        // Handle generic types like Repository<User, Long> — resolve the raw class
        return com.intellij.psi.util.PsiUtil.resolveClassInClassTypeOnly(type)
    }

    private fun isSkippable(qualifiedName: String): Boolean =
        qualifiedName.startsWith("java.") ||
        qualifiedName.startsWith("javax.") ||
        qualifiedName.startsWith("jakarta.") ||
        qualifiedName.startsWith("kotlin.") ||
        qualifiedName.startsWith("org.springframework.") ||
        qualifiedName.startsWith("com.google.common.") ||
        qualifiedName.startsWith("org.slf4j.") ||
        qualifiedName.startsWith("org.apache.logging.")

    private fun formatSignature(method: PsiMethod, qualifiedClassName: String): String {
        val returnType = method.returnType?.presentableText ?: "void"
        val params = method.parameterList.parameters.joinToString(", ") { param ->
            "${param.type.presentableText} ${param.name}"
        }
        return "$returnType $qualifiedClassName.${method.name}($params)"
    }
}
