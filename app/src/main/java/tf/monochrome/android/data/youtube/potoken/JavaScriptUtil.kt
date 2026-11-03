// SPDX-License-Identifier: GPL-3.0-or-later
// Adapted from NewPipe (TeamNewPipe/NewPipe), GPL-3.0-or-later:
// app/src/main/java/org/schabi/newpipe/util/potoken/JavaScriptUtil.kt (master, 00acf2f3).
package tf.monochrome.android.data.youtube.potoken

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString

// The conversions between BotGuard's web service, the page's JavaScript and
// PO tokens. NewPipe reads the JSON with nanojson, which the extractor uses
// but does not expose to the app; kotlinx.serialization reads it the same way
// here: a field that is not a string is null.

/**
 * The raw challenge from BotGuard's Create endpoint, as a JavaScript object
 * literal the page's `runBotGuard` takes. The challenge comes either plain
 * (`[[messageId, …]]`) or scrambled (`[…, "<base64>"]`).
 */
internal fun parseChallengeData(rawChallengeData: String): String {
    val scrambled = Json.parseToJsonElement(rawChallengeData).jsonArray

    val challengeData = if (scrambled.size > 1 && scrambled.stringAt(1) != null) {
        Json.parseToJsonElement(descramble(scrambled.stringAt(1)!!)).jsonArray
    } else {
        scrambled[0].jsonArray
    }

    val literal = buildJsonObject {
        put("messageId", challengeData.stringAt(0))
        putJsonObject("interpreterJavascript") {
            put("privateDoNotAccessOrElseSafeScriptWrappedValue", challengeData.firstStringIn(1))
            put("privateDoNotAccessOrElseTrustedResourceUrlWrappedValue", challengeData.firstStringIn(2))
        }
        put("interpreterHash", challengeData.stringAt(3))
        put("program", challengeData.stringAt(4))
        put("globalName", challengeData.stringAt(5))
        put("clientExperimentsStateBlob", challengeData.stringAt(7))
    }.toString()
    // JSON allows these two in a string, JavaScript before ES2019 does not;
    // BotGuard's interpreter source is long enough to hold anything.
    return literal.replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
}

/**
 * The raw answer from BotGuard's GenerateIT endpoint, as the integrity
 * token in a JavaScript `Uint8Array` the page can hold, and how many seconds
 * it lasts.
 */
internal fun parseIntegrityTokenData(rawIntegrityTokenData: String): Pair<String, Long> {
    val integrityTokenData = Json.parseToJsonElement(rawIntegrityTokenData).jsonArray
    val token = integrityTokenData.stringAt(0) ?: throw PoTokenException("No integrity token")
    val lifetime = (integrityTokenData.getOrNull(1) as? JsonPrimitive)?.longOrNull
        ?: throw PoTokenException("No integrity token lifetime")
    return base64ToU8(token) to lifetime
}

/**
 * [identifier] (what a token is minted for) as a JavaScript `Uint8Array`
 * of its UTF-8 bytes, the input `obtainPoToken` takes.
 */
internal fun stringToU8(identifier: String): String = newUint8Array(identifier.toByteArray())

/**
 * A token as JavaScript prints a `Uint8Array` ("97,98,99" is "abc"), in the
 * URL-safe base64 PO tokens are sent in.
 */
internal fun u8ToBase64(poToken: String): String =
    poToken.split(",")
        .map { it.toUByte().toByte() }
        .toByteArray()
        .toByteString()
        .base64()
        .replace("+", "-")
        .replace("/", "_")

/**
 * [value] as a JavaScript string literal, so an identifier never ends the
 * script it is written into, whatever it holds.
 */
internal fun jsStringLiteral(value: String): String =
    JsonPrimitive(value).toString().replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")

/** The scrambled challenge: base64, then 97 added to every byte. */
private fun descramble(scrambledChallenge: String): String =
    base64ToByteString(scrambledChallenge)
        .map { (it + 97).toByte() }
        .toByteArray()
        .decodeToString()

/** YouTube's base64 as a JavaScript `Uint8Array`. */
private fun base64ToU8(base64: String): String = newUint8Array(base64ToByteString(base64))

private fun newUint8Array(contents: ByteArray): String =
    "new Uint8Array([" + contents.joinToString(separator = ",") { it.toUByte().toString() } + "])"

/** YouTube's base64: URL-safe, with `.` for padding. */
private fun base64ToByteString(base64: String): ByteArray {
    val base64Mod = base64
        .replace('-', '+')
        .replace('_', '/')
        .replace('.', '=')
    return (base64Mod.decodeBase64() ?: throw PoTokenException("Cannot base64 decode")).toByteArray()
}

private fun JsonArray.stringAt(index: Int): String? =
    (getOrNull(index) as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonArray.firstStringIn(index: Int): String? =
    (getOrNull(index) as? JsonArray)?.firstNotNullOfOrNull { element ->
        (element as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
