/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.resolve.inference

import org.jetbrains.kotlin.fir.expressions.FirResolvable
import org.jetbrains.kotlin.fir.expressions.FirStatement
import org.jetbrains.kotlin.fir.resolve.BodyResolveComponents
import org.jetbrains.kotlin.fir.resolve.calls.candidate
import org.jetbrains.kotlin.fir.resolve.calls.Candidate
import org.jetbrains.kotlin.fir.resolve.calls.FirNamedReferenceWithCandidate
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.coneTypeUnsafe
import org.jetbrains.kotlin.resolve.calls.inference.NewConstraintSystem
import org.jetbrains.kotlin.resolve.calls.inference.components.KotlinConstraintSystemCompleter
import org.jetbrains.kotlin.resolve.calls.inference.model.ConstraintStorage
import org.jetbrains.kotlin.resolve.calls.inference.model.SimpleConstraintSystemConstraintPosition
import org.jetbrains.kotlin.utils.addToStdlib.safeAs

abstract class AbstractManyCandidatesInferenceSession(
    protected val components: BodyResolveComponents,
    private val postponedArgumentsAnalyzer: PostponedArgumentsAnalyzer,
) : FirInferenceSession() {
    private val errorCalls: MutableList<FirResolvable> = mutableListOf()
    private val partiallyResolvedCalls: MutableList<FirResolvable> = mutableListOf()
    private val completedCalls: MutableSet<FirResolvable> = mutableSetOf()

    private val unitType: ConeKotlinType = components.session.builtinTypes.unitType.coneTypeUnsafe()

    override val currentConstraintSystem: ConstraintStorage
        get() = partiallyResolvedCalls.lastOrNull()
            ?.calleeReference
            ?.safeAs<FirNamedReferenceWithCandidate>()
            ?.candidate
            ?.system
            ?.currentStorage()
            ?: ConstraintStorage.Empty

    override fun shouldRunCompletion(candidate: Candidate): Boolean {
        return false
    }

    override fun <T> addCompetedCall(call: T) where T : FirResolvable, T : FirStatement {
        // do nothing
    }

    final override fun <T> addPartiallyResolvedCall(call: T) where T : FirResolvable, T : FirStatement {
        partiallyResolvedCalls += call
    }

    final override fun <T> addErrorCall(call: T) where T : FirResolvable, T : FirStatement {
        errorCalls += call
    }

    final override fun <T> callCompleted(call: T): Boolean where T : FirResolvable, T : FirStatement {
        return !completedCalls.add(call)
    }

    protected open fun <T> prepareForCompletion(
        commonSystem: NewConstraintSystem,
        partiallyResolvedCalls: List<T>
    ) where T : FirResolvable, T : FirStatement {
        // do nothing
    }

    fun <T> resolveCandidates(): List<T> where T : FirResolvable, T : FirStatement {
        val hasOneSuccessfulAndOneErrorCandidate = if (partiallyResolvedCalls.size > 1) {
            val errorCount = partiallyResolvedCalls.count {
                it.candidate.system.currentStorage().errors.isNotEmpty()
            }
            errorCount > 0 && errorCount < partiallyResolvedCalls.size
        } else {
            false
        }

        fun runCompletion(constraintSystem: NewConstraintSystem, atoms: List<FirStatement>) {
            components.callCompleter.completer.complete(
                constraintSystem.asConstraintSystemCompleterContext(),
                KotlinConstraintSystemCompleter
                    .ConstraintSystemCompletionMode.FULL,
                atoms,
                unitType
            ) {
                postponedArgumentsAnalyzer.analyze(constraintSystem.asPostponedArgumentsAnalyzerContext(), it)
            }
        }

        @Suppress("UNCHECKED_CAST")
        val resolvedCalls = partiallyResolvedCalls as List<T>
        val allCandidates = mutableListOf<T>()

        if (hasOneSuccessfulAndOneErrorCandidate) {
            val goodCandidate = resolvedCalls.first { it.candidate.system.currentStorage().errors.isEmpty() }
            val badCandidate = resolvedCalls.first { it.candidate.system.currentStorage().errors.isNotEmpty() }

            for (call in listOf(goodCandidate, badCandidate)) {
                val atomsToAnalyze: MutableList<FirStatement> = mutableListOf(call as FirStatement)
                val system = components.inferenceComponents.createConstraintSystem().apply {
                    addOtherSystem(call.candidate.system.currentStorage())
                    if (call == badCandidate) {
                        for ((typeVariable, fixedType) in allCandidates[0].candidate.system.fixedTypeVariables) {
                            this.notFixedTypeVariables[typeVariable]?.let {
                                val type = it.typeVariable.defaultType()
                                addEqualityConstraint(type, fixedType,
                                                      SimpleConstraintSystemConstraintPosition
                                )
                                // TODO
                                // atomsToAnalyze += StubResolvedAtom(typeVariable)
                            }
                        }
                    }
                }
                runCompletion(system, atomsToAnalyze)
                allCandidates += call
            }
        } else {
            val commonSystem = components.inferenceComponents.createConstraintSystem().apply {
                addOtherSystem(currentConstraintSystem)
            }
            prepareForCompletion(commonSystem, resolvedCalls)
            runCompletion(commonSystem, resolvedCalls)
            allCandidates += resolvedCalls
        }

        return allCandidates
    }

    protected val FirResolvable.candidate: Candidate
        get() = candidate()!!
}