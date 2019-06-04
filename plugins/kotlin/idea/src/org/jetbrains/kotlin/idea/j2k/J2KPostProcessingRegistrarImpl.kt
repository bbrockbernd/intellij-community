/*
 * Copyright 2010-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.idea.j2k

import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.diagnostics.Diagnostic
import org.jetbrains.kotlin.diagnostics.DiagnosticFactory
import org.jetbrains.kotlin.diagnostics.Errors
import org.jetbrains.kotlin.idea.caches.resolve.analyze
import org.jetbrains.kotlin.idea.core.replaced
import org.jetbrains.kotlin.idea.core.setVisibility
import org.jetbrains.kotlin.idea.inspections.*
import org.jetbrains.kotlin.idea.inspections.branchedTransformations.IfThenToSafeAccessInspection
import org.jetbrains.kotlin.idea.inspections.conventionNameCalls.ReplaceGetOrSetInspection
import org.jetbrains.kotlin.idea.intentions.*
import org.jetbrains.kotlin.idea.intentions.branchedTransformations.intentions.FoldIfToReturnAsymmetricallyIntention
import org.jetbrains.kotlin.idea.intentions.branchedTransformations.intentions.FoldIfToReturnIntention
import org.jetbrains.kotlin.idea.intentions.branchedTransformations.intentions.IfThenToElvisIntention
import org.jetbrains.kotlin.idea.intentions.branchedTransformations.isTrivialStatementBody
import org.jetbrains.kotlin.idea.quickfix.RemoveModifierFix
import org.jetbrains.kotlin.idea.quickfix.RemoveUselessCastFix
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.j2k.ConverterSettings
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.getChildOfType
import org.jetbrains.kotlin.psi.psiUtil.getParentOfType
import org.jetbrains.kotlin.psi.psiUtil.isAncestor
import org.jetbrains.kotlin.psi.psiUtil.visibilityModifierType
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.calls.callUtil.getType
import org.jetbrains.kotlin.resolve.diagnostics.Diagnostics
import org.jetbrains.kotlin.resolve.lazy.BodyResolveMode
import org.jetbrains.kotlin.utils.mapToIndex
import java.util.*

interface J2kPostProcessing {
    fun createAction(element: KtElement, diagnostics: Diagnostics, settings: ConverterSettings?): (() -> Unit)? =
        createAction(element, diagnostics)

    fun createAction(element: KtElement, diagnostics: Diagnostics): (() -> Unit)? =
        createAction(element, diagnostics, null)

    val writeActionNeeded: Boolean
}

object J2KPostProcessingRegistrarImpl : J2KPostProcessingRegistrar {
    private val myProcessings = ArrayList<J2kPostProcessing>()

    override val processings: Collection<J2kPostProcessing>
        get() = myProcessings

    private val processingsToPriorityMap = HashMap<J2kPostProcessing, Int>()

    override fun priority(processing: J2kPostProcessing): Int = processingsToPriorityMap[processing]!!

    init {
        myProcessings.add(RemoveExplicitTypeArgumentsProcessing())
        myProcessings.add(RemoveRedundantOverrideVisibilityProcessing())
        registerInspectionBasedProcessing(MoveLambdaOutsideParenthesesInspection())
        myProcessings.add(FixObjectStringConcatenationProcessing())
        myProcessings.add(ConvertToStringTemplateProcessing())
        myProcessings.add(UsePropertyAccessSyntaxProcessing())
        myProcessings.add(UninitializedVariableReferenceFromInitializerToThisReferenceProcessing())
        myProcessings.add(UnresolvedVariableReferenceFromInitializerToThisReferenceProcessing())
        myProcessings.add(RemoveRedundantSamAdaptersProcessing())
        myProcessings.add(RemoveRedundantCastToNullableProcessing())
        registerInspectionBasedProcessing(ReplacePutWithAssignmentInspection())
        myProcessings.add(UseExpressionBodyProcessing())
        registerInspectionBasedProcessing(UnnecessaryVariableInspection())

        registerInspectionBasedProcessing(FoldInitializerAndIfToElvisInspection())

        registerIntentionBasedProcessing(FoldIfToReturnIntention()) { it.then.isTrivialStatementBody() && it.`else`.isTrivialStatementBody() }
        registerIntentionBasedProcessing(FoldIfToReturnAsymmetricallyIntention()) {
            it.then.isTrivialStatementBody() && (KtPsiUtil.skipTrailingWhitespacesAndComments(
                it
            ) as KtReturnExpression).returnedExpression.isTrivialStatementBody()
        }

        registerInspectionBasedProcessing(IfThenToSafeAccessInspection())
        registerIntentionBasedProcessing(IfThenToElvisIntention())
        registerInspectionBasedProcessing(SimplifyNegatedBinaryExpressionInspection())
        registerInspectionBasedProcessing(ReplaceGetOrSetInspection())
        registerInspectionBasedProcessing(AddOperatorModifierInspection())
        registerIntentionBasedProcessing(ObjectLiteralToLambdaIntention())
        registerIntentionBasedProcessing(AnonymousFunctionToLambdaIntention())
        registerIntentionBasedProcessing(RemoveUnnecessaryParenthesesIntention())
        registerIntentionBasedProcessing(DestructureIntention())
        registerInspectionBasedProcessing(SimplifyAssertNotNullInspection())
        registerIntentionBasedProcessing(RemoveRedundantCallsOfConversionMethodsIntention())
        registerInspectionBasedProcessing(JavaMapForEachInspection())


        registerDiagnosticBasedProcessing<KtBinaryExpressionWithTypeRHS>(Errors.USELESS_CAST) { element, _ ->
            val expression = RemoveUselessCastFix.invoke(element)

            val variable = expression.parent as? KtProperty
            if (variable != null && expression == variable.initializer && variable.isLocal) {
                val ref = ReferencesSearch.search(variable, LocalSearchScope(variable.containingFile)).findAll().singleOrNull()
                if (ref != null && ref.element is KtSimpleNameExpression) {
                    ref.element.replace(expression)
                    variable.delete()
                }
            }
        }

        registerDiagnosticBasedProcessing<KtTypeProjection>(Errors.REDUNDANT_PROJECTION) { _, diagnostic ->
            val fix = RemoveModifierFix.createRemoveProjectionFactory(true).createActions(diagnostic).single() as RemoveModifierFix
            fix.invoke()
        }

        registerDiagnosticBasedProcessingFactory(
            Errors.VAL_REASSIGNMENT, Errors.CAPTURED_VAL_INITIALIZATION, Errors.CAPTURED_MEMBER_VAL_INITIALIZATION
        ) { element: KtSimpleNameExpression, _: Diagnostic ->
            val property = element.mainReference.resolve() as? KtProperty
            if (property == null) {
                null
            } else {
                {
                    if (!property.isVar) {
                        property.valOrVarKeyword.replace(KtPsiFactory(element.project).createVarKeyword())
                    }
                }
            }
        }

        registerDiagnosticBasedProcessing<KtSimpleNameExpression>(Errors.UNNECESSARY_NOT_NULL_ASSERTION) { element, _ ->
            val exclExclExpr = element.parent as KtUnaryExpression
            val baseExpression = exclExclExpr.baseExpression!!
            val context = baseExpression.analyze(BodyResolveMode.PARTIAL_WITH_DIAGNOSTICS)
            if (context.diagnostics.forElement(element).any { it.factory == Errors.UNNECESSARY_NOT_NULL_ASSERTION }) {
                exclExclExpr.replace(baseExpression)
            }
        }

        processingsToPriorityMap.putAll(myProcessings.mapToIndex())
    }

    private inline fun <reified TElement : KtElement, TIntention : SelfTargetingRangeIntention<TElement>> registerIntentionBasedProcessing(
        intention: TIntention,
        noinline additionalChecker: (TElement) -> Boolean = { true }
    ) {
        myProcessings.add(object : J2kPostProcessing {
            // Intention can either need or not need write action
            override val writeActionNeeded = intention.startInWriteAction()

            override fun createAction(element: KtElement, diagnostics: Diagnostics): (() -> Unit)? {
                if (!TElement::class.java.isInstance(element)) return null
                val tElement = element as TElement
                if (intention.applicabilityRange(tElement) == null) return null
                if (!additionalChecker(tElement)) return null
                return {
                    if (intention.applicabilityRange(tElement) != null) { // check availability of the intention again because something could change
                        intention.applyTo(element, null)
                    }
                }
            }
        })
    }

    private inline fun
            <reified TElement : KtElement,
                    TInspection : AbstractApplicabilityBasedInspection<TElement>> registerInspectionBasedProcessing(

        inspection: TInspection,
        acceptInformationLevel: Boolean = false
    ) {
        myProcessings.add(object : J2kPostProcessing {
            // Inspection can either need or not need write action
            override val writeActionNeeded = inspection.startFixInWriteAction

            private fun isApplicable(element: TElement): Boolean {
                if (!inspection.isApplicable(element)) return false
                return acceptInformationLevel || inspection.inspectionHighlightType(element) != ProblemHighlightType.INFORMATION
            }

            override fun createAction(element: KtElement, diagnostics: Diagnostics): (() -> Unit)? {
                if (!TElement::class.java.isInstance(element)) return null
                val tElement = element as TElement
                if (!isApplicable(tElement)) return null
                return {
                    if (isApplicable(tElement)) { // check availability of the inspection again because something could change
                        inspection.applyTo(inspection.inspectionTarget(tElement))
                    }
                }
            }
        })
    }

    private inline fun <reified TElement : KtElement> registerDiagnosticBasedProcessing(
        vararg diagnosticFactory: DiagnosticFactory<*>,
        crossinline fix: (TElement, Diagnostic) -> Unit
    ) {
        registerDiagnosticBasedProcessingFactory(*diagnosticFactory) { element: TElement, diagnostic: Diagnostic ->
            {
                fix(
                    element,
                    diagnostic
                )
            }
        }
    }

    private inline fun <reified TElement : KtElement> registerDiagnosticBasedProcessingFactory(
        vararg diagnosticFactory: DiagnosticFactory<*>,
        crossinline fixFactory: (TElement, Diagnostic) -> (() -> Unit)?
    ) {
        myProcessings.add(object : J2kPostProcessing {
            // ???
            override val writeActionNeeded = true

            override fun createAction(element: KtElement, diagnostics: Diagnostics): (() -> Unit)? {
                if (!TElement::class.java.isInstance(element)) return null
                val diagnostic = diagnostics.forElement(element).firstOrNull { it.factory in diagnosticFactory } ?: return null
                return fixFactory(element as TElement, diagnostic)
            }
        })
    }

    private class RemoveExplicitTypeArgumentsProcessing : J2kPostProcessing {
        override val writeActionNeeded = true

        override fun createAction(element: KtElement, diagnostics: Diagnostics): (() -> Unit)? {
            if (element !is KtTypeArgumentList || !RemoveExplicitTypeArgumentsIntention.isApplicableTo(
                    element,
                    approximateFlexible = true
                )
            ) return null

            return {
                if (RemoveExplicitTypeArgumentsIntention.isApplicableTo(element, approximateFlexible = true)) {
                    element.delete()
                }
            }
        }
    }

    private class RemoveRedundantOverrideVisibilityProcessing : J2kPostProcessing {
        override val writeActionNeeded = true

        override fun createAction(element: KtElement, diagnostics: Diagnostics): (() -> Unit)? {
            if (element !is KtCallableDeclaration || !element.hasModifier(KtTokens.OVERRIDE_KEYWORD)) return null
            val modifier = element.visibilityModifierType() ?: return null
            return { element.setVisibility(modifier) }
        }
    }

    private class ConvertToStringTemplateProcessing : J2kPostProcessing {
        override val writeActionNeeded = true

        private val intention = ConvertToStringTemplateIntention()

        override fun createAction(element: KtElement, diagnostics: Diagnostics): (() -> Unit)? {
            if (element is KtBinaryExpression && intention.isApplicableTo(element) && ConvertToStringTemplateIntention.shouldSuggestToConvert(
                    element
                )
            ) {
                return { intention.applyTo(element, null) }
            } else {
                return null
            }
        }
    }

    private class UsePropertyAccessSyntaxProcessing : J2kPostProcessing {
        override val writeActionNeeded = true

        private val intention = UsePropertyAccessSyntaxIntention()

        override fun createAction(element: KtElement, diagnostics: Diagnostics): (() -> Unit)? {
            if (element !is KtCallExpression) return null
            val propertyName = intention.detectPropertyNameToUse(element) ?: return null
            return { intention.applyTo(element, propertyName, reformat = true) }
        }
    }

    private class RemoveRedundantSamAdaptersProcessing : J2kPostProcessing {
        override val writeActionNeeded = true

        override fun createAction(element: KtElement, diagnostics: Diagnostics): (() -> Unit)? {
            if (element !is KtCallExpression) return null

            val expressions = RedundantSamConstructorInspection.samConstructorCallsToBeConverted(element)
            if (expressions.isEmpty()) return null

            return {
                RedundantSamConstructorInspection.samConstructorCallsToBeConverted(element)
                    .forEach { RedundantSamConstructorInspection.replaceSamConstructorCall(it) }
            }
        }
    }

    private class UseExpressionBodyProcessing : J2kPostProcessing {
        override val writeActionNeeded = true

        override fun createAction(element: KtElement, diagnostics: Diagnostics): (() -> Unit)? {
            if (element !is KtPropertyAccessor) return null

            val inspection = UseExpressionBodyInspection(convertEmptyToUnit = false)
            if (!inspection.isActiveFor(element)) return null

            return {
                if (inspection.isActiveFor(element)) {
                    inspection.simplify(element, false)
                }
            }
        }
    }

    private class RemoveRedundantCastToNullableProcessing : J2kPostProcessing {
        override val writeActionNeeded = true

        override fun createAction(element: KtElement, diagnostics: Diagnostics): (() -> Unit)? {
            if (element !is KtBinaryExpressionWithTypeRHS) return null

            val context = element.analyze()
            val leftType = context.getType(element.left) ?: return null
            val rightType = context.get(BindingContext.TYPE, element.right) ?: return null

            if (!leftType.isMarkedNullable && rightType.isMarkedNullable) {
                return {
                    val type = element.right?.typeElement as? KtNullableType
                    type?.replace(type.innerType!!)
                }
            }

            return null
        }
    }

    private class FixObjectStringConcatenationProcessing : J2kPostProcessing {
        override val writeActionNeeded = true

        override fun createAction(element: KtElement, diagnostics: Diagnostics): (() -> Unit)? {
            if (element !is KtBinaryExpression ||
                element.operationToken != KtTokens.PLUS ||
                diagnostics.forElement(element.operationReference).none {
                    it.factory == Errors.UNRESOLVED_REFERENCE_WRONG_RECEIVER
                            || it.factory == Errors.NONE_APPLICABLE
                }
            )
                return null

            val bindingContext = element.analyze()
            val rightType = element.right?.getType(bindingContext) ?: return null

            if (KotlinBuiltIns.isString(rightType)) {
                return {
                    val factory = KtPsiFactory(element)
                    element.left!!.replace(factory.buildExpression {
                        appendFixedText("(")
                        appendExpression(element.left)
                        appendFixedText(").toString()")
                    })
                }
            }
            return null
        }
    }

    private class UninitializedVariableReferenceFromInitializerToThisReferenceProcessing : J2kPostProcessing {
        override val writeActionNeeded = true

        override fun createAction(element: KtElement, diagnostics: Diagnostics): (() -> Unit)? {
            if (element !is KtSimpleNameExpression || diagnostics.forElement(element).none { it.factory == Errors.UNINITIALIZED_VARIABLE }) return null

            val resolved = element.mainReference.resolve() ?: return null
            if (resolved.isAncestor(element, strict = true)) {
                if (resolved is KtVariableDeclaration && resolved.hasInitializer()) {
                    val anonymousObject = element.getParentOfType<KtClassOrObject>(true) ?: return null
                    if (resolved.initializer!!.getChildOfType<KtClassOrObject>() == anonymousObject) {
                        return { element.replaced(KtPsiFactory(element).createThisExpression()) }
                    }
                }
            }

            return null
        }
    }

    private class UnresolvedVariableReferenceFromInitializerToThisReferenceProcessing : J2kPostProcessing {
        override val writeActionNeeded = true

        override fun createAction(element: KtElement, diagnostics: Diagnostics): (() -> Unit)? {
            if (element !is KtSimpleNameExpression || diagnostics.forElement(element).none { it.factory == Errors.UNRESOLVED_REFERENCE }) return null

            val anonymousObject = element.getParentOfType<KtClassOrObject>(true) ?: return null

            val variable = anonymousObject.getParentOfType<KtVariableDeclaration>(true) ?: return null

            if (variable.nameAsName == element.getReferencedNameAsName() &&
                variable.initializer?.getChildOfType<KtClassOrObject>() == anonymousObject
            ) {
                return { element.replaced(KtPsiFactory(element).createThisExpression()) }
            }

            return null
        }
    }
}