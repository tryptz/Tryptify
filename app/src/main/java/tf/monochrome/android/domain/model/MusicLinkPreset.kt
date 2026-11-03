// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.domain.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * A set of music links kept under a name: every visual link on the Music
 * links page (lyrics, the player's and the panels' glass, the CRT, the
 * visualizer), each with its own pause. The mixer's knob links are not in
 * one: they name a mix's own effect slots, which another mix does not have,
 * and they travel with mixer presets already.
 */
data class MusicLinkPreset(val name: String, val links: List<VisualLink>)

/** The preset file, and the list of the listener's presets as the phone keeps it. */
object MusicLinkPresets {
    /** What an exported preset says it is, so a file of anything else is refused. */
    const val FILE_TYPE = "tryptify-music-links"
    const val FILE_VERSION = 1

    /** The longest name a preset keeps. */
    const val MAX_NAME = 32

    /** A preset file's name: the preset's, made safe for any file system. */
    fun fileName(name: String): String {
        val safe = name.trim().replace(Regex("[^A-Za-z0-9 _-]"), "").replace(' ', '_').take(40)
        return "${safe.ifBlank { "music-links" }}.links.json"
    }

    @Serializable
    private data class PresetFile(
        val type: String = "",
        val version: Int = 0,
        val name: String = "",
        val links: List<VisualLink> = emptyList(),
    )

    // Tolerant as VisualLinkCodec is: fields and enum values from a newer
    // version are skipped or fall back, and sanitize() does the rest.
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        prettyPrint = true
        encodeDefaults = true
    }

    private fun fileOf(preset: MusicLinkPreset) =
        PresetFile(FILE_TYPE, FILE_VERSION, preset.name, VisualLinkCodec.sanitize(preset.links))

    /** [preset] as the text of a `.links.json` file. */
    fun export(preset: MusicLinkPreset): String = json.encodeToString(PresetFile.serializer(), fileOf(preset))

    /**
     * A preset from a file's [text], or why not. Links to settings this
     * version does not know are dropped and every range is held to its
     * setting's, as for links that sync; a file left with none is refused,
     * since loading it would only clear the page.
     */
    fun import(text: String): Result<MusicLinkPreset> = runCatching {
        val file = json.decodeFromString(PresetFile.serializer(), text)
        require(file.type == FILE_TYPE) { "Not a music links preset" }
        require(file.version in 1..FILE_VERSION) { "Made by a newer Tryptify" }
        val links = VisualLinkCodec.sanitize(file.links)
        require(links.isNotEmpty()) { "No links this version knows" }
        MusicLinkPreset(name = cleanName(file.name, "Imported"), links = links)
    }

    /** The listener's presets, as kept on the phone. */
    fun encodeAll(presets: List<MusicLinkPreset>): String =
        json.encodeToString(ListSerializer(PresetFile.serializer()), presets.map(::fileOf))

    /** What [encodeAll] wrote; an unreadable store is an empty list, never a crash. */
    fun decodeAll(text: String?): List<MusicLinkPreset> {
        if (text.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString(ListSerializer(PresetFile.serializer()), text) }
            .getOrDefault(emptyList())
            .map { MusicLinkPreset(cleanName(it.name, "Preset"), VisualLinkCodec.sanitize(it.links)) }
            .filter { it.links.isNotEmpty() }
    }

    /**
     * Whether [links] are [preset]'s, in any order: what the page marks as
     * the preset in use. Order is not a difference the listener can see.
     */
    fun matches(preset: MusicLinkPreset, links: List<VisualLink>): Boolean =
        preset.links.size == links.size && preset.links.toSet() == links.toSet()

    private fun cleanName(name: String, fallback: String) = name.trim().take(MAX_NAME).ifBlank { fallback }
}
