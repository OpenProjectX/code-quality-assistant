package org.openprojectx.ai.plugin.testgen

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.JavaRecursiveElementVisitor
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression

object DependentMethodCollector {

    fun collect(project: Project, sourceFile: VirtualFile): String {
        return ReadAction.compute<String, RuntimeException> {
            val psiFile = PsiManager.getInstance(project).findFile(sourceFile) as? PsiJavaFile
                ?: return@compute ""
            val psiClass = psiFile.classes.firstOrNull() ?: return@compute ""

            val qualifiedName = psiClass.qualifiedName ?: psiClass.name ?: ""
            val externalSignatures = linkedSetOf<String>()

            for (method in psiClass.methods) {
                val body = method.body ?: continue
                body.accept(object : JavaRecursiveElementVisitor() {
                    override fun visitMethodCallExpression(call: PsiMethodCallExpression) {
                        super.visitMethodCallExpression(call)
                        val target = call.resolveMethod() ?: return
                        val targetClass = target.containingClass ?: return
                        val targetQName = targetClass.qualifiedName ?: return

                        // Skip methods in the same class (already in contractText)
                        if (targetQName == qualifiedName) return
                        // Skip JDK methods
                        if (targetQName.startsWith("java.") || targetQName.startsWith("javax.")) return
                        // Skip Kotlin stdlib and common libraries
                        if (targetQName.startsWith("kotlin.")) return

                        externalSignatures.add(formatSignature(target, targetQName))
                    }
                })
            }

            if (externalSignatures.isEmpty()) return@compute ""

            buildString {
                appendLine("## Methods called by this class (for mock/stub reference)")
                externalSignatures.forEach { appendLine(it) }
            }
        }
    }

    private fun formatSignature(method: PsiMethod, qualifiedClassName: String): String {
        val returnType = method.returnType?.presentableText ?: "void"
        val params = method.parameterList.parameters.joinToString(", ") { param ->
            "${param.type.presentableText} ${param.name}"
        }
        return "$returnType $qualifiedClassName.${method.name}($params)"
    }
}
