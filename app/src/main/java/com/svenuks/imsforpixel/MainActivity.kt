package com.svenuks.imsforpixel

import android.Manifest
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import org.lsposed.hiddenapibypass.HiddenApiBypass
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.content.Intent
import okio.Path.Companion.toPath
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.cert.KadbCert
import com.flyfishxu.kadb.cert.KadbCertPolicy
import com.flyfishxu.kadb.cert.OkioFilePrivateKeyStore

import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle

class MainActivity : ComponentActivity() {

    companion object {
        @JvmStatic
        var pairingPort: Int? = null
        var onAuthStatusChanged: (() -> Unit)? = null
        // Callback: notified when BrokerInstrumentation finishes (activate or restore)
        var onInstrumentDone: ((isActivate: Boolean, success: Boolean) -> Unit)? = null
    }

    fun showImsStatusNotification(isActivate: Boolean) {
        val channelId = "ims_status_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(channelId, "Статус активации IMS", NotificationManager.IMPORTANCE_HIGH)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }
        // Read actual IMS status from files
        val slot0 = try { java.io.File(filesDir, "ims_status_0.txt").readText().trim().toBoolean() } catch (e: Exception) { false }
        val slot1 = try { java.io.File(filesDir, "ims_status_1.txt").readText().trim().toBoolean() } catch (e: Exception) { false }
        val anyRegistered = slot0 || slot1
        val title = if (isActivate) {
            if (anyRegistered) "✅ VoLTE успешно активирован" else "⚠️ Активация VoLTE завершена"
        } else {
            "✅ Настройки оператора сброшены"
        }
        val body = if (isActivate) {
            buildString {
                if (slot0) append("SIM 1: IMS активен  ") else append("SIM 1: IMS не активен  ")
                if (slot1) append("SIM 2: IMS активен") else append("SIM 2: IMS не активен")
            }
        } else {
            "Переопределения настроек оператора удалены. Проверьте, работают ли звонки"
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java)?.notify(203, notification)
    }

    private var pairingReceiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        try {
            HiddenApiBypass.addHiddenApiExemptions("L")
            Log.d("IMSForSven", "Successfully applied HiddenApiBypass exemptions")
        } catch (e: Exception) {
            Log.e("IMSForSven", "Failed to apply HiddenApiBypass exemptions", e)
        }

        // Initialize KadbCert key store
        try {
            val privateKeyFile = java.io.File(filesDir, "kadb_private_key.pem")
            val store = OkioFilePrivateKeyStore(
                privateKeyFile.absolutePath.toPath()
            )
            KadbCert.configure(
                store = store,
                policy = KadbCertPolicy(),
                additionalPrivateKeysPem = emptyList()
            )
            KadbCert.ensureReady()
            Log.d("IMSForSven", "KadbCert configured successfully")
        } catch (e: Exception) {
            Log.e("IMSForSven", "Failed to configure KadbCert", e)
        }

        // Initialize defaults: enable everything except APN editing and Cross-SIM Calling, both of which are hidden and disabled by default.
        val prefs = getSharedPreferences("volte_settings", Context.MODE_PRIVATE)
        val initialized = prefs.getBoolean("initialized_defaults_v3", false)
        if (!initialized) {
            val editor = prefs.edit()
            for (slot in 0..1) {
                editor.putBoolean("volte_slot_$slot", true)
                editor.putBoolean("vonr_slot_$slot", true)
                editor.putBoolean("vowifi_slot_$slot", true)
                editor.putBoolean("cross_sim_slot_$slot", false) // Hidden and default false
                editor.putBoolean("wfc_roaming_slot_$slot", true)
                editor.putBoolean("ss_ut_slot_$slot", true)
                editor.putBoolean("show_ims_slot_$slot", true)
                editor.putBoolean("allow_apn_slot_$slot", false) // Hidden and default false
            }
            editor.putBoolean("initialized_defaults_v3", true)
            editor.apply()
        }

