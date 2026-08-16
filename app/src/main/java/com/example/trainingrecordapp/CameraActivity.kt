package com.example.trainingrecordapp

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.example.trainingrecordapp.databinding.ActivityCameraBinding
import java.io.File

class CameraActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCameraBinding
    private var imageCapture: ImageCapture? = null
    private val capturedFiles = mutableListOf<File>()
    private var completedWithSuccess = false

    companion object {
        const val EXTRA_IMAGE_URIS = "image_uris"
        const val MAX_PHOTOS = 5
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)

        startCamera()

        binding.btnShutter.setOnClickListener {
            takePhoto()
        }

        binding.btnDone.setOnClickListener {
            if (capturedFiles.isNotEmpty()) {
                val uris = ArrayList(capturedFiles.map { it.absolutePath })
                val resultIntent = Intent().apply {
                    putStringArrayListExtra(EXTRA_IMAGE_URIS, uris)
                }
                completedWithSuccess = true
                setResult(RESULT_OK, resultIntent)
                finish()
            } else {
                Toast.makeText(this, getString(R.string.capture_at_least_one), Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnCancel.setOnClickListener {
            deleteTempFiles()
            setResult(RESULT_CANCELED)
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing && !completedWithSuccess) {
            deleteTempFiles()
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
            }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture)
            } catch (e: Exception) {
                Toast.makeText(this, getString(R.string.camera_error, e.message), Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        if (capturedFiles.size >= MAX_PHOTOS) {
            Toast.makeText(this, getString(R.string.max_photos_reached, MAX_PHOTOS), Toast.LENGTH_SHORT).show()
            return
        }

        val imageCapture = imageCapture ?: return
        binding.btnShutter.isEnabled = false

        imageCapture.takePicture(
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val bitmap = image.toBitmap()
                    image.close()
                    val tempFile = saveBitmapToTempFile(bitmap)
                    if (tempFile != null) {
                        capturedFiles.add(tempFile)
                    }
                    binding.btnShutter.isEnabled = capturedFiles.size < MAX_PHOTOS
                    updateCaptureCount()
                    Toast.makeText(
                        this@CameraActivity,
                        getString(R.string.photo_captured, capturedFiles.size),
                        Toast.LENGTH_SHORT
                    ).show()
                }

                override fun onError(exception: ImageCaptureException) {
                    binding.btnShutter.isEnabled = true
                    Toast.makeText(
                        this@CameraActivity,
                        getString(R.string.capture_failed, exception.message),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )
    }

    private fun saveBitmapToTempFile(bitmap: Bitmap): File? {
        return try {
            val file = File.createTempFile("capture_", ".jpg", cacheDir)
            file.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
            file
        } catch (e: Exception) {
            null
        }
    }

    private fun deleteTempFiles() {
        capturedFiles.forEach { it.delete() }
        capturedFiles.clear()
    }

    private fun updateCaptureCount() {
        binding.tvCaptureCount.text = getString(R.string.captured_count, capturedFiles.size)
        binding.btnDone.isEnabled = capturedFiles.isNotEmpty()
        binding.btnShutter.isEnabled = capturedFiles.size < MAX_PHOTOS
    }
}
