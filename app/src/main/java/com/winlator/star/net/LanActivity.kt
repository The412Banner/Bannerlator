package com.winlator.star.net

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import kotlin.concurrent.thread

/**
 * LAN-over-internet host/join UI (P1.5). Host creates a room (6-char code) and starts the overlay as
 * host; a friend enters the code to join as client. Starting the overlay requires the one-time system
 * VPN consent, handled here via [VpnService.prepare]. After the overlay is up the user launches the
 * game normally and uses its own LAN/Multiplayer menu.
 *
 * Launchable for testing: am start -n com.winlator.banner/com.winlator.star.net.LanActivity
 */
class LanActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var codeView: TextView
    private lateinit var codeInput: EditText
    private var pending: LanRoom? = null

    private val vpnConsent =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val room = pending
            if (res.resultCode == RESULT_OK && room != null) startOverlay(room)
            else setStatus("VPN permission denied — can't start the LAN overlay.")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = dp(20)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
        }

        root.addView(title("Play a LAN game over the internet"))
        root.addView(hint("One of you hosts and shares the code; the other joins with it. Then launch the same game and use its LAN menu."))

        // Host
        root.addView(sectionLabel("Host"))
        root.addView(Button(this).apply {
            text = "Host LAN game"
            setOnClickListener { doHost() }
        })
        codeView = TextView(this).apply {
            textSize = 30f
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(8))
            text = ""
        }
        root.addView(codeView)

        // Join
        root.addView(sectionLabel("Join"))
        codeInput = EditText(this).apply {
            hint = "Enter 6-char code"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            filters = arrayOf(android.text.InputFilter.AllCaps(), android.text.InputFilter.LengthFilter(8))
        }
        root.addView(codeInput)
        root.addView(Button(this).apply {
            text = "Join LAN game"
            setOnClickListener { doJoin() }
        })

        // Stop
        root.addView(Button(this).apply {
            text = "Stop LAN overlay"
            setOnClickListener { stopOverlay() }
        })

        status = TextView(this).apply { setPadding(0, dp(16), 0, 0) }
        root.addView(status)

        setContentView(root)
    }

    private fun doHost() {
        setStatus("Creating room…")
        codeView.text = ""
        thread {
            val room = LanRoomWorker.host()
            runOnUiThread {
                if (room == null || room.relay.isBlank()) {
                    setStatus("Couldn't create a room (server unreachable?).")
                } else {
                    codeView.text = room.code
                    setStatus("Share code ${room.code}. Getting the overlay ready…")
                    beginOverlay(room)
                }
            }
        }
    }

    private fun doJoin() {
        val code = codeInput.text.toString().trim()
        if (code.length != 6) { toast("Enter the 6-character code"); return }
        setStatus("Joining $code…")
        thread {
            val room = LanRoomWorker.join(code)
            runOnUiThread {
                if (room == null || room.relay.isBlank()) {
                    setStatus("Room $code not found, full, or expired.")
                } else {
                    setStatus("Joined $code. Getting the overlay ready…")
                    beginOverlay(room)
                }
            }
        }
    }

    /** Ask for VPN consent if needed, then start the overlay. */
    private fun beginOverlay(room: LanRoom) {
        pending = room
        val prep = VpnService.prepare(this)
        if (prep != null) vpnConsent.launch(prep) else startOverlay(room)
    }

    private fun startOverlay(room: LanRoom) {
        val i = Intent(this, LanOverlayVpnService::class.java).apply {
            action = "START"
            putExtra(LanOverlayVpnService.EXTRA_RELAY, room.relay)
            putExtra(LanOverlayVpnService.EXTRA_PORT, room.port)
            putExtra(LanOverlayVpnService.EXTRA_ROOM, room.room)
            putExtra(LanOverlayVpnService.EXTRA_ROLE, room.role)
        }
        startService(i)
        val who = if (room.role == LanOverlay.ROLE_HOST) "host (10.99.0.1)" else "client (10.99.0.2)"
        setStatus("✅ Overlay up — you are $who via ${room.relay}. Launch your game and open its LAN/Multiplayer menu.")
    }

    private fun stopOverlay() {
        startService(Intent(this, LanOverlayVpnService::class.java).setAction("STOP"))
        setStatus("Overlay stopped.")
    }

    // ── tiny view helpers (no XML / no Compose theme dependency) ──
    private fun setStatus(s: String) { status.text = s }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun title(s: String) = TextView(this).apply { text = s; textSize = 20f; setPadding(0, 0, 0, dp(6)) }
    private fun hint(s: String) = TextView(this).apply { text = s; textSize = 13f; alpha = 0.7f; setPadding(0, 0, 0, dp(12)) }
    private fun sectionLabel(s: String) = TextView(this).apply { text = s; textSize = 16f; setPadding(0, dp(14), 0, dp(4)) }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    }
}
