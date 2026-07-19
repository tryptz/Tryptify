package tf.monochrome.android.widget

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/**
 * The broadcast receiver the launcher talks to. Registered in the manifest with
 * @xml/now_playing_widget_info; all the UI + playback wiring lives in
 * [NowPlayingWidget].
 */
class NowPlayingWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NowPlayingWidget()
}
