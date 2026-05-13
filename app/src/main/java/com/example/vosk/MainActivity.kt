package com.example.vosk

import android.Manifest
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.vosk.databinding.ActivityMainBinding
import kotlinx.coroutines.*
import kotlinx.coroutines.delay
import java.io.File

/**
 * DocScriptAI — AI Medical Scribe
 * Flow: Record (Hindi) → Vosk Transcription → LLM Processing → Structured Medical Report
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var voskService: VoskTranscriptionService
    private lateinit var llmProcessor: LlmProcessor
    private lateinit var wavRecorder: WavRecorder

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var recordingJob: Job? = null
    private var isRecording = false
    private val transcriptionBuilder = StringBuilder()

    private var gdriveDownloadId = -1L

    // Permission launcher
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            initVoskModel()
        } else {
            binding.statusText.text = getString(R.string.error_mic_permission)
            binding.progressBar.visibility = View.GONE
        }
    }

    // File picker for LLM .task model (manual selection)
    private val modelPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { loadLlmModelFromUri(it) }
    }

    private lateinit var audioConverter: AudioConverter

    // Audio file picker
    private val audioUploadLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { processUploadedAudio(it) }
    }

    // BroadcastReceiver for GDrive DownloadManager completion
    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            if (id != gdriveDownloadId) return

            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val cursor = dm.query(DownloadManager.Query().setFilterById(id))
            if (cursor.moveToFirst()) {
                val statusCol = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                val status = cursor.getInt(statusCol)
                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    val uriCol = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                    val localUri = cursor.getString(uriCol)
                    val filePath = Uri.parse(localUri).path
                    if (filePath != null) {
                        runOnUiThread {
                            binding.progressBar.isIndeterminate = true
                            binding.statusText.text = getString(R.string.download_complete)
                            loadLlmModelFromFile(File(filePath))
                        }
                    }
                } else {
                    runOnUiThread {
                        binding.progressBar.isIndeterminate = true
                        binding.progressBar.visibility = View.GONE
                        binding.statusText.text = getString(R.string.download_failed)
                        binding.btnDownloadModel.isEnabled = true
                    }
                }
            }
            cursor.close()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        voskService = VoskTranscriptionService()
        llmProcessor = LlmProcessor()
        wavRecorder = WavRecorder(cacheDir)
        audioConverter = AudioConverter(this)

        // Register download completion receiver
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(downloadReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(downloadReceiver, filter)
        }

        setBadgePending(binding.llmStatusBadge, getString(R.string.badge_llm_none))

        setupButtons()
        checkPermissionAndInit()
    }

    private fun setupButtons() {
        binding.btnRecord.setOnClickListener { toggleRecording() }
        binding.btnProcess.setOnClickListener { processWithLlm() }
        binding.btnUploadAudio.setOnClickListener {
            audioUploadLauncher.launch("audio/*")
        }
        binding.btnLoadModel.setOnClickListener { pickModelFile() }
        binding.btnDownloadModel.setOnClickListener { downloadModelFromGDrive() }
        binding.btnClear.setOnClickListener { clearAll() }
    }

    // ──────────────────────────────────────────────
    // Google Drive Auto-Download
    // ──────────────────────────────────────────────

    private fun downloadModelFromGDrive() {
        val fileId = getString(R.string.gdrive_model_file_id)
        if (fileId == "YOUR_GDRIVE_FILE_ID_HERE") {
            Toast.makeText(this, getString(R.string.gdrive_not_configured), Toast.LENGTH_LONG).show()
            return
        }

        val url = "https://drive.usercontent.google.com/download?id=$fileId&export=download&confirm=t"
        val destFile = File(getExternalFilesDir(null), "llm_model.task")
        if (destFile.exists()) destFile.delete()

        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(getString(R.string.download_model))
            .setDescription(getString(R.string.downloading_model))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationUri(Uri.fromFile(destFile))
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)

        val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        gdriveDownloadId = dm.enqueue(request)

        binding.progressBar.isIndeterminate = false
        binding.progressBar.progress = 0
        binding.progressBar.visibility = View.VISIBLE
        binding.statusText.text = getString(R.string.downloading_model)
        binding.btnDownloadModel.isEnabled = false

        pollDownloadProgress(dm, gdriveDownloadId)
    }

    private fun pollDownloadProgress(dm: DownloadManager, downloadId: Long) {
        scope.launch {
            while (isActive) {
                delay(500)
                val cursor = dm.query(DownloadManager.Query().setFilterById(downloadId))
                if (!cursor.moveToFirst()) { cursor.close(); break }

                val statusCol = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                val status = cursor.getInt(statusCol)

                if (status == DownloadManager.STATUS_RUNNING ||
                    status == DownloadManager.STATUS_PAUSED) {
                    val downloadedCol = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                    val totalCol = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                    val downloaded = cursor.getLong(downloadedCol)
                    val total = cursor.getLong(totalCol)
                    cursor.close()

                    if (total > 0) {
                        val pct = (downloaded * 100 / total).toInt()
                        val dlMb = "%.1f".format(downloaded / 1_048_576f)
                        val totMb = "%.1f".format(total / 1_048_576f)
                        binding.progressBar.progress = pct
                        binding.statusText.text = "डाउनलोड हो रहा है: $dlMb / $totMb MB ($pct%)"
                    }
                } else {
                    cursor.close()
                    // Terminal state — receiver will handle success/failure
                    binding.progressBar.isIndeterminate = true
                    break
                }
            }
        }
    }

    // ──────────────────────────────────────────────
    // Storage Scan for .task Models
    // ──────────────────────────────────────────────

    private fun scanAndAutoLoadTaskModel() {
        scope.launch(Dispatchers.IO) {
            val found = scanForTaskModel()
            if (found != null) {
                Log.d(TAG, "Auto-found .task model: ${found.absolutePath}")
                withContext(Dispatchers.Main) {
                    binding.statusText.text = getString(R.string.scan_found_model)
                    loadLlmModelFromFile(found)
                }
            }
        }
    }

    /**
     * Scans app-accessible storage directories for any .task model file.
     * Checks: app external files dir, app internal files, cache, and public Downloads.
     */
    private fun scanForTaskModel(): File? {
        val searchDirs = listOfNotNull(
            getExternalFilesDir(null),
            filesDir,
            cacheDir,
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        )
        // Collect ALL matches across ALL dirs, then prefer .litertlm over .task
        val allMatches = mutableListOf<File>()
        for (dir in searchDirs) {
            if (!dir.exists() || !dir.canRead()) continue
            dir.listFiles()
                ?.filter { it.isFile && it.extension.lowercase() in listOf("litertlm", "task") }
                ?.let { allMatches.addAll(it) }
        }
        return allMatches.maxByOrNull { if (it.extension.lowercase() == "litertlm") 1 else 0 }
    }

    // ──────────────────────────────────────────────
    // Audio Upload
    // ──────────────────────────────────────────────

    private fun processUploadedAudio(uri: Uri) {
        binding.statusText.text = getString(R.string.converting)
        binding.progressBar.visibility = View.VISIBLE
        hideResults()

        scope.launch {
            try {
                val outputFile = File(cacheDir, "upload_converted.wav")
                val result = audioConverter.convertToWav(uri, outputFile)

                withContext(Dispatchers.Main) {
                    result.onSuccess { wavFile ->
                        binding.statusText.text = getString(R.string.transcribing)
                        transcribeFile(wavFile)
                    }.onFailure { e ->
                        Log.e(TAG, "Conversion failed", e)
                        binding.statusText.text = "${getString(R.string.conversion_failed)}: ${e.message}"
                        binding.progressBar.visibility = View.GONE
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Upload processing failed", e)
                withContext(Dispatchers.Main) {
                    binding.statusText.text = "Error: ${e.message}"
                    binding.progressBar.visibility = View.GONE
                }
            }
        }
    }

    // ──────────────────────────────────────────────
    // Permission & Model Init
    // ──────────────────────────────────────────────

    private fun checkPermissionAndInit() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            initVoskModel()
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // ──────────────────────────────────────────────
    // Badge helpers
    // ──────────────────────────────────────────────

    private fun setBadgeReady(badge: android.widget.TextView, label: String) {
        badge.text = label
        badge.setTextColor(ContextCompat.getColor(this, R.color.badge_ready_text))
        badge.setBackgroundResource(R.drawable.badge_bg_ready)
    }

    private fun setBadgePending(badge: android.widget.TextView, label: String) {
        badge.text = label
        badge.setTextColor(ContextCompat.getColor(this, R.color.badge_pending_text))
        badge.setBackgroundResource(R.drawable.badge_bg_pending)
    }

    private fun setBadgeError(badge: android.widget.TextView, label: String) {
        badge.text = label
        badge.setTextColor(ContextCompat.getColor(this, R.color.badge_error_text))
        badge.setBackgroundResource(R.drawable.badge_bg_error)
    }

    private fun initVoskModel() {
        binding.statusText.text = getString(R.string.preparing)
        binding.progressBar.visibility = View.VISIBLE
        setBadgePending(binding.voskStatusBadge, getString(R.string.badge_vosk_loading))

        voskService.initModel(this, object : VoskTranscriptionService.Listener {
            override fun onModelReady() {
                runOnUiThread {
                    binding.statusText.text = getString(R.string.ready)
                    binding.progressBar.visibility = View.GONE
                    binding.btnRecord.isEnabled = true
                    setBadgeReady(binding.voskStatusBadge, getString(R.string.badge_vosk_ready))
                    // Auto-scan storage for any existing .task model
                    scanAndAutoLoadTaskModel()
                }
            }

            override fun onModelError(error: String) {
                runOnUiThread {
                    binding.statusText.text = "${getString(R.string.error_model_load)}\n$error"
                    binding.progressBar.visibility = View.GONE
                    setBadgeError(binding.voskStatusBadge, getString(R.string.badge_vosk_error))
                }
            }

            override fun onPartialResult(text: String) {}
            override fun onFinalResult(text: String) {}
            override fun onError(error: String) {}
        })
    }

    // ──────────────────────────────────────────────
    // Recording
    // ──────────────────────────────────────────────

    private fun toggleRecording() {
        if (isRecording) stopRecording() else startRecording()
    }

    private fun startRecording() {
        isRecording = true
        binding.btnRecord.text = ""
        binding.btnRecord.setBackgroundColor(ContextCompat.getColor(this, R.color.recording_red))
        binding.recordHintText.text = getString(R.string.stop)
        binding.btnProcess.isEnabled = false
        binding.statusText.text = getString(R.string.recording)
        binding.progressBar.visibility = View.VISIBLE
        hideResults()

        recordingJob = scope.launch {
            try {
                val wavFile = wavRecorder.startRecording()
                withContext(Dispatchers.Main) {
                    binding.statusText.text = getString(R.string.transcribing)
                    transcribeFile(wavFile)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.statusText.text = "रिकॉर्डिंग में विफल: ${e.message}"
                    binding.progressBar.visibility = View.GONE
                    resetRecordButton()
                }
            }
        }
    }

    private fun stopRecording() {
        isRecording = false
        wavRecorder.stopRecording()
        resetRecordButton()
    }

    private fun resetRecordButton() {
        binding.btnRecord.text = ""
        binding.btnRecord.setBackgroundColor(ContextCompat.getColor(this, R.color.primary))
        binding.recordHintText.text = getString(R.string.record_hint)
    }

    // ──────────────────────────────────────────────
    // Transcription
    // ──────────────────────────────────────────────

    private fun transcribeFile(file: File) {
        var lastPartialText = ""
        voskService.transcribeFile(file, object : VoskTranscriptionService.Listener {
            override fun onModelReady() {}
            override fun onModelError(error: String) {}

            override fun onPartialResult(text: String) {
                lastPartialText = text
                runOnUiThread {
                    binding.transcriptionText.text = transcriptionBuilder.toString() + text
                }
            }

            override fun onFinalResult(text: String) {
                runOnUiThread {
                    if (text.isNotEmpty()) {
                        transcriptionBuilder.append(text).append(" ")
                    } else if (lastPartialText.isNotEmpty()) {
                        transcriptionBuilder.append(lastPartialText).append(" ")
                    }
                    lastPartialText = ""
                    binding.transcriptionText.text = transcriptionBuilder.toString()
                    binding.statusText.text = getString(R.string.ready)
                    binding.progressBar.visibility = View.GONE
                    binding.btnProcess.isEnabled = transcriptionBuilder.isNotEmpty()
                    binding.btnClear.visibility = View.VISIBLE
                }
            }

            override fun onError(error: String) {
                runOnUiThread {
                    binding.statusText.text = "ट्रांसक्रिप्शन में विफल: $error"
                    binding.progressBar.visibility = View.GONE
                }
            }
        })
    }

    // ──────────────────────────────────────────────
    // LLM Model Loading
    // ──────────────────────────────────────────────

    private fun pickModelFile() {
        modelPickerLauncher.launch("*/*")
    }

    /** Load from a content URI (manual file picker). Copies to cache first. */
    private fun loadLlmModelFromUri(uri: Uri) {
        scope.launch {
            try {
                binding.statusText.text = "LLM मॉडल कॉपी हो रहा है..."
                binding.progressBar.visibility = View.VISIBLE
                setBadgePending(binding.llmStatusBadge, getString(R.string.badge_llm_loading))

                withContext(Dispatchers.IO) {
                    val modelFile = File(cacheDir, "llm_model.task")
                    if (modelFile.exists()) modelFile.delete()

                    contentResolver.openInputStream(uri)?.use { input ->
                        modelFile.outputStream().use { output -> input.copyTo(output) }
                    } ?: throw Exception("Failed to open input stream for content")

                    if (modelFile.length() < 100 * 1024) {
                        throw Exception("Model file too small (${modelFile.length()} bytes). Invalid file?")
                    }

                    val result = llmProcessor.loadModel(this@MainActivity, modelFile.absolutePath)
                    withContext(Dispatchers.Main) { onModelLoadResult(result) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "File copy failed", e)
                withContext(Dispatchers.Main) {
                    binding.statusText.text = "Error: ${e.message}"
                    binding.progressBar.visibility = View.GONE
                }
            }
        }
    }

    /** Load directly from a File path (auto-scan or post-download). */
    private fun loadLlmModelFromFile(file: File) {
        scope.launch {
            try {
                binding.statusText.text = "LLM मॉडल लोड हो रहा है..."
                binding.progressBar.visibility = View.VISIBLE
                setBadgePending(binding.llmStatusBadge, getString(R.string.badge_llm_loading))

                withContext(Dispatchers.IO) {
                    val result = llmProcessor.loadModel(this@MainActivity, file.absolutePath)
                    withContext(Dispatchers.Main) { onModelLoadResult(result) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Model load from file failed", e)
                withContext(Dispatchers.Main) {
                    binding.statusText.text = "LLM मॉडल लोड विफल: ${e.message}"
                    binding.progressBar.visibility = View.GONE
                }
            }
        }
    }

    private fun onModelLoadResult(result: Result<Unit>) {
        result.onSuccess {
            binding.statusText.text = getString(R.string.ready)
            binding.progressBar.visibility = View.GONE
            binding.btnLoadModel.text = getString(R.string.model_loaded)
            binding.btnLoadModel.isEnabled = false
            binding.btnDownloadModel.isEnabled = false
            setBadgeReady(binding.llmStatusBadge, getString(R.string.badge_llm_ready))
            if (transcriptionBuilder.isNotEmpty()) {
                binding.btnProcess.isEnabled = true
            }
        }.onFailure { e ->
            Log.e(TAG, "LLM load failed", e)
            binding.statusText.text = "LLM मॉडल लोड विफल: ${e.message}"
            binding.progressBar.visibility = View.GONE
            setBadgeError(binding.llmStatusBadge, "✗ AI विफल")
        }
    }

    // ──────────────────────────────────────────────
    // LLM Processing
    // ──────────────────────────────────────────────

    private fun processWithLlm() {
        if (!llmProcessor.isModelLoaded) {
            Toast.makeText(this, getString(R.string.llm_not_available), Toast.LENGTH_LONG).show()
            return
        }

        val text = transcriptionBuilder.toString().trim()
        if (text.isEmpty()) return

        if (llmProcessor.wouldTruncate(text)) {
            Toast.makeText(
                this,
                "बातचीत बहुत लंबी है — मध्य भाग हटाकर प्रोसेस होगा\nConversation too long — middle section will be trimmed",
                Toast.LENGTH_LONG
            ).show()
        }

        binding.statusText.text = "Extracting medical report..."
        binding.progressBar.visibility = View.VISIBLE
        binding.btnProcess.isEnabled = false
        binding.btnRecord.isEnabled = false
        binding.btnUploadAudio.isEnabled = false
        hideResults()
        binding.resultsHeader.visibility = View.VISIBLE

        scope.launch {
            val result = llmProcessor.processTranscription(text) { field, value ->
                withContext(Dispatchers.Main) {
                    when (field) {
                        "diagnosis"  -> { binding.diagnosisCard.visibility = View.VISIBLE;  binding.diagnosisText.text = value }
                        "medication" -> { binding.medicationCard.visibility = View.VISIBLE; binding.medicationText.text = value }
                        "tests"      -> { binding.testsCard.visibility = View.VISIBLE;      binding.testsText.text = value }
                        "followUp"   -> { binding.followupCard.visibility = View.VISIBLE;   binding.followupText.text = value }
                    }
                }
            }
            result.onSuccess {
                binding.btnClear.visibility = View.VISIBLE
                binding.statusText.text = "प्रोसेसिंग पूरी हुई ✓"
                binding.progressBar.visibility = View.GONE
                binding.btnRecord.isEnabled = true
                binding.btnUploadAudio.isEnabled = true
                binding.btnProcess.isEnabled = transcriptionBuilder.isNotEmpty()
            }.onFailure { e ->
                binding.statusText.text = "AI प्रोसेसिंग में विफल: ${e.message}"
                binding.progressBar.visibility = View.GONE
                binding.btnProcess.isEnabled = true
                binding.btnRecord.isEnabled = true
                binding.btnUploadAudio.isEnabled = true
            }
        }
    }

    // ──────────────────────────────────────────────
    // Results Display
    // ──────────────────────────────────────────────

    private fun showResults(report: MedicalReport) {
        binding.resultsHeader.visibility = View.VISIBLE
        binding.diagnosisCard.visibility = View.VISIBLE
        binding.medicationCard.visibility = View.VISIBLE
        binding.followupCard.visibility = View.VISIBLE
        binding.testsCard.visibility = View.VISIBLE
        binding.btnClear.visibility = View.VISIBLE

        binding.diagnosisText.text = report.diagnosis
        binding.medicationText.text = report.medication
        binding.followupText.text = report.followUp
        binding.testsText.text = report.otherTests
    }

    private fun hideResults() {
        binding.resultsHeader.visibility = View.GONE
        binding.diagnosisCard.visibility = View.GONE
        binding.medicationCard.visibility = View.GONE
        binding.followupCard.visibility = View.GONE
        binding.testsCard.visibility = View.GONE
    }

    private fun clearAll() {
        transcriptionBuilder.clear()
        binding.transcriptionText.text = ""
        binding.statusText.text = getString(R.string.ready)
        binding.btnProcess.isEnabled = false
        binding.btnClear.visibility = View.GONE
        hideResults()
    }

    // ──────────────────────────────────────────────
    // Lifecycle
    // ──────────────────────────────────────────────

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        voskService.destroy()
        llmProcessor.destroy()
        try { unregisterReceiver(downloadReceiver) } catch (_: Exception) {}
    }
}
