package tf.monochrome.android.data.sync

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.data.db.entity.EqPresetEntity
import tf.monochrome.android.data.db.entity.MixPresetEntity

/**
 * What travels when a preset syncs, and which copy wins when two devices
 * disagree.
 *
 * Every case here is a way the presets could cross the wire looking fine and
 * arrive wrong: a right ear that stays behind, a parametric preset that lands
 * on the AutoEQ screen, two devices' presets collapsing onto one cloud row, a
 * server clock deciding whose tuning survives. None of them would fail a build
 * or throw at runtime.
 */
class PresetSyncMappingTest {

    private fun eq(
        id: String = "custom_1",
        bandsR: String? = null,
        eqType: Int = 0,
        updatedAt: Long = 2_000L,
    ) = EqPresetEntity(
        id = id,
        name = "Test",
        description = "",
        bandsJson = """[{"frequency":100.0,"gain":3.0}]""",
        bandsRJson = bandsR,
        preamp = -3.5f,
        targetId = "harman",
        targetName = "Harman",
        isCustom = true,
        createdAt = 1_000L,
        updatedAt = updatedAt,
        eqType = eqType,
    )

    private fun mix(id: Long, createdAt: Long, updatedAt: Long = createdAt) = MixPresetEntity(
        id = id,
        name = "Bus mix",
        stateJson = """{"buses":[]}""",
        isCustom = true,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    // ── identity ────────────────────────────────────────────────────────

    @Test
    fun `two devices' first mixer preset do not collide in the cloud`() {
        // Both are row 1, because the row id is autoGenerate and starts at 1 on
        // every device. Keyed on the row id they would be one cloud row under
        // UNIQUE(user_id, local_id), and whichever synced second would overwrite
        // a preset it had never seen.
        val phone = mixPushPayload(mix(id = 1L, createdAt = 1_700_000_000_000L), "u")
        val tablet = mixPushPayload(mix(id = 1L, createdAt = 1_700_000_999_999L), "u")

        assertEquals("1700000000000", phone["local_id"]!!.jsonPrimitive.content)
        assertEquals("1700000999999", tablet["local_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an eq preset syncs under its own stable id`() {
        val payload = eqPushPayload(eq(id = "custom_paramEq_1700000000000"), "u")
        assertEquals("custom_paramEq_1700000000000", payload["local_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a pulled mixer preset never adopts the cloud key as its row id`() {
        // local_id is a creation time. Assigned to the primary key it would
        // seed the table's row ids somewhere past a trillion.
        val row = SbMixPreset(
            local_id = "1700000000000",
            name = "Bus mix",
            state_json = "{}",
            updated_at_ms = 5L,
        )
        assertEquals(0L, row.toEntity().id)
        assertEquals(1_700_000_000_000L, row.toEntity().createdAt)
        assertEquals(42L, row.toEntity(existingId = 42L).id)
    }

    // ── the fields that would quietly go missing ────────────────────────

    @Test
    fun `a preset saved back to mono writes an explicit null right ear`() {
        // The client drops Kotlin nulls from the payload, and a field absent
        // from an ON CONFLICT DO UPDATE keeps its previous value. Without an
        // explicit null the cloud would keep the right channel the user just
        // deleted, and the next device to pull would hear it.
        val payload = eqPushPayload(eq(bandsR = null), "u")
        assertTrue("bands_r must be present in the payload", payload.containsKey("bands_r"))
        assertEquals(JsonNull, payload["bands_r"])
    }

    @Test
    fun `a per-ear preset carries both ears`() {
        val right = """[{"frequency":100.0,"gain":1.0}]"""
        val payload = eqPushPayload(eq(bandsR = right), "u")
        assertEquals(right, payload["bands_r"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a parametric preset stays parametric across the wire`() {
        // One Room table serves both EQ screens and the DAO filters strictly on
        // eqType, so a preset that loses this value does not merely look odd:
        // it vanishes from one screen and appears in the other.
        assertEquals(1, eqPushPayload(eq(eqType = 1), "u")["eq_type"]!!.jsonPrimitive.content.toInt())
        assertEquals(0, eqPushPayload(eq(eqType = 0), "u")["eq_type"]!!.jsonPrimitive.content.toInt())

        val pulled = SbEqPreset(local_id = "x", name = "n", eq_type = 1, updated_at_ms = 1L).toEntity()
        assertEquals(1, pulled.eqType)
    }

    @Test
    fun `edit times cross as exact milliseconds`() {
        val payload = eqPushPayload(eq(updatedAt = 1_700_000_123_456L), "u")
        assertEquals("1700000123456", payload["updated_at_ms"]!!.jsonPrimitive.content)
        assertEquals("1000", payload["created_at_ms"]!!.jsonPrimitive.content)
    }

    // ── whose copy wins ─────────────────────────────────────────────────

    @Test
    fun `a preset the device has never seen is adopted`() {
        assertTrue(cloudCopyIsNewer(localUpdatedAtMs = null, cloudUpdatedAtMs = 1L))
    }

    @Test
    fun `a newer edit elsewhere wins`() {
        assertTrue(cloudCopyIsNewer(localUpdatedAtMs = 1_000L, cloudUpdatedAtMs = 2_000L))
    }

    @Test
    fun `an older cloud copy never overwrites a local edit`() {
        assertFalse(cloudCopyIsNewer(localUpdatedAtMs = 2_000L, cloudUpdatedAtMs = 1_000L))
    }

    @Test
    fun `the same edit is not rewritten`() {
        // Ties go to local. Equal timestamps are the same edit, and of the two
        // options writing is the one that can lose something. This also keeps
        // the launch pull a no-op in the overwhelmingly common case, instead of
        // rewriting every preset the account owns on every launch.
        assertFalse(cloudCopyIsNewer(localUpdatedAtMs = 2_000L, cloudUpdatedAtMs = 2_000L))
    }

    // ── rows written before the millisecond columns existed ─────────────

    @Test
    fun `a row with no edit time reads as freshly edited rather than ancient`() {
        // 0 means the column did not exist when the row was written. Treating
        // it as "very old" would make such a preset permanently unable to
        // arrive; the fallback adopts it once instead.
        val pulled = SbEqPreset(local_id = "x", name = "n", updated_at_ms = 0L).toEntity()
        assertTrue(pulled.updatedAt > 0L)
        assertNull(pulled.bandsRJson)
    }
}
