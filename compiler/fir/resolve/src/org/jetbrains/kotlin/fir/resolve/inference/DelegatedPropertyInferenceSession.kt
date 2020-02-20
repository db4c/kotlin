/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.resolve.inference

import org.jetbrains.kotlin.fir.declarations.*
import org.jetbrains.kotlin.fir.expressions.FirResolvable
import org.jetbrains.kotlin.fir.expressions.FirStatement
import org.jetbrains.kotlin.fir.references.FirNamedReference
import org.jetbrains.kotlin.fir.resolve.BodyResolveComponents
import org.jetbrains.kotlin.fir.resolve.calls.Candidate
import org.jetbrains.kotlin.fir.resolve.defaultType
import org.jetbrains.kotlin.fir.symbols.impl.FirFunctionSymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.ConeTypeVariableTypeConstructor
import org.jetbrains.kotlin.fir.types.coneTypeSafe
import org.jetbrains.kotlin.fir.types.coneTypeUnsafe
import org.jetbrains.kotlin.resolve.calls.inference.ConstraintSystemBuilder
import org.jetbrains.kotlin.resolve.calls.inference.NewConstraintSystem
import org.jetbrains.kotlin.resolve.calls.inference.model.ConstraintStorage
import org.jetbrains.kotlin.resolve.calls.inference.model.SimpleConstraintSystemConstraintPosition
import org.jetbrains.kotlin.util.OperatorNameConventions

class DelegatedPropertyInferenceSession(
    val property: FirProperty,
    val expectedType: ConeKotlinType?,
    components: BodyResolveComponents,
    postponedArgumentsAnalyzer: PostponedArgumentsAnalyzer,
) : AbstractManyCandidatesInferenceSession(components, postponedArgumentsAnalyzer) {
    override fun inferPostponedVariables(
        lambda: ResolvedLambdaAtom,
        initialStorage: ConstraintStorage
    ): Map<ConeTypeVariableTypeConstructor, ConeKotlinType> = emptyMap()

    override fun <T> shouldCompleteResolvedSubAtomsOf(call: T): Boolean where T : FirResolvable, T : FirStatement = true

    override fun <T> prepareForCompletion(
        commonSystem: NewConstraintSystem,
        partiallyResolvedCalls: List<T>
    ) where T : FirResolvable, T : FirStatement {
        val csBuilder = commonSystem.getBuilder()
        for (call in partiallyResolvedCalls) {
            val candidate = call.candidate
            when ((call.calleeReference as FirNamedReference).name) {
                OperatorNameConventions.GET_VALUE -> candidate.addConstraintsForGetValueMethod(csBuilder)
                OperatorNameConventions.SET_VALUE -> candidate.addConstraintsForSetValueMethod(csBuilder)
            }
        }
    }

    private fun Candidate.addConstraintsForGetValueMethod(commonSystem: ConstraintSystemBuilder) {
        if (expectedType != null) {
            val accessor = symbol.fir as? FirPropertyAccessor ?: return
            val unsubstitutedParameterType = accessor.returnTypeRef.coneTypeSafe<ConeKotlinType>() ?: return

            val substitutedParameterType = substitutor.substituteOrSelf(unsubstitutedParameterType)
            commonSystem.addSubtypeConstraint(substitutedParameterType, expectedType, SimpleConstraintSystemConstraintPosition)
        }

        addConstraintForThis(commonSystem)
    }


    private fun Candidate.addConstraintsForSetValueMethod(commonSystem: ConstraintSystemBuilder) {
        if (expectedType != null) {
            val accessor = symbol.fir as? FirPropertyAccessor ?: return
            val unsubstitutedReturnType = accessor.valueParameters.getOrNull(2)?.returnTypeRef?.coneTypeSafe<ConeKotlinType>() ?: return

            val substitutedReturnType = substitutor.substituteOrSelf(unsubstitutedReturnType)
            commonSystem.addSubtypeConstraint(substitutedReturnType, expectedType, SimpleConstraintSystemConstraintPosition)
        }

        addConstraintForThis(commonSystem)
    }

    private fun Candidate.addConstraintForThis(commonSystem: ConstraintSystemBuilder) {
        val typeOfThis: ConeKotlinType = property.receiverTypeRef?.coneTypeUnsafe()
            ?: when (val container = components.container) {
                is FirRegularClass -> container.defaultType()
                is FirAnonymousObject -> container.defaultType()
                else -> null
            } ?: components.session.builtinTypes.nullableNothingType.coneTypeUnsafe()
        val valueParameterForThis = (symbol as? FirFunctionSymbol<*>)?.fir?.valueParameters?.firstOrNull() ?: return
        val substitutedType = substitutor.substituteOrSelf(valueParameterForThis.returnTypeRef.coneTypeUnsafe<ConeKotlinType>())
        commonSystem.addSubtypeConstraint(typeOfThis, substitutedType, SimpleConstraintSystemConstraintPosition)
    }
}