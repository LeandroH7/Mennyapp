package com.menny.assistant

/*
 * ============================================================
 *  MENNY — Advanced Personal Voice Assistant
 *  MainActivity.kt
 *
 *  Architecture:
 *  ┌─────────────────────────────────────────────────────────┐
 *  │  UI (MainActivity)                                       │
 *  │   └─ CommandProcessor  (parses spoken/typed command)    │
 *  │        └─ ActionExecutor  (runs the right action)       │
 *  │             ├─ AppActions   (open apps / intents)        │
 *  │             ├─ SystemActions (wifi, BT, flash, volume…)  │
 *  │             ├─ MediaActions  (music, gallery)            │
 *  │             ├─ ContactActions (call, SMS, WhatsApp)      │
 *  │             ├─ ModeActions   (driving, study, sleep…)    │
 *  │             └─ AiActions    (send question to Claude AI) │
 *  └─────────────────────────────────────────────────────────┘
 *
 *  IMPORTANT: Replace AI_API_KEY with your actual Claude/OpenAI key.
 * ============================================================
 */

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.*
import android.provider.*
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.*

// ─────────────────────────────────────────────────────────────────────────────
// CONSTANTS — fill in your API key
// ─────────────────────────────────────────────────────────────────────────────
private const val AI_API_KEY   = "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"
private const val AI_ENDPOINT  = "https://api.groq.com/openai/v1/chat/completions"
// If you prefer OpenAI: "https://api.openai.com/v1/chat/completions"
private const val AI_MODEL     = "llama-3.3-70b-versatile"

private const val TAG          = "Menny"
private const val PREF_NAME    = "menny_prefs"
private const val REQUEST_PERMS = 100

