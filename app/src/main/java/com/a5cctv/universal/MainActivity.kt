package com.a5cctv.universal

import android.app.*
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private val io = Executors.newSingleThreadExecutor()
    private val prefs by lazy {
        getSharedPreferences("a5cctv", Context.MODE_PRIVATE)
    }

    private val devices = mutableListOf<Device>()
    private val groups = mutableListOf<String>()
    private val players = mutableListOf<ExoPlayer>()

    private var gridSize = 4

    data class Device(
        var id: String,
        var name: String,
        var host: String,
        var port: Int,
        var user: String,
        var pass: String,
        var group: String,
        var protocol: String = "ONVIF",
        var rtsp: String = ""
    )

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        load()
        home()
    }

    private fun load() {
        groups.clear()
        devices.clear()

        val ga = JSONArray(
            prefs.getString("groups", "[\"Без группы\"]")
        )

        for (i in 0 until ga.length()) {
            groups += ga.optString(i)
        }

        if (groups.isEmpty()) {
            groups += "Без группы"
        }

        val da = JSONArray(prefs.getString("devices", "[]"))

        for (i in 0 until da.length()) {
            val o = da.getJSONObject(i)

            devices += Device(
                o.optString("id"),
                o.optString("name"),
                o.optString("host"),
                o.optInt("port", 80),
                o.optString("user"),
                o.optString("pass"),
                o.optString("group", "Без группы"),
                o.optString("protocol", "ONVIF"),
                o.optString("rtsp")
            )
        }
    }

    private fun save() {
        val ga = JSONArray()
        groups.distinct().forEach { ga.put(it) }

        val da = JSONArray()

        devices.forEach { d ->
            da.put(JSONObject().apply {
                put("id", d.id)
                put("name", d.name)
                put("host", d.host)
                put("port", d.port)
                put("user", d.user)
                put("pass", d.pass)
                put("group", d.group)
                put("protocol", d.protocol)
                put("rtsp", d.rtsp)
            })
        }

        prefs.edit()
            .putString("groups", ga.toString())
            .putString("devices", da.toString())
            .apply()
    }

    private fun tv(s: String, n: Float = 18f) =
        TextView(this).apply {
            text = s
            textSize = n
            setTextColor(Color.WHITE)
            setPadding(20, 14, 20, 14)
        }

    private fun btn(s: String, on: () -> Unit) =
        Button(this).apply {
            text = s
            minHeight = 56
            setOnClickListener { on() }
        }

    private fun root(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 18, 20, 18)
            setBackgroundColor(Color.rgb(11, 13, 16))
        }

    private fun home() {
        stopPlayers()

        val r = root()

        r.addView(tv("A5 CCTV", 30f))
        r.addView(
            tv("Универсальный видеоклиент • ONVIF + RTSP", 18f)
        )

        r.addView(
            btn("📁 Группы (${groups.size})") {
                groupsDialog()
            }
        )

        r.addView(
            btn("📹 Устройства (${devices.size})") {
                devicesDialog()
            }
        )

        r.addView(
            btn("▦ Просмотр камер") {
                gridDialog()
            }
        )

        r.addView(
            btn("🔎 Найти ONVIF-устройства") {
                discoverDialog()
            }
        )

        r.addView(
            btn("＋ Добавить устройство") {
                deviceEditor(null)
            }
        )

        r.addView(
            btn("▶ Открыть RTSP вручную") {
                rtspDialog()
            }
        )

        setContentView(r)

        r.post {
            val views = mutableListOf<View>()

            r.findViewsWithText(
                views,
                "📁 Группы (${groups.size})",
                View.FIND_VIEWS_WITH_TEXT
            )

            views.firstOrNull()?.requestFocus()
        }
    }

    private fun groupsDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        groups.forEach { g ->
            val row = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
            }

            row.addView(
                tv("$g  •  ${devices.count { it.group == g }}", 18f),
                LinearLayout.LayoutParams(0, 65, 1f)
            )

            row.addView(
                btn("✎") {
                    renameGroup(g)
                }
            )

            row.addView(
                btn("×") {
                    if (groups.size > 1) {
                        devices.filter { it.group == g }
                            .forEach {
                                it.group = "Без группы"
                            }

                        groups.remove(g)
                        save()
                        groupsDialog()
                    }
                }
            )

            box.addView(row)
        }

        AlertDialog.Builder(this)
            .setTitle("Группы")
            .setView(box)
            .setPositiveButton("＋ Добавить") { _, _ ->
                newGroup()
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    private fun newGroup() {
        val e = EditText(this).apply {
            hint = "Название группы"
        }

        AlertDialog.Builder(this)
            .setTitle("Новая группа")
            .setView(e)
            .setPositiveButton("Создать") { _, _ ->
                val name = e.text.toString().trim()

                if (name.isNotBlank()) {
                    groups.add(name)
                    save()
                    groupsDialog()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun renameGroup(old: String) {
        val e = EditText(this).apply {
            setText(old)
        }

        AlertDialog.Builder(this)
            .setTitle("Переименовать группу")
            .setView(e)
            .setPositiveButton("Сохранить") { _, _ ->
                val name = e.text.toString().trim()
                val index = groups.indexOf(old)

                if (name.isNotBlank() && index >= 0) {
                    groups[index] = name

                    devices.filter { it.group == old }
                        .forEach {
                            it.group = name
                        }

                    save()
                    groupsDialog()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun devicesDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        if (devices.isEmpty()) {
            box.addView(
                tv("Устройств пока нет. Добавь камеру или NVR.")
            )
        }

        devices.forEach { d ->
            val b = Button(this).apply {
                text = "${d.name}\n${d.host}:${d.port} • ${d.protocol} • ${d.group}"
                isAllCaps = false
                gravity = Gravity.START
                minHeight = 76

                setOnClickListener {
                    deviceActions(d)
                }
            }

            box.addView(b)
        }

        val scroll = ScrollView(this)
        scroll.addView(box)

        AlertDialog.Builder(this)
            .setTitle("Устройства")
            .setView(scroll)
            .setPositiveButton("＋ Добавить") { _, _ ->
                deviceEditor(null)
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    private fun deviceActions(d: Device) {
        AlertDialog.Builder(this)
            .setTitle(d.name)
            .setItems(
                arrayOf(
                    "▶ Подключить ONVIF/RTSP",
                    "✎ Редактировать",
                    "× Удалить"
                )
            ) { _, which ->
                when (which) {
                    0 -> connectDevice(d)

                    1 -> deviceEditor(d)

                    2 -> {
                        devices.remove(d)
                        save()
                        devicesDialog()
                    }
                }
            }
            .show()
    }

    private fun deviceEditor(existing: Device?) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(25, 5, 25, 5)
        }

        val name = EditText(this).apply {
            hint = "Название устройства"
            setText(existing?.name ?: "Камера")
        }

        val host = EditText(this).apply {
            hint = "IP или hostname"
            setText(existing?.host ?: "")
            setSingleLine()
        }

        val port = EditText(this).apply {
            hint = "Порт ONVIF"
            setText((existing?.port ?: 80).toString())
            inputType = 2
        }

        val user = EditText(this).apply {
            hint = "Логин"
            setText(existing?.user ?: "")
            setSingleLine()
        }

        val pass = EditText(this).apply {
            hint = "Пароль"
            setText(existing?.pass ?: "")
            inputType = 0x81
        }

        val rtsp = EditText(this).apply {
            hint = "RTSP URL (необязательно)"
            setText(existing?.rtsp ?: "")
            setSingleLine()
        }

        val group = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                groups
            )

            setSelection(
                groups.indexOf(existing?.group).coerceAtLeast(0)
            )
        }

        listOf(name, host, port, user, pass, rtsp)
            .forEach {
                box.addView(it)
            }

        box.addView(tv("Группа", 14f))
        box.addView(group)

        AlertDialog.Builder(this)
            .setTitle(
                if (existing == null) {
                    "Добавить устройство"
                } else {
                    "Изменить устройство"
                }
            )
            .setView(box)
            .setPositiveButton("Сохранить") { _, _ ->

                val d = existing ?: Device(
                    System.currentTimeMillis().toString(),
                    "",
                    "",
                    80,
                    "",
                    "",
                    "Без группы"
                )

                d.name = name.text.toString()
                    .trim()
                    .ifBlank { "Камера" }

                d.host = host.text.toString().trim()

                d.port = port.text.toString()
                    .toIntOrNull() ?: 80

                d.user = user.text.toString()
                d.pass = pass.text.toString()
                d.rtsp = rtsp.text.toString().trim()

                d.group = group.selectedItem?.toString()
                    ?: "Без группы"

                if (existing == null) {
                    devices.add(d)
                }

                save()
                devicesDialog()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun connectDevice(d: Device) {
        if (d.rtsp.isNotBlank()) {
            playSingle(
                addCredentials(d.rtsp, d.user, d.pass),
                d.name
            )
            return
        }

        if (d.host.isBlank()) {
            showError(
                "Нет адреса устройства",
                IllegalArgumentException("Укажи IP-адрес камеры или регистратора.")
            )
            return
        }

        Toast.makeText(
            this,
            "ONVIF: подключение…",
            Toast.LENGTH_SHORT
        ).show()

        io.execute {
            val result = runCatching {
                OnvifClient(
                    d.host,
                    d.port,
                    d.user,
                    d.pass
                ).connect()
            }

            runOnUiThread {
                result.onSuccess { res ->
                    d.rtsp = res.streamUri
                    save()

                    playSingle(
                        addCredentials(res.streamUri, d.user, d.pass),
                        d.name
                    )
                }.onFailure {
                    showError("Ошибка ONVIF", it)
                }
            }
        }
    }

    private fun gridDialog() {
        if (devices.isEmpty()) {
            Toast.makeText(
                this,
                "Сначала добавь устройства",
                Toast.LENGTH_SHORT
            ).show()

            return
        }

        val items = arrayOf(
            "1 камера",
            "4 камеры",
            "9 камер",
            "16 камер"
        )

        AlertDialog.Builder(this)
            .setTitle("Сетка просмотра")
            .setItems(items) { _, which ->
                gridSize = intArrayOf(1, 4, 9, 16)[which]
                showGrid()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showGrid() {
        stopPlayers()

        val scroll = ScrollView(this)

        val grid = GridLayout(this).apply {
            columnCount = when (gridSize) {
                1 -> 1
                4 -> 2
                9 -> 3
                else -> 4
            }

            rowCount = (gridSize + columnCount - 1) / columnCount
            setBackgroundColor(Color.BLACK)
        }

        devices.take(gridSize).forEachIndexed { index, d ->

            val frame = FrameLayout(this).apply {
                setBackgroundColor(Color.BLACK)
                contentDescription = d.name
                isFocusable = true
                isClickable = true
            }

            val playerView = PlayerView(this).apply {
                useController = true
                setBackgroundColor(Color.BLACK)
            }

            frame.addView(
                playerView,
                FrameLayout.LayoutParams(-1, -1)
            )

            val label = TextView(this).apply {
                text = d.name
                setTextColor(Color.WHITE)
                setBackgroundColor(0x99000000.toInt())
                setPadding(10, 5, 10, 5)
            }

            frame.addView(
                label,
                FrameLayout.LayoutParams(-2, -2).apply {
                    gravity = Gravity.TOP or Gravity.START
                }
            )

            val lp = GridLayout.LayoutParams().apply {
                width = 0
                height = 0

                columnSpec = GridLayout.spec(
                    index % grid.columnCount,
                    1f
                )

                rowSpec = GridLayout.spec(
                    index / grid.columnCount,
                    1f
                )

                setMargins(2, 2, 2, 2)
            }

            grid.addView(frame, lp)

            frame.setOnClickListener {
                playSingle(resolveRtsp(d), d.name)
            }

            if (d.rtsp.isNotBlank()) {
                startPlayer(resolveRtsp(d), playerView)
            }
        }

        scroll.addView(grid)
        setContentView(scroll)
    }

    private fun resolveRtsp(d: Device): String {
        return if (d.rtsp.isNotBlank()) {
            addCredentials(d.rtsp, d.user, d.pass)
        } else {
            ""
        }
    }

    private fun playSingle(
        raw: String,
        name: String = "Камера"
    ) {
        if (raw.isBlank()) {
            Toast.makeText(
                this,
                "Для устройства ещё не получен RTSP. Подключись через ONVIF.",
                Toast.LENGTH_SHORT
            ).show()

            return
        }

        stopPlayers()

        val playerView = PlayerView(this)
        setContentView(playerView)

        startPlayer(raw, playerView)

        playerView.contentDescription = name

        playerView.setOnClickListener {
            stopPlayers()
            home()
        }
    }

    private fun startPlayer(
        raw: String,
        playerView: PlayerView
    ) {
        val player = ExoPlayer.Builder(this).build()

        players.add(player)
        playerView.player = player

        player.setMediaItem(
            MediaItem.fromUri(Uri.parse(raw))
        )

        player.prepare()
        player.playWhenReady = true
    }

    private fun stopPlayers() {
        players.forEach {
            runCatching {
                it.release()
            }
        }

        players.clear()
    }

    private fun discoverDialog() {
        val status = tv("Поиск ONVIF в локальной сети…")

        AlertDialog.Builder(this)
            .setTitle("Поиск ONVIF")
            .setView(status)
            .setNegativeButton("Закрыть", null)
            .show()

        io.execute {
            val found = runCatching {
                OnvifDiscovery.discover()
            }.getOrElse {
                emptyList()
            }

            runOnUiThread {
                status.text = if (found.isEmpty()) {
                    "Ничего не найдено. Можно добавить устройство вручную."
                } else {
                    found.joinToString("\n\n")
                }

                status.setOnClickListener {
                    if (found.isNotEmpty()) {
                        val first = found.first()

                        val host = runCatching {
                            URI(first).host ?: first
                        }.getOrDefault(first)

                        deviceEditor(
                            Device(
                                System.currentTimeMillis().toString(),
                                "ONVIF устройство",
                                host,
                                80,
                                "",
                                "",
                                "Без группы"
                            )
                        )
                    }
                }
            }
        }
    }

    private fun rtspDialog() {
        val e = EditText(this).apply {
            hint = "rtsp://user:password@host/..."
        }

        AlertDialog.Builder(this)
            .setTitle("RTSP URL")
            .setView(e)
            .setPositiveButton("Открыть") { _, _ ->
                playSingle(e.text.toString())
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun addCredentials(
        raw: String,
        user: String,
        pass: String
    ): String {
        if (raw.isBlank() || user.isBlank()) {
            return raw
        }

        return try {
            val uri = URI(raw)

            if (uri.userInfo != null) {
                return raw
            }

            val host = uri.host ?: return raw
            val port = if (uri.port > 0) ":${uri.port}" else ""
            val path = uri.rawPath ?: ""
            val query = if (!uri.rawQuery.isNullOrBlank()) {
                "?${uri.rawQuery}"
            } else {
                ""
            }

            val encodedUser = URLEncoder.encode(
                user,
                StandardCharsets.UTF_8.name()
            )

            val encodedPass = URLEncoder.encode(
                pass,
                StandardCharsets.UTF_8.name()
            )

            "rtsp://$encodedUser:$encodedPass@$host$port$path$query"
        } catch (_: Exception) {
            raw
        }
    }

    private fun showError(
        title: String,
        e: Throwable
    ) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(e.message ?: "Неизвестная ошибка")
            .setPositiveButton("OK", null)
            .show()
    }

    override fun onDestroy() {
        stopPlayers()
        io.shutdownNow()
        super.onDestroy()
    }
}
