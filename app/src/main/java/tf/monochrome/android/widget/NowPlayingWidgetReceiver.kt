package tf.monochrome.android.widget

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/**
 * The broadcast receivers the launcher talks to. There are three so the
 * widget picker offers three sizes (each registered in the manifest with its
 * own @xml/now_playing_widget_*_info), but they all host the same
 * [NowPlayingWidget], which lays itself out for whatever size it is given,
 * and `updateAll` reaches every one of them.
 *
 * This one is the medium 5x2. It keeps the original class name, so widgets
 * already on a home screen stay put.
 */
class NowPlayingWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NowPlayingWidget()
}

/** The small 2x2. */
class NowPlayingSmallWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NowPlayingWidget()
}

/** The large 4x4. */
class NowPlayingLargeWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NowPlayingWidget()
}
