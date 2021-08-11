/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.java.enhancement

import org.jetbrains.kotlin.builtins.jvm.JavaToKotlinClassMap
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.utils.collectEnumEntries
import org.jetbrains.kotlin.fir.declarations.utils.isStatic
import org.jetbrains.kotlin.fir.declarations.utils.modality
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.builder.buildConstExpression
import org.jetbrains.kotlin.fir.expressions.builder.buildPropertyAccessExpression
import org.jetbrains.kotlin.fir.java.declarations.FirJavaClass
import org.jetbrains.kotlin.fir.java.declarations.FirJavaField
import org.jetbrains.kotlin.fir.references.builder.buildResolvedNamedReference
import org.jetbrains.kotlin.fir.resolve.toSymbol
import org.jetbrains.kotlin.fir.symbols.ConeClassLikeLookupTag
import org.jetbrains.kotlin.fir.symbols.ConeClassifierLookupTag
import org.jetbrains.kotlin.fir.symbols.ConeTypeParameterLookupTag
import org.jetbrains.kotlin.fir.symbols.impl.ConeClassLikeLookupTagImpl
import org.jetbrains.kotlin.fir.typeContext
import org.jetbrains.kotlin.fir.types.*
import org.jetbrains.kotlin.fir.types.builder.buildResolvedTypeRef
import org.jetbrains.kotlin.fir.types.jvm.FirJavaTypeRef
import org.jetbrains.kotlin.load.java.structure.JavaClassifierType
import org.jetbrains.kotlin.load.java.structure.JavaType
import org.jetbrains.kotlin.load.java.typeEnhancement.*
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.types.AbstractStrictEqualityTypeChecker
import org.jetbrains.kotlin.types.ConstantValueKind
import org.jetbrains.kotlin.utils.extractRadix

internal class IndexedJavaTypeQualifiers(private val data: Array<JavaTypeQualifiers>) {
    constructor(size: Int, compute: (Int) -> JavaTypeQualifiers) : this(Array(size) { compute(it) })

    operator fun invoke(index: Int): JavaTypeQualifiers = data.getOrElse(index) { JavaTypeQualifiers.NONE }

    val size: Int get() = data.size
}

internal fun FirJavaTypeRef.enhance(
    session: FirSession,
    qualifiers: IndexedJavaTypeQualifiers,
    typeWithoutEnhancement: ConeKotlinType,
): FirResolvedTypeRef =
    buildResolvedTypeRef {
        val subtreeSizes = mutableListOf<Int>().apply { typeWithoutEnhancement.computeSubtreeSizes(this) }
        type = typeWithoutEnhancement.enhanceConeKotlinType(session, qualifiers, 0, subtreeSizes) ?: typeWithoutEnhancement
        annotations += this@enhance.annotations
    }

// The index in the lambda is the position of the type component in a depth-first walk of the tree.
// Example: A<B<C, D>, E<F>> - 0<1<2, 3>, 4<5>>. For flexible types, the number of nodes in the lower
// and upper bounds should be the same, and their indices match: (A<B>..C<D>) -> (0<1>..0<1>).
// This function precomputes the size of each subtree so that we can quickly skip to the next
// type argument; e.g. subtreeSizes[1] will give 3 for B<C, D>, indicating that E<F> is at 1 + 3 = 4.
private fun ConeKotlinType.computeSubtreeSizes(result: MutableList<Int>): Int {
    val index = result.size
    result.add(0) // reserve space at index
    result[index] = 1 + typeArguments.sumOf {
        // Star projections take up one (empty) entry.
        it.type?.computeSubtreeSizes(result) ?: 1.also { result.add(1) }
    }
    return result[index]
}

private fun ConeKotlinType.enhanceConeKotlinType(
    session: FirSession,
    qualifiers: IndexedJavaTypeQualifiers,
    index: Int,
    subtreeSizes: List<Int>
): ConeKotlinType? {
    return when (this) {
        is ConeFlexibleType -> {
            val lowerResult = lowerBound.enhanceInflexibleType(
                session, TypeComponentPosition.FLEXIBLE_LOWER, qualifiers, index, subtreeSizes
            )
            val upperResult = upperBound.enhanceInflexibleType(
                session, TypeComponentPosition.FLEXIBLE_UPPER, qualifiers, index, subtreeSizes
            )

            when {
                lowerResult == null && upperResult == null -> null
                this is ConeRawType -> ConeRawType(lowerResult ?: lowerBound, upperResult ?: upperBound)
                else -> coneFlexibleOrSimpleType(
                    session, lowerResult ?: lowerBound, upperResult ?: upperBound,
                    isNotNullTypeParameter = qualifiers(index).isNotNullTypeParameter
                )
            }
        }
        is ConeSimpleKotlinType -> enhanceInflexibleType(
            session, TypeComponentPosition.INFLEXIBLE, qualifiers, index, subtreeSizes
        )
        else -> null
    }
}

