package com.geburt2026.app

import android.app.DatePickerDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class SetupWizardActivity : AppCompatActivity() {

    private var currentStep = 0
    private val totalSteps = 6

    // Collected wizard values
    private val dueDateCal: Calendar = Calendar.getInstance().apply {
        set(2026, Calendar.MARCH, 8, 0, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }

    // Step views (set in onCreate)
    private lateinit var stepWelcome: View
    private lateinit var stepDueDate: View
    private lateinit var stepHospital: View
    private lateinit var stepContacts: View
    private lateinit var stepKinder: View
    private lateinit var stepDone: View

    private lateinit var tvStepIndicator: TextView
    private lateinit var tvStepTitle: TextView
    private lateinit var btnBack: Button
    private lateinit var btnNext: Button

    private lateinit var tvWizardDueDate: TextView
    private lateinit var rgHospital: RadioGroup
    private lateinit var etHospitalPhone: EditText
    private lateinit var etHebammePhone: EditText
    private lateinit var etOmaPhone: EditText
    private lateinit var etKinderarztPhone: EditText
    private lateinit var etKinderInfo: EditText
    private lateinit var tvWizardSummary: TextView

    // Dialable phone numbers per hospital radio button id
    private val hospitalPhoneForId = mapOf(
        R.id.rbKhKonstanz to "075318012830",
        R.id.rbKhSingen to "07731891710",
        R.id.rbKhUeberlingen to "07551891310"
    )

    private val stepTitles = listOf(
        "Willkommen",
        "Geburtstermin",
        "Krankenhaus",
        "Kontakte",
        "Kinder",
        "Fertig"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // If wizard already completed, go straight to MainActivity
        if (getSharedPreferences("wizard", MODE_PRIVATE).getBoolean("wizard_completed", false)) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_setup_wizard)

        // Bind views
        stepWelcome = findViewById(R.id.stepWelcome)
        stepDueDate = findViewById(R.id.stepDueDate)
        stepHospital = findViewById(R.id.stepHospital)
        stepContacts = findViewById(R.id.stepContacts)
        stepKinder = findViewById(R.id.stepKinder)
        stepDone = findViewById(R.id.stepDone)
        tvStepIndicator = findViewById(R.id.tvStepIndicator)
        tvStepTitle = findViewById(R.id.tvStepTitle)
        btnBack = findViewById(R.id.btnWizardBack)
        btnNext = findViewById(R.id.btnWizardNext)
        tvWizardDueDate = findViewById(R.id.tvWizardDueDate)
        rgHospital = findViewById(R.id.rgHospital)
        etHospitalPhone = findViewById(R.id.etHospitalPhone)
        etHebammePhone = findViewById(R.id.etHebammePhone)
        etOmaPhone = findViewById(R.id.etOmaPhone)
        etKinderarztPhone = findViewById(R.id.etKinderarztPhone)
        etKinderInfo = findViewById(R.id.etKinderInfo)
        tvWizardSummary = findViewById(R.id.tvWizardSummary)

        // Load existing due date from prefs
        val settingsPrefs = getSharedPreferences("einstellungen", MODE_PRIVATE)
        val defaultDueDateMillis = Calendar.getInstance().apply {
            set(2026, Calendar.MARCH, 8, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        dueDateCal.timeInMillis = settingsPrefs.getLong("due_date_millis", defaultDueDateMillis)
        tvWizardDueDate.text = SimpleDateFormat("dd.MM.yyyy", Locale.GERMAN).format(dueDateCal.time)

        // Load existing hospital phone and pre-select matching radio button
        val existingPhone = settingsPrefs.getString("hospital_call_phone", "") ?: ""
        etHospitalPhone.setText(existingPhone)
        val existingDigits = existingPhone.filter { it.isDigit() }
        val matchedRadio = hospitalPhoneForId.entries
            .firstOrNull { it.value.filter { c -> c.isDigit() } == existingDigits }
        if (matchedRadio != null) {
            rgHospital.check(matchedRadio.key)
        } else if (existingPhone.isEmpty()) {
            rgHospital.check(R.id.rbKhKonstanz)
            etHospitalPhone.setText(hospitalPhoneForId[R.id.rbKhKonstanz])
            etHospitalPhone.isEnabled = false
        }

        // Load existing contacts
        val contactPrefs = getSharedPreferences("contacts", MODE_PRIVATE)
        etHebammePhone.setText(contactPrefs.getString("Hebamme", "") ?: "")
        etOmaPhone.setText(contactPrefs.getString("Oma/Opa", "") ?: "")
        etKinderarztPhone.setText(contactPrefs.getString("Kinderarzt", "") ?: "")

        // Load existing kinder info
        val kinderPrefs = getSharedPreferences("kinder_info", MODE_PRIVATE)
        etKinderInfo.setText(kinderPrefs.getString("text", "") ?: "")

        // Due date click → show DatePickerDialog
        tvWizardDueDate.setOnClickListener {
            val cal = dueDateCal.clone() as Calendar
            DatePickerDialog(
                this,
                { _, year, month, day ->
                    dueDateCal.set(year, month, day, 0, 0, 0)
                    dueDateCal.set(Calendar.MILLISECOND, 0)
                    tvWizardDueDate.text = SimpleDateFormat("dd.MM.yyyy", Locale.GERMAN).format(dueDateCal.time)
                },
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH),
                cal.get(Calendar.DAY_OF_MONTH)
            ).show()
        }

        // Hospital radio selection → auto-fill phone
        rgHospital.setOnCheckedChangeListener { _, checkedId ->
            val phone = hospitalPhoneForId[checkedId]
            if (phone != null) {
                etHospitalPhone.setText(phone)
                etHospitalPhone.isEnabled = false
            } else {
                // "Anderes" selected
                etHospitalPhone.isEnabled = true
                etHospitalPhone.text.clear()
                etHospitalPhone.requestFocus()
            }
        }

        btnBack.setOnClickListener { navigateTo(currentStep - 1) }
        btnNext.setOnClickListener {
            if (currentStep == totalSteps - 1) {
                finishWizard()
            } else {
                navigateTo(currentStep + 1)
            }
        }

        showStep(0)
    }

    private fun showStep(step: Int) {
        currentStep = step

        // Hide all steps
        stepWelcome.visibility = View.GONE
        stepDueDate.visibility = View.GONE
        stepHospital.visibility = View.GONE
        stepContacts.visibility = View.GONE
        stepKinder.visibility = View.GONE
        stepDone.visibility = View.GONE

        // Show current step
        when (step) {
            0 -> stepWelcome.visibility = View.VISIBLE
            1 -> stepDueDate.visibility = View.VISIBLE
            2 -> stepHospital.visibility = View.VISIBLE
            3 -> stepContacts.visibility = View.VISIBLE
            4 -> stepKinder.visibility = View.VISIBLE
            5 -> {
                stepDone.visibility = View.VISIBLE
                buildSummary()
            }
        }

        // Update header
        tvStepIndicator.text = "Schritt ${step + 1} von $totalSteps"
        tvStepTitle.text = stepTitles[step]

        // Back button visibility
        btnBack.visibility = if (step > 0) View.VISIBLE else View.INVISIBLE

        // Next / finish button label
        btnNext.text = if (step == totalSteps - 1) "Los geht's! 🚀" else "Weiter →"
    }

    private fun navigateTo(step: Int) {
        if (step < 0 || step >= totalSteps) return
        if (step > currentStep) saveCurrentStep()
        showStep(step)
    }

    private fun saveCurrentStep() {
        when (currentStep) {
            1 -> {
                // Persist due date
                getSharedPreferences("einstellungen", MODE_PRIVATE).edit()
                    .putLong("due_date_millis", dueDateCal.timeInMillis)
                    .apply()
            }
            2 -> {
                // Persist hospital phone
                val phone = etHospitalPhone.text.toString().trim()
                if (phone.isNotEmpty()) {
                    getSharedPreferences("einstellungen", MODE_PRIVATE).edit()
                        .putString("hospital_call_phone", phone)
                        .apply()
                }
            }
            3 -> {
                // Persist contacts
                val editor = getSharedPreferences("contacts", MODE_PRIVATE).edit()
                val hebamme = etHebammePhone.text.toString().trim()
                val oma = etOmaPhone.text.toString().trim()
                val kinderarzt = etKinderarztPhone.text.toString().trim()
                if (hebamme.isNotEmpty()) editor.putString("Hebamme", hebamme)
                if (oma.isNotEmpty()) editor.putString("Oma/Opa", oma)
                if (kinderarzt.isNotEmpty()) editor.putString("Kinderarzt", kinderarzt)
                editor.apply()
            }
            4 -> {
                // Persist kinder info
                val kinderText = etKinderInfo.text.toString().trim()
                if (kinderText.isNotEmpty()) {
                    getSharedPreferences("kinder_info", MODE_PRIVATE).edit()
                        .putString("text", kinderText)
                        .apply()
                }
            }
        }
    }

    private fun buildSummary() {
        val sdf = SimpleDateFormat("dd.MM.yyyy", Locale.GERMAN)
        val settingsPrefs = getSharedPreferences("einstellungen", MODE_PRIVATE)
        val contactPrefs = getSharedPreferences("contacts", MODE_PRIVATE)
        val kinderPrefs = getSharedPreferences("kinder_info", MODE_PRIVATE)

        val dueDateStr = sdf.format(dueDateCal.time)
        val hospitalPhone = settingsPrefs.getString("hospital_call_phone", "") ?: ""
        val hospitalName = when (hospitalPhone.filter { it.isDigit() }) {
            "075318012830" -> "KH Konstanz"
            "07731891710" -> "KH Singen"
            "07551891310" -> "KH Überlingen"
            else -> if (hospitalPhone.isNotEmpty()) "Anderes ($hospitalPhone)" else "(nicht eingetragen)"
        }
        val hebamme = contactPrefs.getString("Hebamme", "") ?: ""
        val oma = contactPrefs.getString("Oma/Opa", "") ?: ""
        val kinderarzt = contactPrefs.getString("Kinderarzt", "") ?: ""
        val kinderInfo = kinderPrefs.getString("text", "") ?: ""

        tvWizardSummary.text = buildString {
            append("📅 Geburtstermin: $dueDateStr\n")
            append("🏥 Krankenhaus: $hospitalName\n")
            append("🏥 Hebamme: ${if (hebamme.isNotEmpty()) hebamme else "(nicht eingetragen)"}\n")
            append("👵 Oma/Opa: ${if (oma.isNotEmpty()) oma else "(nicht eingetragen)"}\n")
            append("👶 Kinderarzt: ${if (kinderarzt.isNotEmpty()) kinderarzt else "(nicht eingetragen)"}\n")
            append("👨‍👩‍👧‍👦 Kinder: ${if (kinderInfo.isNotEmpty()) "(eingetragen)" else "(nicht eingetragen)"}")
        }
    }

    private fun finishWizard() {
        // Persist the last step's data
        saveCurrentStep()

        // Mark wizard as completed
        getSharedPreferences("wizard", MODE_PRIVATE).edit()
            .putBoolean("wizard_completed", true)
            .apply()

        // Launch the main app
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (currentStep > 0) {
            navigateTo(currentStep - 1)
        } else {
            super.onBackPressed()
        }
    }
}

