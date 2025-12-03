package com.example.chickencounter

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import android.util.Log
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

class MainActivity : AppCompatActivity() {
    private var detector: YoloTFLiteDetector? = null
    private lateinit var imgView: ImageView
    private lateinit var txtCount: TextView
    private val MODEL_FILE = "200x16broiler.tflite"
    private val LABEL_FILE = "labels.txt"

    private var photoUri: Uri? = null
    private var photoFile: File? = null

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        try {
            if (result.resultCode == Activity.RESULT_OK) {
                val uri: Uri? = result.data?.data
                uri?.let {
                    contentResolver.openInputStream(it)?.use { inputStream ->
                        val bitmap = BitmapFactory.decodeStream(inputStream)
                        if (bitmap != null) {
                            imgView.setImageBitmap(bitmap)
                            txtCount.text = "Gambar siap. Tekan DETEKSI."
                        }
                        else Toast.makeText(this, "Gagal memproses gambar dari galeri", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Error saat memuat gambar: ${e.message}", Toast.LENGTH_LONG).show()
            Log.e("PICK_IMAGE", "Error: ${e.stackTraceToString()}")
        }
    }

    private val cameraLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        try {
            if (success && photoUri != null) {
                contentResolver.openInputStream(photoUri!!)?.use { inputStream ->
                    val bitmap = BitmapFactory.decodeStream(inputStream)
                    if (bitmap != null) {
                        imgView.setImageBitmap(bitmap)
                        txtCount.text = "Gambar siap. Tekan DETEKSI."
                        photoFile?.delete()
                        Log.i("CAMERA_CLEANUP", "File kamera sementara dihapus: ${photoFile?.path}")
                    } else {
                        Toast.makeText(this, "Gagal membaca hasil foto", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                Toast.makeText(this, "Foto tidak diambil", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Error kamera: ${e.message}", Toast.LENGTH_LONG).show()
            Log.e("CAMERA", "Error: ${e.stackTraceToString()}")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        imgView = findViewById(R.id.imageView)
        txtCount = findViewById(R.id.txtCount)
        val btnPick = findViewById<Button>(R.id.btnPick)
        val btnDetect = findViewById<Button>(R.id.btnDetect)

        checkPermissions()
        txtCount.text = "Memuat model..."

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                detector = YoloTFLiteDetector.create(this@MainActivity, MODEL_FILE)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Model YOLOv11 berhasil dimuat", Toast.LENGTH_SHORT).show()
                    txtCount.text = "Model siap. Pilih gambar."
                }
            } catch (e: IOException) {
                Log.e("MAIN_INIT", "Gagal memuat model: ${e.message}")
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Gagal memuat model: ${e.message}", Toast.LENGTH_LONG).show()
                    txtCount.text = "Gagal memuat model. Periksa Logcat."
                }
            } catch (e: Exception) {
                Log.e("MAIN_INIT", "Error umum saat inisialisasi: ${e.message}")
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Error inisialisasi: ${e.message}", Toast.LENGTH_LONG).show()
                    txtCount.text = "Error inisialisasi. Periksa Logcat."
                }
            }
        }

        btnPick.setOnClickListener {
            val options = arrayOf("Ambil Foto", "Pilih dari Galeri")
            AlertDialog.Builder(this)
                .setTitle("Pilih sumber gambar")
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> openCamera()
                        1 -> pickFromGallery()
                    }
                }.show()
        }

        btnDetect.setOnClickListener {
            val bitmap = (imgView.drawable as? BitmapDrawable)?.bitmap
            if (bitmap == null) {
                Toast.makeText(this, "Pilih atau ambil gambar terlebih dahulu", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val localDetector = detector
            if (localDetector == null) {
                Toast.makeText(this, "Model belum siap, tunggu sebentar...", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            processImage(bitmap)
        }
    }

    private fun checkPermissions() {
        val permissions = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) permissions.add(Manifest.permission.CAMERA)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES)
            != PackageManager.PERMISSION_GRANTED
        ) permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 200)
        }
    }

    private fun openCamera() {
        try {
            photoFile = File.createTempFile("ayam_", ".jpg", cacheDir)
            photoUri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", photoFile!!)
            cameraLauncher.launch(photoUri)
        } catch (e: Exception) {
            Toast.makeText(this, "Tidak bisa buka kamera: ${e.message}", Toast.LENGTH_LONG).show()
            Log.e("OPEN_CAMERA", "Error: ${e.stackTraceToString()}")
        }
    }

    private fun pickFromGallery() {
        try {
            val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            pickImageLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Tidak bisa buka galeri: ${e.message}", Toast.LENGTH_LONG).show()
            Log.e("OPEN_GALLERY", "Error: ${e.stackTraceToString()}")
        }
    }

    private fun processImage(bitmap: Bitmap) {
        txtCount.text = "Sedang mendeteksi..."

        val localDetector = detector
        if (localDetector == null) {
            Toast.makeText(this, "Model belum siap.", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch(Dispatchers.Default) {
            try {
                val (count, avgConf, resultBitmap) = localDetector.detect(bitmap)
                withContext(Dispatchers.Main) {
                    imgView.setImageBitmap(resultBitmap)
                    txtCount.text = "Jumlah Anak Ayam: $count\nRata-rata Akurasi: ${"%.1f".format(avgConf)}%"
                    Toast.makeText(this@MainActivity, "Deteksi selesai. Ditemukan $count anak ayam.", Toast.LENGTH_SHORT).show()
                }

            } catch (e: Exception) {
                Log.e("PROCESS_IMG", "Gagal deteksi: ${e.message}")
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Gagal mendeteksi: ${e.message}", Toast.LENGTH_LONG).show()
                    txtCount.text = "Error deteksi. Periksa Logcat."
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            detector?.close()
            detector = null
            photoFile?.delete()
            Log.i("MAIN_DESTROY", "Detector di-release dan file sementara dihapus")
        } catch (e: Exception) {
            Log.w("MAIN_DESTROY", "Gagal release resource: ${e.message}")
        }
    }
}