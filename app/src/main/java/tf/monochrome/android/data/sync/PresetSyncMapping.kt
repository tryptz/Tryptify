package tf.monochrome.android.data.sync

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import tf.monochrome.android.data.db.entity.EqPresetEntity
import tf.monochrome.android.data.db.entity.MixPresetEntity

/**
 * Turning presets into cloud rows and back, kept out of the repository so the
 * decisions can be tested without a network or a database.
 *
 * Everything here is a pure function of its arguments. Three decisions live in
 * this file, and each of them is invisible at the call site and expensive to get
 * wrong: which value identifies a preset across devices, which timestamp is
 * allowed to settle a conflict, and which fields must be written even when they
 * are empty.
 */

/**
 * Whether the cloud's copy of a preset should replace the device's.
 *
 * Newest edit wins, which is a deliberate departure from the
 * insert-if-not-exists the rest of the pull uses. Favourites and playlists are
 * set membership: add and remove are the whole vocabulary, a re-add is
 * idempotent, and "never overwrite local" loses nothing. A preset is a mutable
 * document under a stable key, sometimes an hour of tuning, and under
 * insert-if-not-exists the first version ever pushed is the only version any
 * other device ever sees.
 *
 * **Both arguments must be device clocks**, read from `created_at_ms` /
 * `updated_at_ms` and never from the `updated_at` column. Both preset tables
 * carry a `BEFORE UPDATE` trigger that rewrites `updated_at` to `now()`, and an
 * upsert is an INSERT ON CONFLICT DO UPDATE, so from the second push of a
 * preset onwards that column records when a device last synced rather than when
 * anyone edited. Comparing it against `System.currentTimeMillis()` would also
 * put a server clock on one side and a phone's on the other, and ordinary clock
 * skew would then hand a stale cloud copy the right to overwrite a tuning the
 * user finished ten seconds ago.
 *
 * A local row that does not exist is not a conflict, so the caller inserts.
 * Ties go to local: equal timestamps are the same edit, and of the two options
 * writing is the one that can lose something.
 */
internal fun cloudCopyIsNewer(localUpdatedAtMs: Long?, cloudUpdatedAtMs: Long): Boolean =
    localUpdatedAtMs == null || cloudUpdatedAtMs > localUpdatedAtMs

/**
 * The upsert payload for an EQ preset.
 *
 * Built field by field rather than serialized from [SbEqPreset] because of
 * `bands_r`. The Supabase client drops Kotlin nulls from the payload rather
 * than writing them (this is why every other push here can leave `added_at`
 * null and still get the column's default), and a field absent from an
 * ON CONFLICT DO UPDATE is a field left at its previous value. So a preset
 * saved in 2-channel mode and then re-saved as mono would keep its old right
 * ear in the cloud, and the next device to pull it would hear a curve in one
 * ear that the user deleted. Writing an explicit JSON null is the difference.
 *
 * [EqPresetEntity.id] is already a stable string, minted once from a timestamp
 * when the preset is saved, so it is the cross-device identity as-is.
 */
internal fun eqPushPayload(preset: EqPresetEntity, userId: String): JsonObject = buildJsonObject {
    put("user_id", userId)
    put("local_id", preset.id)
    put("name", preset.name)
    put("description", preset.description)
    put("bands", preset.bandsJson)
    put("bands_r", preset.bandsRJson?.let { JsonPrimitive(it) } ?: JsonNull)
    put("preamp", preset.preamp)
    put("target_id", preset.targetId)
    put("target_name", preset.targetName)
    put("is_custom", preset.isCustom)
    put("eq_type", preset.eqType)
    put("created_at_ms", preset.createdAt)
    put("updated_at_ms", preset.updatedAt)
}

/**
 * The upsert payload for a mixer preset.
 *
 * The identity is [MixPresetEntity.createdAt] and emphatically not the row id.
 * The id is Room's autoGenerate Long: it starts at 1 on every device, so under
 * the table's UNIQUE(user_id, local_id) one device's "preset 1" and another's
 * are the same cloud row, and whichever syncs second silently overwrites a
 * preset it has never seen. createdAt is epoch milliseconds fixed at the moment
 * of saving and never rewritten, because the mixer only ever creates presets:
 * `MixerViewModel.savePreset` builds a fresh `MixPreset` every time and there is
 * no edit or rename path. **Anything that later lets a preset be edited in
 * place, duplicated, or imported with its original timestamp has to give the
 * mixer a real sync id first**, because this key silently stops identifying one
 * preset at that moment.
 */
internal fun mixPushPayload(preset: MixPresetEntity, userId: String): JsonObject = buildJsonObject {
    put("user_id", userId)
    put("local_id", preset.createdAt.toString())
    put("name", preset.name)
    put("state_json", preset.stateJson)
    put("is_custom", preset.isCustom)
    put("created_at_ms", preset.createdAt)
    put("updated_at_ms", preset.updatedAt)
}

/**
 * The local row for an EQ preset that arrived from the cloud.
 *
 * Falls back to the current clock when the millisecond columns are 0, which is
 * what a row written before they existed looks like. That makes such a row
 * appear freshly edited exactly once, which is the safe direction: it adopts
 * the cloud copy rather than discarding it.
 */
internal fun SbEqPreset.toEntity(): EqPresetEntity {
    val now = System.currentTimeMillis()
    return EqPresetEntity(
        id = local_id,
        name = name,
        description = description,
        bandsJson = bands,
        bandsRJson = bands_r,
        preamp = preamp,
        targetId = target_id,
        targetName = target_name,
        isCustom = is_custom,
        createdAt = created_at_ms.takeIf { it > 0L } ?: now,
        updatedAt = updated_at_ms.takeIf { it > 0L } ?: now,
        eqType = eq_type,
    )
}

/**
 * The local row for a mixer preset that arrived from the cloud.
 *
 * [existingId] is the row being replaced when the device already has this
 * preset; 0 lets Room assign one. The cloud's `local_id` is a creation time and
 * never a row id: the code this replaces assigned it straight to the primary
 * key, and since Room treats 0 on an autoGenerate key as "give me a new row",
 * every incoming preset either collapsed onto row 0 or, once local_id held a
 * real value, seeded the table's row ids at a number in the trillions.
 */
internal fun SbMixPreset.toEntity(existingId: Long = 0L): MixPresetEntity {
    val now = System.currentTimeMillis()
    return MixPresetEntity(
        id = existingId,
        name = name,
        stateJson = state_json,
        isCustom = is_custom,
        createdAt = local_id.toLongOrNull() ?: created_at_ms.takeIf { it > 0L } ?: now,
        updatedAt = updated_at_ms.takeIf { it > 0L } ?: now,
    )
}
