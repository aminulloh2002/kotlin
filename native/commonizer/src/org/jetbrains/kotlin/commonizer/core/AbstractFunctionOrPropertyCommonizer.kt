/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.commonizer.core

import org.jetbrains.kotlin.commonizer.cir.*
import org.jetbrains.kotlin.commonizer.mergedtree.CirKnownClassifiers
import org.jetbrains.kotlin.commonizer.utils.safeCastValues
import org.jetbrains.kotlin.descriptors.CallableMemberDescriptor
import org.jetbrains.kotlin.descriptors.CallableMemberDescriptor.Kind.DELEGATION
import org.jetbrains.kotlin.descriptors.CallableMemberDescriptor.Kind.SYNTHESIZED

abstract class AbstractFunctionOrPropertyCommonizer<T : CirFunctionOrProperty>(
    classifiers: CirKnownClassifiers
) : AbstractStandardCommonizer<T, T?>() {
    protected lateinit var name: CirName
    protected val modality = ModalityCommonizer()
    protected val visibility = VisibilityCommonizer.lowering()
    protected val extensionReceiver = ExtensionReceiverCommonizer(classifiers)
    protected val returnType = ReturnTypeCommonizer(classifiers).asCommonizer()
    protected lateinit var kind: CallableMemberDescriptor.Kind
    protected val typeParameters = TypeParameterListCommonizer(classifiers)

    override fun initialize(first: T) {
        name = first.name
        kind = first.kind
    }

    override fun doCommonizeWith(next: T): Boolean =
        next.kind != DELEGATION // delegated members should not be commonized
                && (next.kind != SYNTHESIZED || next.containingClass?.isData != true) // synthesized members of data classes should not be commonized
                && kind == next.kind
                && modality.commonizeWith(next.modality)
                && visibility.commonizeWith(next)
                && extensionReceiver.commonizeWith(next.extensionReceiver)
                && returnType.commonizeWith(next)
                && typeParameters.commonizeWith(next.typeParameters)
}

private class ReturnTypeCommonizer(
    private val classifiers: CirKnownClassifiers,
) : NullableContextualSingleInvocationCommonizer<CirFunctionOrProperty, CirType> {
    override fun invoke(values: List<CirFunctionOrProperty>): CirType? {
        if (values.isEmpty()) return null
        val isTopLevel = values.all { it.containingClass == null }
        val returnTypes = if (isTopLevel) makeNullableIfNecessary(values.map { it.returnType }) else values.map { it.returnType }
        return TypeCommonizer(classifiers).asCommonizer().commonize(returnTypes)
    }

    private fun makeNullableIfNecessary(types: List<CirType>): List<CirType> {
        val simpleTypes = types.safeCastValues<CirType, CirSimpleType>() ?: return types

        if (
            simpleTypes.all { type -> type.isMarkedNullable } ||
            simpleTypes.none { type -> type.isMarkedNullable }
        ) return types

        return simpleTypes.map { type -> type.makeNullable() }
    }
}

