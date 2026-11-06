package org.offlinechat

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Text-only offline mesh chat.
 * - Finds nearby phones (Bluetooth / Wi-Fi) via Nearby Connections, no internet.
 * - Flooding: every new message is forwarded to all neighbours (max MAX_HOPS).
 * - Store-and-forward: saved messages are re-sent to every phone that connects.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        const val SERVICE_ID = "org.offlinechat.mesh"
        const val MAX_HOPS = 10
        const val MAX_CHARS = 500
        const val MAX_STORED = 300
    }

    private val strategy = Strategy.P2P_CLUSTER
    private lateinit var client: ConnectionsClient
    private val neighbours = mutableSetOf<String>()
    private val seen = mutableSetOf<String>()
    private val stored = mutableListOf<JSONObject>()
    private lateinit var myName: String
    private lateinit var chatView: TextView
    private lateinit var statusView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var storeFile: File

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        client = Nearby.getConnectionsClient(this)
        storeFile = File(filesDir, "messages.jsonl")

        val prefs = getSharedPreferences("p", MODE_PRIVATE)
        val nick = prefs.getString("nick", null) ?: "User"
        // random suffix makes the endpoint name unique
        myName = nick + "#" + UUID.randomUUID().toString().take(4)

        buildUi()
        loadStored()
        requestPermissionsThenStart()
    }

    // ---------- UI (built in code: no layout files needed) ----------
    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        statusView = TextView(this).apply { textSize = 14f; text = "Searching for nearby phones…" }
        chatView = TextView(this).apply { textSize = 20f }
        scroll = ScrollView(this).apply { addView(chatView) }
        val input = EditText(this).apply { hint = "Type a message"; textSize = 20f }
        val send = Button(this).apply { text = "Send"; textSize = 20f }
        val wipe = Button(this).apply { text = "Delete all messages" }
        val warn = TextView(this).apply {
            text = "Warning: anyone nearby can read these public messages."
            textSize = 12f; gravity = Gravity.CENTER
        }
        root.addView(statusView)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(input); root.addView(send); root.addView(wipe); root.addView(warn)
        setContentView(root)

        send.setOnClickListener {
            val t = input.text.toString().trim().take(MAX_CHARS)
            if (t.isNotEmpty()) {
                val m = JSONObject().put("i", UUID.randomUUID().toString())
                    .put("h", 0).put("n", myName.substringBefore('#'))
                    .put("t", t).put("ts", System.currentTimeMillis())
                handleNew(m, null)
                input.setText("")
            }
        }
        wipe.setOnClickListener {
            stored.clear(); storeFile.delete(); chatView.text = ""
        }
    }

    private fun show(m: JSONObject) {
        chatView.append("${m.getString("n")}: ${m.getString("t")}\n\n")
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    // ---------- Message handling ----------
    /** New or received message: save, show, forward to neighbours. */
    private fun handleNew(m: JSONObject, from: String?) {
        val id = m.getString("i")
        if (!seen.add(id)) return                       // already have it
        stored.add(m)
        if (stored.size > MAX_STORED) stored.removeAt(0)
        saveStored()
        show(m)
        val hop = m.getInt("h")
        if (hop < MAX_HOPS) {
            val fwd = JSONObject(m.toString()).put("h", hop + 1)
            neighbours.filter { it != from }.forEach { send(it, fwd) }
        }
    }

    private fun send(endpoint: String, m: JSONObject) {
        client.sendPayload(endpoint, Payload.fromBytes(m.toString().toByteArray()))
    }

    private fun loadStored() {
        if (!storeFile.exists()) return
        storeFile.readLines().forEach {
            try { val m = JSONObject(it); stored.add(m); seen.add(m.getString("i")); show(m) } catch (_: Exception) {}
        }
    }
    private fun saveStored() = storeFile.writeText(stored.joinToString("\n") { it.toString() })

    // ---------- Nearby Connections ----------
    private val payloadCb = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, p: Payload) {
            val bytes = p.asBytes() ?: return
            try {
                val m = JSONObject(String(bytes))
                if (m.getString("t").length <= MAX_CHARS) runOnUiThread { handleNew(m, endpointId) }
            } catch (_: Exception) {}
        }
        override fun onPayloadTransferUpdate(id: String, u: PayloadTransferUpdate) {}
    }

    private val connCb = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(id: String, info: ConnectionInfo) {
            client.acceptConnection(id, payloadCb)
        }
        override fun onConnectionResult(id: String, r: ConnectionResolution) {
            if (r.status.isSuccess) {
                neighbours.add(id); updateStatus()
                // store-and-forward: give the new phone everything we have
                stored.toList().forEach { send(id, it) }
            }
        }
        override fun onDisconnected(id: String) { neighbours.remove(id); updateStatus() }
    }

    private val discCb = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(id: String, info: DiscoveredEndpointInfo) {
            // only the phone with the smaller name starts the connection (avoids clashes)
            if (myName < info.endpointName && id !in neighbours) {
                client.requestConnection(myName, id, connCb)
            }
        }
        override fun onEndpointLost(id: String) {}
    }

    private fun updateStatus() = runOnUiThread {
        statusView.text = if (neighbours.isEmpty()) "Searching for nearby phones…"
        else "Connected to ${neighbours.size} nearby phone(s)"
    }

    private fun startMesh() {
        client.startAdvertising(myName, SERVICE_ID, connCb,
            AdvertisingOptions.Builder().setStrategy(strategy).build())
        client.startDiscovery(SERVICE_ID, discCb,
            DiscoveryOptions.Builder().setStrategy(strategy).build())
    }

    // ---------- Permissions ----------
    private fun requestPermissionsThenStart() {
        val p = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) p += listOf(
            Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        if (Build.VERSION.SDK_INT >= 33) p += Manifest.permission.NEARBY_WIFI_DEVICES
        ActivityCompat.requestPermissions(this, p.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        startMesh()
    }

    override fun onDestroy() { client.stopAllEndpoints(); client.stopAdvertising(); client.stopDiscovery(); super.onDestroy() }
}
