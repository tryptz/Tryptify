package tf.monochrome.android.data.auth

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import kotlin.math.roundToInt

/** Which picture an account shows. */
enum class AvatarChoice {
    /** The sign-in provider's photo (Google's), the default. */
    PROVIDER,

    /** A picture the listener picked on this device. */
    CUSTOM,

    /** No picture: the initials. */
    NONE,
}

/** Where an account's picture comes from, once [AvatarChoice] has been settled. */
sealed interface AvatarSource {
    data class Custom(val path: String) : AvatarSource
    data class Remote(val url: String) : AvatarSource
}

/** What the account button and the profile page draw. */
data class AccountAvatar(val source: AvatarSource?, val initials: String)

/** Side of the stored picture, in pixels: sharp on the profile page's 128dp disc at xxxhdpi. */
const val AVATAR_PX = 512

/**
 * The picture [choice] asks for, falling back to the provider's photo when a
 * custom one is asked for but its file is gone (cleared storage, a restore
 * without it): a stale choice should show the account's own photo, not nothing.
 */
fun resolveAvatar(choice: AvatarChoice, customPath: String?, providerUrl: String?): AvatarSource? {
    val remote = providerUrl?.takeIf { it.isNotBlank() }?.let { AvatarSource.Remote(sharperProviderPhoto(it)) }
    return when (choice) {
        AvatarChoice.CUSTOM -> customPath?.let { AvatarSource.Custom(it) } ?: remote
        AvatarChoice.PROVIDER -> remote
        AvatarChoice.NONE -> null
    }
}

/**
 * Google hands Supabase a 96px avatar (`…=s96-c`), a blur at the profile
 * page's size. The size is part of the URL, so ask for [AVATAR_PX] instead.
 */
fun sharperProviderPhoto(url: String): String =
    if ("googleusercontent.com" in url) url.replace(Regex("=s\\d+-c$"), "=s$AVATAR_PX-c") else url

/**
 * One or two letters for an account with no picture: the first letters of the
 * first two words of the username, else of the email's local part. Counted in code
 * points, so a name that starts with an emoji or a supplementary character is
 * not cut in half.
 */
fun avatarInitials(name: String?, email: String?): String {
    val base = name?.trim()?.takeIf { it.isNotEmpty() }
        ?: email?.substringBefore('@')?.trim()?.takeIf { it.isNotEmpty() }
        ?: return ""
    val words = base.split(Regex("[\\s._\\-]+")).filter { it.isNotEmpty() }
    return words.take(2).joinToString("") { word ->
        String(Character.toChars(word.codePointAt(0))).uppercase()
    }
}

/** Longest username, in code points. */
const val USERNAME_MAX = 40

/**
 * A username as it will be saved, or null if it cannot be: runs of whitespace
 * become one space, the ends are trimmed, and it must be 1..[USERNAME_MAX]
 * code points with no control characters.
 */
fun cleanUsername(raw: String): String? {
    val name = raw.trim().replace(Regex("\\s+"), " ")
    if (name.isEmpty()) return null
    if (name.codePointCount(0, name.length) > USERNAME_MAX) return null
    if (name.any { Character.isISOControl(it) }) return null
    return name
}

/**
 * The account's picture, kept on this device.
 *
 * A picked photo is cropped to a square, scaled to [AVATAR_PX] and stored in
 * the app's own files, so it survives the picker's grant being dropped and
 * loads without a network. It is per account and per device: Supabase user
 * metadata rides in every access token, so a picture there would be sent with
 * every request, and the project has no storage bucket for it.
 *
 * Each save gets a new file name, so an image loader that cached the last
 * picture by its path does not keep showing it.
 */
