/*
 * Copyright 2017-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package kotlinx.serialization

import kotlinx.serialization.CompositeDecoder.Companion.UNKNOWN_NAME
import kotlinx.serialization.internal.*

import kotlin.test.*

@ImplicitReflectionSerializer
class SerialDescriptorSpecificationTest {

    @Serializable
    class Holder(val a: Int?, @Id(42) val b: String = "", @SerialName("c") val d: Long)

    @Test
    fun testAutoGeneratedDescriptorContract() {
        testHolderDescriptor(Holder.serializer().descriptor)
    }

    @Test
    fun testManuallyConstructedDescriptor() {
        testHolderDescriptor(HolderDescriptor)
    }

    private object StaticHolder {
        val userDefinedHolderDescriptor =
            SerialDescriptor("kotlinx.serialization.SerialDescriptorSpecificationTest.Holder", StructureKind.CLASS) {
                element<Int?>("a")
                val annotation = Holder.serializer().descriptor.findAnnotation<Id>(1)
                element<String>("b", listOf(annotation!!), isOptional = true)
                element<Long>("c")
            }
    }

    private object HolderDescriptor : SerialDescriptor by StaticHolder.userDefinedHolderDescriptor

    private fun testHolderDescriptor(d: SerialDescriptor) {
        assertEquals(StructureKind.CLASS, d.kind)
        assertEquals("kotlinx.serialization.SerialDescriptorSpecificationTest.Holder", d.serialName)
        assertEquals(3, d.elementsCount)
        // Indices
        run {
            assertEquals(0, d.getElementIndex("a"))
            assertEquals(1, d.getElementIndex("b"))
            assertEquals(2, d.getElementIndex("c"))
            assertEquals(UNKNOWN_NAME, d.getElementIndex("?"))
            assertEquals(UNKNOWN_NAME, d.getElementIndex("d"))

            assertEquals("a", d.getElementName(0))
            assertEquals("b", d.getElementName(1))
            assertEquals("c", d.getElementName(2))
        }
        // Children annotations
        run {
            assertEquals(0, d.getElementAnnotations(0).size)
            d.assertSingleAnnotation(1) { it is Id && it.id == 42 }
            assertEquals(0, d.getElementAnnotations(2).size)
        }
        // Optionality
        run {
            assertFalse(d.isElementOptional(0))
            assertTrue(d.isElementOptional(1))
            assertFalse(d.isElementOptional(2))
        }
        // Children descriptors
        run {
            val aDescriptor = d.getElementDescriptor(0)
            assertTrue(aDescriptor.isNullable)
            assertTrue(aDescriptor.kind is PrimitiveKind.INT)

            val bDescriptor = d.getElementDescriptor(1)
            assertFalse(bDescriptor.isNullable)
            assertTrue(bDescriptor.kind is PrimitiveKind.STRING)

            val cDescriptor = d.getElementDescriptor(2)
            assertFalse(cDescriptor.isNullable)
            assertTrue(cDescriptor.kind is PrimitiveKind.LONG)
        }
        // Failure modes
        // TODO sandwraith initialization order assertFailsWith<IndexOutOfBoundsException> { d.isElementOptional(3) }
        assertFailsWith<IndexOutOfBoundsException> { d.isElementOptional(Int.MAX_VALUE) }
        assertFailsWith<IndexOutOfBoundsException> { d.getElementAnnotations(3) }
        assertFailsWith<IndexOutOfBoundsException> { d.getElementName(3) }
        assertFailsWith<IndexOutOfBoundsException> { d.getElementAnnotations(3) }
    }

    @SerialName("Named")
    @Serializable
    @Suppress("UNUSED")
    enum class NamedEnum {
        @Id(42)
        FIRST,
        @SerialName("SECOND")
        THIRD
    }

    @Test
    fun testEnumDescriptor() {
        fun SerialDescriptor.verifyEnumMember(name: String) {
            assertEquals("Named.$name", serialName)
            assertEquals(0, elementsCount)
            assertEquals(StructureKind.OBJECT, kind)
        }

        val d = NamedEnum.serializer().descriptor
        assertEquals(UnionKind.ENUM_KIND, d.kind)
        assertEquals("Named", d.serialName)
        assertEquals(2, d.elementsCount)
        assertFalse(d.isNullable)
        // Names
        assertEquals(0, d.getElementIndex("FIRST"))
        assertEquals(1, d.getElementIndex("SECOND"))
        assertEquals(UNKNOWN_NAME, d.getElementIndex("THIRD"))
        // Elements
        assertEquals("FIRST", d.getElementName(0))
        assertEquals("SECOND", d.getElementName(1))
        assertFailsWith<IndexOutOfBoundsException> { d.getElementName(2) }
        // Annotations
        d.assertSingleAnnotation(0) { it is Id && it.id == 42 }
        assertEquals(0, d.getElementAnnotations(1).size)
        // Element descriptors
        d.getElementDescriptor(0).verifyEnumMember("FIRST")
        d.getElementDescriptor(1).verifyEnumMember("SECOND")
        assertFailsWith<IndexOutOfBoundsException> { d.getElementDescriptor(2) }
    }

    @Test
    fun testListDescriptor() {
        val descriptor = ArrayListSerializer(IntSerializer).descriptor
        assertEquals("kotlin.collections.ArrayList", descriptor.serialName)
        assertFalse(descriptor.isNullable)
        assertEquals(1, descriptor.elementsCount)
        assertSame(IntSerializer.descriptor, descriptor.getElementDescriptor(0))
        assertFalse(descriptor.isElementOptional(0))
        assertFailsWith<IllegalStateException> { descriptor.isElementOptional(1) }
    }

    @Test
    fun testMapDescriptor() {
        val descriptor = HashMapSerializer(IntSerializer, LongSerializer).descriptor
        assertEquals("kotlin.collections.HashMap", descriptor.serialName)
        assertFalse(descriptor.isNullable)
        assertEquals(2, descriptor.elementsCount)
        assertSame(IntSerializer.descriptor, descriptor.getElementDescriptor(0))
        assertSame(LongSerializer.descriptor, descriptor.getElementDescriptor(1))
        assertTrue(descriptor.getElementAnnotations(0).isEmpty())
        assertTrue(descriptor.getElementAnnotations(1).isEmpty())
        assertFalse(descriptor.isElementOptional(0))
        assertFalse(descriptor.isElementOptional(1))
        assertFailsWith<IllegalStateException> { descriptor.isElementOptional(3) }
    }

    @Serializable
    @SerialName("SerializableObject")
    object SerializableObject {}

    @Test
    fun testObjectDescriptor() {
        val descriptor = SerializableObject.serializer().descriptor
        assertEquals(StructureKind.OBJECT, descriptor.kind)
        assertEquals("SerializableObject", descriptor.serialName)
        assertEquals(0, descriptor.elementsCount)
        assertEquals(UNKNOWN_NAME, descriptor.getElementIndex("?"))
        // Failure modes
        assertFailsWith<IndexOutOfBoundsException> { descriptor.isElementOptional(0) }
        assertFailsWith<IndexOutOfBoundsException> { descriptor.getElementAnnotations(0) }
        assertFailsWith<IndexOutOfBoundsException> { descriptor.getElementName(0) }
        assertFailsWith<IndexOutOfBoundsException> { descriptor.getElementAnnotations(0) }
    }

    @Test
    fun testPrimitiveDescriptors() {
        checkPrimitiveDescriptor("int", IntSerializer.descriptor)
        checkPrimitiveDescriptor("unit", UnitSerializer.descriptor)
        checkPrimitiveDescriptor("boolean", BooleanSerializer.descriptor)
        checkPrimitiveDescriptor("byte", ByteSerializer.descriptor)
        checkPrimitiveDescriptor("short", ShortSerializer.descriptor)
        checkPrimitiveDescriptor("long", LongSerializer.descriptor)
        checkPrimitiveDescriptor("float", FloatSerializer.descriptor)
        checkPrimitiveDescriptor("double", DoubleSerializer.descriptor)
        checkPrimitiveDescriptor("char", CharSerializer.descriptor)
        checkPrimitiveDescriptor("string", StringSerializer.descriptor)
    }

    @Test
    fun testCustomPrimitiveDescriptor() {
        assertFailsWith<IllegalArgumentException> { PrimitiveDescriptor("kotlin.Int", PrimitiveKind.INT) }
        assertFailsWith<IllegalArgumentException> { PrimitiveDescriptor("Int", PrimitiveKind.INT) }
        assertFailsWith<IllegalArgumentException> { PrimitiveDescriptor("int", PrimitiveKind.INT) }
    }

    private fun checkPrimitiveDescriptor(type: String, descriptor: SerialDescriptor) {
        assertEquals(0, descriptor.elementsCount)
        val kind = descriptor.kind.toString().toLowerCase()
        assertEquals(type, kind)
        assertEquals("kotlin.${type.capitalize()}", descriptor.serialName)
        assertEquals(0, descriptor.annotations.size)
        assertFalse(descriptor.isNullable)
        assertFailsWith<IllegalStateException> { descriptor.getElementName(0) }
    }

    inline fun SerialDescriptor.assertSingleAnnotation(index: Int, validator: (Annotation) -> Boolean) {
        val annotations = getElementAnnotations(index)
        assertEquals(1, annotations.size)
        assertTrue(validator(annotations.first()))
    }
}