// ─────────────────────────────────────────────────────────────────────────────
// PERMISSION LIST
// ─────────────────────────────────────────────────────────────────────────────
private val REQUIRED_PERMISSIONS = buildList {
    add(Manifest.permission.RECORD_AUDIO)
    add(Manifest.permission.CAMERA)
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.CALL_PHONE)
    add(Manifest.permission.READ_CONTACTS)
    add(Manifest.permission.READ_CALENDAR)
    add(Manifest.permission.WRITE_CALENDAR)
    add(Manifest.permission.SEND_SMS)
    add(Manifest.permission.READ_SMS)
    add(Manifest.permission.CHANGE_WIFI_STATE)
    add(Manifest.permission.BLUETOOTH_CONNECT)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.READ_MEDIA_IMAGES)
        add(Manifest.permission.READ_MEDIA_VIDEO)
        add(Manifest.permission.READ_MEDIA_AUDIO)
        add(Manifest.permission.POST_NOTIFICATIONS)
    } else {
        add(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
}.toTypedArray()


// ─────────────────────────────────────────────────────────────────────────────
// MainActivity
// ─────────────────────────────────────────────────────────────────────────────
class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    // ── UI refs ──────────────────────────────────────────────────────────────
    private lateinit var tvStatus:      TextView
    private lateinit var tvResponse:    TextView
    private lateinit var tvLastCommand: TextView
    private lateinit var tvActionLog:   TextView
    private lateinit var etInput:       EditText
    private lateinit var btnVoice:      Button
    private lateinit var btnSend:       ImageButton
    private lateinit var scrollResponse: ScrollView

    // ── TTS ──────────────────────────────────────────────────────────────────
    private lateinit var tts: TextToSpeech
    private var ttsReady = false

    // ── STT ──────────────────────────────────────────────────────────────────
    private lateinit var speechRecognizer: SpeechRecognizer

    // ── Coroutines ───────────────────────────────────────────────────────────
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // ── Flashlight state ─────────────────────────────────────────────────────
    private var flashOn = false
    private var cameraId: String? = null

    // ── Prefs (for custom modes) ──────────────────────────────────────────────
    private val prefs by lazy { getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE) }

    // ─────────────────────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        bindViews()
        initTts()
        initSpeechRecognizer()
        requestPermissionsIfNeeded()
        setupCameraId()
        setupListeners()
    }

    // ── Bind UI ───────────────────────────────────────────────────────────────
    private fun bindViews() {
        tvStatus      = findViewById(R.id.tvStatus)
        tvResponse    = findViewById(R.id.tvResponse)
        tvLastCommand = findViewById(R.id.tvLastCommand)
        tvActionLog   = findViewById(R.id.tvActionLog)
        etInput       = findViewById(R.id.etInput)
        btnVoice      = findViewById(R.id.btnVoice)
        btnSend       = findViewById(R.id.btnSend)
        scrollResponse = findViewById(R.id.scrollResponse)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TTS
    // ─────────────────────────────────────────────────────────────────────────
    private fun initTts() {
        tts = TextToSpeech(this, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            // Prefer Brazilian Portuguese; fall back to any Portuguese, then default
            val result = tts.setLanguage(Locale("pt", "BR"))
            ttsReady = result != TextToSpeech.LANG_MISSING_DATA &&
                       result != TextToSpeech.LANG_NOT_SUPPORTED
            tts.setSpeechRate(1.05f)  // slightly faster for a confident, strong tone
            tts.setPitch(0.85f)       // lower pitch = more masculine voice
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) { /* no-op */ }
                override fun onDone(id: String?)  { /* no-op */ }
                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) { Log.e(TAG, "TTS error: $id") }
            })
            speak("Menny pronto. Como posso ajudar?")
        } else {
            showResponse("⚠️ TTS não inicializado corretamente.")
        }
    }

    /** Speak + display text */
    private fun speak(text: String) {
        showResponse(text)
        if (ttsReady) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // STT
    // ─────────────────────────────────────────────────────────────────────────
    private fun initSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            showResponse("⚠️ Reconhecimento de voz não disponível neste dispositivo.")
            return
        }
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val spoken  = matches?.firstOrNull() ?: return
                setStatus("● Processando")
                processCommand(spoken)
            }
            override fun onReadyForSpeech(p: Bundle?)  { setStatus("● Ouvindo...") }
            override fun onBeginningOfSpeech()         {}
            override fun onRmsChanged(v: Float)        {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech()               { setStatus("● Processando") }
            override fun onError(error: Int) {
                setStatus("● Aguardando")
                val msg = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "Não entendi. Tente novamente."
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Tempo esgotado."
                    SpeechRecognizer.ERROR_AUDIO -> "Erro de áudio."
                    SpeechRecognizer.ERROR_NETWORK -> "Sem conexão para STT."
                    else -> "Erro STT ($error)."
                }
                speak(msg)
            }
            override fun onPartialResults(p: Bundle?) {}
            override fun onEvent(t: Int, p: Bundle?)  {}
        })
    }

    private fun startListening() {
        if (!::speechRecognizer.isInitialized) { speak("Reconhecimento de voz indisponível."); return }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        try {
            speechRecognizer.startListening(intent)
        } catch (e: Exception) {
            speak("Erro ao iniciar microfone.")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PERMISSIONS
    // ─────────────────────────────────────────────────────────────────────────
    private fun requestPermissionsIfNeeded() {
        val missing = REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQUEST_PERMS)
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        // Silently continue; individual actions will handle missing perms gracefully
    }

    private fun hasPermission(perm: String) =
        ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED

    // ─────────────────────────────────────────────────────────────────────────
    // LISTENERS
    // ─────────────────────────────────────────────────────────────────────────
    private fun setupListeners() {
        btnVoice.setOnClickListener { startListening() }

        btnSend.setOnClickListener {
            val text = etInput.text.toString().trim()
            if (text.isNotEmpty()) {
                processCommand(text)
                etInput.setText("")
            }
        }

        etInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                btnSend.performClick()
                true
            } else false
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // COMMAND PROCESSOR — main routing logic
    // ─────────────────────────────────────────────────────────────────────────
    private fun processCommand(raw: String) {
        val cmd = raw.lowercase(Locale("pt", "BR")).trim()
        setLastCommand(raw)
        setStatus("● Aguardando")

        // Strip wake word(s)
        val clean = cmd
            .removePrefix("menny,").removePrefix("menny")
            .removePrefix("ei menny,").removePrefix("ei menny")
            .trim()

        log("CMD: $clean")

        // ── Route by keyword ──────────────────────────────────────────────────

        when {
            // ── APPS ──────────────────────────────────────────────────────────
            clean.contains("whatsapp")          -> handleWhatsApp(clean)
            clean.contains("instagram")         -> handleInstagram(clean)
            clean.contains("telegram")          -> openApp("org.telegram.messenger", "Telegram")
            clean.contains("youtube")           -> openApp("com.google.android.youtube", "YouTube")
            clean.contains("spotify")           -> openApp("com.spotify.music", "Spotify")
            clean.contains("chrome") ||
              clean.contains("navegador")       -> openApp("com.android.chrome", "Chrome")
            clean.contains("maps") ||
              clean.contains("google maps")     -> handleMaps(clean)
            clean.contains("gmail") ||
              clean.contains("e-mail") ||
              clean.contains("email")           -> openApp("com.google.android.gm", "Gmail")
            clean.contains("câmera") ||
              clean.contains("camera")          -> openCamera()
            clean.contains("galeria") ||
              clean.contains("fotos")           -> openGallery()
            clean.contains("calculadora")       -> openCalculator()
            clean.contains("arquivos") ||
              clean.contains("gerenciador")     -> openFiles()
            clean.contains("play store")        -> openApp("com.android.vending", "Play Store")

            // ── CALLS ─────────────────────────────────────────────────────────
            clean.contains("liga para") ||
              clean.contains("ligar para") ||
              clean.contains("chamar")          -> handleCall(clean)

            // ── SMS ───────────────────────────────────────────────────────────
            clean.contains("sms") ||
              clean.contains("mensagem") &&
              clean.contains("para")            -> handleSms(clean)

            // ── WIFI ─────────────────────────────────────────────────────────
            clean.contains("wi-fi") ||
              clean.contains("wifi")            -> handleWifi(clean)

            // ── BLUETOOTH ─────────────────────────────────────────────────────
            clean.contains("bluetooth")         -> handleBluetooth(clean)

            // ── FLASHLIGHT ───────────────────────────────────────────────────
            clean.contains("lanterna") ||
              clean.contains("flash")           -> handleFlash(clean)

            // ── VOLUME ───────────────────────────────────────────────────────
            clean.contains("volume")            -> handleVolume(clean)

            // ── BRIGHTNESS ───────────────────────────────────────────────────
            clean.contains("brilho")            -> handleBrightness(clean)

            // ── BATTERY ──────────────────────────────────────────────────────
            clean.contains("bateria")           -> showBattery()

            // ── ALARM / TIMER ─────────────────────────────────────────────────
            clean.contains("alarme")            -> handleAlarm(clean)
            clean.contains("temporizador") ||
              clean.contains("timer")           -> handleTimer(clean)
            clean.contains("cronômetro")        -> openClock()

            // ── NOTIFICATIONS ─────────────────────────────────────────────────
            clean.contains("notificações") ||
              clean.contains("notificacoes")    -> expandNotifications()

            // ── CALENDAR / REMINDER ───────────────────────────────────────────
            clean.contains("calendário") ||
              clean.contains("calendario") ||
              clean.contains("agenda")          -> openCalendar()
            clean.contains("lembrete")          -> handleReminder(clean)

            // ── SETTINGS ─────────────────────────────────────────────────────
            clean.contains("configurações") ||
              clean.contains("configuracoes") ||
              clean.contains("ajustes")         -> openSettings()
            clean.contains("modo avião") ||
              clean.contains("aviao")           -> openAirplaneSettings()
            clean.contains("dados móveis") ||
              clean.contains("dados moveis")    -> openMobileDataSettings()

            // ── MUSIC CONTROL ─────────────────────────────────────────────────
            clean.contains("pausar") ||
              clean.contains("parar música")    -> controlMedia(false)
            clean.contains("reproduzir") ||
              clean.contains("play") ||
              clean.contains("tocar")           -> controlMedia(true)
            clean.contains("próxima") &&
              clean.contains("música")          -> mediaNext()
            clean.contains("anterior") &&
              clean.contains("música")          -> mediaPrevious()

            // ── MODES ─────────────────────────────────────────────────────────
            clean.contains("modo dirigindo") ||
              clean.contains("modo carro")      -> activateDrivingMode()
            clean.contains("modo estudo")       -> activateStudyMode()
            clean.contains("modo dormir") ||
              clean.contains("modo sono")       -> activateSleepMode()
            clean.contains("modo trabalho")     -> activateWorkMode()
            clean.contains("modo casa")         -> activateHomeMode()

            // ── RECENT APPS ───────────────────────────────────────────────────
            clean.contains("apps recentes") ||
              clean.contains("multitarefa")     -> openRecentApps()

            // ── DARK MODE ─────────────────────────────────────────────────────
            clean.contains("modo noturno") ||
              clean.contains("dark mode")       -> toggleDarkMode(clean)

            // ── SCREEN OFF ───────────────────────────────────────────────────
            clean.contains("desligar tela") ||
              clean.contains("apagar tela")     -> speak("Pressione o botão de energia para desligar a tela.")

            // ── LOCATION ─────────────────────────────────────────────────────
            clean.contains("minha localização") ||
              clean.contains("onde estou")      -> shareLocation()

            // ── AI QUESTION (fallback) ────────────────────────────────────────
            else -> askAi(clean)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // APP ACTIONS
    // ─────────────────────────────────────────────────────────────────────────

    private fun openApp(packageName: String, label: String) {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        if (intent != null) {
            startActivity(intent)
            speak("Abrindo $label.")
            log("APP: $label aberto")
        } else {
            speak("$label não está instalado. Abrindo a Play Store.")
            val storeIntent = Intent(Intent.ACTION_VIEW,
                Uri.parse("market://details?id=$packageName"))
            startActivity(storeIntent)
        }
    }

    private fun handleWhatsApp(cmd: String) {
        // "abre o whatsapp" → open normally
        // "manda mensagem no whatsapp para X: texto" → deep link
        val forIndex = cmd.indexOf("para ")
        if (forIndex != -1) {
            val rest = cmd.substring(forIndex + 5).trim()
            // Try to find contact by name for phone number
            val number = findContactNumber(rest.substringBefore(":").trim())
            val msg    = if (rest.contains(":")) rest.substringAfter(":").trim() else ""
            if (number != null) {
                val uri = Uri.parse("https://api.whatsapp.com/send?phone=$number&text=${Uri.encode(msg)}")
                val i = Intent(Intent.ACTION_VIEW, uri)
                startActivity(i)
                speak("Abrindo WhatsApp para $rest.")
                log("WA: msg para $rest")
            } else {
                speak("Contato não encontrado. Abrindo o WhatsApp.")
                openApp("com.whatsapp", "WhatsApp")
            }
        } else {
            openApp("com.whatsapp", "WhatsApp")
        }
    }

    private fun handleInstagram(cmd: String) {
        val atIndex = cmd.indexOf("@")
        if (atIndex != -1) {
            val username = cmd.substring(atIndex + 1).trim().split(" ")[0]
            try {
                val uri = Uri.parse("http://instagram.com/_u/$username")
                val i = Intent(Intent.ACTION_VIEW, uri).apply { setPackage("com.instagram.android") }
                startActivity(i)
            } catch (e: Exception) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://instagram.com/$username")))
            }
            speak("Abrindo perfil @$username no Instagram.")
            log("IG: @$username")
        } else {
            openApp("com.instagram.android", "Instagram")
        }
    }

    private fun handleMaps(cmd: String) {
        val query = cmd.replace("abre", "").replace("google maps", "")
            .replace("maps", "").replace("no", "").trim()
        val uri = if (query.isNotEmpty())
            Uri.parse("geo:0,0?q=${Uri.encode(query)}")
        else
            Uri.parse("geo:0,0")
        startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage("com.google.android.apps.maps"))
        speak("Abrindo Maps${if (query.isNotEmpty()) " para $query" else ""}.")
        log("MAPS: $query")
    }

    private fun openCamera() {
        startActivity(Intent(MediaStore.ACTION_IMAGE_CAPTURE))
        speak("Abrindo câmera.")
        log("CAM: aberta")
    }

    private fun openGallery() {
        val i = Intent(Intent.ACTION_VIEW).apply {
            type = "image/*"
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(Intent.createChooser(i, "Galeria"))
        speak("Abrindo galeria.")
        log("GALERIA: aberta")
    }

    private fun openCalculator() {
        val intents = listOf(
            Intent().apply { setClassName("com.google.android.calculator", "com.android.calculator2.Calculator") },
            Intent().apply { action = Intent.ACTION_MAIN; addCategory(Intent.CATEGORY_APP_CALCULATOR) }
        )
        for (i in intents) {
            try { startActivity(i); speak("Abrindo calculadora."); return } catch (_: Exception) {}
        }
        speak("Calculadora não encontrada.")
    }

    private fun openFiles() {
        val i = Intent(Intent.ACTION_VIEW).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivity(Intent.createChooser(i, "Arquivos"))
        speak("Abrindo gerenciador de arquivos.")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CALLS & SMS
    // ─────────────────────────────────────────────────────────────────────────

    private fun handleCall(cmd: String) {
        val namePart = cmd.substringAfter("para ").trim().split(" ").take(3).joinToString(" ")
        val number   = findContactNumber(namePart)
        if (number != null) {
            if (hasPermission(Manifest.permission.CALL_PHONE)) {
                val i = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number"))
                startActivity(i)
                speak("Ligando para $namePart.")
                log("CALL: $namePart ($number)")
            } else {
                val i = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))
                startActivity(i)
                speak("Abrindo discador para $namePart.")
            }
        } else {
            // Try treating namePart as a number
            val digits = namePart.filter { it.isDigit() || it == '+' }
            if (digits.length >= 5) {
                startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$digits")))
                speak("Abrindo discador para $digits.")
            } else {
                speak("Contato '$namePart' não encontrado. Verifique o nome e tente novamente.")
            }
        }
    }

    private fun handleSms(cmd: String) {
        val namePart = cmd.substringAfter("para ").substringBefore(":").trim()
        val body     = if (cmd.contains(":")) cmd.substringAfter(":").trim() else ""
        val number   = findContactNumber(namePart)
        val uri      = if (number != null) Uri.parse("smsto:$number") else Uri.parse("smsto:")
        val i = Intent(Intent.ACTION_SENDTO, uri).apply {
            putExtra("sms_body", body)
        }
        startActivity(i)
        speak("Abrindo mensagem para $namePart.")
        log("SMS: para $namePart")
    }

    /** Looks up a contact phone number by display name. Returns null if not found or no permission. */
    @SuppressLint("Range")
    private fun findContactNumber(name: String): String? {
        if (!hasPermission(Manifest.permission.READ_CONTACTS)) return null
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
        )
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val cursor = contentResolver.query(uri, projection, selection, arrayOf("%$name%"), null)
        cursor?.use {
            if (it.moveToFirst()) {
                return it.getString(it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER))
                    ?.replace(" ", "")?.replace("-", "")
            }
        }
        return null
    }

    // ─────────────────────────────────────────────────────────────────────────
    // NETWORK: WIFI & BLUETOOTH
    // ─────────────────────────────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    private fun handleWifi(cmd: String) {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val turnOn = cmd.contains("ligar") || cmd.contains("ativar") || cmd.contains("conectar")
        val turnOff = cmd.contains("desligar") || cmd.contains("desativar") || cmd.contains("desconectar")

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // Direct API available for Android 9 and below
            when {
                turnOn  -> { wifiManager.isWifiEnabled = true;  speak("Wi-Fi ligado."); log("WIFI: ON") }
                turnOff -> { wifiManager.isWifiEnabled = false; speak("Wi-Fi desligado."); log("WIFI: OFF") }
                else    -> speak("Wi-Fi está ${if (wifiManager.isWifiEnabled) "ligado" else "desligado"}.")
            }
        } else {
            // Android 10+: direct toggle is blocked for third-party apps
            speak("No Android 10 ou superior, o controle direto de Wi-Fi foi bloqueado pelo sistema. Abrindo configurações de Wi-Fi.")
            startActivity(Intent(android.provider.Settings.ACTION_WIFI_SETTINGS))
            log("WIFI: Configurações abertas (Android 10+)")
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleBluetooth(cmd: String) {
        val btManager  = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val btAdapter  = btManager.adapter ?: run { speak("Bluetooth não suportado."); return }
        val turnOn     = cmd.contains("ligar") || cmd.contains("ativar")
        val turnOff    = cmd.contains("desligar") || cmd.contains("desativar")

        if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            speak("Permissão de Bluetooth necessária. Conceda nas configurações.")
            return
        }

        when {
            turnOn && !btAdapter.isEnabled  -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    @Suppress("DEPRECATION")
                    btAdapter.enable()
                    speak("Bluetooth ligado.")
                    log("BT: ON")
                } else {
                    startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS))
                    speak("Abra as configurações para ligar o Bluetooth.")
                }
            }
            turnOff && btAdapter.isEnabled -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    @Suppress("DEPRECATION")
                    btAdapter.disable()
                    speak("Bluetooth desligado.")
                    log("BT: OFF")
                } else {
                    startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS))
                    speak("Abra as configurações para desligar o Bluetooth.")
                }
            }
            else -> speak("Bluetooth está ${if (btAdapter.isEnabled) "ligado" else "desligado"}.")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // FLASHLIGHT
    // ─────────────────────────────────────────────────────────────────────────

    private fun setupCameraId() {
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        cameraId = manager.cameraIdList.firstOrNull { id ->
            manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
    }

    private fun handleFlash(cmd: String) {
        val turnOn  = cmd.contains("ligar") || cmd.contains("ativar") || cmd.contains("acender")
        val turnOff = cmd.contains("desligar") || cmd.contains("apagar")
        val id = cameraId ?: run { speak("Flash não disponível."); return }
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

        val target = when {
            turnOn  -> true
            turnOff -> false
            else    -> !flashOn  // toggle
        }

        try {
            manager.setTorchMode(id, target)
            flashOn = target
            val state = if (target) "ligada" else "desligada"
            speak("Lanterna $state.")
            log("FLASH: $state")
        } catch (e: Exception) {
            speak("Não foi possível controlar a lanterna.")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // VOLUME
    // ─────────────────────────────────────────────────────────────────────────

    private fun handleVolume(cmd: String) {
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val stream = when {
            cmd.contains("chamada") || cmd.contains("ligação") -> AudioManager.STREAM_RING
            cmd.contains("notificação") -> AudioManager.STREAM_NOTIFICATION
            else -> AudioManager.STREAM_MUSIC
        }
        val max  = audio.getStreamMaxVolume(stream)
        val curr = audio.getStreamVolume(stream)

        when {
            cmd.contains("máximo") || cmd.contains("100") ->
                { audio.setStreamVolume(stream, max, 0); speak("Volume no máximo.") }
            cmd.contains("mudo") || cmd.contains("silencioso") || cmd.contains("zero") ->
                { audio.setStreamVolume(stream, 0, 0); speak("Volume zerado.") }
            cmd.contains("aumentar") || cmd.contains("mais") ->
                { audio.setStreamVolume(stream, minOf(curr + 2, max), 0); speak("Volume aumentado.") }
            cmd.contains("diminuir") || cmd.contains("menos") ->
                { audio.setStreamVolume(stream, maxOf(curr - 2, 0), 0); speak("Volume reduzido.") }
            else -> speak("Volume atual: ${(curr * 100 / max)}%.")
        }
        log("VOL: stream $stream → ${audio.getStreamVolume(stream)}")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BRIGHTNESS
    // ─────────────────────────────────────────────────────────────────────────

    private fun handleBrightness(cmd: String) {
        // WRITE_SETTINGS requires explicit user grant via Settings > Apps > Special access
        if (!android.provider.Settings.System.canWrite(this)) {
            speak("Para controlar o brilho, conceda permissão de modificar configurações. Abrindo.")
            val i = Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS,
                Uri.parse("package:$packageName"))
            startActivity(i)
            return
        }
        val value = when {
            cmd.contains("máximo") || cmd.contains("100") -> 255
            cmd.contains("mínimo") || cmd.contains("baixo") -> 20
            cmd.contains("médio") || cmd.contains("metade") -> 128
            cmd.contains("automático") || cmd.contains("auto") -> -1
            else -> null
        }
        if (value == null) { speak("Diga: brilho máximo, mínimo, médio ou automático."); return }
        if (value == -1) {
            android.provider.Settings.System.putInt(contentResolver,
                android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE,
                android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
            speak("Brilho automático ativado.")
        } else {
            android.provider.Settings.System.putInt(contentResolver,
                android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE,
                android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            android.provider.Settings.System.putInt(contentResolver,
                android.provider.Settings.System.SCREEN_BRIGHTNESS, value)
            val lp = window.attributes.apply { screenBrightness = value / 255f }
            window.attributes = lp
            speak("Brilho ajustado.")
        }
        log("BRILHO: $value")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BATTERY
    // ─────────────────────────────────────────────────────────────────────────

    private fun showBattery() {
        val intent = Intent(Intent.ACTION_BATTERY_CHANGED)
        val status = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level  = status?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale  = status?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val pct    = if (scale > 0) (level * 100 / scale) else -1
        val plugged = status?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: 0
        val charging = plugged != 0

        val msg = if (pct >= 0)
            "Bateria em $pct%${if (charging) ", carregando" else ""}."
        else "Não foi possível ler a bateria."

        speak(msg)
        log("BAT: $pct%")

        if (pct in 1..20 && !charging) {
            speak("Bateria baixa. Considere ativar o modo de economia de bateria.")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ALARM & TIMER
    // ─────────────────────────────────────────────────────────────────────────

    private fun handleAlarm(cmd: String) {
        // Extract time from command, e.g. "alarme para 7 horas" or "alarme às 22:30"
        val hourRegex = Regex("(\\d{1,2})(?::(\\d{2}))? ?(hora|h\\b)")
        val match = hourRegex.find(cmd)
        if (match != null) {
            val hour   = match.groupValues[1].toInt()
            val minute = match.groupValues[2].toIntOrNull() ?: 0
            val i = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                putExtra(AlarmClock.EXTRA_MESSAGE, "Menny Alarm")
                putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            }
            startActivity(i)
            speak("Alarme configurado para ${hour}h${if (minute > 0) minute.toString() else ""}.")
            log("ALARM: ${hour}:${minute.toString().padStart(2,'0')}")
        } else {
            openClock()
            speak("Abrindo o relógio para você configurar o alarme.")
        }
    }

    private fun handleTimer(cmd: String) {
        val secRegex = Regex("(\\d+) ?(segundo|seg)")
        val minRegex = Regex("(\\d+) ?(minuto|min)")
        val hours    = Regex("(\\d+) ?(hora|h\\b)").find(cmd)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val minutes  = minRegex.find(cmd)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val seconds  = secRegex.find(cmd)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val total    = hours * 3600 + minutes * 60 + seconds

        if (total > 0) {
            val i = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, total)
                putExtra(AlarmClock.EXTRA_MESSAGE, "Menny Timer")
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            }
            startActivity(i)
            speak("Temporizador de ${if (hours > 0) "${hours}h " else ""}${if (minutes > 0) "${minutes}min " else ""}${if (seconds > 0) "${seconds}s" else ""} iniciado.")
            log("TIMER: ${total}s")
        } else {
            speak("Diga a duração. Ex: 'temporizador de 5 minutos'.")
        }
    }

    private fun openClock() {
        val i = Intent(AlarmClock.ACTION_SHOW_ALARMS)
        startActivity(i)
        speak("Abrindo o relógio.")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CALENDAR & REMINDER
    // ─────────────────────────────────────────────────────────────────────────

    private fun openCalendar() {
        val uri = CalendarContract.CONTENT_URI.buildUpon()
            .appendPath("time").build()
        val i = Intent(Intent.ACTION_VIEW, uri)
        startActivity(i)
        speak("Abrindo calendário.")
        log("CAL: aberto")
    }

    private fun handleReminder(cmd: String) {
        val title = cmd.substringAfter("lembrete").trim().removePrefix("de").trim()
            .removePrefix("para").trim().ifEmpty { "Menny Lembrete" }
        val i = Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI).apply {
            putExtra(CalendarContract.Events.TITLE, title)
            putExtra(CalendarContract.Events.DESCRIPTION, "Criado por Menny")
        }
        startActivity(i)
        speak("Criando lembrete: $title.")
        log("REMINDER: $title")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SETTINGS & SYSTEM
    // ─────────────────────────────────────────────────────────────────────────

    private fun openSettings() {
        startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
        speak("Abrindo configurações.")
    }

    private fun openAirplaneSettings() {
        // NOTE: Direct airplane mode toggle is blocked on Android 4.2+ without root.
        speak("O modo avião não pode ser ativado diretamente. Abrindo as configurações rápidas.")
        startActivity(Intent(android.provider.Settings.ACTION_AIRPLANE_MODE_SETTINGS))
        log("AVIÃO: config aberta")
    }

    private fun openMobileDataSettings() {
        speak("Abrindo configurações de dados móveis.")
        try {
            startActivity(Intent(android.provider.Settings.ACTION_DATA_ROAMING_SETTINGS))
        } catch (_: Exception) {
            startActivity(Intent(android.provider.Settings.ACTION_NETWORK_OPERATOR_SETTINGS))
        }
        log("DADOS: config aberta")
    }

    @SuppressLint("WrongConstant")
    private fun expandNotifications() {
        try {
            val service = getSystemService("statusbar")
            val cls     = Class.forName("android.app.StatusBarManager")
            val expand  = cls.getMethod("expandNotificationsPanel")
            expand.invoke(service)
            speak("Notificações abertas.")
            log("NOTIF: painel aberto")
        } catch (e: Exception) {
            speak("Não foi possível abrir o painel de notificações diretamente.")
        }
    }

    @SuppressLint("WrongConstant")
    private fun openRecentApps() {
        try {
            val service = getSystemService("statusbar")
            val cls     = Class.forName("android.app.StatusBarManager")
            val recent  = cls.getMethod("toggleRecentApps")
            recent.invoke(service)
            log("RECENT: aberto")
        } catch (_: Exception) {
            speak("Não foi possível abrir apps recentes via API.")
        }
    }

    private fun toggleDarkMode(cmd: String) {
        val mode = if (cmd.contains("ligar") || cmd.contains("ativar"))
            android.app.UiModeManager.MODE_NIGHT_YES
        else if (cmd.contains("desligar") || cmd.contains("desativar"))
            android.app.UiModeManager.MODE_NIGHT_NO
        else android.app.UiModeManager.MODE_NIGHT_YES

        val uiManager = getSystemService(Context.UI_MODE_SERVICE) as android.app.UiModeManager
        uiManager.nightMode = mode
        speak("Modo noturno ${if (mode == android.app.UiModeManager.MODE_NIGHT_YES) "ativado" else "desativado"}.")
        log("DARK: $mode")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MEDIA CONTROL
    // ─────────────────────────────────────────────────────────────────────────

    private fun controlMedia(play: Boolean) {
        val keyCode = if (play) android.view.KeyEvent.KEYCODE_MEDIA_PLAY
                      else     android.view.KeyEvent.KEYCODE_MEDIA_PAUSE
        sendMediaKey(keyCode)
        speak(if (play) "Reproduzindo música." else "Música pausada.")
        log("MEDIA: ${if (play) "PLAY" else "PAUSE"}")
    }

    private fun mediaNext()     { sendMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_NEXT);     speak("Próxima música.") }
    private fun mediaPrevious() { sendMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS); speak("Música anterior.") }

    private fun sendMediaKey(keyCode: Int) {
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audio.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, keyCode))
        audio.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, keyCode))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // LOCATION
    // ─────────────────────────────────────────────────────────────────────────

    private fun shareLocation() {
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            speak("Permissão de localização necessária.")
            return
        }
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=minha+localização"))
        startActivity(i)
        speak("Abrindo sua localização no Maps.")
        log("LOC: Maps aberto")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SMART MODES
    // ─────────────────────────────────────────────────────────────────────────

    private fun activateDrivingMode() {
        speak("Ativando modo dirigindo. Abrindo GPS, Bluetooth e música.")
        log("MODE: Dirigindo")
        // Open Maps for navigation
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=meu+destino"))
            .setPackage("com.google.android.apps.maps"))
        // Try to enable BT
        handleBluetooth("ligar bluetooth")
        // Play media
        controlMedia(true)
        // Set ring volume low to avoid distraction
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audio.setStreamVolume(AudioManager.STREAM_RING, 2, 0)
    }

    private fun activateStudyMode() {
        speak("Ativando modo estudo. Silenciando notificações e reduzindo brilho.")
        log("MODE: Estudo")
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audio.ringerMode = AudioManager.RINGER_MODE_SILENT
        handleBrightness("brilho médio")
        controlMedia(false)
    }

    private fun activateSleepMode() {
        speak("Ativando modo sono. Boa noite.")
        log("MODE: Sono")
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audio.ringerMode = AudioManager.RINGER_MODE_SILENT
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        toggleDarkMode("ligar modo noturno")
        handleBrightness("brilho mínimo")
        // Suggest airplane mode since we can't set it directly
        speak("Para economizar bateria, ative o modo avião nas configurações rápidas.")
    }

    private fun activateWorkMode() {
        speak("Ativando modo trabalho.")
        log("MODE: Trabalho")
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audio.ringerMode = AudioManager.RINGER_MODE_VIBRATE
        handleBrightness("brilho médio")
    }

    private fun activateHomeMode() {
        speak("Ativando modo casa.")
        log("MODE: Casa")
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audio.ringerMode = AudioManager.RINGER_MODE_NORMAL
        audio.setStreamVolume(AudioManager.STREAM_MUSIC,
            audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) / 2, 0)
        handleBrightness("brilho médio")
        controlMedia(true)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // AI — CLAUDE / OPENAI
    // ─────────────────────────────────────────────────────────────────────────

    private fun askAi(question: String) {
        if (AI_API_KEY == "YOUR_CLAUDE_OR_OPENAI_API_KEY_HERE") {
            speak("Configure sua chave de API de IA no arquivo MainActivity.kt para responder perguntas.")
            return
        }

        speak("Deixa eu verificar isso para você...")
        log("AI: Consultando…")

        scope.launch {
            val answer = withContext(Dispatchers.IO) {
                callClaudeApi(question)
            }
            speak(answer)
            log("AI: Resposta recebida")
        }
    }

    /**
     * Calls the Anthropic Claude API (claude-sonnet-4-20250514).
     * Switch to OpenAI by changing the endpoint and JSON body format.
     */
    private fun callClaudeApi(question: String): String {
        // Usando Groq (formato compatível com OpenAI)
        return try {
            val url = URL(AI_ENDPOINT)
            val conn = url.openConnection() as HttpURLConnection
            conn.apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $AI_API_KEY")
                doOutput = true
                connectTimeout = 15_000
                readTimeout    = 30_000
            }

            val body = JSONObject().apply {
                put("model", AI_MODEL)
                put("max_tokens", 512)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", "Você é Menny, um assistente pessoal de voz para Android. Responda de forma clara, direta e concisa em português brasileiro. Máximo de 3 frases.")
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", question)
                    })
                })
            }

            OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val response = BufferedReader(InputStreamReader(stream)).readText()

            val json = JSONObject(response)
            if (code in 200..299) {
                json.getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
            } else {
                "Erro da API: ${json.optString("error", response)}"
            }
        } catch (e: Exception) {
            Log.e(TAG, "AI error", e)
            "Não consegui conectar à IA. Verifique sua conexão com a internet."
        }
    }

    /*
     * ── OPENAI ALTERNATIVE ────────────────────────────────────────────────────
     * If you prefer OpenAI, replace callClaudeApi with:
     *
     * private fun callOpenAiApi(question: String): String {
     *     val url  = URL("https://api.openai.com/v1/chat/completions")
     *     val conn = (url.openConnection() as HttpURLConnection).apply {
     *         requestMethod = "POST"
     *         setRequestProperty("Content-Type", "application/json")
     *         setRequestProperty("Authorization", "Bearer $AI_API_KEY")
     *         doOutput = true
     *     }
     *     val body = JSONObject().apply {
     *         put("model", "gpt-4o-mini")
     *         put("messages", JSONArray().apply {
     *             put(JSONObject().apply { put("role","system"); put("content","Você é Menny, assistente pessoal. Responda em português, máximo 3 frases.") })
     *             put(JSONObject().apply { put("role","user");   put("content", question) })
     *         })
     *         put("max_tokens", 512)
     *     }
     *     OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
     *     val resp = BufferedReader(InputStreamReader(conn.inputStream)).readText()
     *     return JSONObject(resp).getJSONArray("choices").getJSONObject(0)
     *         .getJSONObject("message").getString("content")
     * }
     */

    // ─────────────────────────────────────────────────────────────────────────
    // UI HELPERS
    // ─────────────────────────────────────────────────────────────────────────

    private fun showResponse(text: String) {
        runOnUiThread {
            tvResponse.text = text
            scrollResponse.post { scrollResponse.fullScroll(android.view.View.FOCUS_DOWN) }
        }
    }

    private fun setLastCommand(cmd: String) {
        runOnUiThread {
            tvLastCommand.text = "> $cmd"
            tvLastCommand.visibility = android.view.View.VISIBLE
        }
    }

    private fun setStatus(text: String) {
        runOnUiThread {
            tvStatus.text = text
            tvStatus.setTextColor(
                if (text.contains("Ouv")) 0xFF4CAF50.toInt()  // green when listening
                else 0xFF555555.toInt()
            )
        }
    }

    private fun log(entry: String) {
        runOnUiThread {
            val current = tvActionLog.text.toString()
            val lines   = current.split("\n").takeLast(4).filter { it != "—" }
            tvActionLog.text = (lines + entry).joinToString("\n")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // LIFECYCLE
    // ─────────────────────────────────────────────────────────────────────────

    override fun onDestroy() {
        super.onDestroy()
        if (::tts.isInitialized)              { tts.stop(); tts.shutdown() }
        if (::speechRecognizer.isInitialized) { speechRecognizer.destroy() }
        scope.cancel()
    }
}


// ─────────────────────────────────────────────────────────────────────────────
// NOTIFICATION LISTENER SERVICE
// Allows Menny to read incoming notifications (WhatsApp, etc.)
// The user must grant this manually: Settings > Apps > Special app access > Notification access
// ─────────────────────────────────────────────────────────────────────────────
class MennyNotificationListener : android.service.notification.NotificationListenerService() {

    companion object {
        var lastNotification: String = ""
    }

    override fun onNotificationPosted(sbn: android.service.notification.StatusBarNotification?) {
        val pkg   = sbn?.packageName ?: return
        val extras = sbn.notification?.extras ?: return
        val title  = extras.getString(android.app.Notification.EXTRA_TITLE) ?: ""
        val text   = extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString() ?: ""

        // Store the last notification for querying
        lastNotification = "[$pkg] $title: $text"
        Log.d("MennyNotif", lastNotification)
    }

    override fun onNotificationRemoved(sbn: android.service.notification.StatusBarNotification?) {}
}


// ─────────────────────────────────────────────────────────────────────────────
// FOREGROUND SERVICE — continuous microphone listening (optional)
// Keeps Menny alive in background for hotword detection.
// Uncomment body and integrate with STT in MainActivity to enable.
// ─────────────────────────────────────────────────────────────────────────────
class MennyVoiceService : android.app.Service() {

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = android.app.Notification.Builder(this,
            createNotificationChannel())
            .setContentTitle("Menny")
            .setContentText("Escutando em segundo plano…")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
        startForeground(1, notification)
        // TODO: integrate SpeechRecognizer here for always-on hotword detection
        return START_STICKY
    }

    private fun createNotificationChannel(): String {
        val channelId = "menny_bg"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = android.app.NotificationChannel(channelId, "Menny Background",
                android.app.NotificationManager.IMPORTANCE_LOW)
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.createNotificationChannel(ch)
        }
        return channelId
    }

    override fun onDestroy() { super.onDestroy(); stopForeground(true) }
}

