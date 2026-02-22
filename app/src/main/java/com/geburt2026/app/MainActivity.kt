package com.geburt2026.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.geburt2026.app.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val handler = Handler(Looper.getMainLooper())

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

    private val timerRunnable = object : Runnable {
        override fun run() {
            updateBirthTimer()
            handler.postDelayed(this, 1000)
        }
    }

    private val tasks = mutableListOf(
        Task("Hebamme / KH Konstanz über Hep-B-Status informiert?", false),
        Task("Kinderkleidung (4 u. 7 J.) für mind. 4 Tage gepackt?", false),
        Task("Essen für Kinder bei Oma/Opa in Sipplinen organisiert?", false),
        Task("Transport Kinder nach Hause (ohne Opas Auto) geplant?", false),
        Task("Hep-B-Impfung für Neugeborenes angemeldet?", false),
        Task("Wichtige Dokumente im Krankenhaus dabei?", false),
        Task("Kindergarten/Schule über Abwesenheit informiert?", false),
        Task("Verwandte/Freunde für Unterstützung kontaktiert?", false),
        Task("Krankenhaustasche vollständig gepackt?", false),
        Task("Notfallkontakte auf dem Handy gespeichert?", false),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupBirthInfo()
        setupMedicalInfo()
        setupGeburtsWuensche()
        setupWehenfoerderung()
        setupNotizen()
        setupKinderInfo()
        setupHospitalInfo()
        setupChecklist()
        setupContacts()
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
        val wishes = listOf(
            "Wenig CTG",
            "Wenig Untersuchungen",
            "Nabelschnur ausbluten / auspulsieren lassen",
            "Ambulante Geburt",
            "Hörtest ggf. gleich nach Geburt",
        )

        val layout = binding.wishesContainer
        layout.removeAllViews()

        wishes.forEach { wish ->
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
            appendLine("   oder Krankenhaus Konstanz")
            appendLine("📞 KH Singen: 07731 89-0")
            appendLine("📞 KH Konstanz: 07531 801-0")
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

        tasks.forEachIndexed { index, task ->
            val checkBox = CheckBox(this).apply {
                text = task.text
                isChecked = task.done
                textSize = 14f
                setPadding(0, 8, 0, 8)
                setOnCheckedChangeListener { _, isChecked ->
                    tasks[index] = task.copy(done = isChecked)
                    updateProgress()
                }
            }
            checklistLayout.addView(checkBox)
        }
        updateProgress()
    }

    private fun updateProgress() {
        val done = tasks.count { it.done }
        val total = tasks.size
        binding.tvProgress.text = "$done / $total Aufgaben erledigt"
        binding.progressBar.max = total
        binding.progressBar.progress = done
    }

    private fun setupContacts() {
        val contacts = listOf(
            Contact("Oma (Sipplinen)", ""),
            Contact("Hebamme", ""),
            Contact("KH Konstanz", "0753180100"),
            Contact("KH Singen", "0773189-0"),
            Contact("Notruf", "112"),
            Contact("Kinderarzt", ""),
        )

        val layout = binding.contactsContainer
        layout.removeAllViews()

        contacts.forEach { contact ->
            val tv = TextView(this).apply {
                text = if (contact.number.isNotEmpty()) "📞 ${contact.name}: ${contact.number}"
                       else "📞 ${contact.name}: (bitte eintragen)"
                textSize = 14f
                setPadding(0, 8, 0, 8)
                if (contact.number.isNotEmpty()) {
                    setOnClickListener {
                        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${contact.number}"))
                        startActivity(intent)
                    }
                    setTextColor(getColor(R.color.link_blue))
                }
            }
            layout.addView(tv)
        }
    }

    data class Task(val text: String, val done: Boolean)
    data class Contact(val name: String, val number: String)
}
