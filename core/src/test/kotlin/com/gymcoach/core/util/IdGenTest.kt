package com.gymcoach.core.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IdGenTest {

    private val uuidPattern = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

    @Test fun newIdIsCanonicalUuidV4() {
        val id = IdGen.newId()
        assertEquals(36, id.length)
        assertTrue(uuidPattern.matches(id), id)
    }

    @Test fun newIdsAreUnique() {
        val ids = (1..2000).map { IdGen.newId() }.toSet()
        assertEquals(2000, ids.size)
    }

    @Test fun newIdIsLowercase() {
        repeat(50) { val id = IdGen.newId(); assertEquals(id.lowercase(), id) }
    }
}