private fun coneFlexibleOrSimpleType(
    session: FirSession,
    lowerBound: ConeKotlinType,
    upperBound: ConeKotlinType,
    isNotNullTypeParameter: Boolean
): ConeKotlinType {
    if (AbstractStrictEqualityTypeChecker.strictEqualTypes(session.typeContext, lowerBound, upperBound)) {
        val lookupTag = (lowerBound as? ConeLookupTagBasedType)?.lookupTag
        if (isNotNullTypeParameter && lookupTag is ConeTypeParameterLookupTag && !lowerBound.isMarkedNullable) {
            // TODO: we need enhancement for type parameter bounds for this code to work properly
            // At this moment, this condition is always true
            if (lookupTag.typeParameterSymbol.fir.bounds.any {
                    val type = it.coneType
                    type is ConeTypeParameterType || type.isNullable
                }
            ) {
                return ConeDefinitelyNotNullType.create(
                    lowerBound,
                    session.typeContext,
                    useCorrectedNullabilityForFlexibleTypeParameters = true
                ) ?: lowerBound
            }
        }
        return lowerBound
    }
    return ConeFlexibleType(lowerBound, upperBound)
}

private val KOTLIN_COLLECTIONS = FqName("kotlin.collections")

private val KOTLIN_COLLECTIONS_PREFIX_LENGTH = KOTLIN_COLLECTIONS.asString().length + 1

internal fun ClassId.readOnlyToMutable(): ClassId? {
    val mutableFqName = JavaToKotlinClassMap.readOnlyToMutable(asSingleFqName().toUnsafe())
    return mutableFqName?.let {
        ClassId(KOTLIN_COLLECTIONS, FqName(it.asString().substring(KOTLIN_COLLECTIONS_PREFIX_LENGTH)), false)
    }
}

private fun ClassId.mutableToReadOnly(): ClassId? {
    val readOnlyFqName = JavaToKotlinClassMap.mutableToReadOnly(asSingleFqName().toUnsafe())
    return readOnlyFqName?.let {
        ClassId(KOTLIN_COLLECTIONS, FqName(it.asString().substring(KOTLIN_COLLECTIONS_PREFIX_LENGTH)), false)
    }
}

private fun ConeKotlinType.enhanceInflexibleType(
    session: FirSession,
    position: TypeComponentPosition,
    qualifiers: IndexedJavaTypeQualifiers,
    index: Int,
    subtreeSizes: List<Int>,
): ConeKotlinType? {
    require(this !is ConeFlexibleType) { "$this should not be flexible" }
    val shouldEnhance = position.shouldEnhance()
    if ((!shouldEnhance && typeArguments.isEmpty()) || this !is ConeLookupTagBasedType) {
        return null
    }

    val effectiveQualifiers = qualifiers(index)
    val enhancedTag = lookupTag.enhanceMutability(effectiveQualifiers, position)

    val nullabilityFromQualifiers = effectiveQualifiers.nullability.takeIf { shouldEnhance }
    val enhancedNullability = when (nullabilityFromQualifiers) {
        NullabilityQualifier.NULLABLE -> true
        NullabilityQualifier.NOT_NULL -> false
        else -> isNullable
    }

    var globalArgIndex = index + 1
    val enhancedArguments = typeArguments.map { arg ->
        val argIndex = globalArgIndex.also { globalArgIndex += subtreeSizes[it] }
        arg.type?.enhanceConeKotlinType(session, qualifiers, argIndex, subtreeSizes)?.let {
            when (arg.kind) {
                ProjectionKind.IN -> ConeKotlinTypeProjectionIn(it)
                ProjectionKind.OUT -> ConeKotlinTypeProjectionOut(it)
                ProjectionKind.STAR -> ConeStarProjection
                ProjectionKind.INVARIANT -> it
            }
        }
    }

    val shouldAddAttribute = nullabilityFromQualifiers == NullabilityQualifier.NOT_NULL && !hasEnhancedNullability
    if (lookupTag == enhancedTag && enhancedNullability == isNullable && !shouldAddAttribute && enhancedArguments.all { it == null }) {
        return null // absolutely no changes
    }

    // TODO: val nullabilityForWarning = enhancedNullabilityAttribute != null && effectiveQualifiers.isNullabilityQualifierForWarning
    val mergedArguments = Array(typeArguments.size) { enhancedArguments[it] ?: typeArguments[it] }
    val mergedAttributes = if (shouldAddAttribute) attributes + CompilerConeAttributes.EnhancedNullability else attributes
    return enhancedTag.constructType(mergedArguments, enhancedNullability, mergedAttributes)
}

