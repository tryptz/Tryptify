package tf.monochrome.android.data.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The account picture's choice, the initials drawn without one, and what a
 * username is saved as.
 */
class ProfileAvatarTest {

    private val google = "https://lh3.googleusercontent.com/a/abc=s96-c"

    @Test
    fun `the provider photo is the default, at the size the page draws it`() {
        assertEquals(
            AvatarSource.Remote("https://lh3.googleusercontent.com/a/abc=s$AVATAR_PX-c"),
            resolveAvatar(AvatarChoice.PROVIDER, customPath = null, providerUrl = google),
        )
    }

    @Test
    fun `a picked picture wins over the provider's`() {
        assertEquals(
            AvatarSource.Custom("/files/avatars/u-1.jpg"),
            resolveAvatar(AvatarChoice.CUSTOM, "/files/avatars/u-1.jpg", google),
        )
    }

    @Test
    fun `a picked picture whose file is gone falls back to the provider's`() {
        assertEquals(
            AvatarSource.Remote(sharperProviderPhoto(google)),
            resolveAvatar(AvatarChoice.CUSTOM, customPath = null, providerUrl = google),
        )
    }

    @Test
    fun `removed means no picture, even with a provider photo`() {
        assertNull(resolveAvatar(AvatarChoice.NONE, "/files/avatars/u-1.jpg", google))
    }

    @Test
    fun `no provider photo and nothing picked is no picture`() {
        assertNull(resolveAvatar(AvatarChoice.PROVIDER, null, null))
        assertNull(resolveAvatar(AvatarChoice.PROVIDER, null, " "))
    }

    @Test
    fun `only Google's sized URLs are rewritten`() {
        val other = "https://example.com/me=s96-c"
        assertEquals(other, sharperProviderPhoto(other))
        val unsized = "https://lh3.googleusercontent.com/a/abc"
        assertEquals(unsized, sharperProviderPhoto(unsized))
    }

    @Test
    fun `initials come from the first two words of the name`() {
        assertEquals("TT", avatarInitials("Tryp Tz", null))
        assertEquals("T", avatarInitials("TrypT", "x@y.z"))
        assertEquals("AB", avatarInitials("  ada   byron lovelace ", null))
    }

    @Test
    fun `without a name, initials come from the email's local part`() {
        assertEquals("JD", avatarInitials(null, "john.doe@example.com"))
        assertEquals("J", avatarInitials("   ", "jo@example.com"))
        assertEquals("", avatarInitials(null, null))
    }

    @Test
    fun `initials never split a character in half`() {
        assertEquals("🎵", avatarInitials("🎵music", null))
    }

    @Test
    fun `a username is trimmed and its spaces collapsed`() {
        assertEquals("Tryp T", cleanUsername("  Tryp \n  T "))
    }

    @Test
    fun `a blank, overlong or control-character username is refused`() {
        assertNull(cleanUsername("   "))
        assertNull(cleanUsername("a".repeat(USERNAME_MAX + 1)))
        assertNull(cleanUsername("bad\u0000name"))
        assertEquals("a".repeat(USERNAME_MAX), cleanUsername("a".repeat(USERNAME_MAX)))
    }

    @Test
    fun `the length limit counts code points, not chars`() {
        val emoji = "🎵"
        assertEquals(emoji.repeat(USERNAME_MAX), cleanUsername(emoji.repeat(USERNAME_MAX)))
    }
}