        // Setup dynamic broadcast receiver for notification pairing code input
        val filter = IntentFilter("com.svenuks.imsforpixel.ACTION_PAIR")
        pairingReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == "com.svenuks.imsforpixel.ACTION_PAIR") {
                    val remoteInput = androidx.core.app.RemoteInput.getResultsFromIntent(intent)
                    if (remoteInput != null) {
                        val code = remoteInput.getCharSequence("extra_pairing_code")?.toString()?.trim()
                        if (!code.isNullOrEmpty()) {
                            handleNotificationPairing(code)
                        }
                    }
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(pairingReceiver, filter, 2) // RECEIVER_NOTEXPORTED is 2
        } else {
            registerReceiver(pairingReceiver, filter)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 102)
            }
        }

        setContent {
            MainScreen()
        }
    }

    fun requestNotificationPermissionAndShow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
            } else {
                showPairingNotification()
            }
        } else {
            showPairingNotification()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                showPairingNotification()
            } else {
                Toast.makeText(this, "Нужно разрешение на уведомления, чтобы ввести код сопряжения из шторки", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "pairing_channel",
                "Сопряжение беспроводной отладки",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Быстрый ввод кода сопряжения беспроводной отладки из шторки уведомлений, не выходя из настроек"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun showPairingNotification() {
        createNotificationChannel()
        
        val replyLabel = "Введите 6-значный код сопряжения"
        val remoteInput = androidx.core.app.RemoteInput.Builder("extra_pairing_code")
            .setLabel(replyLabel)
            .build()
            
        val intent = Intent("com.svenuks.imsforpixel.ACTION_PAIR").apply {
            `package` = packageName
        }
        
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        
        val replyPendingIntent = PendingIntent.getBroadcast(
            this,
            0,
            intent,
            flags
        )
        
        val action = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send,
            "Отправить код",
            replyPendingIntent
        )
            .addRemoteInput(remoteInput)
            .build()
            
        val notification = NotificationCompat.Builder(this, "pairing_channel")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Сопряжение беспроводной отладки")
            .setContentText("💡 Нажмите «Отправить код» ниже и введите 6 цифр")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .setAutoCancel(false)
            .addAction(action)
            .build()
            
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(202, notification)
    }

    private fun showPairingStatusNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(this, "pairing_channel")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Сопряжение беспроводной отладки")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        manager.notify(202, notification)
    }

    private fun handleNotificationPairing(code: String) {
        val port = pairingPort
        if (port == null) {
            Toast.makeText(this, "Порт сопряжения не найден. Убедитесь, что системное окно сопряжения открыто!", Toast.LENGTH_LONG).show()
            showPairingStatusNotification("Ошибка сопряжения: не найден порт окна сопряжения")
            return
        }
        
        Toast.makeText(this, "Выполняется сопряжение в фоне...", Toast.LENGTH_SHORT).show()
        showPairingStatusNotification("Сопряжение через порт $port...")

        val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO)
        scope.launch {
            val result = try {
                Kadb.pair("127.0.0.1", port, code, filesDir.absolutePath)
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
            
            withContext(Dispatchers.Main) {
                result.fold(
                    onSuccess = {
                        Toast.makeText(this@MainActivity, "Сопряжение выполнено!", Toast.LENGTH_LONG).show()
                        showPairingStatusNotification("Сопряжение выполнено! Вернитесь в приложение и нажмите «Активировать».")
                        onAuthStatusChanged?.invoke()
                    },
                    onFailure = { error ->
                        Toast.makeText(this@MainActivity, "Ошибка сопряжения: ${error.message}", Toast.LENGTH_LONG).show()
                        showPairingStatusNotification("Ошибка сопряжения: ${error.message}")
                    }
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        pairingReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (e: Exception) {}
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(202)
    }
}

// Sleek Color Palette
val PrimaryGradStart = Color(0xFF1E3C72)
val PrimaryGradEnd = Color(0xFF2A5298)
val BackgroundDark = Color(0xFF0F172A)
val CardBackground = Color(0xFF1E293B)
val BorderColor = Color(0xFF334155)
val AccentGreen = Color(0xFF10B981)
val AccentOrange = Color(0xFFF59E0B)
val AccentRed = Color(0xFFEF4444)
val TextLight = Color(0xFFF8FAFC)
val TextMuted = Color(0xFF94A3B8)

// Each background IMS query starts a fresh app_process VM on the device, so it runs rarely
// and only while the app is visible.
const val IMS_POLL_INTERVAL_MS = 10_000L
// How often the status files (written by BrokerInstrumentation and the poller) are re-read for the UI
const val STATUS_REFRESH_INTERVAL_MS = 2_000L

data class SlotStatus(val imsRegistered: Boolean = false, val configApplied: Boolean = false)

private fun readFlagFile(context: Context, name: String): Boolean? = try {
    val file = java.io.File(context.filesDir, name)
    if (file.exists()) file.readText().trim().toBoolean() else null
} catch (e: Exception) {
    null
}

// Blocking disk reads — call off the main thread.
fun readSlotStatuses(context: Context): List<SlotStatus> {
    val prefs = context.getSharedPreferences("volte_settings", Context.MODE_PRIVATE)
    return (0..1).map { slot ->
        SlotStatus(
            imsRegistered = readFlagFile(context, "ims_status_$slot.txt")
                ?: prefs.getBoolean("ims_registered_slot_$slot", false),
            configApplied = readFlagFile(context, "config_applied_$slot.txt") ?: false
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    var selectedSimSlot by remember { mutableStateOf(0) }
    var portInput by remember { mutableStateOf("") }
    var isApplying by remember { mutableStateOf(false) }
    // True while am instrument is running — pauses background ImsQueryTool polling
    val isInstrumenting = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    var slotStatuses by remember { mutableStateOf(listOf(SlotStatus(), SlotStatus())) }
    // Bumped after a successful pairing so that the authorization check runs again
    var authCheckSignal by remember { mutableStateOf(0L) }

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    
    LaunchedEffect(Unit) {
        MainActivity.onAuthStatusChanged = {
            authCheckSignal = System.currentTimeMillis()
        }
        MainActivity.onInstrumentDone = { isActivate, _ ->
            (context as? MainActivity)?.showImsStatusNotification(isActivate)
        }
    }

    // Read the status files on the IO dispatcher and only replace the state when something changed,
    // so the screen does not recompose on every tick.
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val fresh = withContext(Dispatchers.IO) { readSlotStatuses(context) }
                if (fresh != slotStatuses) slotStatuses = fresh
                delay(STATUS_REFRESH_INTERVAL_MS)
            }
        }
    }

    LaunchedEffect(portInput, lifecycleOwner) {
        val port = portInput.toIntOrNull()
        if (port != null && port > 0 && port <= 65535) {
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                withContext(Dispatchers.IO) {
                    // Our own APK path is known locally, no need to run `pm path` over ADB on every poll
                    val apkPath = context.applicationInfo.sourceDir
                    val queryCmd = "export CLASSPATH=$apkPath; app_process /system/bin com.svenuks.imsforpixel.ImsQueryTool"
                    var activeKadb: com.flyfishxu.kadb.Kadb? = null
                    try {
                        while (true) {
                            // Pause while BrokerInstrumentation is running to avoid file conflicts
                            if (isInstrumenting.get()) {
                                delay(1000)
                                continue
                            }
                            try {
                                val kadb = activeKadb ?: com.flyfishxu.kadb.Kadb.create("127.0.0.1", port, 5000, 5000).also { activeKadb = it }
                                val queryRes = kadb.shell(queryCmd)
                                if (queryRes.exitCode == 0) {
                                    val lines = queryRes.output.lines()
                                    for (line in lines) {
                                        if (line.startsWith("RESULT:")) {
                                            val parts = line.split(":")
                                            if (parts.size == 3) {
                                                val slot = parts[1].toIntOrNull() ?: continue
                                                val isImsRegistered = parts[2].trim().toBoolean()
                                                val statusFile = java.io.File(context.filesDir, "ims_status_$slot.txt")
                                                statusFile.writeText(isImsRegistered.toString())
                                                Log.d("LocalAdb", "Updated slot $slot IMS status: $isImsRegistered")
                                            }
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                Log.d("LocalAdb", "Error in background IMS check: ${e.message}")
                                try { activeKadb?.close() } catch (ignored: Exception) {}
                                activeKadb = null
                            }
                            delay(IMS_POLL_INTERVAL_MS)
                        }
                    } finally {
                        try { activeKadb?.close() } catch (ignored: Exception) {}
                    }
                }
            }
        }
    }

    val scope = rememberCoroutineScope()

    fun triggerManualApply() {
        val port = portInput.toIntOrNull()
        if (port == null || port <= 0 || port > 65535) {
            Toast.makeText(context, "Сначала включите беспроводную отладку", Toast.LENGTH_SHORT).show()
            return
        }

        isApplying = true
        isInstrumenting.set(true)
        scope.launch {
            // Run am instrument synchronously (no nohup/&) so we wait for the real result.
            // BrokerInstrumentation polls IMS for up to 30s internally; timeout set to 90s.
            val result = withContext(Dispatchers.IO) {
                // Reset clear flags to false to ensure the configuration overrides are applied.
                // commit() (not apply()) so the flags are on disk before the instrumentation reads them;
                // it runs here on the IO dispatcher to keep the disk write off the main thread.
                val prefs = context.getSharedPreferences("volte_settings", Context.MODE_PRIVATE)
                prefs.edit()
                    .putBoolean("clear_slot_0", false)
                    .putBoolean("clear_slot_1", false)
                    .commit()

                try {
                    Kadb.create("127.0.0.1", port, 90000, 90000).use { kadb ->
                        val cmd = "nohup am instrument -w -e clear false com.svenuks.imsforpixel/com.svenuks.imsforpixel.BrokerInstrumentation > /dev/null 2>&1 &"
                        val response = kadb.shell(cmd)
                        if (response.exitCode == 0) {
                            Result.success(response.output)
                        } else {
                            Result.failure(Exception("Exit code ${response.exitCode}: ${response.output}"))
                        }
                    }
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
            isApplying = false
            isInstrumenting.set(false)
            result.fold(
                onSuccess = {
                    MainActivity.onInstrumentDone?.invoke(true, true)
                },
                onFailure = { error ->
                    isInstrumenting.set(false)
                    Toast.makeText(context, "Ошибка активации: ${error.message}", Toast.LENGTH_LONG).show()
                }
            )
        }
    }

    fun triggerManualRestore() {
        val port = portInput.toIntOrNull()
        if (port == null || port <= 0 || port > 65535) {
            Toast.makeText(context, "Сначала включите беспроводную отладку", Toast.LENGTH_SHORT).show()
            return
        }

        isApplying = true
        isInstrumenting.set(true)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                // Reset prefs for both slots (commit() on the IO dispatcher: durable before am instrument,
                // without blocking the main thread)
                val prefs = context.getSharedPreferences("volte_settings", Context.MODE_PRIVATE)
                prefs.edit()
                    .putBoolean("clear_slot_0", true)
                    .putBoolean("clear_slot_1", true)
                    .putBoolean("volte_slot_0", true)
                    .putBoolean("volte_slot_1", true)
                    .putBoolean("vonr_slot_0", true)
                    .putBoolean("vonr_slot_1", true)
                    .putBoolean("vowifi_slot_0", true)
                    .putBoolean("vowifi_slot_1", true)
                    .putBoolean("wfc_roaming_slot_0", true)
                    .putBoolean("wfc_roaming_slot_1", true)
                    .putBoolean("ss_ut_slot_0", true)
                    .putBoolean("ss_ut_slot_1", true)
                    .putBoolean("show_ims_slot_0", true)
                    .putBoolean("show_ims_slot_1", true)
                    .putBoolean("allow_apn_slot_0", false)
                    .putBoolean("allow_apn_slot_1", false)
                    .putBoolean("cross_sim_slot_0", false)
                    .putBoolean("cross_sim_slot_1", false)
                    .commit()

                try {
                    Kadb.create("127.0.0.1", port, 90000, 90000).use { kadb ->
                        val cmd = "nohup am instrument -w -e clear true com.svenuks.imsforpixel/com.svenuks.imsforpixel.BrokerInstrumentation > /dev/null 2>&1 &"
                        val response = kadb.shell(cmd)
                        if (response.exitCode == 0) {
                            Result.success(response.output)
                        } else {
                            Result.failure(Exception("Exit code ${response.exitCode}: ${response.output}"))
                        }
                    }
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
            isApplying = false
            isInstrumenting.set(false)
            result.fold(
                onSuccess = {
                    MainActivity.onInstrumentDone?.invoke(false, true)
                },
                onFailure = { error ->
                    isInstrumenting.set(false)
                    Toast.makeText(context, "Ошибка сброса: ${error.message}", Toast.LENGTH_LONG).show()
                }
            )
        }
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = PrimaryGradEnd,
            background = BackgroundDark,
            surface = CardBackground,
            onPrimary = TextLight,
            onBackground = TextLight,
            onSurface = TextLight
        )
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            "IMS для Pixel",
                            fontWeight = FontWeight.Bold,
                            color = TextLight
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = CardBackground
                    )
                )
            },
            containerColor = BackgroundDark
        ) { paddingValues ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Description Subtitle
                item {
                    Text(
                        text = "Включение VoLTE/VoNR/VoWiFi без root",
                        color = TextMuted,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }

                // SIM selector tabs
                item {
                    SimSelectorTabs(
                        selectedSlot = selectedSimSlot,
                        onSlotSelected = { selectedSimSlot = it }
                    )
                }

                // Config Panel for Selected Slot
                item {
                    ConfigPanel(
                        slotIndex = selectedSimSlot,
                        status = slotStatuses[selectedSimSlot],
                        onConfigChanged = {}
                    )
                }

                // Local Apply Card (Wireless Debugging self-connect)
                item {
                    LocalAdbCard(
                        slotStatuses = slotStatuses,
                        authCheckSignal = authCheckSignal,
                        portInput = portInput,
                        onPortInputChange = { portInput = it },
                        isApplying = isApplying,
                        onApplyClick = { triggerManualApply() },
                        onRestoreClick = { triggerManualRestore() }
                    )
                }
            }
        }
    }
}


