package com.ethic.vpn.vpn

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.widget.RemoteViews
import com.ethic.vpn.R
import com.ethic.vpn.data.ProfileRepository
import com.ethic.vpn.ui.MainActivity

/**
 * Home screen widgets: a wide one (shield, status, server and a switch) and a round 1×1 button.
 * A tap turns the VPN on or off; the first time, when Android still needs VPN consent, it opens
 * the app instead.
 */
object VpnWidget {
    private const val ACTION_TOGGLE = "com.ethic.vpn.WIDGET_TOGGLE"

    fun updateAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        for ((provider, layout) in listOf(Wide::class.java to R.layout.widget_wide, Small::class.java to R.layout.widget_small)) {
            val ids = manager.getAppWidgetIds(ComponentName(context, provider))
            if (ids.isNotEmpty()) manager.updateAppWidget(ids, views(context, layout))
        }
    }

    private fun views(context: Context, layout: Int): RemoteViews {
        val state = VpnStatus.state.value
        val on = state is VpnState.Connected
        val connecting = state is VpnState.Connecting
        val server = ProfileRepository.get(context).state.value.selected?.name.orEmpty()
        return RemoteViews(context.packageName, layout).apply {
            setImageViewResource(R.id.widget_shield, if (on) R.drawable.widget_shield_on else R.drawable.widget_shield_off)
            setOnClickPendingIntent(R.id.widget_root, toggleIntent(context))
            if (layout == R.layout.widget_wide) {
                setTextViewText(
                    R.id.widget_status,
                    context.getString(
                        when {
                            on -> R.string.status_connected
                            connecting -> R.string.status_connecting
                            else -> R.string.status_disconnected
                        },
                    ),
                )
                setTextViewText(R.id.widget_server, server)
                setImageViewResource(R.id.widget_switch, if (on || connecting) R.drawable.widget_switch_on else R.drawable.widget_switch_off)
            }
        }
    }

    private fun toggleIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 0, Intent(context, Wide::class.java).setAction(ACTION_TOGGLE), PendingIntent.FLAG_IMMUTABLE,
    )

    private fun toggle(context: Context) {
        when (VpnStatus.state.value) {
            is VpnState.Connected, is VpnState.Connecting -> EthicVpnService.disconnect(context)
            else -> if (VpnService.prepare(context) == null) {
                EthicVpnService.connect(context)
            } else {
                context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }

    open class Base : AppWidgetProvider() {
        override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
            VpnStatus.appContext = context.applicationContext
            updateAll(context)
        }

        override fun onReceive(context: Context, intent: Intent) {
            super.onReceive(context, intent)
            if (intent.action == ACTION_TOGGLE) {
                VpnStatus.appContext = context.applicationContext
                toggle(context)
            }
        }
    }

    class Wide : Base()
    class Small : Base()
}
