package com.example.trainingrecordapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import com.example.trainingrecordapp.databinding.ActivityMainBinding
import com.google.gson.GsonBuilder
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import java.io.File
import androidx.exifinterface.media.ExifInterface
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val capturedBitmaps = mutableListOf<Bitmap>()
    private var pendingSaveAfterPermissionRequest = false
    private var retrySaveAfterPermissionFromDeniedDialog = false
    private var isHealthPermissionRequestInFlight = false
    private var captureTimeMs: Long? = null

    // Camera permission launcher
    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            openCamera()
        } else {
            showPermissionDeniedDialog(
                getString(R.string.camera_permission_title),
                getString(R.string.camera_permission_message)
            )
        }
    }

    // Health Connect permissions launcher
    private val healthPermissionLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        isHealthPermissionRequestInFlight = false
        val shouldSave = pendingSaveAfterPermissionRequest
        pendingSaveAfterPermissionRequest = false
        if (granted.containsAll(HealthConnectManager.REQUIRED_PERMISSIONS)) {
            if (shouldSave) {
                lifecycleScope.launch { saveToHealthConnect() }
            }
        } else {
            retrySaveAfterPermissionFromDeniedDialog = shouldSave
            showHealthConnectPermissionDeniedDialog()
        }
    }

    // Camera activity result
    private val cameraLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val paths = result.data?.getStringArrayListExtra(CameraActivity.EXTRA_IMAGE_URIS)
            if (!paths.isNullOrEmpty()) {
                paths.mapNotNull { path ->
                    try {
                        val file = File(path)
                        if (captureTimeMs == null) {
                            try {
                                val exif = ExifInterface(file.absolutePath)
                                captureTimeMs = getExifDateTimeMs(exif)
                            } catch (e: Exception) {
                                // ignore
                            }
                        }
                        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                        file.delete()
                        bitmap
                    } catch (e: Exception) {
                        null
                    }
                }.let { bitmaps ->
                    capturedBitmaps.addAll(bitmaps)
                    updateCapturedImages()
                }
            }
        }
    }

    private val galleryLauncher = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(5)
    ) { uris ->
        if (uris.isNotEmpty()) {
            val remainingSlots = CameraActivity.MAX_PHOTOS - capturedBitmaps.size
            val urisToProcess = uris.take(remainingSlots)
            if (uris.size > remainingSlots) {
                Toast.makeText(this, getString(R.string.max_total_photos_reached, CameraActivity.MAX_PHOTOS), Toast.LENGTH_SHORT).show()
            }
            urisToProcess.mapNotNull { uri ->
                try {
                    contentResolver.openInputStream(uri)?.use { inputStream ->
                        BitmapFactory.decodeStream(inputStream)
                    }?.also {
                        if (captureTimeMs == null) {
                            try {
                                contentResolver.openInputStream(uri)?.use { stream ->
                                    val exif = ExifInterface(stream)
                                    captureTimeMs = getExifDateTimeMs(exif)
                                }
                            } catch (e: Exception) {
                                // ignore
                            }
                        }
                    }
                } catch (e: Exception) {
                    null
                }
            }.let { bitmaps ->
                capturedBitmaps.addAll(bitmaps)
                updateCapturedImages()
            }
        }
    }

    private fun getExifDateTimeMs(exif: ExifInterface): Long? {
        val dateTimeStr = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
        if (dateTimeStr != null) {
            return try {
                val sdf = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
                sdf.timeZone = TimeZone.getDefault()
                sdf.parse(dateTimeStr)?.time
            } catch (e: Exception) {
                null
            }
        }
        return null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        checkAndRequestApiKey()
        setupButtons()
        checkHealthConnectPermissionOnStartup()
        showTopScreen()
    }

    private fun showTopScreen() {
        binding.layoutTop.visibility = View.VISIBLE
        binding.layoutImage.visibility = View.GONE
        binding.layoutResult.visibility = View.GONE
    }

    private fun showImageScreen() {
        binding.layoutTop.visibility = View.GONE
        binding.layoutImage.visibility = View.VISIBLE
        binding.layoutResult.visibility = View.GONE
    }

    private fun showResultScreen() {
        binding.layoutTop.visibility = View.GONE
        binding.layoutImage.visibility = View.GONE
        binding.layoutResult.visibility = View.VISIBLE
    }

    private fun populateResultUI(record: TrainingRecord) {
        binding.etExerciseName.setText(record.exerciseName)
        binding.etMachineName.setText(record.machineName)
        binding.etDuration.setText(if (record.trainingDurationMinutes > 0) record.trainingDurationMinutes.toString() else "")
        binding.etCalories.setText(if (record.caloriesKcal > 0.0) record.caloriesKcal.toString() else "")
        binding.etTotalReps.setText(if (record.totalReps > 0) record.totalReps.toString() else "")
        binding.etTotalWeight.setText(if (record.totalVolumeKg > 0.0) record.totalVolumeKg.toString() else "")
        binding.etNotes.setText(record.notes)

        binding.layoutSets.removeAllViews()
        record.sets.forEach { set ->
            val setView = layoutInflater.inflate(R.layout.item_set, binding.layoutSets, false)
            val tvSetNumber = setView.findViewById<android.widget.TextView>(R.id.tvSetNumber)
            val etReps = setView.findViewById<android.widget.EditText>(R.id.etReps)
            val etWeight = setView.findViewById<android.widget.EditText>(R.id.etWeight)

            tvSetNumber.text = set.setNumber.toString()
            etReps.setText(if (set.reps > 0) set.reps.toString() else "")
            etWeight.setText(if (set.weightKg > 0.0) set.weightKg.toString() else "")

            binding.layoutSets.addView(setView)
        }
    }

    private fun checkAndRequestApiKey() {
        if (!SecurePreferences.hasApiKey(this)) {
            showApiKeyInputDialog()
        }
    }

    private fun showApiKeyInputDialog() {
        val editText = android.widget.EditText(this).apply {
            hint = getString(R.string.api_key_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setPadding(48, 24, 48, 24)
        }

        showStyledDialog(
            MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.api_key_dialog_title))
            .setMessage(getString(R.string.api_key_dialog_message))
            .setView(editText)
            .setCancelable(false)
            .setPositiveButton(getString(R.string.save)) { _, _ ->
                val key = editText.text.toString().trim()
                if (key.isNotBlank()) {
                    SecurePreferences.saveApiKey(this, key)
                    Toast.makeText(this, getString(R.string.api_key_saved), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, getString(R.string.api_key_empty), Toast.LENGTH_SHORT).show()
                    showApiKeyInputDialog()
                }
            }
            .setNegativeButton(getString(R.string.cancel)) { _, _ ->
                Toast.makeText(this, getString(R.string.api_key_required), Toast.LENGTH_LONG).show()
            }
        )
    }

    private fun setupButtons() {
        binding.btnCapture.setOnClickListener {
            checkCameraPermissionAndOpen()
        }

        binding.btnSelectGallery.setOnClickListener {
            if (capturedBitmaps.size >= CameraActivity.MAX_PHOTOS) {
                Toast.makeText(this, getString(R.string.max_total_photos_reached, CameraActivity.MAX_PHOTOS), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }

        binding.btnAnalyze.setOnClickListener {
            if (capturedBitmaps.isEmpty()) {
                Toast.makeText(this, getString(R.string.no_images), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            analyzeImages()
        }

        binding.btnSaveToHealthConnect.setOnClickListener {
            checkHealthConnectAndSave()
        }

        binding.btnClearImages.setOnClickListener {
            capturedBitmaps.clear()
            updateCapturedImages()
            binding.btnSaveToHealthConnect.isEnabled = false
            binding.btnSaveToHealthConnect.tag = null
            captureTimeMs = null
            showTopScreen()
        }

        binding.btnChangeApiKey.setOnClickListener {
            showApiKeyInputDialog()
        }

        binding.btnDiscard.setOnClickListener {
            capturedBitmaps.clear()
            updateCapturedImages()
            binding.btnSaveToHealthConnect.isEnabled = false
            binding.btnSaveToHealthConnect.tag = null
            captureTimeMs = null
            showTopScreen()
        }
    }

    private fun checkCameraPermissionAndOpen() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED -> openCamera()
            shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) -> {
                showStyledDialog(
                    MaterialAlertDialogBuilder(this)
                    .setTitle(getString(R.string.camera_permission_title))
                    .setMessage(getString(R.string.camera_permission_rationale))
                    .setPositiveButton(getString(R.string.request_permission)) { _, _ ->
                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                    .setNegativeButton(getString(R.string.cancel), null)
                )
            }
            else -> cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun openCamera() {
        val intent = Intent(this, CameraActivity::class.java)
        cameraLauncher.launch(intent)
    }

    private fun analyzeImages() {
        val apiKey = SecurePreferences.getApiKey(this)
        if (apiKey.isNullOrBlank()) {
            showApiKeyInputDialog()
            return
        }

        binding.progressBar.visibility = View.VISIBLE
        binding.btnAnalyze.isEnabled = false

        lifecycleScope.launch {
            val client = GeminiApiClient(apiKey)
            val result = client.parseTrainingImages(capturedBitmaps)
            binding.progressBar.visibility = View.GONE
            binding.btnAnalyze.isEnabled = true

            result.onSuccess { record ->
                record.captureTimeMs = captureTimeMs
                binding.btnSaveToHealthConnect.isEnabled = true
                binding.btnSaveToHealthConnect.tag = record

                showResultScreen()
                populateResultUI(record)
            }.onFailure { e ->
                Toast.makeText(this@MainActivity, getString(R.string.analysis_failed) + ": " + e.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun checkHealthConnectAndSave() {
        val sdkStatus = HealthConnectManager.getSdkStatus(this)
        if (sdkStatus != HealthConnectClient.SDK_AVAILABLE) {
            val (title, message, actionText) =
                if (sdkStatus == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED) {
                    Triple(
                        getString(R.string.health_connect_update_required_title),
                        getString(R.string.health_connect_update_required_message),
                        getString(R.string.update)
                    )
                } else {
                    Triple(
                        getString(R.string.health_connect_unavailable_title),
                        getString(R.string.health_connect_unavailable_message),
                        getString(R.string.install)
                    )
                }

            showStyledDialog(
                MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(actionText) { _, _ ->
                    HealthConnectManager.openHealthConnectSettings(this)
                }
                .setNegativeButton(getString(R.string.cancel), null)
            )
            return
        }

        lifecycleScope.launch {
            if (HealthConnectManager.hasAllPermissions(this@MainActivity)) {
                saveToHealthConnect()
            } else {
                pendingSaveAfterPermissionRequest = true
                if (!isHealthPermissionRequestInFlight) {
                    isHealthPermissionRequestInFlight = true
                    healthPermissionLauncher.launch(HealthConnectManager.REQUIRED_PERMISSIONS)
                }
            }
        }
    }

    private fun checkHealthConnectPermissionOnStartup() {
        if (HealthConnectManager.getSdkStatus(this) != HealthConnectClient.SDK_AVAILABLE) return

        lifecycleScope.launch {
            if (!HealthConnectManager.hasAllPermissions(this@MainActivity)) {
                pendingSaveAfterPermissionRequest = false
                if (!isHealthPermissionRequestInFlight) {
                    isHealthPermissionRequestInFlight = true
                    healthPermissionLauncher.launch(HealthConnectManager.REQUIRED_PERMISSIONS)
                }
            }
        }
    }

    private suspend fun saveToHealthConnect() {
        val originalRecord = binding.btnSaveToHealthConnect.tag as? TrainingRecord ?: return

        // Read updated values from UI
        val updatedSets = mutableListOf<ExerciseSet>()
        for (i in 0 until binding.layoutSets.childCount) {
            val setView = binding.layoutSets.getChildAt(i)
            val tvSetNumber = setView.findViewById<android.widget.TextView>(R.id.tvSetNumber)
            val etReps = setView.findViewById<android.widget.EditText>(R.id.etReps)
            val etWeight = setView.findViewById<android.widget.EditText>(R.id.etWeight)

            val setNumber = tvSetNumber.text.toString().toIntOrNull() ?: (i + 1)
            val reps = etReps.text.toString().toIntOrNull() ?: 0
            val weight = etWeight.text.toString().toDoubleOrNull() ?: 0.0

            if (reps > 0 || weight > 0.0) {
                updatedSets.add(ExerciseSet(setNumber = setNumber, reps = reps, weightKg = weight))
            }
        }

        val updatedRecord = originalRecord.copy(
            exerciseName = binding.etExerciseName.text.toString(),
            machineName = binding.etMachineName.text.toString(),
            trainingDurationMinutes = binding.etDuration.text.toString().toIntOrNull() ?: 0,
            caloriesKcal = binding.etCalories.text.toString().toDoubleOrNull() ?: 0.0,
            totalReps = binding.etTotalReps.text.toString().toIntOrNull() ?: 0,
            totalVolumeKg = binding.etTotalWeight.text.toString().toDoubleOrNull() ?: 0.0,
            notes = binding.etNotes.text.toString(),
            sets = updatedSets
        )
        updatedRecord.captureTimeMs = originalRecord.captureTimeMs

        binding.progressBarSave.visibility = View.VISIBLE
        binding.btnSaveToHealthConnect.isEnabled = false
        val result = HealthConnectManager.recordTraining(this, updatedRecord)
        binding.progressBarSave.visibility = View.GONE
        binding.btnSaveToHealthConnect.isEnabled = true

        result.onSuccess {
            Toast.makeText(this, getString(R.string.saved_to_health_connect), Toast.LENGTH_SHORT).show()
            capturedBitmaps.clear()
            updateCapturedImages()
            binding.btnSaveToHealthConnect.isEnabled = false
            binding.btnSaveToHealthConnect.tag = null
            captureTimeMs = null
            showTopScreen()
        }.onFailure { e ->
            Toast.makeText(this, getString(R.string.save_failed, e.message), Toast.LENGTH_LONG).show()
        }
    }

    private fun updateCapturedImages() {
        binding.tvImageCount.text = if (capturedBitmaps.isEmpty()) {
            getString(R.string.image_count_zero)
        } else {
            getString(R.string.image_count, capturedBitmaps.size)
        }
        binding.btnAnalyze.isEnabled = capturedBitmaps.isNotEmpty()
        binding.btnClearImages.isEnabled = capturedBitmaps.isNotEmpty()

        binding.imageContainer.removeAllViews()

        if (capturedBitmaps.isNotEmpty()) {
            showImageScreen()
            capturedBitmaps.forEach { bitmap ->
                val imageView = android.widget.ImageView(this).apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, 0, 0, 16)
                    }
                    adjustViewBounds = true
                    scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                    setImageBitmap(bitmap)
                }
                binding.imageContainer.addView(imageView)
            }
        }
    }

    private fun showPermissionDeniedDialog(title: String, message: String) {
        showStyledDialog(
            MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(getString(R.string.open_settings)) { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
                startActivity(intent)
            }
            .setNegativeButton(getString(R.string.cancel), null)
        )
    }

    private fun showHealthConnectPermissionDeniedDialog() {
        showStyledDialog(
            MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.health_connect_permission_title))
                .setMessage(getString(R.string.health_connect_permission_message))
                .setPositiveButton(getString(R.string.request_permission)) { _, _ ->
                    pendingSaveAfterPermissionRequest = retrySaveAfterPermissionFromDeniedDialog
                    retrySaveAfterPermissionFromDeniedDialog = false
                    if (!isHealthPermissionRequestInFlight) {
                        isHealthPermissionRequestInFlight = true
                        healthPermissionLauncher.launch(HealthConnectManager.REQUIRED_PERMISSIONS)
                    }
                }
                .setNegativeButton(getString(R.string.health_connect_open_settings)) { _, _ ->
                    if (!HealthConnectManager.openHealthConnectPermissionSettings(this)) {
                        Toast.makeText(this, getString(R.string.health_connect_settings_open_failed), Toast.LENGTH_LONG).show()
                    }
                }
                .setNeutralButton(getString(R.string.cancel), null)
        )
    }

    private fun showStyledDialog(builder: MaterialAlertDialogBuilder) {
        val dialog = builder.create()
        dialog.setOnShowListener {
            val actionColor = ContextCompat.getColor(this, R.color.action_button_bg)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(actionColor)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(actionColor)
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(actionColor)
        }
        dialog.show()
    }
}
