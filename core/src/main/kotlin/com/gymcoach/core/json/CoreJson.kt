// core/src/main/kotlin/com/gymcoach/core/json/CoreJson.kt
package com.gymcoach.core.json

import kotlinx.serialization.json.Json

/**
 * Three Json instances, chosen by data class:
 *  - [stored]: user documents persisted by :app (plan snapshots, mutations, chat content). Lenient
 *    to unknown keys so a newer document version never crashes an older reader.
 *  - [assets]: knowledge JSON shipped with the app. STRICT (ignoreUnknownKeys = false) so a typo or a
 *    silently dropped key fails the :core tests (R4 §6.3 test 7; sacred content applied to data files).
 *  - [wire]: Messages-API bodies. explicitNulls = false so optional fields are omitted, encodeDefaults =
 *    true so `stream: true` and `cache_control` defaults are always sent.
 */
object CoreJson {
    val stored: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        classDiscriminator = "type"
        prettyPrint = false
    }
    val assets: Json = Json {
        ignoreUnknownKeys = false
        encodeDefaults = true
        explicitNulls = false
        classDiscriminator = "type"
        prettyPrint = false
    }
    val wire: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = false
    }
}
