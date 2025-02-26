// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package org.jetbrains.kotlin.idea.refactoring.introduce.extractFunction

import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.refactoring.RefactoringActionHandler
import org.jetbrains.annotations.Nls
import org.jetbrains.kotlin.idea.KotlinBundle
import org.jetbrains.kotlin.idea.base.psi.unifier.toRange
import org.jetbrains.kotlin.idea.core.util.CodeInsightUtils
import org.jetbrains.kotlin.idea.refactoring.getExtractionContainers
import org.jetbrains.kotlin.idea.refactoring.introduce.extractFunction.ui.KotlinExtractFunctionDialog
import org.jetbrains.kotlin.idea.refactoring.introduce.extractionEngine.*
import org.jetbrains.kotlin.idea.refactoring.introduce.selectElementsWithTargetSibling
import org.jetbrains.kotlin.idea.refactoring.introduce.validateExpressionElements
import org.jetbrains.kotlin.idea.util.nonBlocking
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.utils.addToStdlib.safeAs

class ExtractKotlinFunctionHandler(
    private val allContainersEnabled: Boolean = false,
    private val helper: ExtractionEngineHelper = InteractiveExtractionHelper
) : RefactoringActionHandler {

    object InteractiveExtractionHelper : ExtractionEngineHelper(EXTRACT_FUNCTION) {
        override fun configureAndRun(
            project: Project,
            editor: Editor,
            descriptorWithConflicts: ExtractableCodeDescriptorWithConflicts,
            onFinish: (ExtractionResult) -> Unit
        ) {
            KotlinExtractFunctionDialog(descriptorWithConflicts.descriptor.extractionData.project, descriptorWithConflicts) {
                doRefactor(it.currentConfiguration, onFinish)
            }.show()
        }
    }

    fun doInvoke(
        editor: Editor,
        file: KtFile,
        elements: List<PsiElement>,
        targetSibling: PsiElement
    ) {
        nonBlocking(file.project, {
            val adjustedElements = elements.singleOrNull().safeAs<KtBlockExpression>()?.statements ?: elements
            ExtractionData(file, adjustedElements.toRange(false), targetSibling)
        }) { extractionData ->
            ExtractionEngine(helper).run(editor, extractionData) {
                processDuplicates(it.duplicateReplacers, file.project, editor)
            }
        }
    }

    fun selectElements(editor: Editor, file: KtFile, continuation: (elements: List<PsiElement>, targetSibling: PsiElement) -> Unit) {
        selectElementsWithTargetSibling(
            EXTRACT_FUNCTION,
            editor,
            file,
            KotlinBundle.message("title.select.target.code.block"),
            listOf(CodeInsightUtils.ElementKind.EXPRESSION),
            ::validateExpressionElements,
            { elements, parent -> parent.getExtractionContainers(elements.size == 1, allContainersEnabled) },
            continuation
        )
    }

    override fun invoke(project: Project, editor: Editor, file: PsiFile, dataContext: DataContext?) {
        if (file !is KtFile) return
        selectElements(editor, file) { elements, targetSibling -> doInvoke(editor, file, elements, targetSibling) }
    }

    override fun invoke(project: Project, elements: Array<out PsiElement>, dataContext: DataContext?) {
        throw AssertionError("Extract Function can only be invoked from editor")
    }
}

val EXTRACT_FUNCTION: String
    @Nls
    get() = KotlinBundle.message("extract.function")
