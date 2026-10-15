package tf.monochrome.android.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import tf.monochrome.android.data.auth.AccountAvatar
import tf.monochrome.android.data.auth.ProfileAvatarStore
import tf.monochrome.android.data.auth.SupabaseAuthManager
import javax.inject.Inject

/** The signed-in account's picture for the top bar's account button; null when signed out. */
@HiltViewModel
class AccountBadgeViewModel @Inject constructor(
    authManager: SupabaseAuthManager,
    avatarStore: ProfileAvatarStore,
) : ViewModel() {
    val account: StateFlow<AccountAvatar?> = avatarStore.avatars(authManager.userProfile)
        // Seeded from the current account so a signed-in bar does not open on
        // the "Sign in" pill for a frame and then swap; the initials, which
        // need no disk read, until the picture is read.
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), avatarStore.placeholder(authManager.userProfile.value))
}
