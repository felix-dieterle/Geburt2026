package com.geburt2026.app

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.speech.RecognizerIntent
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.content.FileProvider
import com.geburt2026.app.databinding.ActivityMainBinding
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val handler = Handler(Looper.getMainLooper())

    // Callback set before launching the contact picker; receives (displayName, phoneNumber)
    private var pendingContactPickCallback: ((String, String) -> Unit)? = null

    private val requestContactPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            pickContactLauncher.launch(null)
        } else {
            pendingContactPickCallback = null
            Toast.makeText(this, "Kontakte-Berechtigung verweigert", Toast.LENGTH_SHORT).show()
        }
    }

    private val pickContactLauncher = registerForActivityResult(
        ActivityResultContracts.PickContact()
    ) { uri ->
        val callback = pendingContactPickCallback
        pendingContactPickCallback = null
        uri ?: return@registerForActivityResult
        val name = getContactDisplayName(uri)
        val number = getContactPhoneNumber(uri)
        callback?.invoke(name, number)
    }

    private val contactsImportLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { importContacts(it) }
    }

    private val betreuungImportLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { importBetreuung(it) }
    }

    private val importAllConfigLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { importAllConfig(it) }
    }

    private val requestCameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchCamera() else Toast.makeText(this, "Kamera-Berechtigung verweigert", Toast.LENGTH_SHORT).show()
    }

    private val takePhotoLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && currentCameraPhotoPath.isNotEmpty()) {
            photoPaths.add(currentCameraPhotoPath)
            savePhotoPaths()
            renderPhotos()
            currentCameraPhotoPath = ""
        }
    }

    private val pickPhotoLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        uris.forEach { copyPhotoToInternalStorage(it) }
    }

    // ── Audio recording fields ─────────────────────────────────────────────────

    private var mediaRecorder: MediaRecorder? = null
    private var currentMediaPlayer: MediaPlayer? = null
    private var currentRecordingId: Long = 0L
    private var currentAudioFilePath: String = ""
    private var isRecording: Boolean = false

    private val requestAudioPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startAudioRecording()
        else Toast.makeText(this, "Mikrofon-Berechtigung verweigert", Toast.LENGTH_SHORT).show()
    }

    private val speechRecognitionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val transcription = if (result.resultCode == RESULT_OK) {
            result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull() ?: ""
        } else ""
        saveAudioNotiz(currentRecordingId, currentAudioFilePath, transcription)
        currentRecordingId = 0L
        currentAudioFilePath = ""
        renderAudioNotizen()
    }

    // Blasensprung: 22.02.2026 um 6:15 Uhr (editable, persisted)
    private val blasensprungDefault: Long = Calendar.getInstance().apply {
        set(2026, Calendar.FEBRUARY, 22, 6, 15, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    private var blasensprungTime: Long = blasensprungDefault

    // Errechneter Geburtstermin – loaded from settings, default 08.03.2026
    private val dueDateCalendar: Calendar = Calendar.getInstance().apply {
        set(2026, Calendar.MARCH, 8, 0, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }

    // Configurable warning thresholds (loaded from settings, defaults below)
    private var blasensprungWarnOrangeH: Int = 18
    private var blasensprungWarnRedH: Int = 24
    private var einleitungWarnOrangeH: Int = EINLEITUNG_WARN_ORANGE_H_DEFAULT
    private var einleitungWarnRedH: Int = EINLEITUNG_WARN_RED_H_DEFAULT
    private var wehenUnregWarnOrangeH: Int = WEHEN_UNREG_WARN_ORANGE_H_DEFAULT
    private var wehenUnregWarnRedH: Int = WEHEN_UNREG_WARN_RED_H_DEFAULT
    private var wehenRegWarnOrangeH: Int = WEHEN_REG_WARN_ORANGE_H_DEFAULT
    private var wehenRegWarnRedH: Int = WEHEN_REG_WARN_RED_H_DEFAULT
    private var hospitalCallPhone: String = DEFAULT_HOSPITAL_CALL_PHONE
    private var opnvUrl: String = DEFAULT_OPNV_URL

    // Milestone timer timestamps (0 = not started)
    private var einleitungStartTime: Long = 0L
    private var wehenUnregelStartTime: Long = 0L
    private var wehenRegelStartTime: Long = 0L
    // Milestone timer stop timestamps (0 = not stopped)
    private var einleitungStopTime: Long = 0L
    private var wehenUnregelStopTime: Long = 0L
    private var wehenRegelStopTime: Long = 0L

    // Custom (user-defined) timers
    private data class CustomTimer(
        val id: Long,
        var label: String,
        var startTime: Long,
        var stopTime: Long = 0L,
        var hasCounter: Boolean = true,
        var comment: String = ""
    )
    private val customTimers = mutableListOf<CustomTimer>()

    // Editable notes list (in-memory backing)
    private val notizenItems = mutableListOf<Pair<Long, String>>()

    // Editable medical hints list (in-memory backing)
    private val medicalItems = mutableListOf<Pair<Long, String>>()

    // Photos list (absolute file paths stored in app-internal storage)
    private val photoPaths = mutableListOf<String>()
    private var currentCameraPhotoPath: String = ""

    // Birth date & time (recorded when baby is born)
    private var geburtszeit: Long = 0L

    private val timerRunnable = object : Runnable {
        override fun run() {
            updateBirthTimer()
            updateMilestoneTimers()
            handler.postDelayed(this, 1000)
        }
    }

    private val geburtswuensche = mutableListOf<String>()

    private val geburtPhasen: List<GeburtPhase> by lazy {
        listOf(
            GeburtPhase("🌅", "Latenzphase", "Unregelmäßige Wehen – Vorbereitung & Abwarten") { b ->
                listOf(b.cardTimer, b.cardMilestones, b.cardLabor, b.cardNotes, b.cardKids, b.cardBetreuung, b.cardChecklist, b.cardContacts, b.cardAudioNotizen, b.cardEckdaten)
            },
            GeburtPhase("🌊", "Eröffnungsphase", "Regelmäßige Wehen – Gebärmutterhals öffnet sich") { b ->
                listOf(b.cardTimer, b.cardMilestones, b.cardMedical, b.cardWishes, b.cardLabor, b.cardHospital, b.cardContacts, b.cardAudioNotizen)
            },
            GeburtPhase("⚡", "Übergangsphase", "Intensive Wehen – kurze Pausen, Fokus halten") { b ->
                listOf(b.cardTimer, b.cardMilestones, b.cardMedical, b.cardWishes, b.cardHospital, b.cardContacts, b.cardAudioNotizen)
            },
            GeburtPhase("💪", "Austreibungsphase", "Pressen – Baby kommt!") { b ->
                listOf(b.cardTimer, b.cardMedical, b.cardWishes, b.cardHospital, b.cardContacts, b.cardAudioNotizen, b.cardEckdaten)
            },
            GeburtPhase("🍼", "Nachgeburtsphase", "Hep-B-Impfung, erste Stunden, Nachgeburt") { b ->
                listOf(b.cardMedical, b.cardChecklist, b.cardContacts, b.cardAudioNotizen, b.cardEckdaten)
            },
            GeburtPhase("🏠", "Erste Tage Zuhause", "Ankommen – Wochenbett & Familie") { b ->
                listOf(b.cardEckdaten, b.cardKids, b.cardBetreuung, b.cardChecklist, b.cardContacts, b.cardNotes, b.cardAudioNotizen)
            },
        )
    }

    private var currentPhaseIndex: Int = 0

    private val defaultTasks = listOf(
        "Hebamme / KH Konstanz über Hep-B-Status informiert?",
        "Kinderkleidung (4 u. 7 J.) für mind. 4 Tage gepackt?",
        "Essen für Kinder bei Oma/Opa in Sipplinen organisiert?",
        "Transport Kinder nach Hause (ohne Opas Auto) geplant?",
        "Hep-B-Impfung für Neugeborenes angemeldet?",
        "Wichtige Dokumente im Krankenhaus dabei?",
        "Kindergarten/Schule über Abwesenheit informiert?",
        "Verwandte/Freunde für Unterstützung kontaktiert?",
        "Krankenhaustasche vollständig gepackt?",
        "Hörtest für Neugeborenes vor Entlassung?",
        "Notfallkontakte auf dem Handy gespeichert?",
    )

    private val tasks = mutableListOf<Task>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadSettings()
        setupBirthInfo()
        setupGeburtszeit()
        setupMilestoneTimers()
        setupMedicalInfo()
        setupGeburtsWuensche()
        setupWehenfoerderung()
        setupNotizen()
        setupKinderInfo()
        setupBetreuung()
        setupHospitalInfo()
        tasks.addAll(loadTasks())
        setupChecklist()
        setupContacts()
        setupEckdaten()
        setupSearch()
        setupAudioNotizen()
        setupPhasen()
        setupEinstellungen()
    }

    override fun onResume() {
        super.onResume()
        handler.post(timerRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(timerRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaRecorder?.release()
        mediaRecorder = null
        currentMediaPlayer?.release()
        currentMediaPlayer = null
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences(PREFS_SETTINGS, MODE_PRIVATE)
        // Due date
        val defaultDueDateMillis = Calendar.getInstance().apply {
            set(DEFAULT_DUE_DATE_YEAR, DEFAULT_DUE_DATE_MONTH, DEFAULT_DUE_DATE_DAY, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        dueDateCalendar.timeInMillis = prefs.getLong("due_date_millis", defaultDueDateMillis)
        // Warning thresholds
        blasensprungWarnOrangeH = prefs.getInt("blasensprung_warn_orange_h", 18)
        blasensprungWarnRedH = prefs.getInt("blasensprung_warn_red_h", 24)
        einleitungWarnOrangeH = prefs.getInt("einleitung_warn_orange_h", EINLEITUNG_WARN_ORANGE_H_DEFAULT)
        einleitungWarnRedH = prefs.getInt("einleitung_warn_red_h", EINLEITUNG_WARN_RED_H_DEFAULT)
        wehenUnregWarnOrangeH = prefs.getInt("wehen_unreg_warn_orange_h", WEHEN_UNREG_WARN_ORANGE_H_DEFAULT)
        wehenUnregWarnRedH = prefs.getInt("wehen_unreg_warn_red_h", WEHEN_UNREG_WARN_RED_H_DEFAULT)
        wehenRegWarnOrangeH = prefs.getInt("wehen_reg_warn_orange_h", WEHEN_REG_WARN_ORANGE_H_DEFAULT)
        wehenRegWarnRedH = prefs.getInt("wehen_reg_warn_red_h", WEHEN_REG_WARN_RED_H_DEFAULT)
        // Contact / navigation
        hospitalCallPhone = prefs.getString("hospital_call_phone", DEFAULT_HOSPITAL_CALL_PHONE) ?: DEFAULT_HOSPITAL_CALL_PHONE
        opnvUrl = prefs.getString("opnv_url", DEFAULT_OPNV_URL) ?: DEFAULT_OPNV_URL
    }

    private fun saveSettings() {
        getSharedPreferences(PREFS_SETTINGS, MODE_PRIVATE).edit()
            .putLong("due_date_millis", dueDateCalendar.timeInMillis)
            .putInt("blasensprung_warn_orange_h", blasensprungWarnOrangeH)
            .putInt("blasensprung_warn_red_h", blasensprungWarnRedH)
            .putInt("einleitung_warn_orange_h", einleitungWarnOrangeH)
            .putInt("einleitung_warn_red_h", einleitungWarnRedH)
            .putInt("wehen_unreg_warn_orange_h", wehenUnregWarnOrangeH)
            .putInt("wehen_unreg_warn_red_h", wehenUnregWarnRedH)
            .putInt("wehen_reg_warn_orange_h", wehenRegWarnOrangeH)
            .putInt("wehen_reg_warn_red_h", wehenRegWarnRedH)
            .putString("hospital_call_phone", hospitalCallPhone)
            .putString("opnv_url", opnvUrl)
            .apply()
    }

    private fun updateBirthTimer() {
        val now = System.currentTimeMillis()
        val elapsed = now - blasensprungTime
        if (elapsed < 0) return

        val hours = TimeUnit.MILLISECONDS.toHours(elapsed)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(elapsed) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(elapsed) % 60

        binding.tvElapsedTime.text = String.format(
            Locale.GERMAN, "%02d:%02d:%02d", hours, minutes, seconds
        )

        // Medizinischer Hinweis: Blasensprung-Warnungen
        when {
            hours >= blasensprungWarnRedH -> {
                binding.tvTimerWarning.visibility = View.VISIBLE
                binding.tvTimerWarning.text = "⚠️ >${blasensprungWarnRedH}h: Erhöhtes Infektionsrisiko – Arzt informieren!"
                binding.tvTimerWarning.setTextColor(getColor(R.color.warning_red))
            }
            hours >= blasensprungWarnOrangeH -> {
                binding.tvTimerWarning.visibility = View.VISIBLE
                binding.tvTimerWarning.text = "⚠️ >${blasensprungWarnOrangeH}h: Arzt über Blasensprung-Dauer informieren"
                binding.tvTimerWarning.setTextColor(getColor(R.color.warning_orange))
            }
            else -> {
                binding.tvTimerWarning.visibility = View.GONE
            }
        }
    }

    private fun setupBirthInfo() {
        val blasensprungPrefs = getSharedPreferences(PREFS_BLASENSPRUNG, MODE_PRIVATE)
        blasensprungTime = blasensprungPrefs.getLong("timestamp", blasensprungDefault)

        val sdfDateTime = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.GERMAN)
        val sdfDate = SimpleDateFormat("dd.MM.yyyy", Locale.GERMAN)
        binding.tvBlasensprungTime.text = sdfDateTime.format(Date(blasensprungTime))
        binding.tvDueDate.text = sdfDate.format(dueDateCalendar.time)

        // Schwangerschaftswoche berechnen (ET 08.03.2026 → SSW rückrechnen)
        // Naegele: ET = LMP + 280 Tage → LMP = ET - 280
        val lmpCal = dueDateCalendar.clone() as Calendar
        lmpCal.add(Calendar.DAY_OF_YEAR, -280)
        val weeksDiff = TimeUnit.MILLISECONDS.toDays(
            blasensprungTime - lmpCal.timeInMillis
        ) / 7
        val daysDiff = TimeUnit.MILLISECONDS.toDays(
            blasensprungTime - lmpCal.timeInMillis
        ) % 7
        binding.tvSsw.text = "SSW ${weeksDiff}+${daysDiff} (Frühgeburt)"

        binding.tvBlasensprungTime.setOnClickListener {
            val initial = Calendar.getInstance().apply { timeInMillis = blasensprungTime }
            showDateTimePicker(initial) { cal ->
                blasensprungTime = cal.timeInMillis
                blasensprungPrefs.edit().putLong("timestamp", blasensprungTime).apply()
                binding.tvBlasensprungTime.text = sdfDateTime.format(Date(blasensprungTime))
            }
        }
    }

    private fun setupGeburtszeit() {
        val prefs = getSharedPreferences("geburtszeit", MODE_PRIVATE)
        geburtszeit = prefs.getLong("timestamp", 0L)
        updateGeburtszeitDisplay()
        binding.btnSetGeburtszeit.setOnClickListener {
            val options = arrayOf("Datum & Uhrzeit wählen", "Zurücksetzen")
            AlertDialog.Builder(this)
                .setTitle("👶 Geburtszeit eintragen")
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> {
                            val initial = if (geburtszeit > 0L) {
                                Calendar.getInstance().apply { timeInMillis = geburtszeit }
                            } else {
                                Calendar.getInstance()
                            }
                            showDateTimePicker(initial) { cal ->
                                geburtszeit = cal.timeInMillis
                                prefs.edit().putLong("timestamp", geburtszeit).apply()
                                updateGeburtszeitDisplay()
                            }
                        }
                        1 -> {
                            geburtszeit = 0L
                            prefs.edit().putLong("timestamp", 0L).apply()
                            updateGeburtszeitDisplay()
                        }
                    }
                }
                .show()
        }
    }

    private fun updateGeburtszeitDisplay() {
        if (geburtszeit > 0L) {
            val sdf = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.GERMAN)
            binding.tvGeburtszeit.text = sdf.format(Date(geburtszeit))
        } else {
            binding.tvGeburtszeit.text = "–"
        }
    }

    private fun setupMilestoneTimers() {
        val prefs = getSharedPreferences("milestones", MODE_PRIVATE)
        einleitungStartTime = prefs.getLong("einleitung", 0L)
        wehenUnregelStartTime = prefs.getLong("wehen_unregelmaessig", 0L)
        wehenRegelStartTime = prefs.getLong("wehen_regelmaessig", 0L)
        einleitungStopTime = prefs.getLong("einleitung_stop", 0L)
        wehenUnregelStopTime = prefs.getLong("wehen_unregelmaessig_stop", 0L)
        wehenRegelStopTime = prefs.getLong("wehen_regelmaessig_stop", 0L)

        updateMilestoneTimerRow(
            einleitungStartTime,
            binding.tvEinleitungTime,
            binding.tvEinleitungElapsed,
            binding.tvEinleitungWarning,
            stopTime = einleitungStopTime,
            warnOrangeHours = einleitungWarnOrangeH, warnOrangeText = ">${einleitungWarnOrangeH}h: Arzt über Einleitungsdauer informieren",
            warnRedHours = einleitungWarnRedH, warnRedText = ">${einleitungWarnRedH}h: Dringend Arzt kontaktieren!"
        )
        updateMilestoneTimerRow(
            wehenUnregelStartTime,
            binding.tvWehenUnregelTime,
            binding.tvWehenUnregelElapsed,
            binding.tvWehenUnregelWarning,
            stopTime = wehenUnregelStopTime,
            warnOrangeHours = wehenUnregWarnOrangeH, warnOrangeText = ">${wehenUnregWarnOrangeH}h: Hebamme informieren",
            warnRedHours = wehenUnregWarnRedH, warnRedText = ">${wehenUnregWarnRedH}h: Arzt / KH kontaktieren!"
        )
        updateMilestoneTimerRow(
            wehenRegelStartTime,
            binding.tvWehenRegelTime,
            binding.tvWehenRegelElapsed,
            binding.tvWehenRegelWarning,
            stopTime = wehenRegelStopTime,
            warnOrangeHours = wehenRegWarnOrangeH, warnOrangeText = ">${wehenRegWarnOrangeH}h: KH-Fahrt prüfen",
            warnRedHours = wehenRegWarnRedH, warnRedText = ">${wehenRegWarnRedH}h: Sofort ins Krankenhaus!"
        )
        updateMilestoneStopButtonVisibility()

        binding.btnStartEinleitung.setOnClickListener {
            showMilestoneStartDialog("Einleitung") { ts ->
                einleitungStartTime = ts
                einleitungStopTime = 0L
                prefs.edit().putLong("einleitung", ts).putLong("einleitung_stop", 0L).apply()
                updateMilestoneTimerRow(
                    ts, binding.tvEinleitungTime, binding.tvEinleitungElapsed,
                    binding.tvEinleitungWarning,
                    warnOrangeHours = einleitungWarnOrangeH, warnOrangeText = ">${einleitungWarnOrangeH}h: Arzt über Einleitungsdauer informieren",
                    warnRedHours = einleitungWarnRedH, warnRedText = ">${einleitungWarnRedH}h: Dringend Arzt kontaktieren!"
                )
                updateMilestoneStopButtonVisibility()
            }
        }
        binding.btnStopEinleitung.setOnClickListener {
            einleitungStopTime = System.currentTimeMillis()
            prefs.edit().putLong("einleitung_stop", einleitungStopTime).apply()
            updateMilestoneStopButtonVisibility()
        }
        binding.btnStartWehenUnregel.setOnClickListener {
            showMilestoneStartDialog("Wehen unregelmäßig") { ts ->
                wehenUnregelStartTime = ts
                wehenUnregelStopTime = 0L
                prefs.edit().putLong("wehen_unregelmaessig", ts).putLong("wehen_unregelmaessig_stop", 0L).apply()
                updateMilestoneTimerRow(
                    ts, binding.tvWehenUnregelTime, binding.tvWehenUnregelElapsed,
                    binding.tvWehenUnregelWarning,
                    warnOrangeHours = wehenUnregWarnOrangeH, warnOrangeText = ">${wehenUnregWarnOrangeH}h: Hebamme informieren",
                    warnRedHours = wehenUnregWarnRedH, warnRedText = ">${wehenUnregWarnRedH}h: Arzt / KH kontaktieren!"
                )
                updateMilestoneStopButtonVisibility()
            }
        }
        binding.btnStopWehenUnregel.setOnClickListener {
            wehenUnregelStopTime = System.currentTimeMillis()
            prefs.edit().putLong("wehen_unregelmaessig_stop", wehenUnregelStopTime).apply()
            updateMilestoneStopButtonVisibility()
        }
        binding.btnStartWehenRegel.setOnClickListener {
            showMilestoneStartDialog("Wehen regelmäßig") { ts ->
                wehenRegelStartTime = ts
                wehenRegelStopTime = 0L
                prefs.edit().putLong("wehen_regelmaessig", ts).putLong("wehen_regelmaessig_stop", 0L).apply()
                updateMilestoneTimerRow(
                    ts, binding.tvWehenRegelTime, binding.tvWehenRegelElapsed,
                    binding.tvWehenRegelWarning,
                    warnOrangeHours = wehenRegWarnOrangeH, warnOrangeText = ">${wehenRegWarnOrangeH}h: KH-Fahrt prüfen",
                    warnRedHours = wehenRegWarnRedH, warnRedText = ">${wehenRegWarnRedH}h: Sofort ins Krankenhaus!"
                )
                updateMilestoneStopButtonVisibility()
            }
        }
        binding.btnStopWehenRegel.setOnClickListener {
            wehenRegelStopTime = System.currentTimeMillis()
            prefs.edit().putLong("wehen_regelmaessig_stop", wehenRegelStopTime).apply()
            updateMilestoneStopButtonVisibility()
        }

        // Custom timers
        loadCustomTimers()
        renderCustomTimers()
        binding.btnAddCustomTimer.setOnClickListener { showAddCustomTimerDialog() }
    }

    private fun updateMilestoneStopButtonVisibility() {
        binding.btnStopEinleitung.visibility =
            if (einleitungStartTime > 0L && einleitungStopTime == 0L) View.VISIBLE else View.GONE
        binding.btnStopWehenUnregel.visibility =
            if (wehenUnregelStartTime > 0L && wehenUnregelStopTime == 0L) View.VISIBLE else View.GONE
        binding.btnStopWehenRegel.visibility =
            if (wehenRegelStartTime > 0L && wehenRegelStopTime == 0L) View.VISIBLE else View.GONE
    }

    private fun showMilestoneStartDialog(label: String, onConfirmed: (Long) -> Unit) {
        val options = arrayOf("Jetzt starten", "Datum & Uhrzeit wählen", "Zurücksetzen")
        AlertDialog.Builder(this)
            .setTitle("$label – Startzeit")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> onConfirmed(System.currentTimeMillis())
                    1 -> showDateTimePicker(Calendar.getInstance()) { cal -> onConfirmed(cal.timeInMillis) }
                    2 -> onConfirmed(0L)
                }
            }
            .show()
    }

    private fun updateMilestoneTimerRow(
        startTime: Long,
        tvTime: TextView,
        tvElapsed: TextView,
        tvWarning: TextView? = null,
        stopTime: Long = 0L,
        warnOrangeHours: Int = -1,
        warnOrangeText: String = "",
        warnRedHours: Int = -1,
        warnRedText: String = ""
    ) {
        if (startTime <= 0L) {
            tvTime.text = "–"
            tvElapsed.visibility = View.GONE
            tvWarning?.visibility = View.GONE
            return
        }
        val sdf = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.GERMAN)
        tvTime.text = sdf.format(Date(startTime))
        tvElapsed.visibility = View.VISIBLE
        val elapsed = if (stopTime > 0L) stopTime - startTime else System.currentTimeMillis() - startTime
        if (elapsed < 0) {
            tvElapsed.visibility = View.GONE
            tvWarning?.visibility = View.GONE
            return
        }
        val hours = TimeUnit.MILLISECONDS.toHours(elapsed)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(elapsed) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(elapsed) % 60
        tvElapsed.text = String.format(Locale.GERMAN, "%02d:%02d:%02d", hours, minutes, seconds)

        if (tvWarning != null) {
            when {
                warnRedHours >= 0 && hours >= warnRedHours -> {
                    tvWarning.visibility = View.VISIBLE
                    tvWarning.text = "⚠️ $warnRedText"
                    tvWarning.setTextColor(getColor(R.color.warning_red))
                }
                warnOrangeHours >= 0 && hours >= warnOrangeHours -> {
                    tvWarning.visibility = View.VISIBLE
                    tvWarning.text = "⚠️ $warnOrangeText"
                    tvWarning.setTextColor(getColor(R.color.warning_orange))
                }
                else -> tvWarning.visibility = View.GONE
            }
        }
    }

    private fun updateMilestoneTimers() {
        updateMilestoneTimerRow(
            einleitungStartTime,
            binding.tvEinleitungTime,
            binding.tvEinleitungElapsed,
            binding.tvEinleitungWarning,
            stopTime = einleitungStopTime,
            warnOrangeHours = einleitungWarnOrangeH, warnOrangeText = ">${einleitungWarnOrangeH}h: Arzt über Einleitungsdauer informieren",
            warnRedHours = einleitungWarnRedH, warnRedText = ">${einleitungWarnRedH}h: Dringend Arzt kontaktieren!"
        )
        updateMilestoneTimerRow(
            wehenUnregelStartTime,
            binding.tvWehenUnregelTime,
            binding.tvWehenUnregelElapsed,
            binding.tvWehenUnregelWarning,
            stopTime = wehenUnregelStopTime,
            warnOrangeHours = wehenUnregWarnOrangeH, warnOrangeText = ">${wehenUnregWarnOrangeH}h: Hebamme informieren",
            warnRedHours = wehenUnregWarnRedH, warnRedText = ">${wehenUnregWarnRedH}h: Arzt / KH kontaktieren!"
        )
        updateMilestoneTimerRow(
            wehenRegelStartTime,
            binding.tvWehenRegelTime,
            binding.tvWehenRegelElapsed,
            binding.tvWehenRegelWarning,
            stopTime = wehenRegelStopTime,
            warnOrangeHours = wehenRegWarnOrangeH, warnOrangeText = ">${wehenRegWarnOrangeH}h: KH-Fahrt prüfen",
            warnRedHours = wehenRegWarnRedH, warnRedText = ">${wehenRegWarnRedH}h: Sofort ins Krankenhaus!"
        )
        // Update all custom timer elapsed views (only for timers with counter)
        for (timer in customTimers) {
            if (!timer.hasCounter) continue
            val container = binding.llCustomTimers.findViewWithTag<LinearLayout>("ct_${timer.id}") ?: continue
            val tvTime = container.findViewWithTag<TextView>("ct_time_${timer.id}") ?: continue
            val tvElapsed = container.findViewWithTag<TextView>("ct_elapsed_${timer.id}") ?: continue
            updateMilestoneTimerRow(timer.startTime, tvTime, tvElapsed, stopTime = timer.stopTime)
        }
    }

    // ── Custom timers ─────────────────────────────────────────────────────────

    private fun loadCustomTimers() {
        val prefs = getSharedPreferences("custom_timers", MODE_PRIVATE)
        val json = prefs.getString("timers_json", null) ?: return
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                customTimers.add(
                    CustomTimer(
                        id = obj.getLong("id"),
                        label = obj.getString("label"),
                        startTime = obj.getLong("startTime"),
                        stopTime = obj.optLong("stopTime", 0L),
                        hasCounter = obj.optBoolean("hasCounter", true),
                        comment = obj.optString("comment", "")
                    )
                )
            }
        } catch (e: Exception) {
            Log.w("CustomTimers", "Failed to load custom timers", e)
        }
    }

    private fun saveCustomTimers() {
        val arr = JSONArray()
        for (t in customTimers) {
            val obj = JSONObject()
            obj.put("id", t.id)
            obj.put("label", t.label)
            obj.put("startTime", t.startTime)
            obj.put("stopTime", t.stopTime)
            obj.put("hasCounter", t.hasCounter)
            obj.put("comment", t.comment)
            arr.put(obj)
        }
        getSharedPreferences("custom_timers", MODE_PRIVATE)
            .edit().putString("timers_json", arr.toString()).apply()
    }

    private fun showAddCustomTimerDialog() {
        val suggestions = arrayOf(
            "💧 Blasensprung",
            "💊 Oxytocin-Start",
            "🩺 Erste Untersuchung",
            "🧬 Plazenta abgegangen",
            "👶 Baby geboren",
            "🔔 Erster Pressdrang",
            "✏️ Eigene Bezeichnung…"
        )
        AlertDialog.Builder(this)
            .setTitle("Timer hinzufügen")
            .setItems(suggestions) { _, which ->
                if (which == suggestions.size - 1) {
                    // Free-text input
                    showCustomTimerFreeTextDialog()
                } else {
                    addCustomTimer(suggestions[which])
                }
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun showCustomTimerFreeTextDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
        }
        val editText = EditText(this).apply {
            hint = "Timer-Bezeichnung"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val cbCounter = CheckBox(this).apply {
            text = "Mit Zähler (Laufuhr)"
            isChecked = true
            setPadding(0, 8, 0, 0)
        }
        layout.addView(editText)
        layout.addView(cbCounter)
        AlertDialog.Builder(this)
            .setTitle("Eigene Bezeichnung")
            .setView(layout)
            .setPositiveButton("Hinzufügen") { _, _ ->
                val label = editText.text.toString().trim()
                if (label.isNotEmpty()) addCustomTimer(label, cbCounter.isChecked)
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun addCustomTimer(label: String, hasCounter: Boolean = true) {
        val timer = CustomTimer(id = System.currentTimeMillis(), label = label, startTime = 0L, hasCounter = hasCounter)
        customTimers.add(timer)
        saveCustomTimers()
        renderCustomTimers()
    }

    private fun renderCustomTimers() {
        val container = binding.llCustomTimers
        container.removeAllViews()
        val sdf = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.GERMAN)

        for (timer in customTimers) {
            // Divider
            val divider = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1
                ).also { it.setMargins(0, 0, 0, 10) }
                setBackgroundColor(getColor(R.color.divider))
            }
            container.addView(divider)

            val timerRow = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.setMargins(0, 0, 0, 10) }
                tag = "ct_${timer.id}"
            }

            // Header row: label + start button + stop button (counter only) + delete button
            val headerRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                gravity = android.view.Gravity.CENTER_VERTICAL
            }

            val tvLabel = TextView(this).apply {
                text = timer.label
                textSize = 14f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(getColor(R.color.primary_dark))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val btnStart = Button(this).apply {
                text = "Jetzt"
                textSize = 12f
                backgroundTintList = ColorStateList.valueOf(getColor(R.color.primary_dark))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.setMargins(8, 0, 0, 0) }
                setOnClickListener { showCustomTimerStartDialog(timer) }
            }

            val btnStop = Button(this).apply {
                text = "⏹"
                textSize = 12f
                backgroundTintList = ColorStateList.valueOf(getColor(R.color.text_secondary))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.setMargins(4, 0, 0, 0) }
                // Visible only when counter is active and running (started but not yet stopped)
                visibility = if (timer.hasCounter && timer.startTime > 0L && timer.stopTime == 0L) View.VISIBLE else View.GONE
                setOnClickListener {
                    timer.stopTime = System.currentTimeMillis()
                    saveCustomTimers()
                    renderCustomTimers()
                }
            }

            val btnDelete = Button(this).apply {
                text = "✕"
                textSize = 12f
                backgroundTintList = ColorStateList.valueOf(getColor(R.color.warning_red))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.setMargins(4, 0, 0, 0) }
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Timer löschen?")
                        .setMessage("\"${timer.label}\" wirklich entfernen?")
                        .setPositiveButton("Löschen") { _, _ ->
                            customTimers.remove(timer)
                            saveCustomTimers()
                            renderCustomTimers()
                        }
                        .setNegativeButton("Abbrechen", null)
                        .show()
                }
            }

            headerRow.addView(tvLabel)
            headerRow.addView(btnStart)
            if (timer.hasCounter) headerRow.addView(btnStop)
            headerRow.addView(btnDelete)
            timerRow.addView(headerRow)

            // Start time display
            val tvTime = TextView(this).apply {
                text = if (timer.startTime > 0L) sdf.format(Date(timer.startTime)) else "–"
                textSize = 12f
                setTextColor(getColor(R.color.text_secondary))
                setPadding(0, 4, 0, 0)
                tag = "ct_time_${timer.id}"
            }
            timerRow.addView(tvTime)

            // Elapsed time display (only for timers with counter)
            if (timer.hasCounter) {
                val tvElapsed = TextView(this).apply {
                    text = ""
                    textSize = 22f
                    setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
                    setTextColor(getColor(R.color.primary_dark))
                    visibility = if (timer.startTime > 0L) View.VISIBLE else View.GONE
                    tag = "ct_elapsed_${timer.id}"
                }
                timerRow.addView(tvElapsed)
                updateMilestoneTimerRow(timer.startTime, tvTime, tvElapsed, stopTime = timer.stopTime)
            }

            // Comment/note EditText
            val etComment = EditText(this).apply {
                setText(timer.comment)
                hint = "Kommentar…"
                textSize = 13f
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                minLines = 1
                gravity = android.view.Gravity.TOP
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                setPadding(0, 6, 0, 0)
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(s: Editable?) {
                        timer.comment = s?.toString() ?: ""
                        saveCustomTimers()
                    }
                })
            }
            timerRow.addView(etComment)

            container.addView(timerRow)
        }
    }

    private fun showCustomTimerStartDialog(timer: CustomTimer) {
        val options = arrayOf("Jetzt starten", "Datum & Uhrzeit wählen", "Zurücksetzen")
        AlertDialog.Builder(this)
            .setTitle("${timer.label} – Startzeit")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        timer.startTime = System.currentTimeMillis()
                        timer.stopTime = 0L
                        saveCustomTimers()
                        renderCustomTimers()
                    }
                    1 -> {
                        showDateTimePicker(Calendar.getInstance()) { cal ->
                            timer.startTime = cal.timeInMillis
                            timer.stopTime = 0L
                            saveCustomTimers()
                            renderCustomTimers()
                        }
                    }
                    2 -> {
                        timer.startTime = 0L
                        timer.stopTime = 0L
                        saveCustomTimers()
                        renderCustomTimers()
                    }
                }
            }
            .show()
    }

    private fun setupMedicalInfo() {
        val prefs = getSharedPreferences(PREFS_MEDICAL_LIST, MODE_PRIVATE)
        if (!prefs.contains("items_json")) {
            val defaultItems = listOf(
                "Mutter: Chron. Hepatitis B, niederschwellig aktiv",
                "Neugeborenes: Hep-B-Simultanimpfung direkt nach Geburt!",
                "Hep-B-Immunglobulin für Neugeborenes erforderlich",
                "Geburtshelfer/Hebamme informiert?",
                "Neonatologie Rücksprache empfohlen"
            )
            val arr = JSONArray()
            defaultItems.forEachIndexed { i, text ->
                val obj = JSONObject()
                obj.put("id", System.currentTimeMillis() + i)
                obj.put("text", text)
                arr.put(obj)
            }
            prefs.edit().putString("items_json", arr.toString()).apply()
        }
        loadMedicalItemsIntoMemory()
        renderMedicalItems()
        binding.btnAddMedical.setOnClickListener { addMedicalItem() }
    }

    private fun loadMedicalItemsIntoMemory() {
        val prefs = getSharedPreferences(PREFS_MEDICAL_LIST, MODE_PRIVATE)
        val json = prefs.getString("items_json", "[]") ?: "[]"
        medicalItems.clear()
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                medicalItems.add(Pair(obj.getLong("id"), obj.getString("text")))
            }
        } catch (e: Exception) {
            Log.w("Medical", "Failed to load medical items", e)
        }
    }

    private fun saveMedicalItems() {
        val arr = JSONArray()
        medicalItems.forEach { (id, text) ->
            val obj = JSONObject()
            obj.put("id", id)
            obj.put("text", text)
            arr.put(obj)
        }
        getSharedPreferences(PREFS_MEDICAL_LIST, MODE_PRIVATE)
            .edit().putString("items_json", arr.toString()).apply()
    }

    private fun renderMedicalItems() {
        val container = binding.llMedicalList
        container.removeAllViews()
        medicalItems.forEach { (id, text) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.setMargins(0, 0, 0, 6) }
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            val etText = EditText(this).apply {
                setText(text)
                textSize = 14f
                setTextColor(getColor(R.color.text_primary))
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                minLines = 1
                gravity = android.view.Gravity.TOP
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(s: Editable?) {
                        val idx = medicalItems.indexOfFirst { it.first == id }
                        if (idx >= 0) {
                            medicalItems[idx] = Pair(id, s?.toString() ?: "")
                            saveMedicalItems()
                        }
                    }
                })
            }
            val btnDelete = Button(this).apply {
                this.text = "✕"
                textSize = 11f
                backgroundTintList = ColorStateList.valueOf(getColor(R.color.warning_red))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.setMargins(6, 0, 0, 0) }
                setOnClickListener {
                    medicalItems.removeAll { it.first == id }
                    saveMedicalItems()
                    renderMedicalItems()
                }
            }
            row.addView(etText)
            row.addView(btnDelete)
            container.addView(row)
        }
    }

    private fun addMedicalItem() {
        val editText = EditText(this).apply {
            hint = "Neuer Hinweis…"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setPadding(48, 24, 48, 8)
        }
        AlertDialog.Builder(this)
            .setTitle("Medizinischen Hinweis hinzufügen")
            .setView(editText)
            .setPositiveButton("Hinzufügen") { _, _ ->
                val noteText = editText.text.toString().trim()
                if (noteText.isNotEmpty()) {
                    medicalItems.add(Pair(System.currentTimeMillis(), noteText))
                    saveMedicalItems()
                    renderMedicalItems()
                }
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun loadGeburtswuensceListe(): List<String> {
        val prefs = getSharedPreferences(PREFS_GEBURTSWUENSCHE, MODE_PRIVATE)
        val json = prefs.getString("items_json", null)
        if (json != null) {
            return try {
                val arr = JSONArray(json)
                (0 until arr.length()).map { arr.getString(it) }
            } catch (e: Exception) { emptyList() }
        }
        return listOf(
            "Wenig CTG",
            "Wenig Untersuchungen",
            "Nabelschnur ausbluten / auspulsieren lassen",
            "Ambulante Geburt",
            "Hörtest ggf. gleich nach Geburt",
        )
    }

    private fun saveGeburtswuensche(list: List<String>) {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        getSharedPreferences(PREFS_GEBURTSWUENSCHE, MODE_PRIVATE)
            .edit().putString("items_json", arr.toString()).apply()
    }

    private fun setupGeburtsWuensche() {
        geburtswuensche.clear()
        geburtswuensche.addAll(loadGeburtswuensceListe())
        renderGeburtswuensche()
        binding.btnAddWunsch.setOnClickListener { showAddWunschDialog() }
    }

    private fun renderGeburtswuensche() {
        val layout = binding.wishesContainer
        layout.removeAllViews()
        geburtswuensche.forEachIndexed { idx, wish ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.setMargins(0, 0, 0, 4) }
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            val tvText = TextView(this).apply {
                text = "• $wish"
                textSize = 14f
                setPadding(0, 6, 0, 6)
                setTextColor(getColor(R.color.text_primary))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val btnDelete = Button(this).apply {
                text = "✕"
                textSize = 11f
                backgroundTintList = ColorStateList.valueOf(getColor(R.color.warning_red))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.setMargins(6, 0, 0, 0) }
                setOnClickListener {
                    val currentIdx = geburtswuensche.indexOf(wish)
                    if (currentIdx >= 0) {
                        geburtswuensche.removeAt(currentIdx)
                        saveGeburtswuensche(geburtswuensche)
                        renderGeburtswuensche()
                    }
                }
            }
            row.addView(tvText)
            row.addView(btnDelete)
            layout.addView(row)
        }
    }

    private fun showAddWunschDialog() {
        val editText = EditText(this).apply {
            hint = "Neuer Wunsch…"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setPadding(48, 24, 48, 8)
        }
        AlertDialog.Builder(this)
            .setTitle("💜 Geburtswunsch hinzufügen")
            .setView(editText)
            .setPositiveButton("Hinzufügen") { _, _ ->
                val text = editText.text.toString().trim()
                if (text.isNotEmpty()) {
                    geburtswuensche.add(text)
                    saveGeburtswuensche(geburtswuensche)
                    renderGeburtswuensche()
                }
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun setupWehenfoerderung() {
        binding.tvWehenfoerderung.text = buildString {
            appendLine("🚶 Bewegung & Schwerkraft nutzen:")
            appendLine("  • Spazieren gehen, Treppen steigen")
            appendLine("  • Auf Geburtsball wippen / kreisen")
            appendLine("  • Aufrechte Positionen bevorzugen")
            appendLine("")
            appendLine("🛁 Wärme & Entspannung:")
            appendLine("  • Warmes Bad oder Dusche")
            appendLine("  • Wärmekissen auf Bauch / Kreuz")
            appendLine("  • Massage, Entspannungsübungen")
            appendLine("")
            appendLine("🌿 Natürliche Mittel:")
            appendLine("  • Rizinus (nach Absprache mit Hebamme)")
            appendLine("  • Himbeerblatttee")
            appendLine("  • Akupressur (z. B. Punkt Milz 6)")
            appendLine("  • Brustwarzen-Stimulation")
            appendLine("")
            appendLine("💨 Atemtechniken & mentale Stärke:")
            appendLine("  • Langsam und tief ausatmen bei Wehen")
            appendLine("  • Hypnobirthing / Visualisierung")
            appendLine("  • Vertraute Musik, Ruhe, Kerzenlicht")
            appendLine("")
            appendLine("👫 Unterstützung:")
            appendLine("  • Vater/Begleitung aktiv dabei")
            appendLine("  • Kontinuierliche Doula-/Hebammenbegleitung")
            append("  • Wenig Störungen, dunkles ruhiges Zimmer")
        }

        // Restore collapse state (initially collapsed)
        val prefs = getSharedPreferences("ui_state", MODE_PRIVATE)
        val expanded = prefs.getBoolean("wehenfoerderung_expanded", false)
        binding.tvWehenfoerderung.visibility = if (expanded) View.VISIBLE else View.GONE
        binding.tvWehenfoerderungHeader.text =
            "🌿 Wehenförderung & Geburtserleichterung ${if (expanded) "▼" else "▶"}"

        binding.tvWehenfoerderungHeader.setOnClickListener {
            val nowExpanded = binding.tvWehenfoerderung.visibility == View.VISIBLE
            binding.tvWehenfoerderung.visibility = if (nowExpanded) View.GONE else View.VISIBLE
            binding.tvWehenfoerderungHeader.text =
                "🌿 Wehenförderung & Geburtserleichterung ${if (nowExpanded) "▶" else "▼"}"
            prefs.edit().putBoolean("wehenfoerderung_expanded", !nowExpanded).apply()
        }
    }

    private fun setupNotizen() {
        // Migrate old single-text notes to list on first run
        val oldPrefs = getSharedPreferences("notizen", MODE_PRIVATE)
        val listPrefs = getSharedPreferences(PREFS_NOTIZEN_LIST, MODE_PRIVATE)
        if (!listPrefs.contains("items_json") && oldPrefs.contains("text")) {
            val oldText = oldPrefs.getString("text", "") ?: ""
            if (oldText.isNotEmpty()) {
                val arr = JSONArray()
                oldText.lines().filter { it.isNotBlank() }.forEachIndexed { i, line ->
                    val obj = JSONObject()
                    obj.put("id", System.currentTimeMillis() + i)
                    obj.put("text", line.trimStart('•', ' '))
                    arr.put(obj)
                }
                listPrefs.edit().putString("items_json", arr.toString()).apply()
            }
        }
        if (!listPrefs.contains("items_json")) {
            val defaultItems = listOf(
                "Falls Einleitung in Konstanz: Rizinus empfohlen (kann auch 12h gewartet werden)",
                "Geburt/Einleitung nach Blasensprung: Paar Tage möglich, wenn Blutwerte gut",
                "Nach 12h darf man nach Hause"
            )
            val arr = JSONArray()
            defaultItems.forEachIndexed { i, text ->
                val obj = JSONObject()
                obj.put("id", System.currentTimeMillis() + i)
                obj.put("text", text)
                arr.put(obj)
            }
            listPrefs.edit().putString("items_json", arr.toString()).apply()
        }

        loadNotizenItemsIntoMemory()
        renderNotizenItems()
        binding.btnAddNotiz.setOnClickListener { addNotizenItem() }

        val einleitungPrefs = getSharedPreferences("einleitung_notizen", MODE_PRIVATE)
        binding.etEinleitungNotizen.setText(einleitungPrefs.getString("text", ""))
        binding.etEinleitungNotizen.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                einleitungPrefs.edit().putString("text", s?.toString() ?: "").apply()
            }
        })

        val wehenUnregelPrefs = getSharedPreferences("wehen_unregelmaessig_notizen", MODE_PRIVATE)
        binding.etWehenUnregelNotizen.setText(wehenUnregelPrefs.getString("text", ""))
        binding.etWehenUnregelNotizen.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                wehenUnregelPrefs.edit().putString("text", s?.toString() ?: "").apply()
            }
        })

        val wehenRegelPrefs = getSharedPreferences("wehen_regelmaessig_notizen", MODE_PRIVATE)
        binding.etWehenRegelNotizen.setText(wehenRegelPrefs.getString("text", ""))
        binding.etWehenRegelNotizen.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                wehenRegelPrefs.edit().putString("text", s?.toString() ?: "").apply()
            }
        })
    }

    private fun loadNotizenItemsIntoMemory() {
        val prefs = getSharedPreferences(PREFS_NOTIZEN_LIST, MODE_PRIVATE)
        val json = prefs.getString("items_json", "[]") ?: "[]"
        notizenItems.clear()
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                notizenItems.add(Pair(obj.getLong("id"), obj.getString("text")))
            }
        } catch (e: Exception) {
            Log.w("Notizen", "Failed to load notizen items", e)
        }
    }

    private fun saveNotizenItems(items: List<Pair<Long, String>>) {
        val arr = JSONArray()
        items.forEach { (id, text) ->
            val obj = JSONObject()
            obj.put("id", id)
            obj.put("text", text)
            arr.put(obj)
        }
        getSharedPreferences(PREFS_NOTIZEN_LIST, MODE_PRIVATE)
            .edit().putString("items_json", arr.toString()).apply()
    }

    private fun renderNotizenItems() {
        val container = binding.llNotizenList
        container.removeAllViews()
        notizenItems.forEach { (id, text) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.setMargins(0, 0, 0, 6) }
                gravity = android.view.Gravity.CENTER_VERTICAL
            }

            val etText = EditText(this).apply {
                setText(text)
                textSize = 14f
                setTextColor(getColor(R.color.text_primary))
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                minLines = 1
                gravity = android.view.Gravity.TOP
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(s: Editable?) {
                        val idx = notizenItems.indexOfFirst { it.first == id }
                        if (idx >= 0) {
                            notizenItems[idx] = Pair(id, s?.toString() ?: "")
                            saveNotizenItems(notizenItems)
                        }
                    }
                })
            }

            val btnDelete = Button(this).apply {
                this.text = "✕"
                textSize = 11f
                backgroundTintList = ColorStateList.valueOf(getColor(R.color.warning_red))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.setMargins(6, 0, 0, 0) }
                setOnClickListener {
                    notizenItems.removeAll { it.first == id }
                    saveNotizenItems(notizenItems)
                    renderNotizenItems()
                }
            }

            row.addView(etText)
            row.addView(btnDelete)
            container.addView(row)
        }
    }

    private fun addNotizenItem() {
        val editText = EditText(this).apply {
            hint = "Neue Notiz…"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setPadding(48, 24, 48, 8)
        }
        AlertDialog.Builder(this)
            .setTitle("Notiz hinzufügen")
            .setView(editText)
            .setPositiveButton("Hinzufügen") { _, _ ->
                val noteText = editText.text.toString().trim()
                if (noteText.isNotEmpty()) {
                    notizenItems.add(Pair(System.currentTimeMillis(), noteText))
                    saveNotizenItems(notizenItems)
                    renderNotizenItems()
                }
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun setupKinderInfo() {
        val prefs = getSharedPreferences(PREFS_KINDER_INFO, MODE_PRIVATE)
        val defaultText = buildString {
            appendLine("👦 Kind 1: 7 Jahre")
            appendLine("👧 Kind 2: 4 Jahre")
            appendLine("📍 Aktuell: Oma & Opa in Sipplinen")
            appendLine("🚗 Opa kann NICHT Auto fahren")
            appendLine("⚠️ Transport organisieren!")
            appendLine("")
            appendLine("Mögliche Lösungen:")
            appendLine("• Taxi / Uber für Oma+Kinder")
            appendLine("• Freunde/Nachbarn fragen")
            append("• ÖPNV: Sipplinen – Singen prüfen")
        }
        val text = prefs.getString("text", defaultText) ?: defaultText
        binding.tvKinderStatus.text = text

        binding.btnOrganizeTransport.setOnClickListener {
            val sanitizedUrl = opnvUrl.trim()
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(sanitizedUrl)))
            } catch (e: Exception) {
                Toast.makeText(this, "URL konnte nicht geöffnet werden", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnEditKinderInfo.setOnClickListener {
            val currentText = prefs.getString("text", defaultText) ?: defaultText
            val editText = EditText(this).apply {
                setText(currentText)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                minLines = 6
                gravity = android.view.Gravity.TOP
                setPadding(48, 24, 48, 8)
            }
            AlertDialog.Builder(this)
                .setTitle("👨‍👩‍👧‍👦 Kinder & Transport bearbeiten")
                .setView(editText)
                .setPositiveButton("Speichern") { _, _ ->
                    val newText = editText.text.toString()
                    prefs.edit().putString("text", newText).apply()
                    binding.tvKinderStatus.text = newText
                }
                .setNegativeButton("Abbrechen", null)
                .setNeutralButton("Zurücksetzen") { _, _ ->
                    prefs.edit().remove("text").apply()
                    binding.tvKinderStatus.text = defaultText
                }
                .show()
        }
    }

    private fun setupHospitalInfo() {
        val prefs = getSharedPreferences(PREFS_HOSPITAL_INFO, MODE_PRIVATE)
        val defaultText = buildString {
            appendLine("🏥 Hegau-Bodensee-Klinikum Singen")
            appendLine("   📞 Zentrale: 07731 89-0")
            appendLine("   📞 Kreissaal: 07731 89-1710")
            appendLine("")
            appendLine("🏥 Hegau-Bodensee-Klinikum Überlingen")
            appendLine("   📞 Zentrale: 07551 89-0")
            appendLine("   📞 Kreissaal: 07551 89-1310")
            appendLine("")
            appendLine("🏥 Krankenhaus Konstanz")
            appendLine("   📞 Zentrale: 07531 801-0")
            appendLine("   📞 Kreissaal: 07531 801-2830")
            appendLine("")
            appendLine("📞 Notruf: 112")
            appendLine("")
            appendLine("👨‍⚕️ Vater ist im Krankenhaus dabei")
            append("📅 Blasensprung: 22.02.2026, 06:15 Uhr")
        }
        val text = prefs.getString("text", defaultText) ?: defaultText
        binding.tvHospitalInfo.text = text

        binding.btnCallHospital.setOnClickListener {
            val sanitized = hospitalCallPhone.replace(Regex("[^0-9+\\-*#, ]"), "")
            if (sanitized.isNotEmpty()) {
                startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$sanitized")))
            }
        }

        binding.btnCallEmergency.setOnClickListener {
            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:112"))
            startActivity(intent)
        }

        binding.btnEditHospitalInfo.setOnClickListener {
            val currentText = prefs.getString("text", defaultText) ?: defaultText
            val editText = EditText(this).apply {
                setText(currentText)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                minLines = 6
                gravity = android.view.Gravity.TOP
                setPadding(48, 24, 48, 8)
            }
            AlertDialog.Builder(this)
                .setTitle("🏥 Krankenhausinfos bearbeiten")
                .setView(editText)
                .setPositiveButton("Speichern") { _, _ ->
                    val newText = editText.text.toString()
                    prefs.edit().putString("text", newText).apply()
                    binding.tvHospitalInfo.text = newText
                }
                .setNegativeButton("Abbrechen", null)
                .setNeutralButton("Zurücksetzen") { _, _ ->
                    prefs.edit().remove("text").apply()
                    binding.tvHospitalInfo.text = defaultText
                }
                .show()
        }
    }

    private fun setupChecklist() {
        val checklistLayout = binding.checklistContainer
        checklistLayout.removeAllViews()

        tasks.forEach { task ->
            val checkBox = CheckBox(this).apply {
                text = task.text
                isChecked = task.done
                textSize = 14f
                setPadding(0, 8, 0, 8)
                setOnCheckedChangeListener { _, isChecked ->
                    val currentIndex = tasks.indexOf(task)
                    if (currentIndex >= 0) {
                        tasks[currentIndex] = task.copy(done = isChecked)
                        saveTasks()
                        updateProgress()
                    }
                }
                setOnLongClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Aufgabe löschen?")
                        .setMessage("\"${task.text}\" entfernen?")
                        .setPositiveButton("Löschen") { _, _ ->
                            val currentIndex = tasks.indexOf(task)
                            if (currentIndex >= 0) tasks.removeAt(currentIndex)
                            saveTasks()
                            setupChecklist()
                        }
                        .setNegativeButton("Abbrechen", null)
                        .show()
                    true
                }
            }
            checklistLayout.addView(checkBox)
        }
        updateProgress()

        binding.btnAddTask.setOnClickListener { showAddTaskDialog() }
    }

    private fun showAddTaskDialog() {
        val editText = EditText(this).apply {
            hint = "Aufgabe eingeben"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setPadding(48, 24, 48, 24)
        }
        AlertDialog.Builder(this)
            .setTitle("✅ Aufgabe hinzufügen")
            .setView(editText)
            .setPositiveButton("Hinzufügen") { _, _ ->
                val text = editText.text.toString().trim()
                if (text.isNotEmpty()) {
                    tasks.add(Task(text, false))
                    saveTasks()
                    setupChecklist()
                } else {
                    Toast.makeText(this, "Bitte Aufgabe eingeben", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun updateProgress() {
        val done = tasks.count { it.done }
        val total = tasks.size
        binding.tvProgress.text = "$done / $total Aufgaben erledigt"
        binding.progressBar.max = total
        binding.progressBar.progress = done
    }

    private fun loadTasks(): List<Task> {
        val prefs = getSharedPreferences("checklist", MODE_PRIVATE)
        val json = prefs.getString("tasks", null)
        if (json != null) {
            return try {
                val array = JSONArray(json)
                (0 until array.length()).map { i ->
                    val obj = array.getJSONObject(i)
                    Task(obj.getString("text"), obj.getBoolean("done"))
                }
            } catch (e: Exception) {
                Log.e("Checklist", "Failed to load tasks", e)
                defaultTasks.map { Task(it, false) }
            }
        }
        return defaultTasks.map { Task(it, false) }
    }

    private fun saveTasks() {
        val array = JSONArray()
        tasks.forEach { t ->
            array.put(JSONObject().apply {
                put("text", t.text)
                put("done", t.done)
            })
        }
        getSharedPreferences("checklist", MODE_PRIVATE)
            .edit()
            .putString("tasks", array.toString())
            .apply()
    }

    private fun setupContacts() {
        val prefs = getSharedPreferences("contacts", MODE_PRIVATE)
        val contacts = listOf(
            Contact("Oma (Sipplinen)", prefs.getString("Oma (Sipplinen)", "") ?: "", editable = true),
            Contact("Hebamme", prefs.getString("Hebamme", "") ?: "", editable = true),
            Contact("KH Singen (Zentrale)", "07731 89-0"),
            Contact("KH Singen (Kreissaal)", "07731 89-1710"),
            Contact("KH Überlingen (Zentrale)", "07551 89-0"),
            Contact("KH Überlingen (Kreissaal)", "07551 89-1310"),
            Contact("KH Konstanz (Zentrale)", "07531 801-0"),
            Contact("KH Konstanz (Kreissaal)", "07531 801-2830"),
            Contact("Notruf", "112"),
            Contact("Kinderarzt", prefs.getString("Kinderarzt", "") ?: "", editable = true),
            Contact("Arbeit (Teams)", prefs.getString("Arbeit (Teams)", "") ?: "", editable = true),
            Contact("Gemeinde (Essen)", prefs.getString("Gemeinde (Essen)", "") ?: "", editable = true),
        )

        val layout = binding.contactsContainer
        layout.removeAllViews()

        contacts.forEach { contact ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            val tv = TextView(this).apply {
                text = when {
                    contact.number.isNotEmpty() -> "📞 ${contact.name}: ${contact.number}"
                    contact.editable -> "✏️ ${contact.name}: (tippen zum Eintragen)"
                    else -> "📞 ${contact.name}"
                }
                textSize = 14f
                setPadding(0, 8, 0, 8)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                if (contact.number.isNotEmpty()) {
                    setTextColor(getColor(R.color.link_blue))
                    setOnClickListener {
                        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${contact.number}"))
                        startActivity(intent)
                    }
                }
                if (contact.editable && contact.number.isEmpty()) {
                    setOnClickListener { showEditContactDialog(contact.name) }
                }
            }
            row.addView(tv)
            if (contact.editable) {
                val btnEdit = Button(this).apply {
                    text = "✏️"
                    textSize = 12f
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    lp.setMargins(8, 0, 0, 0)
                    layoutParams = lp
                    val outValue = android.util.TypedValue()
                    context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
                    setBackgroundResource(outValue.resourceId)
                    setOnClickListener { showEditContactDialog(contact.name) }
                }
                row.addView(btnEdit)
            }
            layout.addView(row)
        }

        binding.btnExportContacts.setOnClickListener { exportContacts() }
        binding.btnImportContacts.setOnClickListener {
            contactsImportLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
        }
    }

    private fun showEditContactDialog(contactName: String) {
        val prefs = getSharedPreferences("contacts", MODE_PRIVATE)
        val currentNumber = prefs.getString(contactName, "") ?: ""
        val dialogLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }
        val editText = EditText(this).apply {
            setText(currentNumber)
            hint = "Telefonnummer eingeben"
            inputType = InputType.TYPE_CLASS_PHONE
        }
        val btnPickContact = Button(this).apply {
            text = "📇 Aus Kontakten wählen"
        }
        dialogLayout.addView(editText)
        dialogLayout.addView(btnPickContact)

        AlertDialog.Builder(this)
            .setTitle("📞 $contactName")
            .setView(dialogLayout)
            .setPositiveButton("Speichern") { _, _ ->
                val number = editText.text.toString().trim()
                prefs.edit().putString(contactName, number).apply()
                setupContacts()
            }
            .setNegativeButton("Abbrechen", null)
            .show()

        btnPickContact.setOnClickListener {
            pendingContactPickCallback = { _, number ->
                editText.setText(number)
            }
            launchContactPicker()
        }
    }

    private fun setupEckdaten() {
        val prefs = getSharedPreferences("eckdaten", MODE_PRIVATE)

        // Restore saved values
        binding.etBabyName.setText(prefs.getString("name", ""))
        binding.etBabyGewicht.setText(prefs.getString("gewicht_g", ""))
        binding.etBabyGroesse.setText(prefs.getString("groesse_cm", ""))
        binding.etBabyKopfumfang.setText(prefs.getString("kopfumfang_cm", ""))
        binding.etApgar1.setText(prefs.getString("apgar_1", ""))
        binding.etApgar5.setText(prefs.getString("apgar_5", ""))
        binding.etApgar10.setText(prefs.getString("apgar_10", ""))
        binding.etGeburtsart.setText(prefs.getString("geburtsart", ""))
        binding.etGeburtsort.setText(prefs.getString("geburtsort", ""))
        binding.etBlutgruppe.setText(prefs.getString("blutgruppe", ""))
        binding.etEckdatenNotizen.setText(prefs.getString("notizen", ""))

        // Auto-save helper
        fun saveField(key: String, view: EditText) {
            view.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    prefs.edit().putString(key, s?.toString() ?: "").apply()
                }
            })
        }

        saveField("name", binding.etBabyName)
        saveField("gewicht_g", binding.etBabyGewicht)
        saveField("groesse_cm", binding.etBabyGroesse)
        saveField("kopfumfang_cm", binding.etBabyKopfumfang)
        saveField("apgar_1", binding.etApgar1)
        saveField("apgar_5", binding.etApgar5)
        saveField("apgar_10", binding.etApgar10)
        saveField("geburtsart", binding.etGeburtsart)
        saveField("geburtsort", binding.etGeburtsort)
        saveField("blutgruppe", binding.etBlutgruppe)
        saveField("notizen", binding.etEckdatenNotizen)

        setupPhotos()
        binding.btnExportUrkunde.setOnClickListener { exportUrkunde() }
    }

    private fun setupPhotos() {
        loadPhotoPathsIntoMemory()
        renderPhotos()
        binding.btnTakePhoto.setOnClickListener {
            if (checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                launchCamera()
            } else {
                requestCameraPermission.launch(android.Manifest.permission.CAMERA)
            }
        }
        binding.btnPickPhoto.setOnClickListener {
            pickPhotoLauncher.launch("image/*")
        }
        binding.btnExportPhotos.setOnClickListener { exportPhotos() }
    }

    private fun exportPhotos() {
        if (photoPaths.isEmpty()) {
            Toast.makeText(this, "Keine Fotos vorhanden", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val uris = ArrayList<Uri>()
            photoPaths.forEach { path ->
                val file = File(path)
                if (file.exists()) {
                    uris.add(FileProvider.getUriForFile(this, "$packageName.fileprovider", file))
                }
            }
            if (uris.isEmpty()) {
                Toast.makeText(this, "Keine Fotos gefunden", Toast.LENGTH_SHORT).show()
                return
            }
            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "image/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                putExtra(Intent.EXTRA_SUBJECT, "Geburt2026 – Fotos")
            }
            startActivity(Intent.createChooser(intent, "Fotos exportieren"))
        } catch (e: Exception) {
            Log.e("Photos", "Export failed", e)
            Toast.makeText(this, "Foto-Export fehlgeschlagen", Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchCamera() {
        val photoDir = File(filesDir, "photos").also { it.mkdirs() }
        val photoFile = File(photoDir, "photo_${System.currentTimeMillis()}.jpg")
        currentCameraPhotoPath = photoFile.absolutePath
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", photoFile)
        takePhotoLauncher.launch(uri)
    }

    private fun copyPhotoToInternalStorage(uri: Uri) {
        try {
            val photoDir = File(filesDir, "photos").also { it.mkdirs() }
            val destFile = File(photoDir, "photo_${UUID.randomUUID()}.jpg")
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output -> input.copyTo(output) }
            }
            photoPaths.add(destFile.absolutePath)
            savePhotoPaths()
            renderPhotos()
        } catch (e: Exception) {
            Log.e("Photos", "Failed to copy photo", e)
            Toast.makeText(this, "Foto konnte nicht gespeichert werden", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadPhotoPathsIntoMemory() {
        val prefs = getSharedPreferences(PREFS_PHOTOS, MODE_PRIVATE)
        val json = prefs.getString("paths_json", "[]") ?: "[]"
        photoPaths.clear()
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val path = arr.getString(i)
                if (File(path).exists()) photoPaths.add(path)
            }
        } catch (e: Exception) {
            Log.w("Photos", "Failed to load photo paths", e)
        }
    }

    private fun savePhotoPaths() {
        val arr = JSONArray()
        photoPaths.forEach { arr.put(it) }
        getSharedPreferences(PREFS_PHOTOS, MODE_PRIVATE)
            .edit().putString("paths_json", arr.toString()).apply()
    }

    private fun renderPhotos() {
        val container = binding.llPhotosContainer
        container.removeAllViews()
        val sizePx = (96 * resources.displayMetrics.density).toInt()
        if (photoPaths.isEmpty()) {
            val tv = TextView(this).apply {
                text = "Noch keine Fotos"
                textSize = 13f
                setTextColor(getColor(R.color.text_secondary))
                setPadding(4, 8, 4, 8)
            }
            container.addView(tv)
            return
        }
        photoPaths.toList().forEach { path ->
            val frame = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.setMargins(0, 0, 8, 0) }
                gravity = android.view.Gravity.CENTER_HORIZONTAL
            }
            val imageView = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(sizePx, sizePx)
                scaleType = ImageView.ScaleType.CENTER_CROP
                try {
                    val opts = BitmapFactory.Options().apply {
                        inJustDecodeBounds = true
                        BitmapFactory.decodeFile(path, this)
                        inSampleSize = maxOf(1, maxOf(outWidth, outHeight) / sizePx)
                        inJustDecodeBounds = false
                    }
                    val bmp = BitmapFactory.decodeFile(path, opts)
                    if (bmp != null) setImageBitmap(bmp)
                    else setImageResource(android.R.drawable.ic_menu_gallery)
                } catch (e: Exception) {
                    setImageResource(android.R.drawable.ic_menu_gallery)
                }
            }
            val btnDel = Button(this).apply {
                text = "✕"
                textSize = 10f
                backgroundTintList = ColorStateList.valueOf(getColor(R.color.warning_red))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setPadding(8, 0, 8, 0)
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Foto löschen?")
                        .setPositiveButton("Löschen") { _, _ ->
                            photoPaths.remove(path)
                            File(path).delete()
                            savePhotoPaths()
                            renderPhotos()
                        }
                        .setNegativeButton("Abbrechen", null)
                        .show()
                }
            }
            frame.addView(imageView)
            frame.addView(btnDel)
            container.addView(frame)
        }
    }

    private fun exportUrkunde() {
        val prefs = getSharedPreferences("eckdaten", MODE_PRIVATE)
        val sdf = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.GERMAN)

        fun field(key: String, suffix: String = "") =
            prefs.getString(key, "")?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { if (suffix.isEmpty()) it else "$it $suffix" } ?: "–"

        val name = field("name")
        val gewicht = field("gewicht_g", "g")
        val groesse = field("groesse_cm", "cm")
        val kopfumfang = field("kopfumfang_cm", "cm")
        val apgar1 = field("apgar_1")
        val apgar5 = field("apgar_5")
        val apgar10 = field("apgar_10")
        val geburtsart = field("geburtsart")
        val geburtsort = field("geburtsort")
        val blutgruppe = field("blutgruppe")
        val notizen = prefs.getString("notizen", "")?.trim()

        val geburtszeitStr = if (geburtszeit > 0L) sdf.format(Date(geburtszeit)) + " Uhr" else "–"

        // SSW at birth
        val lmpCal = dueDateCalendar.clone() as Calendar
        lmpCal.add(Calendar.DAY_OF_YEAR, -280)
        val refTime = if (geburtszeit > 0L) geburtszeit else blasensprungTime
        val totalDays = TimeUnit.MILLISECONDS.toDays(refTime - lmpCal.timeInMillis)
        val sswStr = if (totalDays >= 0) "SSW ${totalDays / 7}+${totalDays % 7}" else "–"

        // Duration from Blasensprung to birth
        val blasensprungBisGeburtStr = if (geburtszeit > 0L) {
            val ms = geburtszeit - blasensprungTime
            if (ms >= 0) {
                val h = TimeUnit.MILLISECONDS.toHours(ms)
                val m = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
                "${h}h ${m}min"
            } else "–"
        } else "–"

        // Milestone timers
        fun milestoneStr(ts: Long): String {
            if (ts <= 0L) return "–"
            val ms = (if (geburtszeit > 0L) geburtszeit else System.currentTimeMillis()) - ts
            val h = TimeUnit.MILLISECONDS.toHours(ms)
            val m = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
            return "${sdf.format(Date(ts))} Uhr (${h}h ${m}min)"
        }

        val text = buildString {
            appendLine("🍼 ══════════════════════════════")
            appendLine("       GEBURTSURKUNDE 2026")
            appendLine("   ══════════════════════════════")
            appendLine()
            appendLine("👶  Name:            $name")
            appendLine("📅  Geburtszeitpunkt: $geburtszeitStr")
            appendLine("🏥  Geburtsort:       $geburtsort")
            appendLine("🍼  Geburtsart:       $geburtsart")
            appendLine()
            appendLine("⚖️  Gewicht:          $gewicht")
            appendLine("📏  Körperlänge:      $groesse")
            appendLine("🔵  Kopfumfang:       $kopfumfang")
            appendLine("🩸  Blutgruppe:       $blutgruppe")
            appendLine()
            appendLine("💗  APGAR-Werte:")
            appendLine("      1 Minute:    $apgar1 / 10")
            appendLine("      5 Minuten:   $apgar5 / 10")
            appendLine("     10 Minuten:   $apgar10 / 10")
            appendLine()
            appendLine("──────────────────────────────────")
            appendLine("🤰  Schwangerschaftswoche: $sswStr")
            appendLine("💧  Blasensprung: ${sdf.format(Date(blasensprungTime))} Uhr")
            if (geburtszeit > 0L) {
                appendLine("⏱️  Blasensprung → Geburt: $blasensprungBisGeburtStr")
            }
            appendLine()
            appendLine("🏁  Geburts-Meilensteine:")
            appendLine("      🔔 Einleitung:          ${milestoneStr(einleitungStartTime)}")
            appendLine("      🌊 Wehen unregelmäßig:  ${milestoneStr(wehenUnregelStartTime)}")
            appendLine("      ⚡ Wehen regelmäßig:    ${milestoneStr(wehenRegelStartTime)}")
            if (customTimers.isNotEmpty()) {
                appendLine()
                appendLine("⏱️  Weitere Zeiten:")
                customTimers.forEach { timer ->
                    val timeStr = if (timer.startTime > 0L) sdf.format(Date(timer.startTime)) + " Uhr" else "–"
                    val commentStr = if (timer.comment.isNotEmpty()) " – ${timer.comment}" else ""
                    appendLine("      • ${timer.label}: $timeStr$commentStr")
                }
            }
            appendLine("──────────────────────────────────")
            if (medicalItems.isNotEmpty()) {
                appendLine()
                appendLine("🏥  Medizinische Hinweise:")
                medicalItems.forEach { (_, text) ->
                    appendLine("      • $text")
                }
                appendLine()
            }
            if (!notizen.isNullOrEmpty()) {
                appendLine()
                appendLine("📝  Notizen:")
                appendLine(notizen)
                appendLine()
            }
            if (photoPaths.isNotEmpty()) {
                appendLine()
                appendLine("📷  Fotos: ${photoPaths.size} Foto(s) gespeichert")
                appendLine()
            }
            append("Erstellt mit Geburt2026 🍼")
        }

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_SUBJECT, "Geburtsurkunde – $name")
        }
        startActivity(Intent.createChooser(intent, "Zusammenfassung teilen"))
    }

    private fun setupSearch() {
        val sections = listOf(
            SearchSection(
                "🏥 Medizinische Hinweise",
                { medicalItems.joinToString(" ") { it.second } },
                binding.cardMedical
            ),
            SearchSection(
                "💜 Geburtswünsche",
                { geburtswuensche.joinToString(" ") },
                binding.cardWishes
            ),
            SearchSection(
                "🌿 Wehenförderung",
                { binding.tvWehenfoerderung.text.toString() },
                binding.cardLabor
            ),
            SearchSection(
                "📝 Notizen zur Einleitung",
                { notizenItems.joinToString(" ") { it.second } },
                binding.cardNotes
            ),
            SearchSection(
                "👨‍👩‍👧‍👦 Kinder & Transport",
                { binding.tvKinderStatus.text.toString() },
                binding.cardKids
            ),
            SearchSection(
                "👶 Betreuungsplaner",
                { loadBetreuungsEintraege().joinToString(" ") { "${it.name} ${if (it.unbegrenzt) "Unbegrenzt" else ""}" } },
                binding.cardBetreuung
            ),
            SearchSection(
                "🏥 Krankenhaus",
                { binding.tvHospitalInfo.text.toString() },
                binding.cardHospital
            ),
            SearchSection(
                "✅ Checkliste",
                { tasks.joinToString(" ") { it.text } },
                binding.cardChecklist
            ),
            SearchSection(
                "📞 Kontakte",
                {
                    val prefs = getSharedPreferences("contacts", MODE_PRIVATE)
                    EDITABLE_CONTACT_KEYS
                        .joinToString(" ") { "$it ${prefs.getString(it, "") ?: ""}" } +
                        " KH Konstanz KH Singen KH Überlingen Kreissaal Notruf"
                },
                binding.cardContacts
            ),
            SearchSection(
                "👶 Eckdaten – Geburt & Kind",
                {
                    val prefs = getSharedPreferences("eckdaten", MODE_PRIVATE)
                    listOf("name", "gewicht_g", "groesse_cm", "kopfumfang_cm", "geburtsart", "geburtsort", "blutgruppe", "notizen")
                        .joinToString(" ") { prefs.getString(it, "") ?: "" }
                },
                binding.cardEckdaten
            ),
        )

        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString()?.trim()?.lowercase() ?: ""
                val resultsLayout = binding.searchResultsContainer
                resultsLayout.removeAllViews()

                if (query.length < 2) {
                    resultsLayout.visibility = View.GONE
                    return
                }

                val matches = sections.filter { section ->
                    section.title.lowercase().contains(query) ||
                        section.getContent().lowercase().contains(query)
                }

                resultsLayout.visibility = View.VISIBLE
                if (matches.isEmpty()) {
                    val tv = TextView(this@MainActivity).apply {
                        text = "Kein Ergebnis für „$query“"
                        textSize = 13f
                        setPadding(12, 8, 12, 8)
                        setTextColor(getColor(R.color.text_secondary))
                    }
                    resultsLayout.addView(tv)
                } else {
                    matches.forEach { section ->
                        val tv = TextView(this@MainActivity).apply {
                            text = "➡️ ${section.title}"
                            textSize = 14f
                            setPadding(12, 10, 12, 10)
                            setTextColor(getColor(R.color.link_blue))
                            setOnClickListener {
                                if (section.cardView.visibility == View.GONE) {
                                    section.cardView.visibility = View.VISIBLE
                                }
                                binding.scrollView.post {
                                    binding.scrollView.smoothScrollTo(0, section.cardView.top)
                                }
                            }
                        }
                        resultsLayout.addView(tv)
                    }
                }
            }
        })
    }

    private fun setupBetreuung() {
        renderBetreuung()
        binding.btnAddBetreuung.setOnClickListener {
            showAddBetreuungDialog()
        }
        binding.btnExportBetreuung.setOnClickListener { exportBetreuung() }
        binding.btnImportBetreuung.setOnClickListener {
            betreuungImportLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
        }
    }

    private fun renderBetreuung() {
        val layout = binding.betreuungContainer
        layout.removeAllViews()

        val eintraege = loadBetreuungsEintraege()
        val now = System.currentTimeMillis()
        val in48h = now + TimeUnit.HOURS.toMillis(48)
        val sdf = SimpleDateFormat("EE dd.MM. HH:mm", Locale.GERMAN)

        val relevant = eintraege.filter { e ->
            if (e.unbegrenzt) true else e.bis > now && e.von < in48h
        }

        if (relevant.isEmpty()) {
            val tv = TextView(this).apply {
                text = "Keine Betreuung für die nächsten 48 Stunden eingetragen"
                textSize = 13f
                setTextColor(getColor(R.color.text_secondary))
                setPadding(0, 4, 0, 4)
            }
            layout.addView(tv)
        } else {
            val expiringEntry = relevant.filter { !it.unbegrenzt }.minByOrNull { it.bis }
            if (expiringEntry != null) {
                val hoursLeft = TimeUnit.MILLISECONDS.toHours(expiringEntry.bis - now)
                if (hoursLeft <= 24) {
                    val warnTv = TextView(this).apply {
                        text = "⚠️ Betreuung läuft ab: ${sdf.format(Date(expiringEntry.bis))} Uhr (noch ${hoursLeft} Std.)"
                        textSize = 13f
                        setTextColor(getColor(R.color.warning_orange))
                        setPadding(0, 4, 0, 8)
                        setTypeface(null, android.graphics.Typeface.BOLD)
                    }
                    layout.addView(warnTv)
                }
            }

            relevant.forEach { entry ->
                val timeText = if (entry.unbegrenzt) {
                    "Unbegrenzt verfügbar"
                } else {
                    "${sdf.format(Date(entry.von))} – ${sdf.format(Date(entry.bis))} Uhr"
                }
                val icon = if (entry.unbegrenzt) "♾️" else "📅"

                val rowLayout = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, 6, 0, 6)
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }
                val tv = TextView(this).apply {
                    text = "$icon ${entry.name}: $timeText"
                    textSize = 14f
                    setTextColor(getColor(R.color.text_primary))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    setOnLongClickListener {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Eintrag löschen?")
                            .setMessage("\"${entry.name}\" entfernen?")
                            .setPositiveButton("Löschen") { _, _ ->
                                deleteBetreuungsEintrag(entry.id)
                                renderBetreuung()
                            }
                            .setNegativeButton("Abbrechen", null)
                            .show()
                        true
                    }
                }
                rowLayout.addView(tv)
                if (entry.phone.isNotEmpty()) {
                    val btnCall = Button(this).apply {
                        text = "📞"
                        textSize = 14f
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { setMargins(8, 0, 0, 0) }
                        setPadding(16, 4, 16, 4)
                        setOnClickListener {
                            val sanitized = entry.phone.replace(Regex("[^0-9+\\-*#, ]"), "")
                            if (sanitized.isNotEmpty()) {
                                startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$sanitized")))
                            }
                        }
                    }
                    rowLayout.addView(btnCall)
                }
                layout.addView(rowLayout)
            }
        }

        val count = relevant.size
        binding.tvBetreuungInfo.text =
            "📅 Nächste 48 Stunden – $count Einträge (lang drücken zum Löschen)"
    }

    private fun showAddBetreuungDialog() {
        val dialogLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }

        val etName = EditText(this).apply {
            hint = "Name der Betreuungsperson"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }
        val etPhone = EditText(this).apply {
            hint = "Telefonnummer (optional)"
            inputType = InputType.TYPE_CLASS_PHONE
        }
        val btnPickContact = Button(this).apply {
            text = "📇 Aus Kontakten wählen"
        }
        dialogLayout.addView(etName)
        dialogLayout.addView(etPhone)
        dialogLayout.addView(btnPickContact)

        val radioGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            setPadding(0, 16, 0, 8)
        }
        val rbUnbegrenzt = RadioButton(this).apply {
            text = "♾️ Unbegrenzt"
            id = View.generateViewId()
        }
        val rbZuweisung = RadioButton(this).apply {
            text = "📅 Zuweisung (Zeitraum festlegen)"
            id = View.generateViewId()
        }
        radioGroup.addView(rbUnbegrenzt)
        radioGroup.addView(rbZuweisung)
        rbUnbegrenzt.isChecked = true
        dialogLayout.addView(radioGroup)

        var vonCalendar = Calendar.getInstance()
        var bisCalendar = Calendar.getInstance().apply { add(Calendar.HOUR_OF_DAY, 8) }
        val sdfShort = SimpleDateFormat("EE dd.MM. HH:mm", Locale.GERMAN)

        val tvVon = TextView(this).apply {
            text = "Von: ${sdfShort.format(vonCalendar.time)} (antippen)"
            textSize = 14f
            setPadding(0, 8, 0, 4)
            setTextColor(getColor(R.color.link_blue))
            visibility = View.GONE
        }
        val tvBis = TextView(this).apply {
            text = "Bis: ${sdfShort.format(bisCalendar.time)} (antippen)"
            textSize = 14f
            setPadding(0, 4, 0, 8)
            setTextColor(getColor(R.color.link_blue))
            visibility = View.GONE
        }

        tvVon.setOnClickListener {
            showDateTimePicker(vonCalendar) { cal ->
                vonCalendar = cal
                tvVon.text = "Von: ${sdfShort.format(vonCalendar.time)} (antippen)"
            }
        }
        tvBis.setOnClickListener {
            showDateTimePicker(bisCalendar) { cal ->
                bisCalendar = cal
                tvBis.text = "Bis: ${sdfShort.format(bisCalendar.time)} (antippen)"
            }
        }

        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            val isZuweisung = checkedId == rbZuweisung.id
            tvVon.visibility = if (isZuweisung) View.VISIBLE else View.GONE
            tvBis.visibility = if (isZuweisung) View.VISIBLE else View.GONE
        }

        dialogLayout.addView(tvVon)
        dialogLayout.addView(tvBis)

        AlertDialog.Builder(this)
            .setTitle("👶 Betreuung eintragen")
            .setView(dialogLayout)
            .setPositiveButton("Speichern") { _, _ ->
                val name = etName.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this, "Bitte Name eingeben", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val unbegrenzt = rbUnbegrenzt.isChecked
                if (!unbegrenzt && vonCalendar.timeInMillis >= bisCalendar.timeInMillis) {
                    Toast.makeText(this, "Startzeit muss vor der Endzeit liegen", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                saveBetreuungsEintrag(
                    BetreuungsEintrag(
                        id = System.currentTimeMillis(),
                        name = name,
                        unbegrenzt = unbegrenzt,
                        von = if (unbegrenzt) 0L else vonCalendar.timeInMillis,
                        bis = if (unbegrenzt) 0L else bisCalendar.timeInMillis,
                        phone = etPhone.text.toString().trim()
                    )
                )
                renderBetreuung()
            }
            .setNegativeButton("Abbrechen", null)
            .show()

        btnPickContact.setOnClickListener {
            pendingContactPickCallback = { name, number ->
                etName.setText(name)
                if (number.isNotEmpty()) etPhone.setText(number)
            }
            launchContactPicker()
        }
    }

    private fun showDateTimePicker(initial: Calendar, onSet: (Calendar) -> Unit) {
        val cal = initial.clone() as Calendar
        DatePickerDialog(
            this,
            { _, year, month, day ->
                cal.set(year, month, day)
                TimePickerDialog(
                    this,
                    { _, hour, minute ->
                        cal.set(Calendar.HOUR_OF_DAY, hour)
                        cal.set(Calendar.MINUTE, minute)
                        onSet(cal)
                    },
                    cal.get(Calendar.HOUR_OF_DAY),
                    cal.get(Calendar.MINUTE),
                    true
                ).show()
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun loadBetreuungsEintraege(): List<BetreuungsEintrag> {
        val prefs = getSharedPreferences("betreuung", MODE_PRIVATE)
        val json = prefs.getString("eintraege", "[]") ?: "[]"
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                BetreuungsEintrag(
                    id = obj.getLong("id"),
                    name = obj.getString("name"),
                    unbegrenzt = obj.getBoolean("unbegrenzt"),
                    von = obj.getLong("von"),
                    bis = obj.getLong("bis"),
                    phone = obj.optString("phone", "")
                )
            }
        } catch (e: Exception) {
            Log.e("Betreuung", "Failed to load care entries", e)
            emptyList()
        }
    }

    private fun saveBetreuungsEintrag(eintrag: BetreuungsEintrag) {
        val eintraege = loadBetreuungsEintraege().toMutableList()
        eintraege.add(eintrag)
        saveAllEintraege(eintraege)
    }

    private fun deleteBetreuungsEintrag(id: Long) {
        val eintraege = loadBetreuungsEintraege().toMutableList()
        eintraege.removeAll { it.id == id }
        saveAllEintraege(eintraege)
    }

    private fun saveAllEintraege(eintraege: List<BetreuungsEintrag>) {
        val array = JSONArray()
        eintraege.forEach { e ->
            array.put(JSONObject().apply {
                put("id", e.id)
                put("name", e.name)
                put("unbegrenzt", e.unbegrenzt)
                put("von", e.von)
                put("bis", e.bis)
                put("phone", e.phone)
            })
        }
        getSharedPreferences("betreuung", MODE_PRIVATE)
            .edit()
            .putString("eintraege", array.toString())
            .apply()
    }

    private fun setupPhasen() {
        currentPhaseIndex = getSharedPreferences("phasen", MODE_PRIVATE)
            .getInt("currentPhase", 0)
        applyPhase(currentPhaseIndex)

        binding.btnNextPhase.setOnClickListener {
            if (currentPhaseIndex < geburtPhasen.lastIndex) {
                currentPhaseIndex++
                applyPhase(currentPhaseIndex)
                getSharedPreferences("phasen", MODE_PRIVATE).edit()
                    .putInt("currentPhase", currentPhaseIndex).apply()
            }
        }
        binding.btnPrevPhase.setOnClickListener {
            if (currentPhaseIndex > 0) {
                currentPhaseIndex--
                applyPhase(currentPhaseIndex)
                getSharedPreferences("phasen", MODE_PRIVATE).edit()
                    .putInt("currentPhase", currentPhaseIndex).apply()
            }
        }
    }

    private fun applyPhase(index: Int) {
        val phase = geburtPhasen[index]
        val allCards = listOf(
            binding.cardTimer, binding.cardMedical, binding.cardWishes,
            binding.cardLabor, binding.cardNotes, binding.cardKids,
            binding.cardBetreuung, binding.cardHospital, binding.cardChecklist,
            binding.cardContacts, binding.cardAudioNotizen, binding.cardEckdaten
        )
        val visibleCards = phase.visibleCards(binding)
        allCards.forEach { card ->
            card.visibility = if (visibleCards.contains(card)) View.VISIBLE else View.GONE
        }

        binding.tvCurrentPhase.text = "${phase.emoji} Phase ${index + 1}: ${phase.name}"
        binding.tvPhaseHint.text = phase.hint
        binding.btnPrevPhase.isEnabled = index > 0
        binding.btnPrevPhase.text = if (index > 0) "← ${geburtPhasen[index - 1].name}" else "← Zurück"
        binding.btnNextPhase.isEnabled = index < geburtPhasen.lastIndex
        binding.btnNextPhase.text = if (index < geburtPhasen.lastIndex) "${geburtPhasen[index + 1].name} →" else "Weiter →"

        val indicator = (0..geburtPhasen.lastIndex).joinToString(" ") { i ->
            if (i == index) "●" else "○"
        }
        binding.tvPhaseIndicator.text = indicator
        updateAudioNotizPhaseLabel()
    }

    data class Task(val text: String, val done: Boolean)
    data class Contact(val name: String, val number: String, val editable: Boolean = false)
    data class BetreuungsEintrag(val id: Long, val name: String, val unbegrenzt: Boolean, val von: Long, val bis: Long, val phone: String = "")
    data class SearchSection(val title: String, val getContent: () -> String, val cardView: CardView)
    data class GeburtPhase(val emoji: String, val name: String, val hint: String, val visibleCards: (ActivityMainBinding) -> List<CardView>)
    data class AudioNotiz(val id: Long, val phaseIndex: Int, val phaseName: String, val timestamp: Long, val audioFilePath: String, val transcription: String)

    // ── Contact picker helpers ────────────────────────────────────────────────

    private fun launchContactPicker() {
        if (checkSelfPermission(android.Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            pickContactLauncher.launch(null)
        } else {
            requestContactPermission.launch(android.Manifest.permission.READ_CONTACTS)
        }
    }

    private fun getContactDisplayName(uri: Uri): String {
        contentResolver.query(
            uri,
            arrayOf(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
            null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0) ?: ""
        }
        return ""
    }

    private fun getContactPhoneNumber(uri: Uri): String {
        val id = uri.lastPathSegment ?: return ""
        contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
            arrayOf(id), null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val raw = cursor.getString(0) ?: return ""
                // Keep digits and a leading '+' for international numbers
                val normalized = buildString {
                    raw.forEachIndexed { i, c ->
                        if (c.isDigit() || (c == '+' && i == 0)) append(c)
                    }
                }
                return normalized
            }
        }
        return ""
    }

    // ── Contacts export / import ──────────────────────────────────────────────

    private fun exportContacts() {
        val prefs = getSharedPreferences("contacts", MODE_PRIVATE)
        val obj = JSONObject()
        EDITABLE_CONTACT_KEYS.forEach { key -> obj.put(key, prefs.getString(key, "") ?: "") }
        val json = obj.toString(2)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_TEXT, json)
            putExtra(Intent.EXTRA_SUBJECT, "Geburt2026 Kontakte")
        }
        startActivity(Intent.createChooser(intent, "Kontakte exportieren"))
    }

    private fun importContacts(uri: Uri) {
        try {
            val json = contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: return
            val obj = JSONObject(json)
            val prefs = getSharedPreferences("contacts", MODE_PRIVATE).edit()
            // Only accept known contact keys to prevent arbitrary data injection
            EDITABLE_CONTACT_KEYS.forEach { key ->
                if (obj.has(key)) prefs.putString(key, obj.getString(key))
            }
            prefs.apply()
            setupContacts()
            Toast.makeText(this, "Kontakte importiert ✓", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e("Contacts", "Import failed", e)
            Toast.makeText(this, "Import fehlgeschlagen", Toast.LENGTH_SHORT).show()
        }
    }

    // ── Betreuung export / import ─────────────────────────────────────────────

    private fun exportBetreuung() {
        val eintraege = loadBetreuungsEintraege()
        val array = JSONArray()
        eintraege.forEach { e ->
            array.put(JSONObject().apply {
                put("id", e.id)
                put("name", e.name)
                put("unbegrenzt", e.unbegrenzt)
                put("von", e.von)
                put("bis", e.bis)
                put("phone", e.phone)
            })
        }
        val json = array.toString(2)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_TEXT, json)
            putExtra(Intent.EXTRA_SUBJECT, "Geburt2026 Betreuungsplaner")
        }
        startActivity(Intent.createChooser(intent, "Betreuung exportieren"))
    }

    private fun importBetreuung(uri: Uri) {
        AlertDialog.Builder(this)
            .setTitle("Betreuung importieren")
            .setMessage("Bestehende Einträge werden überschrieben. Fortfahren?")
            .setPositiveButton("Ja") { _, _ ->
                try {
                    val json = contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: return@setPositiveButton
                    val array = JSONArray(json)
                    val eintraege = (0 until array.length()).map { i ->
                        val obj = array.getJSONObject(i)
                        BetreuungsEintrag(
                            id = obj.getLong("id"),
                            name = obj.getString("name"),
                            unbegrenzt = obj.getBoolean("unbegrenzt"),
                            von = obj.getLong("von"),
                            bis = obj.getLong("bis"),
                            phone = obj.optString("phone", "")
                        )
                    }
                    saveAllEintraege(eintraege)
                    renderBetreuung()
                    Toast.makeText(this, "Betreuung importiert ✓", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Log.e("Betreuung", "Import failed", e)
                    Toast.makeText(this, "Import fehlgeschlagen", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    // ── Audionotizen ──────────────────────────────────────────────────────────

    private fun setupAudioNotizen() {
        updateAudioNotizPhaseLabel()
        renderAudioNotizen()
        binding.btnStartAudioNotiz.setOnClickListener {
            if (isRecording) {
                stopAudioRecording()
            } else {
                if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    startAudioRecording()
                } else {
                    requestAudioPermission.launch(android.Manifest.permission.RECORD_AUDIO)
                }
            }
        }
    }

    private fun updateAudioNotizPhaseLabel() {
        val phase = geburtPhasen[currentPhaseIndex]
        binding.tvAudioNotizPhase.text = "Aktuelle Phase: ${phase.emoji} ${phase.name}"
    }

    private fun startAudioRecording() {
        currentRecordingId = System.currentTimeMillis()
        val fileName = "audionotiz_${currentRecordingId}.3gp"
        currentAudioFilePath = File(filesDir, fileName).absolutePath
        try {
            // MediaRecorder(Context) was added in API 31; use no-arg constructor on older versions
            @Suppress("DEPRECATION")
            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                MediaRecorder()
            }
            mediaRecorder?.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                setOutputFile(currentAudioFilePath)
                prepare()
                start()
            }
            isRecording = true
            binding.btnStartAudioNotiz.text = "⏹ Aufnahme stoppen"
            binding.btnStartAudioNotiz.backgroundTintList =
                ColorStateList.valueOf(getColor(R.color.warning_red))
            Toast.makeText(this, "🎙️ Aufnahme läuft...", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e("AudioNotiz", "Recording failed", e)
            Toast.makeText(this, "Aufnahme fehlgeschlagen", Toast.LENGTH_SHORT).show()
            mediaRecorder?.release()
            mediaRecorder = null
            currentRecordingId = 0L
            currentAudioFilePath = ""
        }
    }

    private fun stopAudioRecording() {
        try {
            mediaRecorder?.stop()
            mediaRecorder?.release()
            mediaRecorder = null
        } catch (e: Exception) {
            Log.e("AudioNotiz", "Stop recording failed", e)
            mediaRecorder?.release()
            mediaRecorder = null
        }
        isRecording = false
        binding.btnStartAudioNotiz.text = "🎙️ Aufnahme starten"
        binding.btnStartAudioNotiz.backgroundTintList =
            ColorStateList.valueOf(getColor(R.color.primary_dark))
        attemptTranscription()
    }

    private fun attemptTranscription() {
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.GERMAN.toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Bitte Notiz für Transkription sprechen")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            speechRecognitionLauncher.launch(intent)
        } catch (e: Exception) {
            Log.w("AudioNotiz", "Speech recognition not available", e)
            saveAudioNotiz(currentRecordingId, currentAudioFilePath, "")
            currentRecordingId = 0L
            currentAudioFilePath = ""
            renderAudioNotizen()
        }
    }

    private fun loadAudioNotizen(): List<AudioNotiz> {
        val prefs = getSharedPreferences("audio_notizen", MODE_PRIVATE)
        val json = prefs.getString("notizen", "[]") ?: "[]"
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                AudioNotiz(
                    id = obj.getLong("id"),
                    phaseIndex = obj.getInt("phaseIndex"),
                    phaseName = obj.getString("phaseName"),
                    timestamp = obj.getLong("timestamp"),
                    audioFilePath = obj.getString("audioFilePath"),
                    transcription = obj.optString("transcription", "")
                )
            }
        } catch (e: Exception) {
            Log.e("AudioNotiz", "Failed to load notizen", e)
            emptyList()
        }
    }

    private fun saveAudioNotiz(id: Long, audioFilePath: String, transcription: String) {
        if (id == 0L) return
        val phase = geburtPhasen[currentPhaseIndex]
        val notiz = AudioNotiz(
            id = id,
            phaseIndex = currentPhaseIndex,
            phaseName = phase.name,
            timestamp = id,
            audioFilePath = audioFilePath,
            transcription = transcription
        )
        val notizen = loadAudioNotizen().toMutableList()
        notizen.add(notiz)
        persistAudioNotizen(notizen)
    }

    private fun deleteAudioNotiz(id: Long) {
        val notizen = loadAudioNotizen().toMutableList()
        notizen.find { it.id == id }?.let { File(it.audioFilePath).delete() }
        notizen.removeAll { it.id == id }
        persistAudioNotizen(notizen)
    }

    private fun persistAudioNotizen(notizen: List<AudioNotiz>) {
        val array = JSONArray()
        notizen.forEach { n ->
            array.put(JSONObject().apply {
                put("id", n.id)
                put("phaseIndex", n.phaseIndex)
                put("phaseName", n.phaseName)
                put("timestamp", n.timestamp)
                put("audioFilePath", n.audioFilePath)
                put("transcription", n.transcription)
            })
        }
        getSharedPreferences("audio_notizen", MODE_PRIVATE)
            .edit()
            .putString("notizen", array.toString())
            .apply()
    }

    private fun renderAudioNotizen() {
        val layout = binding.audioNotizenContainer
        layout.removeAllViews()
        val notizen = loadAudioNotizen()
        if (notizen.isEmpty()) {
            val tv = TextView(this).apply {
                text = "Noch keine Aufnahmen"
                textSize = 13f
                setTextColor(getColor(R.color.text_secondary))
                setPadding(0, 4, 0, 4)
            }
            layout.addView(tv)
            return
        }
        val sdf = SimpleDateFormat("dd.MM. HH:mm", Locale.GERMAN)
        notizen.reversed().forEach { notiz ->
            val rowLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 6, 0, 6)
            }
            val headerRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            val tvInfo = TextView(this).apply {
                text = "🎙️ ${sdf.format(Date(notiz.timestamp))} · ${notiz.phaseName}"
                textSize = 13f
                setTextColor(getColor(R.color.text_secondary))
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
            }
            val btnPlay = Button(this).apply {
                text = "▶"
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(8, 0, 0, 0) }
                setPadding(16, 4, 16, 4)
                setOnClickListener { playAudioNotiz(notiz.audioFilePath) }
            }
            headerRow.addView(tvInfo)
            headerRow.addView(btnPlay)
            rowLayout.addView(headerRow)
            if (notiz.transcription.isNotEmpty()) {
                val tvTranscription = TextView(this).apply {
                    text = "📝 ${notiz.transcription}"
                    textSize = 13f
                    setTextColor(getColor(R.color.text_primary))
                    setPadding(0, 4, 0, 0)
                }
                rowLayout.addView(tvTranscription)
            }
            rowLayout.setOnLongClickListener {
                AlertDialog.Builder(this)
                    .setTitle("Aufnahme löschen?")
                    .setMessage("${sdf.format(Date(notiz.timestamp))} – ${notiz.phaseName}")
                    .setPositiveButton("Löschen") { _, _ ->
                        deleteAudioNotiz(notiz.id)
                        renderAudioNotizen()
                    }
                    .setNegativeButton("Abbrechen", null)
                    .show()
                true
            }
            layout.addView(rowLayout)
            val divider = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1
                )
                setBackgroundColor(getColor(R.color.divider))
            }
            layout.addView(divider)
        }
    }

    private fun playAudioNotiz(filePath: String) {
        val file = File(filePath)
        if (!file.exists()) {
            Toast.makeText(this, "Audiodatei nicht gefunden", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            currentMediaPlayer?.stop()
            currentMediaPlayer?.release()
            currentMediaPlayer = MediaPlayer().apply {
                setDataSource(filePath)
                prepare()
                start()
                setOnCompletionListener {
                    release()
                    currentMediaPlayer = null
                }
            }
        } catch (e: Exception) {
            Log.e("AudioNotiz", "Playback failed", e)
            Toast.makeText(this, "Wiedergabe fehlgeschlagen", Toast.LENGTH_SHORT).show()
        }
    }

    // ── Einstellungen ─────────────────────────────────────────────────────────

    private fun setupEinstellungen() {
        binding.btnEinstellungen.setOnClickListener { showSettingsDialog() }
        binding.btnExportAll.setOnClickListener { exportAllConfig() }
        binding.btnImportAll.setOnClickListener {
            importAllConfigLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
        }
    }

    private fun showSettingsDialog() {
        val sdfDate = SimpleDateFormat("dd.MM.yyyy", Locale.GERMAN)
        val dialogLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }

        fun addLabel(text: String) {
            dialogLayout.addView(TextView(this).apply {
                this.text = text
                textSize = 13f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(getColor(R.color.primary_dark))
                setPadding(0, 16, 0, 4)
            })
        }

        fun addNumberField(label: String, currentValue: Int): EditText {
            addLabel(label)
            return EditText(this).apply {
                setText(currentValue.toString())
                inputType = InputType.TYPE_CLASS_NUMBER
                textSize = 14f
                dialogLayout.addView(this)
            }
        }

        fun addTextField(label: String, currentValue: String): EditText {
            addLabel(label)
            return EditText(this).apply {
                setText(currentValue)
                inputType = InputType.TYPE_CLASS_TEXT
                textSize = 14f
                dialogLayout.addView(this)
            }
        }

        // Due date clickable
        addLabel("📅 Errechneter Geburtstermin")
        val tvDueDate = TextView(this).apply {
            text = sdfDate.format(dueDateCalendar.time)
            textSize = 14f
            setTextColor(getColor(R.color.link_blue))
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 4, 0, 4)
        }
        var tempDueDateCal = dueDateCalendar.clone() as Calendar
        tvDueDate.setOnClickListener {
            showDateTimePicker(tempDueDateCal) { cal ->
                tempDueDateCal = cal
                tvDueDate.text = sdfDate.format(cal.time)
            }
        }
        dialogLayout.addView(tvDueDate)

        addLabel("⏱️ Blasensprung-Warnung Orange (Stunden)")
        val etBsOrange = addNumberField("", blasensprungWarnOrangeH)
        addLabel("⏱️ Blasensprung-Warnung Rot (Stunden)")
        val etBsRed = addNumberField("", blasensprungWarnRedH)

        addLabel("🔔 Einleitung Warnung Orange (Stunden)")
        val etElOrange = addNumberField("", einleitungWarnOrangeH)
        addLabel("🔔 Einleitung Warnung Rot (Stunden)")
        val etElRed = addNumberField("", einleitungWarnRedH)

        addLabel("🌊 Wehen unregelm. Warnung Orange (Stunden)")
        val etWuOrange = addNumberField("", wehenUnregWarnOrangeH)
        addLabel("🌊 Wehen unregelm. Warnung Rot (Stunden)")
        val etWuRed = addNumberField("", wehenUnregWarnRedH)

        addLabel("⚡ Wehen regelm. Warnung Orange (Stunden)")
        val etWrOrange = addNumberField("", wehenRegWarnOrangeH)
        addLabel("⚡ Wehen regelm. Warnung Rot (Stunden)")
        val etWrRed = addNumberField("", wehenRegWarnRedH)

        val etHospitalPhone = addTextField("📞 Krankenhaus-Rufnummer (für Anruf-Button)", hospitalCallPhone)
        val etOpnvUrl = addTextField("🚌 ÖPNV-URL (für Transport-Button)", opnvUrl)

        // ── Übersicht aktueller Listen ────────────────────────────────────────
        dialogLayout.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 2
            ).also { it.setMargins(0, 24, 0, 8) }
            setBackgroundColor(getColor(R.color.divider))
        })
        dialogLayout.addView(TextView(this).apply {
            text = "📋 Aktuelle Listen-Konfiguration"
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.primary_dark))
            setPadding(0, 8, 0, 8)
        })

        // Medical hints
        addLabel("🏥 Medizinische Hinweise (${medicalItems.size} Einträge):")
        medicalItems.forEach { (_, text) ->
            dialogLayout.addView(TextView(this).apply {
                this.text = "• $text"
                textSize = 13f
                setPadding(16, 2, 0, 2)
                setTextColor(getColor(R.color.text_primary))
            })
        }
        if (medicalItems.isEmpty()) {
            dialogLayout.addView(TextView(this).apply {
                text = "  (keine Einträge)"
                textSize = 13f
                setTextColor(getColor(R.color.text_secondary))
            })
        }

        // Checklist
        val doneTasks = tasks.count { it.done }
        addLabel("✅ Checkliste (${doneTasks}/${tasks.size} erledigt):")
        tasks.forEach { task ->
            dialogLayout.addView(TextView(this).apply {
                this.text = "${if (task.done) "☑" else "☐"} ${task.text}"
                textSize = 13f
                setPadding(16, 2, 0, 2)
                setTextColor(getColor(R.color.text_primary))
            })
        }
        if (tasks.isEmpty()) {
            dialogLayout.addView(TextView(this).apply {
                text = "  (keine Aufgaben)"
                textSize = 13f
                setTextColor(getColor(R.color.text_secondary))
            })
        }

        // Contacts
        val contactPrefs = getSharedPreferences("contacts", MODE_PRIVATE)
        val editableContacts = EDITABLE_CONTACT_KEYS.map { key ->
            Pair(key, contactPrefs.getString(key, "") ?: "")
        }
        val filledContacts = editableContacts.count { it.second.isNotEmpty() }
        addLabel("📞 Kontakte (${filledContacts}/${EDITABLE_CONTACT_KEYS.size} eingetragen):")
        editableContacts.forEach { (name, number) ->
            dialogLayout.addView(TextView(this).apply {
                this.text = if (number.isNotEmpty()) "📞 $name: $number"
                             else "✏️ $name: (nicht eingetragen)"
                textSize = 13f
                setPadding(16, 2, 0, 2)
                setTextColor(
                    getColor(if (number.isNotEmpty()) R.color.link_blue else R.color.text_secondary)
                )
            })
        }

        // Photos
        addLabel("📷 Fotos (${photoPaths.size} gespeichert)")

        val scrollView = android.widget.ScrollView(this).apply {
            addView(dialogLayout)
        }

        AlertDialog.Builder(this)
            .setTitle("⚙️ Einstellungen")
            .setView(scrollView)
            .setPositiveButton("Speichern") { _, _ ->
                dueDateCalendar.timeInMillis = tempDueDateCal.timeInMillis
                blasensprungWarnOrangeH = etBsOrange.text.toString().toIntOrNull() ?: blasensprungWarnOrangeH
                blasensprungWarnRedH = etBsRed.text.toString().toIntOrNull() ?: blasensprungWarnRedH
                einleitungWarnOrangeH = etElOrange.text.toString().toIntOrNull() ?: einleitungWarnOrangeH
                einleitungWarnRedH = etElRed.text.toString().toIntOrNull() ?: einleitungWarnRedH
                wehenUnregWarnOrangeH = etWuOrange.text.toString().toIntOrNull() ?: wehenUnregWarnOrangeH
                wehenUnregWarnRedH = etWuRed.text.toString().toIntOrNull() ?: wehenUnregWarnRedH
                wehenRegWarnOrangeH = etWrOrange.text.toString().toIntOrNull() ?: wehenRegWarnOrangeH
                wehenRegWarnRedH = etWrRed.text.toString().toIntOrNull() ?: wehenRegWarnRedH
                hospitalCallPhone = etHospitalPhone.text.toString().trim()
                opnvUrl = etOpnvUrl.text.toString().trim()
                saveSettings()
                // Refresh due date and SSW display
                val sdfD = SimpleDateFormat("dd.MM.yyyy", Locale.GERMAN)
                binding.tvDueDate.text = sdfD.format(dueDateCalendar.time)
                val lmpCal = dueDateCalendar.clone() as Calendar
                lmpCal.add(Calendar.DAY_OF_YEAR, -280)
                val totalDaysFromLmp = TimeUnit.MILLISECONDS.toDays(blasensprungTime - lmpCal.timeInMillis)
                binding.tvSsw.text = "SSW ${totalDaysFromLmp / 7}+${totalDaysFromLmp % 7}"
                Toast.makeText(this, "Einstellungen gespeichert ✓", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    // ── Gesamt-Export / Import ────────────────────────────────────────────────

    private fun exportAllConfig() {
        try {
            val root = JSONObject()

            // Settings
            val settingsPrefs = getSharedPreferences(PREFS_SETTINGS, MODE_PRIVATE)
            val settingsObj = JSONObject()
            settingsPrefs.all.forEach { (k, v) -> settingsObj.put(k, v) }
            root.put("einstellungen", settingsObj)

            // Blasensprung
            val bsPrefs = getSharedPreferences(PREFS_BLASENSPRUNG, MODE_PRIVATE)
            root.put("blasensprung_timestamp", bsPrefs.getLong("timestamp", blasensprungDefault))

            // Geburtszeit
            val gzPrefs = getSharedPreferences("geburtszeit", MODE_PRIVATE)
            root.put("geburtszeit_timestamp", gzPrefs.getLong("timestamp", 0L))

            // Milestones
            val msPrefs = getSharedPreferences("milestones", MODE_PRIVATE)
            root.put("einleitung_start", msPrefs.getLong("einleitung", 0L))
            root.put("wehen_unreg_start", msPrefs.getLong("wehen_unregelmaessig", 0L))
            root.put("wehen_reg_start", msPrefs.getLong("wehen_regelmaessig", 0L))

            // Phasen
            val phasenPrefs = getSharedPreferences("phasen", MODE_PRIVATE)
            root.put("current_phase", phasenPrefs.getInt("currentPhase", 0))

            // Custom timers
            val ctPrefs = getSharedPreferences("custom_timers", MODE_PRIVATE)
            root.put("custom_timers", JSONArray(ctPrefs.getString("timers_json", "[]") ?: "[]"))

            // Medical items
            val medPrefs = getSharedPreferences(PREFS_MEDICAL_LIST, MODE_PRIVATE)
            root.put("medical_items", JSONArray(medPrefs.getString("items_json", "[]") ?: "[]"))

            // Notizen list
            val notPrefs = getSharedPreferences(PREFS_NOTIZEN_LIST, MODE_PRIVATE)
            root.put("notizen_items", JSONArray(notPrefs.getString("items_json", "[]") ?: "[]"))

            // Inline notes
            val einleitungNotes = getSharedPreferences("einleitung_notizen", MODE_PRIVATE)
            root.put("einleitung_notizen", einleitungNotes.getString("text", "") ?: "")
            val wehenUnregNotes = getSharedPreferences("wehen_unregelmaessig_notizen", MODE_PRIVATE)
            root.put("wehen_unreg_notizen", wehenUnregNotes.getString("text", "") ?: "")
            val wehenRegNotes = getSharedPreferences("wehen_regelmaessig_notizen", MODE_PRIVATE)
            root.put("wehen_reg_notizen", wehenRegNotes.getString("text", "") ?: "")

            // Checklist
            val checkPrefs = getSharedPreferences("checklist", MODE_PRIVATE)
            root.put("checklist", JSONArray(checkPrefs.getString("tasks", "[]") ?: "[]"))

            // Contacts
            val contactPrefs = getSharedPreferences("contacts", MODE_PRIVATE)
            val contactObj = JSONObject()
            EDITABLE_CONTACT_KEYS.forEach { key -> contactObj.put(key, contactPrefs.getString(key, "") ?: "") }
            root.put("contacts", contactObj)

            // Betreuung
            val betrPrefs = getSharedPreferences("betreuung", MODE_PRIVATE)
            root.put("betreuung", JSONArray(betrPrefs.getString("eintraege", "[]") ?: "[]"))

            // Eckdaten
            val eckPrefs = getSharedPreferences("eckdaten", MODE_PRIVATE)
            val eckObj = JSONObject()
            listOf("name", "gewicht_g", "groesse_cm", "kopfumfang_cm", "apgar_1", "apgar_5", "apgar_10", "geburtsart", "geburtsort", "blutgruppe", "notizen")
                .forEach { key -> eckObj.put(key, eckPrefs.getString(key, "") ?: "") }
            root.put("eckdaten", eckObj)

            // Geburtswünsche
            root.put("geburtswuensche", JSONArray(
                getSharedPreferences(PREFS_GEBURTSWUENSCHE, MODE_PRIVATE).getString("items_json", "[]") ?: "[]"
            ))

            // KinderInfo
            val kinderPrefs = getSharedPreferences(PREFS_KINDER_INFO, MODE_PRIVATE)
            root.put("kinder_info", kinderPrefs.getString("text", "") ?: "")

            // HospitalInfo
            val hospitalPrefs = getSharedPreferences(PREFS_HOSPITAL_INFO, MODE_PRIVATE)
            root.put("hospital_info", hospitalPrefs.getString("text", "") ?: "")

            // Audio notizen metadata
            val audioPrefs = getSharedPreferences("audio_notizen", MODE_PRIVATE)
            root.put("audio_notizen", JSONArray(audioPrefs.getString("notizen", "[]") ?: "[]"))

            val json = root.toString(2)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_TEXT, json)
                putExtra(Intent.EXTRA_SUBJECT, "Geburt2026 – Gesamtkonfiguration")
            }
            startActivity(Intent.createChooser(intent, "Konfiguration exportieren"))
        } catch (e: Exception) {
            Log.e("ExportAll", "Export failed", e)
            Toast.makeText(this, "Export fehlgeschlagen", Toast.LENGTH_SHORT).show()
        }
    }

    private fun importAllConfig(uri: Uri) {
        AlertDialog.Builder(this)
            .setTitle("📥 Alles importieren")
            .setMessage("Alle bestehenden Konfigurationen werden überschrieben. Fortfahren?")
            .setPositiveButton("Ja") { _, _ ->
                try {
                    val json = contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: return@setPositiveButton
                    val root = JSONObject(json)

                    // Settings
                    if (root.has("einstellungen")) {
                        val s = root.getJSONObject("einstellungen")
                        val sp = getSharedPreferences(PREFS_SETTINGS, MODE_PRIVATE).edit()
                        if (s.has("due_date_millis")) sp.putLong("due_date_millis", s.getLong("due_date_millis"))
                        if (s.has("blasensprung_warn_orange_h")) sp.putInt("blasensprung_warn_orange_h", s.getInt("blasensprung_warn_orange_h"))
                        if (s.has("blasensprung_warn_red_h")) sp.putInt("blasensprung_warn_red_h", s.getInt("blasensprung_warn_red_h"))
                        if (s.has("einleitung_warn_orange_h")) sp.putInt("einleitung_warn_orange_h", s.getInt("einleitung_warn_orange_h"))
                        if (s.has("einleitung_warn_red_h")) sp.putInt("einleitung_warn_red_h", s.getInt("einleitung_warn_red_h"))
                        if (s.has("wehen_unreg_warn_orange_h")) sp.putInt("wehen_unreg_warn_orange_h", s.getInt("wehen_unreg_warn_orange_h"))
                        if (s.has("wehen_unreg_warn_red_h")) sp.putInt("wehen_unreg_warn_red_h", s.getInt("wehen_unreg_warn_red_h"))
                        if (s.has("wehen_reg_warn_orange_h")) sp.putInt("wehen_reg_warn_orange_h", s.getInt("wehen_reg_warn_orange_h"))
                        if (s.has("wehen_reg_warn_red_h")) sp.putInt("wehen_reg_warn_red_h", s.getInt("wehen_reg_warn_red_h"))
                        if (s.has("hospital_call_phone")) sp.putString("hospital_call_phone", s.getString("hospital_call_phone"))
                        if (s.has("opnv_url")) sp.putString("opnv_url", s.getString("opnv_url"))
                        sp.apply()
                        loadSettings()
                    }

                    // Blasensprung
                    if (root.has("blasensprung_timestamp")) {
                        val ts = root.getLong("blasensprung_timestamp")
                        getSharedPreferences(PREFS_BLASENSPRUNG, MODE_PRIVATE).edit().putLong("timestamp", ts).apply()
                        blasensprungTime = ts
                    }

                    // Geburtszeit
                    if (root.has("geburtszeit_timestamp")) {
                        val ts = root.getLong("geburtszeit_timestamp")
                        getSharedPreferences("geburtszeit", MODE_PRIVATE).edit().putLong("timestamp", ts).apply()
                        geburtszeit = ts
                    }

                    // Milestones
                    val msEditor = getSharedPreferences("milestones", MODE_PRIVATE).edit()
                    if (root.has("einleitung_start")) { val v = root.getLong("einleitung_start"); einleitungStartTime = v; msEditor.putLong("einleitung", v) }
                    if (root.has("wehen_unreg_start")) { val v = root.getLong("wehen_unreg_start"); wehenUnregelStartTime = v; msEditor.putLong("wehen_unregelmaessig", v) }
                    if (root.has("wehen_reg_start")) { val v = root.getLong("wehen_reg_start"); wehenRegelStartTime = v; msEditor.putLong("wehen_regelmaessig", v) }
                    msEditor.apply()

                    // Phasen
                    if (root.has("current_phase")) {
                        val idx = root.getInt("current_phase")
                        getSharedPreferences("phasen", MODE_PRIVATE).edit().putInt("currentPhase", idx).apply()
                        currentPhaseIndex = idx
                    }

                    // Custom timers
                    if (root.has("custom_timers")) {
                        getSharedPreferences("custom_timers", MODE_PRIVATE).edit()
                            .putString("timers_json", root.getJSONArray("custom_timers").toString()).apply()
                        customTimers.clear()
                        loadCustomTimers()
                    }

                    // Medical items
                    if (root.has("medical_items")) {
                        getSharedPreferences(PREFS_MEDICAL_LIST, MODE_PRIVATE).edit()
                            .putString("items_json", root.getJSONArray("medical_items").toString()).apply()
                        loadMedicalItemsIntoMemory()
                    }

                    // Notizen list
                    if (root.has("notizen_items")) {
                        getSharedPreferences(PREFS_NOTIZEN_LIST, MODE_PRIVATE).edit()
                            .putString("items_json", root.getJSONArray("notizen_items").toString()).apply()
                        loadNotizenItemsIntoMemory()
                    }

                    // Inline notes
                    if (root.has("einleitung_notizen")) {
                        val t = root.getString("einleitung_notizen")
                        getSharedPreferences("einleitung_notizen", MODE_PRIVATE).edit().putString("text", t).apply()
                        binding.etEinleitungNotizen.setText(t)
                    }
                    if (root.has("wehen_unreg_notizen")) {
                        val t = root.getString("wehen_unreg_notizen")
                        getSharedPreferences("wehen_unregelmaessig_notizen", MODE_PRIVATE).edit().putString("text", t).apply()
                        binding.etWehenUnregelNotizen.setText(t)
                    }
                    if (root.has("wehen_reg_notizen")) {
                        val t = root.getString("wehen_reg_notizen")
                        getSharedPreferences("wehen_regelmaessig_notizen", MODE_PRIVATE).edit().putString("text", t).apply()
                        binding.etWehenRegelNotizen.setText(t)
                    }

                    // Checklist
                    if (root.has("checklist")) {
                        getSharedPreferences("checklist", MODE_PRIVATE).edit()
                            .putString("tasks", root.getJSONArray("checklist").toString()).apply()
                        tasks.clear()
                        tasks.addAll(loadTasks())
                    }

                    // Contacts
                    if (root.has("contacts")) {
                        val contactObj = root.getJSONObject("contacts")
                        val sp = getSharedPreferences("contacts", MODE_PRIVATE).edit()
                        EDITABLE_CONTACT_KEYS.forEach { key ->
                            if (contactObj.has(key)) sp.putString(key, contactObj.getString(key))
                        }
                        sp.apply()
                    }

                    // Betreuung
                    if (root.has("betreuung")) {
                        getSharedPreferences("betreuung", MODE_PRIVATE).edit()
                            .putString("eintraege", root.getJSONArray("betreuung").toString()).apply()
                    }

                    // Eckdaten
                    if (root.has("eckdaten")) {
                        val eckObj = root.getJSONObject("eckdaten")
                        val sp = getSharedPreferences("eckdaten", MODE_PRIVATE).edit()
                        listOf("name", "gewicht_g", "groesse_cm", "kopfumfang_cm", "apgar_1", "apgar_5", "apgar_10", "geburtsart", "geburtsort", "blutgruppe", "notizen")
                            .forEach { key -> if (eckObj.has(key)) sp.putString(key, eckObj.getString(key)) }
                        sp.apply()
                    }

                    // Geburtswünsche
                    if (root.has("geburtswuensche")) {
                        getSharedPreferences(PREFS_GEBURTSWUENSCHE, MODE_PRIVATE).edit()
                            .putString("items_json", root.getJSONArray("geburtswuensche").toString()).apply()
                        geburtswuensche.clear()
                        geburtswuensche.addAll(loadGeburtswuensceListe())
                    }

                    // KinderInfo
                    if (root.has("kinder_info")) {
                        getSharedPreferences(PREFS_KINDER_INFO, MODE_PRIVATE).edit()
                            .putString("text", root.getString("kinder_info")).apply()
                    }

                    // HospitalInfo
                    if (root.has("hospital_info")) {
                        getSharedPreferences(PREFS_HOSPITAL_INFO, MODE_PRIVATE).edit()
                            .putString("text", root.getString("hospital_info")).apply()
                    }

                    // Audio notizen metadata
                    if (root.has("audio_notizen")) {
                        getSharedPreferences("audio_notizen", MODE_PRIVATE).edit()
                            .putString("notizen", root.getJSONArray("audio_notizen").toString()).apply()
                    }

                    // Refresh all UI
                    setupBirthInfo()
                    updateGeburtszeitDisplay()
                    setupMilestoneTimers()
                    renderMedicalItems()
                    renderNotizenItems()
                    renderGeburtswuensche()
                    renderCustomTimers()
                    setupChecklist()
                    setupContacts()
                    setupEckdaten()
                    renderBetreuung()
                    renderAudioNotizen()
                    applyPhase(currentPhaseIndex)
                    setupKinderInfo()
                    setupHospitalInfo()

                    Toast.makeText(this, "Import erfolgreich ✓", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Log.e("ImportAll", "Import failed", e)
                    Toast.makeText(this, "Import fehlgeschlagen: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    companion object {
        private val EDITABLE_CONTACT_KEYS = listOf(
            "Oma (Sipplinen)", "Hebamme", "Kinderarzt", "Arbeit (Teams)", "Gemeinde (Essen)"
        )

        // Default values for milestone warning thresholds (hours)
        private const val EINLEITUNG_WARN_ORANGE_H_DEFAULT = 12
        private const val EINLEITUNG_WARN_RED_H_DEFAULT = 24

        private const val WEHEN_UNREG_WARN_ORANGE_H_DEFAULT = 8
        private const val WEHEN_UNREG_WARN_RED_H_DEFAULT = 12

        private const val WEHEN_REG_WARN_ORANGE_H_DEFAULT = 4
        private const val WEHEN_REG_WARN_RED_H_DEFAULT = 8

        private const val DEFAULT_HOSPITAL_CALL_PHONE = "0753180100"
        private const val DEFAULT_OPNV_URL =
            "https://www.bahn.de/buchung/fahrplan/suche#sts=true&so=Sipplinen&zo=Singen+(Htw)&kl=2&r=13:16:KLASSENLOS:1&soid=A%3D1%40L%3D8005762&zoid=A%3D1%40L%3D8005745&soei=8005762&zoei=8005745&hd=2026-02-22T08:00:00"
        private const val DEFAULT_DUE_DATE_YEAR = 2026
        private const val DEFAULT_DUE_DATE_MONTH = 2 // Calendar.MARCH = 2
        private const val DEFAULT_DUE_DATE_DAY = 8

        private const val PREFS_BLASENSPRUNG = "blasensprung"
        private const val PREFS_NOTIZEN_LIST = "notizen_list"
        private const val PREFS_MEDICAL_LIST = "medical_list"
        private const val PREFS_PHOTOS = "photos"
        private const val PREFS_SETTINGS = "einstellungen"
        private const val PREFS_KINDER_INFO = "kinder_info"
        private const val PREFS_HOSPITAL_INFO = "hospital_info"
        private const val PREFS_GEBURTSWUENSCHE = "geburtswuensche"
    }
}