private fun ConeClassifierLookupTag.enhanceMutability(
    qualifiers: JavaTypeQualifiers,
    position: TypeComponentPosition
): ConeClassifierLookupTag {
    if (!position.shouldEnhance()) return this
    if (this !is ConeClassLikeLookupTag) return this // mutability is not applicable for type parameters

    when (qualifiers.mutability) {
        MutabilityQualifier.READ_ONLY -> {
            val readOnlyId = classId.mutableToReadOnly()
            if (position == TypeComponentPosition.FLEXIBLE_LOWER && readOnlyId != null) {
                return ConeClassLikeLookupTagImpl(readOnlyId)
            }
        }
        MutabilityQualifier.MUTABLE -> {
            val mutableId = classId.readOnlyToMutable()
            if (position == TypeComponentPosition.FLEXIBLE_UPPER && mutableId != null) {
                return ConeClassLikeLookupTagImpl(mutableId)
            }
        }
        null -> {}
    }

    return this
}

internal fun JavaType.typeArguments(): List<JavaType?> = (this as? JavaClassifierType)?.typeArguments.orEmpty()

internal fun ConeKotlinType.lexicalCastFrom(session: FirSession, value: String): FirExpression? {
    val lookupTagBasedType = when (this) {
        is ConeLookupTagBasedType -> this
        is ConeFlexibleType -> return lowerBound.lexicalCastFrom(session, value)
        else -> return null
    }
    val lookupTag = lookupTagBasedType.lookupTag
    val firElement = lookupTag.toSymbol(session)?.fir
    if (firElement is FirRegularClass && firElement.classKind == ClassKind.ENUM_CLASS) {
        val name = Name.identifier(value)
        val firEnumEntry = firElement.collectEnumEntries().find { it.name == name }

        return if (firEnumEntry != null) buildPropertyAccessExpression {
            calleeReference = buildResolvedNamedReference {
                this.name = name
                resolvedSymbol = firEnumEntry.symbol
            }
        } else if (firElement is FirJavaClass) {
            val firStaticProperty = firElement.declarations.filterIsInstance<FirJavaField>().find {
                it.isStatic && it.modality == Modality.FINAL && it.name == name
            }
            if (firStaticProperty != null) {
                buildPropertyAccessExpression {
                    calleeReference = buildResolvedNamedReference {
                        this.name = name
                        resolvedSymbol = firStaticProperty.symbol
                    }
                }
            } else null
        } else null
    }

    if (lookupTag !is ConeClassLikeLookupTag) return null
    val classId = lookupTag.classId
    if (classId.packageFqName != FqName("kotlin")) return null

    val (number, radix) = extractRadix(value)
    return when (classId.relativeClassName.asString()) {
        "Boolean" -> buildConstExpression(null, ConstantValueKind.Boolean, value.toBoolean())
        "Char" -> buildConstExpression(null, ConstantValueKind.Char, value.singleOrNull() ?: return null)
        "Byte" -> buildConstExpression(null, ConstantValueKind.Byte, number.toByteOrNull(radix) ?: return null)
        "Short" -> buildConstExpression(null, ConstantValueKind.Short, number.toShortOrNull(radix) ?: return null)
        "Int" -> buildConstExpression(null, ConstantValueKind.Int, number.toIntOrNull(radix) ?: return null)
        "Long" -> buildConstExpression(null, ConstantValueKind.Long, number.toLongOrNull(radix) ?: return null)
        "Float" -> buildConstExpression(null, ConstantValueKind.Float, value.toFloatOrNull() ?: return null)
        "Double" -> buildConstExpression(null, ConstantValueKind.Double, value.toDoubleOrNull() ?: return null)
        "String" -> buildConstExpression(null, ConstantValueKind.String, value)
        else -> null
    }
}
