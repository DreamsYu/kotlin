/*
 * Copyright 2010-2017 JetBrains s.r.o.
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

package org.jetbrains.kotlin.resolve.calls.tower

import org.jetbrains.kotlin.builtins.isFunctionType
import org.jetbrains.kotlin.descriptors.CallableDescriptor
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.annotations.AnnotationDescriptor
import org.jetbrains.kotlin.descriptors.synthetic.SyntheticMemberDescriptor
import org.jetbrains.kotlin.load.java.descriptors.JavaClassDescriptor
import org.jetbrains.kotlin.load.java.descriptors.JavaMethodDescriptor
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtImplicitThisExpression
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.resolve.annotations.argumentValue
import org.jetbrains.kotlin.resolve.calls.inference.returnTypeOrNothing
import org.jetbrains.kotlin.resolve.calls.model.ExpressionValueArgument
import org.jetbrains.kotlin.resolve.calls.model.ResolvedCallImpl
import org.jetbrains.kotlin.resolve.calls.tasks.ExplicitReceiverKind
import org.jetbrains.kotlin.resolve.calls.util.CallMaker
import org.jetbrains.kotlin.resolve.scopes.MemberScope
import org.jetbrains.kotlin.resolve.scopes.SyntheticScopes
import org.jetbrains.kotlin.resolve.scopes.getDescriptorsFiltered
import org.jetbrains.kotlin.resolve.scopes.receivers.ExpressionReceiver
import org.jetbrains.kotlin.resolve.scopes.receivers.ImplicitReceiver
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.SimpleType
import org.jetbrains.kotlin.types.checker.KotlinTypeChecker
import org.jetbrains.kotlin.types.typeUtil.supertypes
import java.lang.IllegalStateException

abstract internal class ResolvedCallCompatReplacer {
    abstract protected fun replaceWithCompatCallIfNeeded(
            syntheticScopes: SyntheticScopes,
            candidates: Collection<NewResolutionOldInference.MyCandidate>
    ): Collection<NewResolutionOldInference.MyCandidate>

    companion object {
        // TODO:
        internal var replacer: ResolvedCallCompatReplacer = ResolverCallCompatReplacerImpl()

        internal fun replace(
                syntheticScopes: SyntheticScopes,
                candidates: Collection<NewResolutionOldInference.MyCandidate>
        ): Collection<NewResolutionOldInference.MyCandidate> {
            return replacer.replaceWithCompatCallIfNeeded(syntheticScopes, candidates)
        }
    }
}

internal class ResolverCallCompatReplacerStub : ResolvedCallCompatReplacer() {
    override fun replaceWithCompatCallIfNeeded(
            syntheticScopes: SyntheticScopes,
            candidates: Collection<NewResolutionOldInference.MyCandidate>
    ): Collection<NewResolutionOldInference.MyCandidate> {
        return candidates
    }
}

internal class ResolverCallCompatReplacerImpl : ResolvedCallCompatReplacer() {
    private class CompatCandidate(
            // Call candidate to check
            val candidate: NewResolutionOldInference.MyCandidate,
            // All classes and interfaces, annotated with @Compat mapped to their compat companions
            val compatClasses: Map<KotlinType, SimpleType>
    )

    private fun findCompatAnnotationOnType(t: KotlinType): AnnotationDescriptor? =
            t.constructor.declarationDescriptor?.annotations?.firstOrNull { it.fqName == FqName(ANNOTATION_NAME) }

    // Find all compat annotations on the type and its supertypes
    private fun findCompatAnnotations(type: KotlinType): Map<KotlinType, AnnotationDescriptor> {
        val allTypes = type.supertypes() + type
        val res = hashMapOf<KotlinType, AnnotationDescriptor>()
        for (t in allTypes) {
            val annotation = findCompatAnnotationOnType(t)
            if (annotation != null) res[t] = annotation
        }
        return res
    }

    // Find all compat classes of the type and its supertypes including interfaces
    private fun findCompatClasses(type: KotlinType): Map<KotlinType, SimpleType> {
        val res = hashMapOf<KotlinType, SimpleType>()
        for ((t, annotation) in findCompatAnnotations(type)) {
            res[t] = annotation.argumentValue("value") as? SimpleType ?: continue
        }
        return res
    }

    private fun functionSignaturesEqual(
            call: CallableDescriptor,
            candidate: CallableDescriptor,
            scope: MemberScope,
            syntheticScopes: SyntheticScopes,
            originType: KotlinType? = null,
            receiverType: KotlinType? = null
    ): Boolean {
        if (call.name != candidate.name) return false
        if (!KotlinTypeChecker.DEFAULT.equalTypes(call.returnTypeOrNothing, candidate.returnTypeOrNothing)) return false
        if (call.typeParameters.size != candidate.typeParameters.size) return false
        if (call.typeParameters.indices.any { call.typeParameters[it] != candidate.typeParameters[it] }) return false
        if (call.valueParameters.size + (if (originType != null) 1 else 0) != candidate.valueParameters.size) return false
        if (originType != null && !KotlinTypeChecker.DEFAULT.equalTypes(originType, candidate.valueParameters[0].type)) return false
        for (i in call.valueParameters.indices) {
            val j = if (originType != null) i + 1 else i
            if (KotlinTypeChecker.DEFAULT.equalTypes(candidate.valueParameters[j].type, call.valueParameters[i].type)) continue
            // TODO: Check for sam adapters, not only function type
            if (call.valueParameters[i].type.isFunctionType) {
                val synthetics = syntheticScopes.scopes.flatMap {
                    if (receiverType == null) it.getSyntheticStaticFunctions(scope)
                    else it.getSyntheticMemberFunctions(listOf(receiverType))
                }
                val originFun = synthetics.firstOrNull {
                    functionSignaturesEqual(call, it, scope, syntheticScopes)
                } as? SyntheticMemberDescriptor<*> ?: return false
                val realParam = (originFun.baseDescriptorForSynthetic as? JavaMethodDescriptor)?.valueParameters?.get(i) ?: return false
                if (!KotlinTypeChecker.DEFAULT.equalTypes(candidate.valueParameters[j].type, realParam.type)) return false
            }
            else return false
        }
        return true
    }

    override fun replaceWithCompatCallIfNeeded(
            syntheticScopes: SyntheticScopes,
            candidates: Collection<NewResolutionOldInference.MyCandidate>
    ): Collection<NewResolutionOldInference.MyCandidate> {
        val compatCandidates = arrayListOf<CompatCandidate>()
        for (candidate in candidates) {
            val call = candidate.resolvedCall
            val receiver = call.dispatchReceiver ?: continue
            val compatClasses = findCompatClasses(receiver.type)
            if (compatClasses.isNotEmpty()) compatCandidates += CompatCandidate(candidate, compatClasses)
        }
        if (compatCandidates.isEmpty()) return candidates

        // Calls with appropriate compat
        val callsToReplace = hashMapOf<NewResolutionOldInference.MyCandidate, NewResolutionOldInference.MyCandidate>()
        // Most of these loops iterate only once. It's OK to let them nest
        for (compatCandidate in compatCandidates) {
            val resolvedCall = compatCandidate.candidate.resolvedCall
            val callDescriptor = resolvedCall.candidateDescriptor ?: continue
            val receiver = resolvedCall.dispatchReceiver ?: continue

            // Find appropriate compat class/method and replace the call
            for ((origin, compat) in compatCandidate.compatClasses) {
                val scope = (compat.constructor.declarationDescriptor as? JavaClassDescriptor)?.staticScope ?: continue
                var compatMethod: CallableDescriptor? = null
                // Find method in compat class
                for (compatMethodDescriptor in scope.getDescriptorsFiltered { it == callDescriptor.name }) {
                    if (compatMethodDescriptor !is JavaMethodDescriptor) continue
                    if (!functionSignaturesEqual(callDescriptor, compatMethodDescriptor, scope, syntheticScopes, origin, receiver.type)) continue
                    compatMethod = syntheticScopes.scopes.flatMap { it.getSyntheticStaticFunctions(scope) }.firstOrNull {
                        functionSignaturesEqual(callDescriptor, it, scope, syntheticScopes, origin)
                    } ?: compatMethodDescriptor
                }
                if (compatMethod == null) continue

                // Replace the call
                val psiFactory = KtPsiFactory(resolvedCall.call.callElement, markGenerated = false)
                val calleeExpression = psiFactory.createSimpleName(callDescriptor.name.asString())

                val receiverExpr: KtExpression =
                        if (receiver is ExpressionReceiver) receiver.expression
                        else {
                            val implicitThisDescriptor = (receiver as? ImplicitReceiver)?.declarationDescriptor ?: continue
                            when (implicitThisDescriptor) {
                                is CallableDescriptor -> psiFactory.createImplicitThisExpression(implicitThisDescriptor)
                                is ClassDescriptor -> psiFactory.createImplicitThisExpression(implicitThisDescriptor)
                                else -> throw IllegalStateException("Implicit this might be either class or closure")
                            }
                        }
                val originValue = CallMaker.makeValueArgument(receiverExpr)
                val compatCall = CallMaker.makeCall(
                        resolvedCall.call.callElement,
                        null,
                        resolvedCall.call.callOperationNode,
                        calleeExpression,
                        listOf(originValue) + resolvedCall.call.valueArguments
                )
                val compatResolverCall = ResolvedCallImpl(
                        compatCall,
                        compatMethod,
                        null,
                        null,
                        ExplicitReceiverKind.NO_EXPLICIT_RECEIVER,
                        resolvedCall.knownTypeParametersSubstitutor,
                        resolvedCall.trace,
                        resolvedCall.tracingStrategy,
                        resolvedCall.dataFlowInfoForArguments
                )
                compatResolverCall.recordValueArgument(compatMethod.valueParameters.first(), ExpressionValueArgument(originValue))
                resolvedCall.valueArguments.forEach {
                    compatResolverCall.recordValueArgument(compatMethod!!.valueParameters[it.key.index + 1], it.value)
                }
                // TODO: Hack
                compatResolverCall.setStatusToSuccess()
                callsToReplace[compatCandidate.candidate] = NewResolutionOldInference.MyCandidate(compatCandidate.candidate.diagnostics, compatResolverCall)
            }
        }

        if (callsToReplace.isEmpty()) return candidates

        val res = arrayListOf<NewResolutionOldInference.MyCandidate>()
        for (candidate in candidates) res += callsToReplace[candidate] ?: candidate
        return res
    }

    private fun KtPsiFactory.createImplicitThisExpression(descriptor: CallableDescriptor) = KtImplicitThisExpression(createThisExpression().node, descriptor)
    private fun KtPsiFactory.createImplicitThisExpression(descriptor: ClassDescriptor) = KtImplicitThisExpression(createThisExpression().node, descriptor)

    companion object {
        private const val ANNOTATION_NAME = "kotlin.android.Compat"
    }
}