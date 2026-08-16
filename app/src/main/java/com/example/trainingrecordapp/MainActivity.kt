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

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val capturedBitmaps = mutableListOf<Bitmap>()

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
        if (granted.containsAll(HealthConnectManager.REQUIRED_PERMISSIONS)) {
            lifecycleScope.launch { saveToHealthConnect() }
        } else {
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        checkAndRequestApiKey()
        setupButtons()
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
            binding.tvResult.text = ""
            binding.btnSaveToHealthConnect.isEnabled = false
            binding.btnSaveToHealthConnect.tag = null
        }

        binding.btnChangeApiKey.setOnClickListener {
            showApiKeyInputDialog()
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
        binding.tvResult.text = getString(R.string.analyzing)

        lifecycleScope.launch {
            val client = GeminiApiClient(apiKey)
            val result = client.parseTrainingImages(capturedBitmaps)
            binding.progressBar.visibility = View.GONE
            binding.btnAnalyze.isEnabled = true

            result.onSuccess { record ->
                val json = GsonBuilder().setPrettyPrinting().create().toJson(record)
                binding.tvResult.text = json
                binding.btnSaveToHealthConnect.isEnabled = true
                binding.btnSaveToHealthConnect.tag = record
            }.onFailure { e ->
                binding.tvResult.text = getString(R.string.analysis_error, e.message)
                Toast.makeText(this@MainActivity, getString(R.string.analysis_failed), Toast.LENGTH_SHORT).show()
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
                healthPermissionLauncher.launch(HealthConnectManager.REQUIRED_PERMISSIONS)
            }
        }
    }

    private suspend fun saveToHealthConnect() {
        val record = binding.btnSaveToHealthConnect.tag as? TrainingRecord ?: return

        binding.progressBar.visibility = View.VISIBLE
        val result = HealthConnectManager.recordTraining(this, record)
        binding.progressBar.visibility = View.GONE

        result.onSuccess {
            Toast.makeText(this, getString(R.string.saved_to_health_connect), Toast.LENGTH_SHORT).show()
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

        if (capturedBitmaps.isNotEmpty()) {
            binding.ivPreview.setImageBitmap(capturedBitmaps.last())
            binding.ivPreview.visibility = View.VISIBLE
        } else {
            binding.ivPreview.visibility = View.GONE
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
                    healthPermissionLauncher.launch(HealthConnectManager.REQUIRED_PERMISSIONS)
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
