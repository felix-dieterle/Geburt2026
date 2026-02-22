package com.geburt2026.app

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import com.geburt2026.app.databinding.ActivityMainBinding
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
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

    // Blasensprung: 22.02.2026 um 6:15 Uhr
    private val blasensprungTime: Long = Calendar.getInstance().apply {
        set(2026, Calendar.FEBRUARY, 22, 6, 15, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    // Errechneter Geburtstermin: 08.03.2026
    private val dueDateCalendar: Calendar = Calendar.getInstance().apply {
        set(2026, Calendar.MARCH, 8, 0, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }

    // Milestone timer timestamps (0 = not started)
    private var einleitungStartTime: Long = 0L
    private var wehenUnregelStartTime: Long = 0L
    private var wehenRegelStartTime: Long = 0L

    private val timerRunnable = object : Runnable {
        override fun run() {
            updateBirthTimer()
            updateMilestoneTimers()
            handler.postDelayed(this, 1000)
        }
    }

    private val geburtswuensche = listOf(
        "Wenig CTG",
        "Wenig Untersuchungen",
        "Nabelschnur ausbluten / auspulsieren lassen",
        "Ambulante Geburt",
        "Hörtest ggf. gleich nach Geburt",
    )

    private val geburtPhasen: List<GeburtPhase> by lazy {
        listOf(
            GeburtPhase("🌅", "Latenzphase", "Unregelmäßige Wehen – Vorbereitung & Abwarten") { b ->
                listOf(b.cardTimer, b.cardMilestones, b.cardLabor, b.cardNotes, b.cardKids, b.cardBetreuung, b.cardChecklist, b.cardContacts)
            },
            GeburtPhase("🌊", "Eröffnungsphase", "Regelmäßige Wehen – Gebärmutterhals öffnet sich") { b ->
                listOf(b.cardTimer, b.cardMilestones, b.cardMedical, b.cardWishes, b.cardLabor, b.cardHospital, b.cardContacts)
            },
            GeburtPhase("⚡", "Übergangsphase", "Intensive Wehen – kurze Pausen, Fokus halten") { b ->
                listOf(b.cardTimer, b.cardMilestones, b.cardMedical, b.cardWishes, b.cardHospital, b.cardContacts)
            },
            GeburtPhase("💪", "Austreibungsphase", "Pressen – Baby kommt!") { b ->
                listOf(b.cardTimer, b.cardMedical, b.cardWishes, b.cardHospital, b.cardContacts)
            },
            GeburtPhase("🍼", "Nachgeburtsphase", "Hep-B-Impfung, erste Stunden, Nachgeburt") { b ->
                listOf(b.cardMedical, b.cardChecklist, b.cardContacts)
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

        setupBirthInfo()
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
        setupSearch()
        setupPhasen()
    }

    override fun onResume() {
        super.onResume()
        handler.post(timerRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(timerRunnable)
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

        // Medizinischer Hinweis: Nach 18h Blasensprung steigt Infektionsrisiko
        when {
            hours >= 24 -> {
                binding.tvTimerWarning.visibility = View.VISIBLE
                binding.tvTimerWarning.text = "⚠️ >24h: Erhöhtes Infektionsrisiko – Arzt informieren!"
                binding.tvTimerWarning.setTextColor(getColor(R.color.warning_red))
            }
            hours >= 18 -> {
                binding.tvTimerWarning.visibility = View.VISIBLE
                binding.tvTimerWarning.text = "⚠️ >18h: Arzt über Blasensprung-Dauer informieren"
                binding.tvTimerWarning.setTextColor(getColor(R.color.warning_orange))
            }
            else -> {
                binding.tvTimerWarning.visibility = View.GONE
            }
        }
    }

    private fun setupBirthInfo() {
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
    }

    private fun setupMilestoneTimers() {
        val prefs = getSharedPreferences("milestones", MODE_PRIVATE)
        einleitungStartTime = prefs.getLong("einleitung", 0L)
        wehenUnregelStartTime = prefs.getLong("wehen_unregelmaessig", 0L)
        wehenRegelStartTime = prefs.getLong("wehen_regelmaessig", 0L)

        updateMilestoneTimerRow(
            einleitungStartTime,
            binding.tvEinleitungTime,
            binding.tvEinleitungElapsed,
            binding.tvEinleitungWarning,
            warnOrangeHours = EINLEITUNG_WARN_ORANGE_H, warnOrangeText = EINLEITUNG_WARN_ORANGE_TEXT,
            warnRedHours = EINLEITUNG_WARN_RED_H, warnRedText = EINLEITUNG_WARN_RED_TEXT
        )
        updateMilestoneTimerRow(
            wehenUnregelStartTime,
            binding.tvWehenUnregelTime,
            binding.tvWehenUnregelElapsed,
            binding.tvWehenUnregelWarning,
            warnOrangeHours = WEHEN_UNREG_WARN_ORANGE_H, warnOrangeText = WEHEN_UNREG_WARN_ORANGE_TEXT,
            warnRedHours = WEHEN_UNREG_WARN_RED_H, warnRedText = WEHEN_UNREG_WARN_RED_TEXT
        )
        updateMilestoneTimerRow(
            wehenRegelStartTime,
            binding.tvWehenRegelTime,
            binding.tvWehenRegelElapsed,
            binding.tvWehenRegelWarning,
            warnOrangeHours = WEHEN_REG_WARN_ORANGE_H, warnOrangeText = WEHEN_REG_WARN_ORANGE_TEXT,
            warnRedHours = WEHEN_REG_WARN_RED_H, warnRedText = WEHEN_REG_WARN_RED_TEXT
        )

        binding.btnStartEinleitung.setOnClickListener {
            showMilestoneStartDialog("Einleitung") { ts ->
                einleitungStartTime = ts
                prefs.edit().putLong("einleitung", ts).apply()
                updateMilestoneTimerRow(
                    ts, binding.tvEinleitungTime, binding.tvEinleitungElapsed,
                    binding.tvEinleitungWarning,
                    warnOrangeHours = EINLEITUNG_WARN_ORANGE_H, warnOrangeText = EINLEITUNG_WARN_ORANGE_TEXT,
                    warnRedHours = EINLEITUNG_WARN_RED_H, warnRedText = EINLEITUNG_WARN_RED_TEXT
                )
            }
        }
        binding.btnStartWehenUnregel.setOnClickListener {
            showMilestoneStartDialog("Wehen unregelmäßig") { ts ->
                wehenUnregelStartTime = ts
                prefs.edit().putLong("wehen_unregelmaessig", ts).apply()
                updateMilestoneTimerRow(
                    ts, binding.tvWehenUnregelTime, binding.tvWehenUnregelElapsed,
                    binding.tvWehenUnregelWarning,
                    warnOrangeHours = WEHEN_UNREG_WARN_ORANGE_H, warnOrangeText = WEHEN_UNREG_WARN_ORANGE_TEXT,
                    warnRedHours = WEHEN_UNREG_WARN_RED_H, warnRedText = WEHEN_UNREG_WARN_RED_TEXT
                )
            }
        }
        binding.btnStartWehenRegel.setOnClickListener {
            showMilestoneStartDialog("Wehen regelmäßig") { ts ->
                wehenRegelStartTime = ts
                prefs.edit().putLong("wehen_regelmaessig", ts).apply()
                updateMilestoneTimerRow(
                    ts, binding.tvWehenRegelTime, binding.tvWehenRegelElapsed,
                    binding.tvWehenRegelWarning,
                    warnOrangeHours = WEHEN_REG_WARN_ORANGE_H, warnOrangeText = WEHEN_REG_WARN_ORANGE_TEXT,
                    warnRedHours = WEHEN_REG_WARN_RED_H, warnRedText = WEHEN_REG_WARN_RED_TEXT
                )
            }
        }
    }

    private fun showMilestoneStartDialog(label: String, onConfirmed: (Long) -> Unit) {
        val now = Calendar.getInstance()
        val options = arrayOf("Jetzt starten", "Uhrzeit wählen", "Zurücksetzen")
        AlertDialog.Builder(this)
            .setTitle("$label – Startzeit")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> onConfirmed(System.currentTimeMillis())
                    1 -> {
                        TimePickerDialog(
                            this,
                            { _, hour, minute ->
                                val cal = Calendar.getInstance()
                                cal.set(Calendar.HOUR_OF_DAY, hour)
                                cal.set(Calendar.MINUTE, minute)
                                cal.set(Calendar.SECOND, 0)
                                cal.set(Calendar.MILLISECOND, 0)
                                if (cal.timeInMillis > System.currentTimeMillis()) {
                                    cal.add(Calendar.DAY_OF_YEAR, -1)
                                }
                                onConfirmed(cal.timeInMillis)
                            },
                            now.get(Calendar.HOUR_OF_DAY),
                            now.get(Calendar.MINUTE),
                            true
                        ).show()
                    }
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
        val elapsed = System.currentTimeMillis() - startTime
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
            warnOrangeHours = EINLEITUNG_WARN_ORANGE_H, warnOrangeText = EINLEITUNG_WARN_ORANGE_TEXT,
            warnRedHours = EINLEITUNG_WARN_RED_H, warnRedText = EINLEITUNG_WARN_RED_TEXT
        )
        updateMilestoneTimerRow(
            wehenUnregelStartTime,
            binding.tvWehenUnregelTime,
            binding.tvWehenUnregelElapsed,
            binding.tvWehenUnregelWarning,
            warnOrangeHours = WEHEN_UNREG_WARN_ORANGE_H, warnOrangeText = WEHEN_UNREG_WARN_ORANGE_TEXT,
            warnRedHours = WEHEN_UNREG_WARN_RED_H, warnRedText = WEHEN_UNREG_WARN_RED_TEXT
        )
        updateMilestoneTimerRow(
            wehenRegelStartTime,
            binding.tvWehenRegelTime,
            binding.tvWehenRegelElapsed,
            binding.tvWehenRegelWarning,
            warnOrangeHours = WEHEN_REG_WARN_ORANGE_H, warnOrangeText = WEHEN_REG_WARN_ORANGE_TEXT,
            warnRedHours = WEHEN_REG_WARN_RED_H, warnRedText = WEHEN_REG_WARN_RED_TEXT
        )
    }

    private fun setupMedicalInfo() {
        // Hep B Hinweise für Geburtsklinik
        binding.tvHepBInfo.text = buildString {
            appendLine("• Mutter: Chron. Hepatitis B, niederschwellig aktiv")
            appendLine("• Neugeborenes: Hep-B-Simultanimpfung direkt nach Geburt!")
            appendLine("• Hep-B-Immunglobulin für Neugeborenes erforderlich")
            appendLine("• Geburtshelfer/Hebamme informiert?")
            append("• Neonatologie Rücksprache empfohlen")
        }
    }

    private fun setupGeburtsWuensche() {
        val layout = binding.wishesContainer
        layout.removeAllViews()

        geburtswuensche.forEach { wish ->
            val tv = TextView(this).apply {
                text = "• $wish"
                textSize = 14f
                setPadding(0, 6, 0, 6)
                setTextColor(getColor(R.color.text_primary))
            }
            layout.addView(tv)
        }
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
        binding.tvNotizen.text = buildString {
            appendLine("• Falls Einleitung in Konstanz: Rizinus empfohlen")
            appendLine("  (kann auch 12h gewartet werden)")
            appendLine("• Geburt/Einleitung nach Blasensprung:")
            appendLine("  Paar Tage möglich, wenn Blutwerte gut")
            append("• Nach 12h darf man nach Hause")
        }
    }

    private fun setupKinderInfo() {
        binding.tvKinderStatus.text = buildString {
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

        binding.btnOrganizeTransport.setOnClickListener {
            // ÖPNV-Verbindung suchen
            val uri = Uri.parse("https://www.bahn.de/buchung/fahrplan/suche#sts=true&so=Sipplinen&zo=Singen+(Htw)&kl=2&r=13:16:KLASSENLOS:1&soid=A%3D1%40L%3D8005762&zoid=A%3D1%40L%3D8005745&soei=8005762&zoei=8005745&hd=2026-02-22T08:00:00")
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        }
    }

    private fun setupHospitalInfo() {
        binding.tvHospitalInfo.text = buildString {
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

        binding.btnCallHospital.setOnClickListener {
            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:0753180100"))
            startActivity(intent)
        }

        binding.btnCallEmergency.setOnClickListener {
            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:112"))
            startActivity(intent)
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

    private fun setupSearch() {
        val sections = listOf(
            SearchSection(
                "🏥 Medizinische Hinweise",
                { binding.tvHepBInfo.text.toString() },
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
                { binding.tvNotizen.text.toString() },
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
            binding.cardContacts
        )
        val visibleCards = phase.visibleCards(binding)
        allCards.forEach { card ->
            card.visibility = if (visibleCards.contains(card)) View.VISIBLE else View.GONE
        }

        binding.tvCurrentPhase.text = "${phase.emoji} Phase ${index + 1}: ${phase.name}"
        binding.tvPhaseHint.text = phase.hint
        binding.btnPrevPhase.isEnabled = index > 0
        binding.btnNextPhase.isEnabled = index < geburtPhasen.lastIndex

        val indicator = (0..geburtPhasen.lastIndex).joinToString(" ") { i ->
            if (i == index) "●" else "○"
        }
        binding.tvPhaseIndicator.text = indicator
    }

    data class Task(val text: String, val done: Boolean)
    data class Contact(val name: String, val number: String, val editable: Boolean = false)
    data class BetreuungsEintrag(val id: Long, val name: String, val unbegrenzt: Boolean, val von: Long, val bis: Long, val phone: String = "")
    data class SearchSection(val title: String, val getContent: () -> String, val cardView: CardView)
    data class GeburtPhase(val emoji: String, val name: String, val hint: String, val visibleCards: (ActivityMainBinding) -> List<CardView>)

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

    companion object {
        /** Names of the editable contact entries. Used for export/import. */
        private val EDITABLE_CONTACT_KEYS = listOf(
            "Oma (Sipplinen)", "Hebamme", "Kinderarzt", "Arbeit (Teams)", "Gemeinde (Essen)"
        )

        // Milestone timer warning thresholds (hours) and messages
        private const val EINLEITUNG_WARN_ORANGE_H = 12
        private const val EINLEITUNG_WARN_ORANGE_TEXT = ">12h: Arzt über Einleitungsdauer informieren"
        private const val EINLEITUNG_WARN_RED_H = 24
        private const val EINLEITUNG_WARN_RED_TEXT = ">24h: Dringend Arzt kontaktieren!"

        private const val WEHEN_UNREG_WARN_ORANGE_H = 8
        private const val WEHEN_UNREG_WARN_ORANGE_TEXT = ">8h: Hebamme informieren"
        private const val WEHEN_UNREG_WARN_RED_H = 12
        private const val WEHEN_UNREG_WARN_RED_TEXT = ">12h: Arzt / KH kontaktieren!"

        private const val WEHEN_REG_WARN_ORANGE_H = 4
        private const val WEHEN_REG_WARN_ORANGE_TEXT = ">4h: KH-Fahrt prüfen"
        private const val WEHEN_REG_WARN_RED_H = 8
        private const val WEHEN_REG_WARN_RED_TEXT = ">8h: Sofort ins Krankenhaus!"
    }
}