@Composable
fun SimSelectorTabs(
    selectedSlot: Int,
    onSlotSelected: (Int) -> Unit
) {
    TabRow(
        selectedTabIndex = selectedSlot,
        containerColor = CardBackground,
        contentColor = TextLight,
        indicator = { tabPositions ->
            TabRowDefaults.SecondaryIndicator(
                Modifier.tabIndicatorOffset(tabPositions[selectedSlot]),
                color = PrimaryGradEnd
            )
        },
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, BorderColor, RoundedCornerShape(12.dp))
    ) {
        Tab(
            selected = selectedSlot == 0,
            onClick = { onSlotSelected(0) },
            text = { Text("SIM 1", fontWeight = FontWeight.Bold) }
        )
        Tab(
            selected = selectedSlot == 1,
            onClick = { onSlotSelected(1) },
            text = { Text("SIM 2", fontWeight = FontWeight.Bold) }
        )
    }
}

@Composable
fun ConfigPanel(
    slotIndex: Int,
    status: SlotStatus,
    onConfigChanged: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember(slotIndex) {
        context.getSharedPreferences("volte_settings", Context.MODE_PRIVATE)
    }

    var voLteEnabled by remember(slotIndex) { mutableStateOf(prefs.getBoolean("volte_slot_$slotIndex", true)) }
    var voNrEnabled by remember(slotIndex) { mutableStateOf(prefs.getBoolean("vonr_slot_$slotIndex", true)) }
    var voWifiEnabled by remember(slotIndex) { mutableStateOf(prefs.getBoolean("vowifi_slot_$slotIndex", true)) }
    var crossSimEnabled by remember(slotIndex) { mutableStateOf(prefs.getBoolean("cross_sim_slot_$slotIndex", false)) } // Default false
    var wfcRoamingEnabled by remember(slotIndex) { mutableStateOf(prefs.getBoolean("wfc_roaming_slot_$slotIndex", true)) }
    var ssUtEnabled by remember(slotIndex) { mutableStateOf(prefs.getBoolean("ss_ut_slot_$slotIndex", true)) }
    var allowApnEdit by remember(slotIndex) { mutableStateOf(prefs.getBoolean("allow_apn_slot_$slotIndex", false)) } // Default false
    
    val imsRegistered = status.imsRegistered
    val configApplied = status.configApplied

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, BorderColor, RoundedCornerShape(16.dp)),
        colors = CardDefaults.cardColors(containerColor = CardBackground)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Параметры оператора (слот ${slotIndex + 1})",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = TextLight,
                    // Russian title is longer than the original; let it wrap instead of squeezing the badges
                    modifier = Modifier.weight(1f).padding(end = 8.dp)
                )
                
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (configApplied) AccentGreen.copy(alpha = 0.15f) else BorderColor.copy(alpha = 0.3f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = if (configApplied) "Конфиг: свой" else "Конфиг: стандарт",
                            color = if (configApplied) AccentGreen else TextMuted,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 10.sp
                        )
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (imsRegistered) AccentGreen.copy(alpha = 0.15f) else AccentRed.copy(alpha = 0.15f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = if (imsRegistered) "IMS: есть" else "IMS: нет",
                            color = if (imsRegistered) AccentGreen else AccentRed,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 10.sp
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = BorderColor)
            Spacer(modifier = Modifier.height(8.dp))

            // Toggles
            ToggleRow("Звонки VoLTE", "Голосовые вызовы через сеть 4G/LTE", voLteEnabled) { 
                voLteEnabled = it
                prefs.edit().putBoolean("volte_slot_$slotIndex", it).putBoolean("clear_slot_$slotIndex", false).apply()
                onConfigChanged()
            }
            ToggleRow("Звонки 5G (VoNR)", "Голосовые вызовы в автономной сети 5G SA (VoNR)", voNrEnabled) { 
                voNrEnabled = it
                prefs.edit().putBoolean("vonr_slot_$slotIndex", it).putBoolean("clear_slot_$slotIndex", false).apply()
                onConfigChanged()
            }
            ToggleRow("Звонки по Wi-Fi (VoWiFi)", "Звонки через Wi-Fi при слабом сигнале сотовой сети", voWifiEnabled) { 
                voWifiEnabled = it
                prefs.edit().putBoolean("vowifi_slot_$slotIndex", it).putBoolean("clear_slot_$slotIndex", false).apply()
                onConfigChanged()
            }
            ToggleRow("Звонки по Wi-Fi в роуминге", "Не отключать звонки по Wi-Fi за границей и в роуминге", wfcRoamingEnabled) { 
                wfcRoamingEnabled = it
                prefs.edit().putBoolean("wfc_roaming_slot_$slotIndex", it).putBoolean("clear_slot_$slotIndex", false).apply()
                onConfigChanged()
            }
            ToggleRow("Дополнительные услуги (UT)", "Переадресация, ожидание вызова и другие услуги оператора", ssUtEnabled) { 
                ssUtEnabled = it
                prefs.edit().putBoolean("ss_ut_slot_$slotIndex", it).putBoolean("clear_slot_$slotIndex", false).apply()
                onConfigChanged()
            }
        }
    }
}

