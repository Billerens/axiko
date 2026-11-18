package app.axiko.sense

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.provider.Telephony
import android.telephony.TelephonyManager
import app.axiko.core.Engine
import org.json.JSONObject

/**
 * Things that happen to the phone, passed on to scripts as events:
 *   on('power', e => e.charging)            the charger was plugged in or out
 *   on('screen', e => e.state)              'on', 'off', 'unlocked'
 *   on('network', e => e.online, e.wifi)    the connection came, went or changed
 *   on('sms', e => e.from, e.text)          a text message arrived
 *   on('call', e => e.state, e.number)      'ringing', 'offhook', 'idle'
 *   on('headset', e => e.plugged)           wired headphones
 * Nothing is delivered until some script subscribes (see Engine.fire).
 */
object Triggers {
    fun start(context: Context) {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_HEADSET_PLUG)
            addAction(Telephony.Sms.Intents.SMS_RECEIVED_ACTION)
            addAction(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
        }
        context.registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) = handle(intent)
            },
            filter,
            Context.RECEIVER_EXPORTED,
        )

        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        runCatching {
            connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    val wifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    if (wifi != lastWifi || !lastOnline) {
                        lastWifi = wifi
                        lastOnline = true
                        Engine.fire("network", JSONObject().put("online", true).put("wifi", wifi))
                    }
                }

                override fun onLost(network: Network) {
                    lastOnline = false
                    Engine.fire("network", JSONObject().put("online", false).put("wifi", false))
                }
            })
        }
    }

    private var lastOnline = false
    private var lastWifi: Boolean? = null

    private fun handle(intent: Intent) {
        when (intent.action) {
            Intent.ACTION_POWER_CONNECTED -> Engine.fire("power", JSONObject().put("charging", true))
            Intent.ACTION_POWER_DISCONNECTED -> Engine.fire("power", JSONObject().put("charging", false))
            Intent.ACTION_SCREEN_ON -> Engine.fire("screen", JSONObject().put("state", "on"))
            Intent.ACTION_SCREEN_OFF -> Engine.fire("screen", JSONObject().put("state", "off"))
            Intent.ACTION_USER_PRESENT -> Engine.fire("screen", JSONObject().put("state", "unlocked"))
            Intent.ACTION_HEADSET_PLUG -> Engine.fire("headset", JSONObject().put("plugged", intent.getIntExtra("state", 0) == 1))
            Telephony.Sms.Intents.SMS_RECEIVED_ACTION -> {
                val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent).orEmpty()
                if (parts.isNotEmpty()) {
                    Engine.fire(
                        "sms",
                        JSONObject().put("from", parts[0].originatingAddress.orEmpty())
                            .put("text", parts.joinToString("") { it.messageBody.orEmpty() }),
                    )
                }
            }
            TelephonyManager.ACTION_PHONE_STATE_CHANGED -> Engine.fire(
                "call",
                JSONObject().put("state", intent.getStringExtra(TelephonyManager.EXTRA_STATE).orEmpty().lowercase())
                    .put("number", intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER).orEmpty()),
            )
        }
    }
}