@Singleton
class ProfileAvatarStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val prefs = context.getSharedPreferences("profile_avatar", Context.MODE_PRIVATE)
    private val dir = File(context.filesDir, "avatars")

    private val _revision = MutableStateFlow(0)

    /** Bumped on every change, so a flow combined with it reads the store again. */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    fun choice(userId: String): AvatarChoice =
        prefs.getString(choiceKey(userId), null)
            ?.let { runCatching { AvatarChoice.valueOf(it) }.getOrNull() }
            ?: AvatarChoice.PROVIDER

    /**
     * The signed-in account's avatar as the account and its stored picture
     * change: one definition for every screen that shows it (the top bar's
     * button, the profile page). Read on IO, since [avatarOf] reads prefs and
     * checks a file.
     */
    fun avatars(profiles: Flow<UserProfile?>): Flow<AccountAvatar?> =
        combine(profiles, revision) { profile, _ -> profile?.let(::avatarOf) }
            .flowOn(Dispatchers.IO)

    /**
     * What a screen shows before [avatars] has read the store: a signed-in
     * account's initials, so it doesn't open on the "Sign in" pill. No disk
     * read, so a view model can start on it on the main thread; it used to
     * start on [avatarOf] there, the first prefs load and a file check.
     */
    fun placeholder(profile: UserProfile?): AccountAvatar? =
        profile?.let { AccountAvatar(source = null, initials = avatarInitials(it.username, it.email)) }

    fun avatarOf(profile: UserProfile): AccountAvatar {
        val custom = prefs.getString(fileKey(profile.id), null)
            ?.let { File(dir, it) }
            ?.takeIf { it.isFile }
            ?.path
        return AccountAvatar(
            source = resolveAvatar(choice(profile.id), custom, profile.photoUrl),
            // The username, never Google's name: the account is what it is called here.
            initials = avatarInitials(profile.username, profile.email),
        )
    }

    /** Store [uri] as [userId]'s picture. False when it could not be read as an image. */
    suspend fun setCustom(userId: String, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val bitmap = decodeSquare(uri) ?: return@withContext false
        val name = "${fileStem(userId)}-${System.currentTimeMillis()}.jpg"
        val written = runCatching {
            dir.mkdirs()
            val tmp = File(dir, "$name.tmp")
            tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            check(tmp.renameTo(File(dir, name)))
        }.isSuccess
        bitmap.recycle()
        if (!written) return@withContext false
        val old = prefs.getString(fileKey(userId), null)
        prefs.edit()
            .putString(fileKey(userId), name)
            .putString(choiceKey(userId), AvatarChoice.CUSTOM.name)
            .apply()
        old?.takeIf { it != name }?.let { File(dir, it).delete() }
        _revision.update { it + 1 }
        true
    }

    /** Back to the sign-in provider's photo; the picked one is deleted. */
    fun useProvider(userId: String) = set(userId, AvatarChoice.PROVIDER)

    /** No picture at all: the initials. The picked one is deleted. */
    fun remove(userId: String) = set(userId, AvatarChoice.NONE)

    private fun set(userId: String, choice: AvatarChoice) {
        prefs.getString(fileKey(userId), null)?.let { File(dir, it).delete() }
        prefs.edit()
            .remove(fileKey(userId))
            .putString(choiceKey(userId), choice.name)
            .apply()
        _revision.update { it + 1 }
    }

    /**
     * The picture at [uri], centre-cropped to a square of at most [AVATAR_PX].
     * Decoded at a reduced size first: a 50 MP photo decoded whole is 200 MB.
     */
    private fun decodeSquare(uri: Uri): Bitmap? = runCatching {
        val decoded: Bitmap? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // ImageDecoder also applies the photo's EXIF rotation.
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                val shortSide = min(info.size.width, info.size.height)
                if (shortSide > AVATAR_PX) {
                    val f = shortSide / AVATAR_PX.toFloat()
                    decoder.setTargetSize(
                        (info.size.width / f).roundToInt().coerceAtLeast(AVATAR_PX),
                        (info.size.height / f).roundToInt().coerceAtLeast(AVATAR_PX),
                    )
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (min(bounds.outWidth, bounds.outHeight) / (sample * 2) >= AVATAR_PX) sample *= 2
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            }
        }
        if (decoded == null) return@runCatching null

        val side = min(decoded.width, decoded.height)
        val cropped = Bitmap.createBitmap(decoded, (decoded.width - side) / 2, (decoded.height - side) / 2, side, side)
        if (cropped !== decoded) decoded.recycle()
        if (side <= AVATAR_PX) return@runCatching cropped
        val scaled = Bitmap.createScaledBitmap(cropped, AVATAR_PX, AVATAR_PX, true)
        if (scaled !== cropped) cropped.recycle()
        scaled
    }.getOrNull()

    private fun fileStem(userId: String) = userId.replace(Regex("[^A-Za-z0-9-]"), "_")
    private fun choiceKey(userId: String) = "choice_$userId"
    private fun fileKey(userId: String) = "file_$userId"
}
