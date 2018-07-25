/*
 * Copyright 2018 JetBrains s.r.o.
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

package kotlinx.serialization.internal

import kotlinx.serialization.*
import kotlinx.serialization.CompositeDecoder.Companion.READ_ALL
import kotlinx.serialization.CompositeDecoder.Companion.READ_DONE
import kotlin.reflect.KClass

// ============================= serializers =============================

sealed class ListLikeSerializer<E, C, B>(open val eSerializer: KSerializer<E>) : KSerializer<C> {
    abstract override val descriptor: ListLikeDesc

    abstract fun C.objSize(): Int
    abstract fun C.objIterator(): Iterator<E>
    abstract fun builder(): B
    abstract fun B.builderSize(): Int
    abstract fun B.toResult(): C
    abstract fun C.toBuilder(): B
    abstract fun B.checkCapacity(size: Int)
    abstract fun B.insert(index: Int, element: E)

    open val typeParams: Array<KSerializer<*>> = arrayOf(eSerializer)

    override fun serialize(output: Encoder, obj: C) {
        val size = obj.objSize()
        @Suppress("NAME_SHADOWING")
        val output = output.beginCollection(descriptor, size, *typeParams)
        val iterator = obj.objIterator()
        for (index in 0 until size)
            output.encodeSerializableElement(descriptor, index, eSerializer, iterator.next())
        output.endStructure(descriptor)
    }

    override fun patch(input: Decoder, old: C): C {
        val builder = old.toBuilder()
        val startIndex = builder.builderSize()
        @Suppress("NAME_SHADOWING")
        val input = input.beginStructure(descriptor, *typeParams)
        readSize(input, builder)
        mainLoop@ while (true) {
            val index = input.decodeElementIndex(descriptor)
            when (index) {
                READ_ALL -> {
                    readAll(input, builder, startIndex)
                    break@mainLoop
                }
                READ_DONE -> break@mainLoop
                else -> readItem(input, startIndex + index, builder)
            }

        }
        input.endStructure(descriptor)
        return builder.toResult()
    }

    override fun deserialize(input: Decoder): C {
        val builder = builder()
        return patch(input, builder.toResult())
    }

    private fun readSize(input: CompositeDecoder, builder: B): Int {
        val size = input.decodeCollectionSize(descriptor)
        builder.checkCapacity(size)
        return size
    }

    protected open fun readItem(input: CompositeDecoder, index: Int, builder: B) {
        builder.insert(index, input.decodeSerializableElement(descriptor, index, eSerializer))
    }

    private fun readAll(input: CompositeDecoder, builder: B, startIndex: Int) {
        val size = readSize(input, builder)
        require(size >= 0) { "Size must be known in advance when using READ_ALL" }
        for (index in 0 until size)
            readItem(input, startIndex + index, builder)
    }
}

abstract class MapLikeSerializer<K, V, B : MutableMap<K, V>>(override val eSerializer: MapEntrySerializer<K, V>) :
        ListLikeSerializer<Map.Entry<K, V>, Map<K, V>, B>(eSerializer) {

    override fun readItem(input: CompositeDecoder, index: Int, builder: B) {
        input.decodeSerializableElement(descriptor, index, MapEntryUpdatingSerializer(eSerializer, builder))
    }
}

// todo: can be more efficient when array size is know in advance, this one always uses temporary ArrayList as builder
class ReferenceArraySerializer<T: Any, E: T?>(private val kClass: KClass<T>, eSerializer: KSerializer<E>):
        ListLikeSerializer<E, Array<E>, ArrayList<E>>(eSerializer) {
    override val descriptor = ArrayClassDesc(eSerializer.descriptor)

    override fun Array<E>.objSize(): Int = size
    override fun Array<E>.objIterator(): Iterator<E> = iterator()
    override fun builder(): ArrayList<E> = arrayListOf()
    override fun ArrayList<E>.builderSize(): Int = size
    @Suppress("UNCHECKED_CAST")
    override fun ArrayList<E>.toResult(): Array<E> = toNativeArray<T, E>(kClass)
    override fun Array<E>.toBuilder(): ArrayList<E> = ArrayList(this.asList())
    override fun ArrayList<E>.checkCapacity(size: Int) = ensureCapacity(size)
    override fun ArrayList<E>.insert(index: Int, element: E) { add(index, element) }
}

class ArrayListSerializer<E>(element: KSerializer<E>) : ListLikeSerializer<E, List<E>, ArrayList<E>>(element) {
    override val descriptor = ArrayListClassDesc(element.descriptor)

    override fun List<E>.objSize(): Int = size
    override fun List<E>.objIterator(): Iterator<E> = iterator()
    override fun builder(): ArrayList<E> = arrayListOf()
    override fun ArrayList<E>.builderSize(): Int = size
    override fun ArrayList<E>.toResult(): List<E> = this
    override fun List<E>.toBuilder(): ArrayList<E> = this as? ArrayList<E> ?: ArrayList(this)
    override fun ArrayList<E>.checkCapacity(size: Int) = ensureCapacity(size)
    override fun ArrayList<E>.insert(index: Int, element: E) { add(index, element) }
}

class LinkedHashSetSerializer<E>(eSerializer: KSerializer<E>) : ListLikeSerializer<E, Set<E>, LinkedHashSet<E>>(eSerializer) {
    override val descriptor = LinkedHashSetClassDesc(eSerializer.descriptor)

    override fun Set<E>.objSize(): Int = size
    override fun Set<E>.objIterator(): Iterator<E> = iterator()
    override fun builder(): LinkedHashSet<E> = linkedSetOf()
    override fun LinkedHashSet<E>.builderSize(): Int = size
    override fun LinkedHashSet<E>.toResult(): Set<E> = this
    override fun Set<E>.toBuilder(): LinkedHashSet<E> = this as? LinkedHashSet<E> ?: LinkedHashSet(this)
    override fun LinkedHashSet<E>.checkCapacity(size: Int) {}
    override fun LinkedHashSet<E>.insert(index: Int, element: E) { add(element) }
}

class HashSetSerializer<E>(eSerializer: KSerializer<E>) : ListLikeSerializer<E, Set<E>, HashSet<E>>(eSerializer) {
    override val descriptor = HashSetClassDesc(eSerializer.descriptor)

    override fun Set<E>.objSize(): Int = size
    override fun Set<E>.objIterator(): Iterator<E> = iterator()
    override fun builder(): HashSet<E> = HashSet()
    override fun HashSet<E>.builderSize(): Int = size
    override fun HashSet<E>.toResult(): Set<E> = this
    override fun Set<E>.toBuilder(): HashSet<E> = this as? HashSet<E> ?: HashSet(this)
    override fun HashSet<E>.checkCapacity(size: Int) {}
    override fun HashSet<E>.insert(index: Int, element: E) { add(element) }
}

class LinkedHashMapSerializer<K, V>(kSerializer: KSerializer<K>, vSerializer: KSerializer<V>) :
        MapLikeSerializer<K, V, LinkedHashMap<K, V>>(MapEntrySerializer<K, V>(kSerializer, vSerializer)) {
    override val descriptor = LinkedHashMapClassDesc(MapEntrySerializer<K, V>(kSerializer, vSerializer).descriptor)
    override val typeParams: Array<KSerializer<*>> = arrayOf(kSerializer, vSerializer)

    override fun Map<K, V>.objSize(): Int = size
    override fun Map<K, V>.objIterator(): Iterator<Map.Entry<K, V>> = iterator()
    override fun builder(): LinkedHashMap<K, V> = LinkedHashMap()
    override fun LinkedHashMap<K, V>.builderSize(): Int = size
    override fun LinkedHashMap<K, V>.toResult(): Map<K, V> = this
    override fun Map<K, V>.toBuilder(): LinkedHashMap<K, V> = this as? LinkedHashMap<K, V> ?: LinkedHashMap(this)
    override fun LinkedHashMap<K, V>.checkCapacity(size: Int) {}
    override fun LinkedHashMap<K, V>.insert(index: Int, element: Map.Entry<K, V>) { put(element.key, element.value) }
}

class HashMapSerializer<K, V>(kSerializer: KSerializer<K>, vSerializer: KSerializer<V>) :
        MapLikeSerializer<K, V, HashMap<K, V>>(MapEntrySerializer<K, V>(kSerializer, vSerializer)) {
    override val descriptor: ListLikeDesc = HashMapClassDesc(MapEntrySerializer<K, V>(kSerializer, vSerializer).descriptor)
    override val typeParams: Array<KSerializer<*>> = arrayOf(kSerializer, vSerializer)

    override fun Map<K, V>.objSize(): Int = size
    override fun Map<K, V>.objIterator(): Iterator<Map.Entry<K, V>> = iterator()
    override fun builder(): HashMap<K, V> = HashMap()
    override fun HashMap<K, V>.builderSize(): Int = size
    override fun HashMap<K, V>.toResult(): Map<K, V> = this
    override fun Map<K, V>.toBuilder(): HashMap<K, V> = this as? HashMap<K, V> ?: HashMap(this)
    override fun HashMap<K, V>.checkCapacity(size: Int) {}
    override fun HashMap<K, V>.insert(index: Int, element: Map.Entry<K, V>) { put(element.key, element.value) }
}

const val KEY_INDEX = 0
const val VALUE_INDEX = 1

sealed class KeyValueSerializer<K, V, R>(val kSerializer: KSerializer<K>, val vSerializer: KSerializer<V>) : KSerializer<R> {
    abstract override val descriptor: SerialDescriptor
    abstract fun toResult(key: K, value: V): R
    abstract val R.key: K
    abstract val R.value: V

    override fun serialize(output: Encoder, obj: R) {
        @Suppress("NAME_SHADOWING")
        val output = output.beginStructure(descriptor, kSerializer, vSerializer)
        output.encodeSerializableElement(descriptor, KEY_INDEX, kSerializer, obj.key)
        output.encodeSerializableElement(descriptor, VALUE_INDEX, vSerializer, obj.value)
        output.endStructure(descriptor)
    }

    override fun deserialize(input: Decoder): R {
        @Suppress("NAME_SHADOWING")
        val input = input.beginStructure(descriptor, kSerializer, vSerializer)
        var kSet = false
        var vSet = false
        var k: Any? = null
        var v: Any? = null
        mainLoop@ while (true) {
            when (input.decodeElementIndex(descriptor)) {
                READ_ALL -> {
                    k = readKey(input)
                    kSet = true
                    v = readValue(input, k, kSet)
                    vSet = true
                    break@mainLoop
                }
                READ_DONE -> {
                    break@mainLoop
                }
                KEY_INDEX -> {
                    k = readKey(input)
                    kSet = true
                }
                VALUE_INDEX -> {
                    v = readValue(input, k, kSet)
                    vSet = true
                }
                else -> throw SerializationException("Invalid index")
            }
        }
        input.endStructure(descriptor)
        if (!kSet) throw SerializationException("Required key is missing")
        if (!vSet) throw SerializationException("Required value is missing")
        @Suppress("UNCHECKED_CAST")
        return toResult(k as K, v as V)
    }

    protected open fun readKey(input: CompositeDecoder): K {
        return input.decodeSerializableElement(descriptor, KEY_INDEX, kSerializer)
    }

    protected open fun readValue(input: CompositeDecoder, k: Any?, kSet: Boolean): V {
        return input.decodeSerializableElement(descriptor, VALUE_INDEX, vSerializer)
    }
}

@Suppress("EXTENSION_SHADOWED_BY_MEMBER")
class MapEntryUpdatingSerializer<K, V>(mSerializer: MapEntrySerializer<K, V>, private val mapBuilder: MutableMap<K, V>) :
        KeyValueSerializer<K, V, Map.Entry<K, V>>(mSerializer.kSerializer, mSerializer.vSerializer) {

    override val descriptor = MapEntryClassDesc
    override fun toResult(key: K, value: V): Map.Entry<K, V> = MapEntry(key, value)

    override fun readValue(input: CompositeDecoder, k: Any?, kSet: Boolean): V {
        if (!kSet) throw SerializationException("Key must be before value in serialization stream")
        @Suppress("UNCHECKED_CAST")
        val key = k as K
        val v = if (mapBuilder.containsKey(key) && vSerializer.descriptor.kind !is PrimitiveKind) {
            input.updateSerializableElement(descriptor, VALUE_INDEX, vSerializer, mapBuilder.getValue(key))
        } else {
            input.decodeSerializableElement(descriptor, VALUE_INDEX, vSerializer)
        }
        mapBuilder[key] = v
        return v
    }

    override val Map.Entry<K, V>.key: K
        get() = this.key
    override val Map.Entry<K, V>.value: V
        get() = this.value

}

@Suppress("EXTENSION_SHADOWED_BY_MEMBER")
class MapEntrySerializer<K, V>(kSerializer: KSerializer<K>, vSerializer: KSerializer<V>) :
        KeyValueSerializer<K, V, Map.Entry<K, V>>(kSerializer, vSerializer) {
    override val descriptor = MapEntryClassDesc
    override fun toResult(key: K, value: V): Map.Entry<K, V> = MapEntry(key, value)

    override val Map.Entry<K, V>.key: K
        get() = this.key
    override val Map.Entry<K, V>.value: V
        get() = this.value
}

class PairSerializer<K, V>(kSerializer: KSerializer<K>, vSerializer: KSerializer<V>) :
        KeyValueSerializer<K, V, Pair<K, V>>(kSerializer, vSerializer) {
    override val descriptor = PairClassDesc
    override fun toResult(key: K, value: V) = key to value

    override val Pair<K, V>.key: K
        get() = this.first
    override val Pair<K, V>.value: V
        get() = this.second
}

// ============================= class descriptors =============================

sealed class ListLikeDesc(private val elementDesc: SerialDescriptor) : SerialDescriptor {
    override fun getElementName(index: Int): String = index.toString()
    override fun getElementIndex(name: String): Int = name.toIntOrNull() ?: throw IllegalArgumentException("$name is not a valid list index")
    override fun getElementDescriptor(index: Int): SerialDescriptor = elementDesc
    override fun isElementOptional(index: Int): Boolean = false
}

internal val ARRAY_NAME = "kotlin.Array"
internal val ARRAYLIST_NAME = "kotlin.collections.ArrayList"
internal val LINKEDHASHSET_NAME = "kotlin.collections.LinkedHashSet"
internal val HASHSET_NAME = "kotlin.collections.HashSet"
internal val LINKEDHASHMAP_NAME = "kotlin.collections.LinkedHashMap"
internal val HASHMAP_NAME = "kotlin.collections.HashMap"

class ArrayClassDesc(elementDesc: SerialDescriptor) : ListLikeDesc(elementDesc) {
    override val name: String get() = ARRAY_NAME
    override val kind: SerialKind get() = StructureKind.LIST
}

class ArrayListClassDesc (elementDesc: SerialDescriptor) : ListLikeDesc(elementDesc) {
    override val name: String get() = ARRAYLIST_NAME
    override val kind: SerialKind get() = StructureKind.LIST
}

class LinkedHashSetClassDesc (elementDesc: SerialDescriptor) : ListLikeDesc(elementDesc) {
    override val name: String get() = LINKEDHASHSET_NAME
    override val kind: SerialKind get() = StructureKind.SET
}

class HashSetClassDesc (elementDesc: SerialDescriptor) : ListLikeDesc(elementDesc) {
    override val name: String get() = "kotlin.collections.HashSet"
    override val kind: SerialKind get() = StructureKind.SET
}

class LinkedHashMapClassDesc (elementDesc: SerialDescriptor) : ListLikeDesc(elementDesc) {
    override val name: String get() = LINKEDHASHMAP_NAME
    override val kind: SerialKind get() = StructureKind.MAP
}

class HashMapClassDesc (elementDesc: SerialDescriptor) : ListLikeDesc(elementDesc) {
    override val name: String get() = HASHMAP_NAME
    override val kind: SerialKind get() = StructureKind.MAP
}

data class MapEntry<K, V>(override val key: K, override val value: V) : Map.Entry<K, V>

object MapEntryClassDesc : SerialClassDescImpl("kotlin.collections.Map.Entry") {
    override val kind: SerialKind = StructureKind.ENTRY

    init {
        addElement("key")
        // TODO pushDescriptor()
        addElement("value")
    }
}

object PairClassDesc : SerialClassDescImpl("kotlin.Pair") {
    init {
        addElement("first")
        addElement("second")
    }
}

class TripleSerializer<A, B, C>(
        private val aSerializer: KSerializer<A>,
        private val bSerializer: KSerializer<B>,
        private val cSerializer: KSerializer<C>
) : KSerializer<Triple<A, B, C>> {
    object TripleDesc : SerialClassDescImpl("kotlin.Triple") {
        init {
            addElement("first")
            addElement("second")
            addElement("third")
        }
    }

    override val descriptor: SerialDescriptor = TripleDesc

    override fun serialize(output: Encoder, obj: Triple<A, B, C>) {
        @Suppress("NAME_SHADOWING")
        val output = output.beginStructure(descriptor, aSerializer, bSerializer, cSerializer)
        output.encodeSerializableElement(descriptor, 0, aSerializer, obj.first)
        output.encodeSerializableElement(descriptor, 1, bSerializer, obj.second)
        output.encodeSerializableElement(descriptor, 2, cSerializer, obj.third)
        output.endStructure(descriptor)
    }

    override fun deserialize(input: Decoder): Triple<A, B, C> {
        @Suppress("NAME_SHADOWING")
        val input = input.beginStructure(descriptor, aSerializer, bSerializer, cSerializer)
        var aSet = false
        var bSet = false
        var cSet = false
        var a: Any? = null
        var b: Any? = null
        var c: Any? = null
        mainLoop@ while (true) {
            when (input.decodeElementIndex(descriptor)) {
                READ_ALL -> {
                    a = input.decodeSerializableElement(descriptor, 0, aSerializer)
                    aSet = true
                    b = input.decodeSerializableElement(descriptor, 1, bSerializer)
                    bSet = true
                    c = input.decodeSerializableElement(descriptor, 2, cSerializer)
                    cSet = true
                    break@mainLoop
                }
                READ_DONE -> {
                    break@mainLoop
                }
                0 -> {
                    a = input.decodeSerializableElement(descriptor, 0, aSerializer)
                    aSet = true
                }
                1 -> {
                    b = input.decodeSerializableElement(descriptor, 1, bSerializer)
                    bSet = true
                }
                2 -> {
                    c = input.decodeSerializableElement(descriptor, 2, cSerializer)
                    cSet = true
                }
                else -> throw SerializationException("Invalid index")
            }
        }
        input.endStructure(descriptor)
        if (!aSet) throw SerializationException("Required first is missing")
        if (!bSet) throw SerializationException("Required second is missing")
        if (!cSet) throw SerializationException("Required third is missing")
        @Suppress("UNCHECKED_CAST")
        return Triple(a as A, b as B, c as C)
    }
}