/*
 * ─────────────────────────────────────────────────────────────────────────────
 * TASKER / MACRODROID INTEGRATION NOTE
 * ─────────────────────────────────────────────────────────────────────────────
 * Some actions require deeper automation (e.g., sending a WhatsApp message
 * without user interaction, clicking buttons in other apps). For these:
 *
 * 1. TASKER (https://tasker.joaoapps.com):
 *    - Create a Profile triggered by the Intent:
 *        Action: com.menny.assistant.TASKER_ACTION
 *        Extra:  menny_cmd (string) = "whatsapp_send_msg"
 *        Extra:  menny_to  (string) = "+5511999999999"
 *        Extra:  menny_msg (string) = "Olá!"
 *    - In the Task, use "AutoInput" plugin to automate the WhatsApp UI.
 *
 * 2. MACRODROID:
 *    - Trigger: Intent received (same as above)
 *    - Action: Send Intent to open WhatsApp deep link, then simulate UI.
 *
 * To send the intent from MainActivity, add:
 *    val i = Intent("com.menny.assistant.TASKER_ACTION").apply {
 *        putExtra("menny_cmd", "whatsapp_send_msg")
 *        putExtra("menny_to", number)
 *        putExtra("menny_msg", message)
 *    }
 *    sendBroadcast(i)
 * ─────────────────────────────────────────────────────────────────────────────
 */
