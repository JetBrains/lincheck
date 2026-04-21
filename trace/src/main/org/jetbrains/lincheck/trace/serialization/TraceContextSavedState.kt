package org.jetbrains.lincheck.trace.serialization

import org.jetbrains.lincheck.descriptors.AccessPath
import org.jetbrains.lincheck.descriptors.ClassDescriptor
import org.jetbrains.lincheck.descriptors.CodeLocation
import org.jetbrains.lincheck.descriptors.FieldDescriptor
import org.jetbrains.lincheck.descriptors.MethodDescriptor
import org.jetbrains.lincheck.descriptors.VariableDescriptor
import org.jetbrains.lincheck.util.Logger
import org.jetbrains.lincheck.util.collections.Bitmap
import org.jetbrains.lincheck.util.collections.SimpleBitmap
import kotlin.reflect.KClass

/**
 * Interface to check and mark if a given piece of reference data was already stored.
 */
internal interface TraceContextSavedState {
    fun isDescriptorSaved(descriptorClass: KClass<*>, id: Int): Boolean
    fun markDescriptorSaved(descriptorClass: KClass<*>, id: Int)
}

internal inline fun <reified T> TraceContextSavedState.isDescriptorSaved(id: Int) = isDescriptorSaved(T::class, id)
internal inline fun <reified T> TraceContextSavedState.markDescriptorSaved(id: Int) = markDescriptorSaved(T::class, id)


internal open class SimpleTraceContextSavedState: TraceContextSavedState {
    protected open val seenClassDescriptors = SimpleBitmap()
    val classDescriptors: Set<Int> = seenClassDescriptors
    protected open val seenMethodDescriptors = SimpleBitmap()
    val methodDescriptors: Set<Int> = seenMethodDescriptors
    protected open val seenFieldDescriptors = SimpleBitmap()
    val fieldDescriptors: Set<Int> = seenFieldDescriptors
    protected open val seenVariableDescriptors = SimpleBitmap()
    val variableDescriptors: Set<Int> = seenVariableDescriptors
    protected open val seenStringDescriptors = SimpleBitmap()
    val strings: Set<Int> = seenStringDescriptors
    protected open val seenCodeLocationDescriptors = SimpleBitmap()
    val codeLocations: Set<Int> = seenCodeLocationDescriptors
    protected open val seenAccessPathDescriptors = SimpleBitmap()
    val accessPaths: Set<Int> = seenAccessPathDescriptors

    override fun isDescriptorSaved(descriptorClass: KClass<*>, id: Int): Boolean {
        val bitmap = getBitmapArray(descriptorClass) ?: return false
        return bitmap.isSet(id)
    }

    override fun markDescriptorSaved(descriptorClass: KClass<*>, id: Int) {
        val bitmap = getBitmapArray(descriptorClass) ?: return
        bitmap.set(id)
    }

    private fun getBitmapArray(descriptorClass: KClass<*>): Bitmap? {
        return when (descriptorClass) {
            ClassDescriptor::class -> seenClassDescriptors
            MethodDescriptor::class -> seenMethodDescriptors
            FieldDescriptor::class -> seenFieldDescriptors
            VariableDescriptor::class -> seenVariableDescriptors
            String::class -> seenStringDescriptors
            CodeLocation::class -> seenCodeLocationDescriptors
            AccessPath::class -> seenAccessPathDescriptors
            else -> {
                Logger.error { "Unknown descriptor class: $descriptorClass" }
                null
            }
        }
    }
}