@Composable
fun ToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(description, color = TextMuted, fontSize = 11.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = TextLight,
                checkedTrackColor = PrimaryGradEnd
            )
        )
    }
}

// mDNS also reports the Wireless Debugging services of other devices on the same Wi-Fi network.
// Only a service advertised from one of this device's own addresses is reachable via 127.0.0.1.
// Called on the NSD callback thread.
private fun isOwnDeviceService(info: NsdServiceInfo): Boolean {
    @Suppress("DEPRECATION")
    val host = info.host ?: return true
    return try {
        java.net.NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .any { iface -> iface.inetAddresses.toList().any { it == host } }
    } catch (e: Exception) {
        true
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalAdbCard(
    slotStatuses: List<SlotStatus>,
    authCheckSignal: Long,
    portInput: String,
    onPortInputChange: (String) -> Unit,
    isApplying: Boolean,
    onApplyClick: () -> Unit,
    onRestoreClick: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pairingPortInput by remember { mutableStateOf("") }
    var showManualPorts by remember { mutableStateOf(false) }
    var isAuthorized by remember { mutableStateOf(false) }
    var isWifiConnected by remember { mutableStateOf(false) }
    // System network/NSD callbacks arrive on binder threads; Compose state is only written on the main thread
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    val slot0Active = slotStatuses[0].imsRegistered
    val slot1Active = slotStatuses[1].imsRegistered
    val slot0Applied = slotStatuses[0].configApplied
    val slot1Applied = slotStatuses[1].configApplied

    // Auth check: re-run when the port changes or after a successful pairing, not periodically.
    // Running it often would spam new Kadb connections and compete with the
    // background IMS polling loop and any active BrokerInstrumentation command.
    LaunchedEffect(portInput, authCheckSignal) {
        val port = portInput.toIntOrNull()
        isAuthorized = if (port != null && port > 0 && port <= 65535) {
            withContext(Dispatchers.IO) {
                try {
                    Kadb.create("127.0.0.1", port, 3000, 3000).use { kadb ->
                        kadb.shell("echo 1").exitCode == 0
                    }
                } catch (e: Exception) {
                    false
                }
            }
        } else {
            false
        }
    }

    DisposableEffect(Unit) {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val callback = object : android.net.ConnectivityManager.NetworkCallback() {
            // Delivered on mainHandler (see registerNetworkCallback below)
            override fun onAvailable(network: android.net.Network) {
                val caps = connectivityManager.getNetworkCapabilities(network)
                isWifiConnected = caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
            }
            override fun onLost(network: android.net.Network) {
                isWifiConnected = false
            }
            override fun onCapabilitiesChanged(network: android.net.Network, networkCapabilities: android.net.NetworkCapabilities) {
                isWifiConnected = networkCapabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
            }
        }
        val request = android.net.NetworkRequest.Builder()
            .addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        try {
            connectivityManager.registerNetworkCallback(request, callback, mainHandler)
        } catch (e: Exception) {}
        
        val activeNet = connectivityManager.activeNetwork
        val caps = connectivityManager.getNetworkCapabilities(activeNet)
        isWifiConnected = caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true

        onDispose {
            try {
                connectivityManager.unregisterNetworkCallback(callback)
            } catch (e: Exception) {}
        }
    }

    // Auto-discover the ports via mDNS (Network Service Discovery)
    val nsdManager = remember { context.getSystemService(Context.NSD_SERVICE) as NsdManager }
    DisposableEffect(Unit) {
        val connectListener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                Log.e("LocalAdb", "Start connect discovery failed: $errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {
                Log.e("LocalAdb", "Stop connect discovery failed: $errorCode")
            }

            override fun onDiscoveryStarted(serviceType: String?) {
                Log.d("LocalAdb", "Connect discovery started")
            }

            override fun onDiscoveryStopped(serviceType: String?) {
                Log.d("LocalAdb", "Connect discovery stopped")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.d("LocalAdb", "Connect service found: ${serviceInfo.serviceName}")
                if (serviceInfo.serviceType.contains("adb-tls-connect")) {
                    nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                            Log.e("LocalAdb", "Connect resolve failed: $errorCode")
                        }

                        override fun onServiceResolved(resolvedServiceInfo: NsdServiceInfo) {
                            val port = resolvedServiceInfo.port
                            if (!isOwnDeviceService(resolvedServiceInfo)) {
                                Log.d("LocalAdb", "Ignoring ADB port $port of another device (${resolvedServiceInfo.host})")
                                return
                            }
                            Log.d("LocalAdb", "Resolved local ADB port: $port")
                            mainHandler.post { onPortInputChange(port.toString()) }
                        }
                    })
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo?) {
                Log.d("LocalAdb", "Connect service lost")
            }
        }

        val pairingListener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                Log.e("LocalAdb", "Start pairing discovery failed: $errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {
                Log.e("LocalAdb", "Stop pairing discovery failed: $errorCode")
            }

            override fun onDiscoveryStarted(serviceType: String?) {
                Log.d("LocalAdb", "Pairing discovery started")
            }

            override fun onDiscoveryStopped(serviceType: String?) {
                Log.d("LocalAdb", "Pairing discovery stopped")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.d("LocalAdb", "Pairing service found: ${serviceInfo.serviceName}")
                if (serviceInfo.serviceType.contains("adb-tls-pairing")) {
                    nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                            Log.e("LocalAdb", "Pairing resolve failed: $errorCode")
                        }

                        override fun onServiceResolved(resolvedServiceInfo: NsdServiceInfo) {
                            val port = resolvedServiceInfo.port
                            if (!isOwnDeviceService(resolvedServiceInfo)) {
                                Log.d("LocalAdb", "Ignoring ADB pairing port $port of another device (${resolvedServiceInfo.host})")
                                return
                            }
                            Log.d("LocalAdb", "Resolved local ADB pairing port: $port")
                            mainHandler.post {
                                pairingPortInput = port.toString()
                                MainActivity.pairingPort = port
                            }
                        }
                    })
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo?) {
                Log.d("LocalAdb", "Pairing service lost")
            }
        }

        try {
            nsdManager.discoverServices("_adb-tls-connect._tcp", NsdManager.PROTOCOL_DNS_SD, connectListener)
        } catch (e: Exception) {
            Log.e("LocalAdb", "Failed to start connect discovery", e)
        }

        try {
            nsdManager.discoverServices("_adb-tls-pairing._tcp", NsdManager.PROTOCOL_DNS_SD, pairingListener)
        } catch (e: Exception) {
            Log.e("LocalAdb", "Failed to start pairing discovery", e)
        }

        onDispose {
            mainHandler.removeCallbacksAndMessages(null)
            try {
                nsdManager.stopServiceDiscovery(connectListener)
            } catch (e: Exception) {
                // Ignore
            }
            try {
                nsdManager.stopServiceDiscovery(pairingListener)
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, BorderColor, RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = CardBackground)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Активация через беспроводную отладку",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = TextLight
                )
            }
            Spacer(modifier = Modifier.height(12.dp))

            // Step 1: Wi-Fi connection status (Prerequisite)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Шаг 1: подключитесь к Wi-Fi (обязательно)", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = TextLight)
                    Text("Беспроводная отладка работает только при подключении к Wi-Fi", fontSize = 10.sp, color = TextMuted)
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isWifiConnected) AccentGreen.copy(alpha = 0.15f) else AccentRed.copy(alpha = 0.15f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = if (isWifiConnected) "Подключено" else "Не подключено",
                        color = if (isWifiConnected) AccentGreen else AccentRed,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = BorderColor)
            Spacer(modifier = Modifier.height(12.dp))

            // Step 2: Wireless Debugging & Pairing
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Шаг 2: включите отладку и выполните сопряжение", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = TextLight)
                    Text(
                        text = if (isAuthorized) "Сопряжение выполнено, доступ разрешён" else if (portInput.isNotEmpty()) "Отладка включена (нужно сопряжение)" else "Включите беспроводную отладку в настройках",
                        fontSize = 10.sp,
                        color = if (isAuthorized) AccentGreen else if (portInput.isNotEmpty()) AccentOrange else TextMuted
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isAuthorized) AccentGreen.copy(alpha = 0.15f) else if (portInput.isNotEmpty()) AccentOrange.copy(alpha = 0.15f) else BorderColor.copy(alpha = 0.3f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = if (isAuthorized) "Разрешено" else if (portInput.isNotEmpty()) "Нет сопряжения" else "Не подключено",
                        color = if (isAuthorized) AccentGreen else if (portInput.isNotEmpty()) AccentOrange else TextMuted,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    (context as? MainActivity)?.requestNotificationPermissionAndShow()
                    try {
                        context.startActivity(Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS"))
                    } catch (e: Exception) {
                        try {
                            context.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                        } catch (e2: Exception) {
                            Toast.makeText(context, "Раздел «Для разработчиков» не найден", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextLight),
                border = androidx.compose.foundation.BorderStroke(1.dp, BorderColor)
            ) {
                Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Открыть настройки беспроводной отладки", fontSize = 11.sp)
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = BorderColor)
            Spacer(modifier = Modifier.height(12.dp))

            // Step 3: Activation & Status check
            Text("Шаг 3: активация в одно касание", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = TextLight)
            Spacer(modifier = Modifier.height(4.dp))
            Text("Применить выбранные выше параметры к SIM-картам", fontSize = 10.sp, color = TextMuted)
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(BackgroundDark.copy(alpha = 0.5f))
                        .border(1.dp, BorderColor, RoundedCornerShape(8.dp))
                        .padding(8.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Text("Настройки SIM 1", fontSize = 10.sp, color = TextMuted)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = if (slot0Applied) "Применено" else "По умолчанию",
                            color = if (slot0Applied) AccentGreen else TextMuted,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                        Spacer(modifier = Modifier.height(1.dp))
                        Text(
                            text = if (slot0Active) "IMS активен" else "IMS не активен",
                            color = if (slot0Active) AccentGreen else AccentRed,
                            fontSize = 9.sp
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(BackgroundDark.copy(alpha = 0.5f))
                        .border(1.dp, BorderColor, RoundedCornerShape(8.dp))
                        .padding(8.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Text("Настройки SIM 2", fontSize = 10.sp, color = TextMuted)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = if (slot1Applied) "Применено" else "По умолчанию",
                            color = if (slot1Applied) AccentGreen else TextMuted,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                        Spacer(modifier = Modifier.height(1.dp))
                        Text(
                            text = if (slot1Active) "IMS активен" else "IMS не активен",
                            color = if (slot1Active) AccentGreen else AccentRed,
                            fontSize = 9.sp
                        )
                    }
                }
            }

            val hasResolvedPort = portInput.isNotEmpty()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onApplyClick,
                    enabled = !isApplying,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = if (hasResolvedPort) AccentGreen else BorderColor)
                ) {
                    if (isApplying) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), color = Color.White, strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            if (hasResolvedPort) "Активировать" else "Ожидание...",
                            fontSize = 12.sp
                        )
                    }
                }

                OutlinedButton(
                    onClick = onRestoreClick,
                    enabled = !isApplying,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentRed),
                    border = androidx.compose.foundation.BorderStroke(1.dp, AccentRed.copy(alpha = 0.5f))
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        "Сбросить",
                        fontSize = 12.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "💡 Статус IMS обновится через несколько секунд.",
                fontSize = 10.sp,
                color = AccentOrange,
                modifier = Modifier.padding(horizontal = 2.dp),
                maxLines = 1
            )

            // Advanced manual override
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Text(
                    text = if (showManualPorts) "Скрыть дополнительные настройки" else "Дополнительные настройки",
                    color = TextMuted,
                    fontSize = 10.sp,
                    modifier = Modifier
                        .clickable { showManualPorts = !showManualPorts }
                        .padding(4.dp)
                )
            }

            if (showManualPorts) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = portInput,
                    onValueChange = { onPortInputChange(it) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Порт подключения (ADB)", fontSize = 9.sp) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    textStyle = LocalTextStyle.current.copy(fontSize = 11.sp)
                )
            }
        }
    }
}
