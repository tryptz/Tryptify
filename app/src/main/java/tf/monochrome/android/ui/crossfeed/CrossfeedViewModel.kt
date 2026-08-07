package tf.monochrome.android.ui.crossfeed

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import tf.monochrome.android.audio.dsp.crossfeed.CrossfeedEffect
import javax.inject.Inject

@HiltViewModel
class CrossfeedViewModel @Inject constructor(
    val crossfeed: CrossfeedEffect,
) : ViewModel()
