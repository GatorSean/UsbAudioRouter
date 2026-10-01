package com.example.usbaudiorouter

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media.session.MediaButtonReceiver

/**
 * Foreground service that reads stereo audio from a USB audio input device
 * (e.g. Saramonic SR-C2003) and writes it in real time to the system audio
 * output (speakers, Bluetooth headphones, Android Auto).
 *
 * A MediaSession is held active while routing so that:
 *  - Android's media output switcher (volume panel / quick settings) lists the
 *    app and allows the user to redirect audio to any available device, including
 *    wireless Android Auto (e.g. "Sean's Sync").
 *  - Android Auto recognises the app as an active media source.
 *
 * Start  → send Intent with action ACTION_START
 * Stop   → send Intent with action ACTION_STOP, or tap "Stop" in the notification
 */
class AudioRouterService : Service() {

    // ── Constants ────────────────────────────────────────────────────────────

    companion object {
        const val ACTION_START = "com.example.usbaudiorouter.ACTION_START"
        const val ACTION_STOP  = "com.example.usbaudiorouter.ACTION_STOP"
        const val ACTION_QUERY_STATUS = "com.example.usbaudiorouter.ACTION_QUERY_STATUS"

        /** Broadcast sent back to MainActivity with routing state updates. */
        const val ACTION_ROUTING_STATUS = "com.example.usbaudiorouter.ROUTING_STATUS"
        const val EXTRA_IS_ROUTING      = "is_routing"
        const val EXTRA_STATUS_MESSAGE  = "status_message"

        private const val NOTIFICATION_CHANNEL_ID = "usb_audio_router"
        private const val NOTIFICATION_ID         = 1001

        // SR-C2003 confirmed: 48 kHz, stereo, 16-bit PCM
        private const val ENCODING    = AudioFormat.ENCODING_PCM_16BIT
        private const val CHANNEL_IN  = AudioFormat.CHANNEL_IN_STEREO
        private const val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_STEREO
    }

    // ── State ────────────────────────────────────────────────────────────────

    private var audioRecord:   AudioRecord?        = null
    private var audioTrack:    AudioTrack?          = null
    private var routingThread: Thread?              = null
    private var mediaSession:  MediaSessionCompat?  = null
    private var focusRequest:  AudioFocusRequest?   = null
    @Volatile private var isRunning = false

    // ── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Let MediaButtonReceiver forward hardware button events to the session
        MediaButtonReceiver.handleIntent(mediaSession, intent)

        when (intent?.action) {
            ACTION_START -> {
                startServiceForeground()
                startRouting()
            }
            ACTION_STOP -> {
                stopRouting()
                stopSelf()
            }
            ACTION_QUERY_STATUS -> {
                broadcastStatus(isRunning, if (isRunning) "Routing active" else "Inactive")
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopRouting()
        super.onDestroy()
    }

    // ── Routing ──────────────────────────────────────────────────────────────

    private fun startRouting() {
        if (isRunning) {
            broadcastStatus(true, "Routing active — 48 kHz stereo")
            return
        }

        val audioManager = getSystemService(AudioManager::class.java)

        // ── 1. Audio focus ────────────────────────────────────────────────────
        // Requesting AUDIOFOCUS_GAIN with USAGE_MEDIA is what tells the system
        // (and Android Auto) that this app owns the media stream.
        val mediaAttrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(mediaAttrs)
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener { change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS -> {
                        // Permanent loss — stop cleanly
                        stopRouting()
                        stopSelf()
                    }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                        // Transient loss — keep service alive, the system handles
                        // ducking for us. We stay running to avoid dropouts
                        // during minor system events like orientation changes.
                    }
                }
            }
            .build()

        val focusResult = audioManager.requestAudioFocus(request)
        if (focusResult != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            broadcastStatus(false, "Could not obtain audio focus")
            return
        }
        focusRequest = request

