// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package org.jetbrains.kotlin.idea.project

import com.intellij.openapi.project.Project
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import org.jetbrains.kotlin.idea.caches.trackers.KotlinCodeBlockModificationListener
import org.jetbrains.kotlin.idea.stubindex.KotlinProbablyContractedFunctionShortNameIndex
import org.jetbrains.kotlin.idea.util.runReadActionInSmartMode
import org.jetbrains.kotlin.resolve.lazy.ProbablyContractedCallableNames

class ProbablyContractedCallableNamesImpl(project: Project) : ProbablyContractedCallableNames {
    private val functionNames = CachedValuesManager.getManager(project).createCachedValue(
        {
            CachedValueProvider.Result.create(
                project.runReadActionInSmartMode { KotlinProbablyContractedFunctionShortNameIndex.getAllKeys(project) },
                KotlinCodeBlockModificationListener.getInstance(project).kotlinOutOfCodeBlockTracker
            )
        },
        false
    )

    override fun isProbablyContractedCallableName(name: String): Boolean = name in functionNames.value
}