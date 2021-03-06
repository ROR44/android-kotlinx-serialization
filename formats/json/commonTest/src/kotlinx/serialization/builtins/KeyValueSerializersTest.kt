/*
 * Copyright 2017-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package kotlinx.serialization.builtins

import kotlinx.serialization.*
import kotlinx.serialization.json.*
import kotlinx.serialization.test.*
import kotlin.test.*

class KeyValueSerializersTest : JsonTestBase() {

    @Test
    fun testPair() = parametrizedTest { jsonTestingMode ->
        testPair(Pair(42, 42), Int.serializer(), Int.serializer(), jsonTestingMode, """{"first":42,"second":42}""")
        testPair(
            Pair(42, Pair("a", "b")),
            Int.serializer(),
            serializer(),
            jsonTestingMode,
            """{"first":42,"second":{"first":"a","second":"b"}}"""
        )
        testPair(
            Pair(42, null),
            Int.serializer(),
            Int.serializer().nullable,
            jsonTestingMode,
            """{"first":42,"second":null}"""
        )
    }

    private fun <K, V> testPair(
        pairInstance: Pair<K, V>,
        kSerializer: KSerializer<K>,
        vSerializer: KSerializer<V>,
        jsonTestingMode: JsonTestingMode,
        expectedJson: String
    ) {
        val serializer = PairSerializer(kSerializer, vSerializer)
        val json = default.encodeToString(serializer, pairInstance, jsonTestingMode)
        assertEquals(expectedJson, json)
        val pair = default.decodeFromString(serializer, json, jsonTestingMode)
        assertEquals(pairInstance, pair)
    }

    @Test
    fun testTriple() = parametrizedTest { jsonTestingMode ->
        testTriple(
            Triple(42, 42, "42"),
            Int.serializer(),
            Int.serializer(),
            String.serializer(),
            jsonTestingMode,
            """{"first":42,"second":42,"third":"42"}"""
        )

        testTriple(
            Triple(42, Triple(42, "f", 'c'), "42"),
            Int.serializer(),
            serializer(),
            String.serializer(),
            jsonTestingMode,
            """{"first":42,"second":{"first":42,"second":"f","third":"c"},"third":"42"}"""
        )

        testTriple(
            Triple(42, null, null),
            Int.serializer(),
            Int.serializer().nullable,
            String.serializer().nullable,
            jsonTestingMode,
            """{"first":42,"second":null,"third":null}"""
        )
    }

    private fun  <A, B, C> testTriple(
        tripleInstance: Triple<A, B, C>,
        aSerializer: KSerializer<A>,
        bSerializer: KSerializer<B>,
        cSerializer: KSerializer<C>,
        jsonTestingMode: JsonTestingMode,
        expectedJson: String
    ) {
        val serializer = TripleSerializer(aSerializer, bSerializer, cSerializer)
        val json = default.encodeToString(serializer, tripleInstance, jsonTestingMode)
        assertEquals(expectedJson, json)
        val triple = default.decodeFromString(serializer, json, jsonTestingMode)
        assertEquals(tripleInstance, triple)
    }

    class Entry<K, V>(override val key: K, override val value: V) : Map.Entry<K, V> {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other == null || other !is Map.Entry<*, *>) return false
            if (key != other.key) return false
            if (value != other.value) return false
            return true
        }

        override fun hashCode(): Int {
            var result = key?.hashCode() ?: 0
            result = 31 * result + (value?.hashCode() ?: 0)
            return result
        }
    }

    @Test
    fun testKeyValuePair() = parametrizedTest { jsonTestingMode ->
        jvmOnly {
            testEntry(Entry(42, 42), Int.serializer(), Int.serializer(), jsonTestingMode, """{"42":42}""")
            testEntry(
                Entry(42, Entry("a", "b")),
                Int.serializer(),
                serializer<Map.Entry<String, String>>(),
                jsonTestingMode,
                """{"42":{"a":"b"}}"""
            )
            testEntry(
                Entry(42, null),
                Int.serializer(),
                Int.serializer().nullable,
                jsonTestingMode,
                """{"42":null}"""
            )
        }
    }

    private inline fun <reified K, reified V> testEntry(
        entryInstance: Map.Entry<K, V>,
        kSerializer: KSerializer<K>,
        vSerializer: KSerializer<V>,
        jsonTestingMode: JsonTestingMode,
        expectedJson: String
    ) {
        val serializer = MapEntrySerializer(kSerializer, vSerializer)
        val json = default.encodeToString(serializer, entryInstance, jsonTestingMode)
        assertEquals(expectedJson, json)
        val entry = default.decodeFromString(serializer, json, jsonTestingMode)
        assertEquals(entryInstance, entry)
    }
}