        // ── 2. MediaSession ───────────────────────────────────────────────────
        // An active MediaSession is what makes our app appear in the system
        // media output switcher and Android Auto's audio routing.
        val session = MediaSessionCompat(this, "UsbAudioRouter").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onStop() {
                    stopRouting()
                    stopSelf()
                }
            })
            setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setState(PlaybackStateCompat.STATE_PLAYING, 0L, 1f)
                    .setActions(PlaybackStateCompat.ACTION_STOP)
                    .build()
            )
            isActive = true
        }
        mediaSession = session

        // Rebuild notification with the session token so the OS links them
        startServiceForeground()

        // ── 3. AudioRecord ────────────────────────────────────────────────────
        val record = buildAudioRecord() ?: run {
            tearDownSession()
            broadcastStatus(false, "Could not open USB audio input")
            return
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            tearDownSession()
            broadcastStatus(false, "AudioRecord failed to initialize")
            return
        }

        // ── 4. AudioTrack ─────────────────────────────────────────────────────
        val outBufferSize = maxOf(
            AudioTrack.getMinBufferSize(UsbAudioHelper.SAMPLE_RATE, CHANNEL_OUT, ENCODING), 4096
        ) * 4

        val track = AudioTrack.Builder()
            .setAudioAttributes(mediaAttrs)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(ENCODING)
                    .setSampleRate(UsbAudioHelper.SAMPLE_RATE)
                    .setChannelMask(CHANNEL_OUT)
                    .build()
            )
            .setBufferSizeInBytes(outBufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        if (track.state != AudioTrack.STATE_INITIALIZED) {
            record.release()
            track.release()
            tearDownSession()
            broadcastStatus(false, "AudioTrack failed to initialize")
            return
        }

        audioRecord = record
        audioTrack  = track
        isRunning   = true

        record.startRecording()
        track.play()
        broadcastStatus(true, "Routing active — 48 kHz stereo")

        // ── 5. Loopback thread ────────────────────────────────────────────────
        routingThread = Thread({
            val buffer = ByteArray(record.bufferSizeInFrames * 4) // 16-bit stereo = 4 bytes/frame
            var consecutiveErrors = 0
            while (isRunning) {
                val bytesRead = record.read(buffer, 0, buffer.size)
                when {
                    bytesRead > 0 -> {
                        track.write(buffer, 0, bytesRead)
                        consecutiveErrors = 0
                    }
                    bytesRead < 0 -> {
                        // Handle errors (INVALID_OPERATION, BAD_VALUE, etc.)
                        consecutiveErrors++
                        if (consecutiveErrors > 20) { // Tolerate ~400ms of errors
                            isRunning = false
                            broadcastStatus(false, "USB device read error — was it unplugged?")
                        } else {
                            // Transient error (likely during orientation/device reset)
                            // Sleep briefly and retry
                            Thread.sleep(20)
                        }
                    }
                    // bytesRead == 0: legal transient, keep looping
                }
            }
        }, "UsbAudioRoutingThread").apply { start() }
    }

    private fun stopRouting() {
        isRunning = false
        routingThread?.join(2_000)
        routingThread = null

        audioRecord?.run { stop(); release() }
        audioRecord = null

        audioTrack?.run { stop(); release() }
        audioTrack = null

        tearDownSession()
        broadcastStatus(false, "Routing stopped")
    }

    private fun tearDownSession() {
        mediaSession?.run { isActive = false; release() }
        mediaSession = null

        focusRequest?.let {
            getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it)
        }
        focusRequest = null
    }

    // ── AudioRecord construction ──────────────────────────────────────────────

    private fun buildAudioRecord(): AudioRecord? {
        val bufferSize = maxOf(
            AudioRecord.getMinBufferSize(UsbAudioHelper.SAMPLE_RATE, CHANNEL_IN, ENCODING), 4096
        ) * 4

        if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
            return null
        }

        val record = try {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.UNPROCESSED)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(UsbAudioHelper.SAMPLE_RATE)
                        .setChannelMask(CHANNEL_IN)
                        .setEncoding(ENCODING)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .build()
        } catch (e: Exception) {
            return null
        }

        UsbAudioHelper.preferredInputDevice(this)?.let { record.setPreferredDevice(it) }
        return record
    }

    // ── Broadcast helpers ─────────────────────────────────────────────────────

    private fun startServiceForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var foregroundType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                foregroundType = foregroundType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            startForeground(NOTIFICATION_ID, notification, foregroundType)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun broadcastStatus(routing: Boolean, message: String) {
        val intent = Intent(ACTION_ROUTING_STATUS).apply {
            putExtra(EXTRA_IS_ROUTING, routing)
            putExtra(EXTRA_STATUS_MESSAGE, message)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "USB Audio Router",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows while USB audio is being routed to system output"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, AudioRouterService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("USB Audio Router")
            .setContentText("Routing USB audio to system output")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        // Attach the session token when available so the system media UI
        // (output switcher, Android Auto) links the notification to this session
        mediaSession?.sessionToken?.let { builder.setStyle(
            androidx.media.app.NotificationCompat.MediaStyle()
                .setMediaSession(it)
                .setShowActionsInCompactView(0)
        )}

        return builder.build()
    }
}
