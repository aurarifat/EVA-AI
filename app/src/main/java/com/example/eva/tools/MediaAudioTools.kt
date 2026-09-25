package com.example.eva.tools

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class AudioTrack(
    val id: Long,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val uri: Uri
)

class MediaAudioTools(private val context: Context) {

    // Voice recorder
    private var recorder: MediaRecorder? = null
    private var currentRecordingFile: File? = null
    private var recordingStartTime = 0L
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    // Local music player
    private var mediaPlayer: MediaPlayer? = null
    private val _isPlayingMusic = MutableStateFlow(false)
    val isPlayingMusic: StateFlow<Boolean> = _isPlayingMusic.asStateFlow()
    private val _currentTrack = MutableStateFlow<AudioTrack?>(null)
    val currentTrack: StateFlow<AudioTrack?> = _currentTrack.asStateFlow()

    fun startVoiceRecording(): ToolExecutionResult {
        if (_isRecording.value) {
            return ToolExecutionResult(false, "Voice recording is already in progress.")
        }
        return try {
            val dir = File(context.filesDir, "eva_recordings").apply { mkdirs() }
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val file = File(dir, "EVA_Recording_$timeStamp.m4a")
            currentRecordingFile = file

            recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            recordingStartTime = System.currentTimeMillis()
            _isRecording.value = true
            ToolExecutionResult(true, "Voice recording started.", data = file.absolutePath)
        } catch (e: Exception) {
            _isRecording.value = false
            ToolExecutionResult(false, "Failed to start recording: ${e.localizedMessage}")
        }
    }

    fun stopVoiceRecording(): ToolExecutionResult {
        if (!_isRecording.value || recorder == null) {
            return ToolExecutionResult(false, "No active recording to stop.")
        }
        return try {
            recorder?.stop()
            recorder?.release()
            recorder = null
            _isRecording.value = false
            val durationSec = ((System.currentTimeMillis() - recordingStartTime) / 1000).toInt()
            val path = currentRecordingFile?.absolutePath ?: ""
            ToolExecutionResult(true, "Recording stopped and saved ($durationSec seconds).", data = path)
        } catch (e: Exception) {
            recorder = null
            _isRecording.value = false
            ToolExecutionResult(false, "Error stopping recording: ${e.localizedMessage}")
        }
    }

    suspend fun getLocalAudioTracks(): List<AudioTrack> = withContext(Dispatchers.IO) {
        val tracks = mutableListOf<AudioTrack>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        try {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                null,
                "${MediaStore.Audio.Media.TITLE} ASC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val durCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val title = cursor.getString(titleCol)
                    val artist = cursor.getString(artistCol)
                    val duration = cursor.getLong(durCol)
                    val contentUri = Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id.toString())
                    tracks.add(AudioTrack(id, title, artist, duration, contentUri))
                }
            }
        } catch (_: Exception) {}
        tracks
    }

    suspend fun playMusic(): ToolExecutionResult = withContext(Dispatchers.IO) {
        if (mediaPlayer != null && !_isPlayingMusic.value) {
            mediaPlayer?.start()
            _isPlayingMusic.value = true
            return@withContext ToolExecutionResult(true, "Resumed playback.")
        }

        val tracks = getLocalAudioTracks()
        if (tracks.isEmpty()) {
            return@withContext ToolExecutionResult(false, "No local music tracks found on device.")
        }

        val track = tracks.first()
        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer.create(context, track.uri)?.apply {
                start()
                setOnCompletionListener {
                    _isPlayingMusic.value = false
                }
            }
            _isPlayingMusic.value = true
            _currentTrack.value = track
            ToolExecutionResult(true, "Playing '${track.title}' by ${track.artist}.")
        } catch (e: Exception) {
            ToolExecutionResult(false, "Could not play music: ${e.localizedMessage}")
        }
    }

    fun pauseMusic(): ToolExecutionResult {
        return try {
            if (mediaPlayer?.isPlaying == true) {
                mediaPlayer?.pause()
                _isPlayingMusic.value = false
                ToolExecutionResult(true, "Music paused.")
            } else {
                ToolExecutionResult(false, "Music is not currently playing.")
            }
        } catch (e: Exception) {
            ToolExecutionResult(false, "Failed to pause music: ${e.localizedMessage}")
        }
    }

    fun cleanup() {
        stopVoiceRecording()
        mediaPlayer?.release()
        mediaPlayer = null
    }
}
