package com.example.speechrecognitionapp

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.*
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.*
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.Tensor
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.common.TensorProcessor
import org.tensorflow.lite.support.label.TensorLabel
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import kotlin.collections.ArrayList
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.text.SimpleDateFormat
import androidx.work.Constraints
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await


data class PredictionLog(
    val timestamp: Long,
    val keyword: String,
    val confidence: Double
)

class AudioRecordingService : Service() {

    companion object {
        private val TAG = AudioRecordingService::class.simpleName

        private const val SAMPLE_RATE = 16000
        private const val AUDIO_CHANNELS = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val AUDIO_INPUT = MediaRecorder.AudioSource.MIC


        private const val DESIRED_LENGTH_SECONDS = 1
        private const val RECORDING_LENGTH = SAMPLE_RATE * DESIRED_LENGTH_SECONDS // in seconds

        // MFCC parameters
        private const val NUM_MFCC = 13
        // private const val NUM_FILTERS = 26
        // private const val FFT_SIZE = 2048

        // Notifications
        private const val CHANNEL_ID = "word_recognition"
        private const val NOTIFICATION_ID = 202
    }
    // Tweak parameters
    private var energyThreshold = 0.05
    private var probabilityThreshold = 0.002f
    private var windowSize = SAMPLE_RATE / 2
    private var topK = 3

    private var recordingBufferSize = 0

    private var audioRecord: AudioRecord? = null
    private var audioRecordingThread: Thread? = null
    // private var recognitionThread: Thread? = null

    var isRecording: Boolean = false
    var recordingBuffer: DoubleArray = DoubleArray(RECORDING_LENGTH)
    var interpreter: Interpreter? = null

    private var notificationBuilder: NotificationCompat.Builder? = null
    private var notification: Notification? = null

    private var callback: RecordingCallback? = null

    private var isBackground = true

    //NOVE SPREMENLJIVKE ZA OBJEKTE
    private lateinit var tflite: Interpreter
    private lateinit var labels: List<String>
    private lateinit var outputBuffer: TensorBuffer
    private lateinit var inputBuffer: TensorBuffer

    private val firebaseRef = FirebaseDatabase.getInstance().getReference("predictions")

    private var inSpeech = false


