// core/src/main/kotlin/com/gymcoach/core/util/IdGen.kt
package com.gymcoach.core.util

import java.util.UUID

object IdGen {
    fun newId(): String = UUID.randomUUID().toString()
}