    inner class RunServiceBinder : Binder() {
        val service: AudioRecordingService
            get() = this@AudioRecordingService
    }
    private fun scheduleFirebaseUpload() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED) // Wi-Fi
            .setRequiresBatteryNotLow(true)
            .build()

        val request = PeriodicWorkRequestBuilder<FirebaseUploadWorker>(
            1, java.util.concurrent.TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(this)
            .enqueueUniquePeriodicWork(
                "firebase_upload_work",
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
    }
    var serviceBinder = RunServiceBinder()



    override fun onCreate() {
        Log.d(TAG, "Creating service")
        super.onCreate()

        scheduleFirebaseUpload()

        createNotificationChannel()

        recordingBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, AUDIO_CHANNELS, AUDIO_FORMAT)

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        audioRecord = AudioRecord(AUDIO_INPUT, SAMPLE_RATE, AUDIO_CHANNELS, AUDIO_FORMAT, recordingBufferSize)

        val modelBuffer = FileUtil.loadMappedFile(this, "model_16K_LR.tflite")
        tflite = Interpreter(modelBuffer)

        labels = FileUtil.loadLabels(this, "labels.txt")

        val inputTensor = tflite.getInputTensor(0)
        inputBuffer = TensorBuffer.createFixedSize(inputTensor.shape(), inputTensor.dataType())

        val outputTensor = tflite.getOutputTensor(0)
        outputBuffer = TensorBuffer.createFixedSize(outputTensor.shape(), outputTensor.dataType())
    }

    override fun onBind(intent: Intent): IBinder {
        Log.d(TAG, "Binding service")

        return serviceBinder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Starting service")

        if (intent != null) {
            val bundle = intent.extras
            if (bundle != null) {
                energyThreshold = bundle.getDouble("energyThreshold")
                probabilityThreshold = bundle.getFloat("probabilityThreshold")
                windowSize = bundle.getInt("windowSize")
                topK = bundle.getInt("topK")

                //NEW, sensitivity
                val mode = bundle.getString("sensitivityMode", "NORMAL")
                when (mode) {
                    "QUIET" -> {
                        energyThreshold = 0.01       // very sensitive to low-volume sounds
                        probabilityThreshold = 0.05f // accept lower-confidence predictions
                    }
                    "NORMAL" -> {
                        energyThreshold = 0.03        // medium sensitivity
                        probabilityThreshold = 0.10f  // medium-confidence predictions only
                    }
                    "NOISY" -> {
                        energyThreshold = 0.06       // only louder sounds
                        probabilityThreshold = 0.2f  // only high-confidence predictions
                    }
                }

                Log.d(TAG, "Mode=$mode, energyThreshold=$energyThreshold, probabilityThreshold=$probabilityThreshold")
            }
            Log.d(TAG, "Energy threshold: $energyThreshold")
            Log.d(TAG, "Probability threshold: $probabilityThreshold")
            Log.d(TAG, "Window size: $windowSize")
        }

        startRecording()
        return START_STICKY
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_HIGH)
        channel.description = getString(R.string.channel_desc)
        channel.enableLights(true)
        channel.lightColor = Color.BLUE
        channel.enableVibration(true)
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.baseline_notifications_24)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)

        val resultIntent = Intent(this, MainActivity::class.java)
        resultIntent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        val resultPendingIntent = PendingIntent.getActivity(this, 0, resultIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        builder.setContentIntent(resultPendingIntent)

        notificationBuilder = builder
        return builder.build()
    }

    private fun updateNotification(label: String) {
        if (isBackground) return
        if (notificationBuilder == null) {
            return
        } else {
            notificationBuilder?.setContentText(getText(R.string.notification_prediction).toString() + " " + label)
        }
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notificationBuilder?.build())
    }

    fun setCallback(callback: RecordingCallback) {
        this.callback = callback
    }

    private fun updateData(data: ArrayList<Result>) {
        // Sort results
        Collections.sort(data, object : Comparator<Result> {
            override fun compare(o1: Result, o2: Result): Int {
                return o2.confidence.compareTo(o1.confidence)
            }
        })

        // Keep top K results
        if (data.size > topK) {
            data.subList(topK, data.size).clear()
        }

        callback?.onDataUpdated(data)
    }

    private fun startRecording() {
        if(ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED){
            // Access denied
            return
        }
        isRecording = true
        audioRecordingThread = Thread {
            run {
                record()
            }
        }
        audioRecordingThread?.start()
        Log.d(TAG, "START RECORDING THREAD")
    }

    private fun record() {
        Log.d(TAG, "RECORD THREAD ENTERED: ${Thread.currentThread().name}")
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
        if(ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED){
            // Access denied
            return
        }

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "Audio Record can't initialize!")
            return
        }

        audioRecord?.startRecording()
        Log.v(TAG, "Start recording")

        var firstLoop = true
        var totalSamplesRead: Int

        while (isRecording) {
            val tempRecordingBuffer = DoubleArray(SAMPLE_RATE - windowSize)

            if (!firstLoop) {
                totalSamplesRead = SAMPLE_RATE - windowSize

            } else {
                totalSamplesRead = 0
                firstLoop = false
            }
            while (totalSamplesRead < SAMPLE_RATE) {
                val remainingSamples = SAMPLE_RATE - totalSamplesRead
                val samplesToRead = if (remainingSamples > recordingBufferSize) recordingBufferSize else remainingSamples
                val audioBuffer = ShortArray(samplesToRead)
                val read = audioRecord?.read(audioBuffer, 0, samplesToRead)

                if (read != AudioRecord.ERROR_INVALID_OPERATION && read != AudioRecord.ERROR_BAD_VALUE) {
                    for (i in 0 until read!!){
                        recordingBuffer[totalSamplesRead + i] = audioBuffer[i].toDouble() / Short.MAX_VALUE
                    }
                    totalSamplesRead += read
                }


            }

            if (cheapPreCheck(recordingBuffer)) {
                if (hasSpeech(recordingBuffer)) {
                    // Speech just started → allow inference
                    inSpeech = true
                    computeBuffer(recordingBuffer)
                } else if (!hasSpeech(recordingBuffer) && inSpeech) {
                    // Speech ended → reset
                    inSpeech = false
                }
            } else {
                Log.d(TAG, "Skipped by precheck")
            }

            System.arraycopy(recordingBuffer, windowSize, tempRecordingBuffer, 0, recordingBuffer.size - windowSize)
            recordingBuffer = DoubleArray(RECORDING_LENGTH)

            System.arraycopy(tempRecordingBuffer, 0, recordingBuffer, 0, tempRecordingBuffer.size)

        }
        Log.d(TAG, "RECORD THREAD EXITING")
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        audioRecord = null
        stopRecording()
    }

    private fun computeBuffer(audioBuffer: DoubleArray) {
        val mfccConvert = MFCC()
        mfccConvert.setSampleRate(SAMPLE_RATE)
        val nMFCC = NUM_MFCC
        mfccConvert.setN_mfcc(nMFCC)
        val mfccInput = mfccConvert.process(audioBuffer)
        val nFFT = mfccInput.size / nMFCC
        val mfccValues = Array(nMFCC) { FloatArray(nFFT) }

        //loop to convert the mfcc values into multi-dimensional array
        for (i in 0 until nFFT) {
            var indexCounter = i * nMFCC
            val rowIndexValue = i % nFFT
            for (j in 0 until nMFCC) {
                mfccValues[j][rowIndexValue] = mfccInput[indexCounter]
                indexCounter++
            }
        }

        Log.d(TAG, "MFCC Shape: ${mfccValues.size}, ${mfccValues[0].size}")

        // Pass matrix to model
        loadAndPredict(mfccInput)
    }

    private fun loadAndPredict(mfccs: FloatArray) {
        //Load input
        inputBuffer.loadArray(mfccs)

        //run inference
        tflite.run(inputBuffer.buffer, outputBuffer.buffer)

        //results
        val probabilityProcessor = TensorProcessor.Builder().build()
        val labelProbabilities = TensorLabel(labels, probabilityProcessor.process(outputBuffer)).mapWithFloatValue

        val results = ArrayList<Result>()
        for (entry in labelProbabilities){
            results.add(Result(entry.key, entry.value.toDouble()))
        }

        Log.d(TAG, "Labels are: $labelProbabilities")

        val best = labelProbabilities.maxByOrNull { it.value }

        if(best != null) {
            Log.d(TAG, "Best result: ${best.key} -> ${best.value}")
        }
        if(best != null && best.value > probabilityThreshold) {
            Log.d(TAG, "Accepted results: ${best.key}")

            logPrediction(best.key, best.value.toDouble())

            updateData(results)
            updateNotification(best.key)
        }
    }
    
    fun foreground() {
        notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)
        isBackground = false
    }

    fun background() {
        isBackground = true
        stopForeground(STOP_FOREGROUND_DETACH)
    }

    private fun stopRecording() {
        isRecording = false
    }

    override fun onDestroy() {
        stopRecording()
        super.onDestroy()

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        Log.d(TAG, "Destroying service")
    }

    //NEW FUNCS
    private fun hasSpeech(audio: DoubleArray): Boolean {
        val frameSize = (0.02 * SAMPLE_RATE).toInt() // 20 ms
        val hopSize = frameSize / 2
        var speechFrames = 0
        var totalFrames = 0

        var i = 0
        while (i + frameSize < audio.size) {
            var sum = 0.0
            for (j in i until i + frameSize) {
                sum += audio[j] * audio[j]
            }
            val rms = Math.sqrt(sum / frameSize)
            if (rms > energyThreshold) speechFrames++
            totalFrames++
            i += hopSize
        }

        val ratio = speechFrames.toDouble() / totalFrames
        return ratio > 0.15   // at least 15% of frames contain sound
    }

    private fun cheapPreCheck(audio: DoubleArray): Boolean {
        var sum = 0.0
        for (sample in audio) sum += sample * sample
        val rms = Math.sqrt(sum / audio.size)
        return rms > (energyThreshold * 0.6)
    }

    private fun logPrediction(keyword: String, confidence: Double) {
        val prefs = getSharedPreferences("prediction_logs", MODE_PRIVATE)

        val existing = prefs.getString("logs", null)
        val logs = if (existing != null)
            FirebaseLogSerializer.fromJson(existing).toMutableList()
        else
            mutableListOf()

        logs.add(
            mapOf(
                "keyword" to keyword,
                "confidence" to String.format("%.3f", confidence),
                "timestamp" to SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss",
                    Locale.getDefault()
                ).format(Date())
            )
        )

        prefs.edit()
            .putString("logs", FirebaseLogSerializer.toJson(logs))
            .apply()
    }
}
