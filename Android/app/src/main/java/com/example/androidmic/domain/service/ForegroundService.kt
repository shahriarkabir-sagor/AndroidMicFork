package com.example.androidmic.domain.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.*
import android.util.Log
import com.example.androidmic.R
import com.example.androidmic.domain.audio.AudioBuffer
import com.example.androidmic.domain.audio.MicAudioManager
import com.example.androidmic.domain.streaming.MicStreamManager
import com.example.androidmic.utils.Command.Companion.COMMAND_DISC_STREAM
import com.example.androidmic.utils.Command.Companion.COMMAND_GET_STATUS
import com.example.androidmic.utils.Command.Companion.COMMAND_START_AUDIO
import com.example.androidmic.utils.Command.Companion.COMMAND_START_STREAM
import com.example.androidmic.utils.Command.Companion.COMMAND_STOP_AUDIO
import com.example.androidmic.utils.Command.Companion.COMMAND_STOP_STREAM
import com.example.androidmic.utils.States
import com.example.androidmic.utils.ignore
import com.example.androidmic.utils.DebugModes
import kotlinx.coroutines.*

class ForegroundService : Service() {
    private val TAG = "MicService"
    private val scope = CoroutineScope(Dispatchers.Default)
    private val WAIT_PERIOD = 500L


    private inner class ServiceHandler(looper: Looper) : Handler(looper) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                COMMAND_START_STREAM -> startStream(msg)
                COMMAND_STOP_STREAM -> stopStream(msg)
                COMMAND_START_AUDIO -> startAudio(msg)
                COMMAND_STOP_AUDIO -> stopAudio(msg)
                COMMAND_GET_STATUS -> getStatus(msg)
            }
        }
    }

    private lateinit var handlerThread: HandlerThread
    private lateinit var serviceLooper: Looper
    private lateinit var serviceHandler: ServiceHandler
    private lateinit var serviceMessenger: Messenger

    private var sharedBuffer = AudioBuffer()
    private var managerAudio: MicAudioManager? = null
    private var managerStream: MicStreamManager? = null
    private var jobStreamM: Job? = null
    private var jobAudioM: Job? = null


    private val states = States.ServiceStates()
    private lateinit var messageui: MessageUi

    override fun onCreate() {
        // create message handler
        handlerThread = HandlerThread("MicServiceStart", Process.THREAD_PRIORITY_BACKGROUND)
        handlerThread.start()
        serviceLooper = handlerThread.looper
        serviceHandler = ServiceHandler(handlerThread.looper)
        serviceMessenger = Messenger(serviceHandler)

        // Create the NotificationChannel, but only on API 26+ because
        // the NotificationChannel class is new and not in the support library
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = getString(R.string.app_name)
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel("service", name, importance)
            // Register the channel with the system
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
        messageui = MessageUi(this)
        messageui.removeNotification(2)
    }

    override fun onBind(intent: Intent?): IBinder? {
        return serviceMessenger.binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand")
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")
        messageui.removeNotification(2)
        managerAudio?.shutdown()
        managerStream?.shutdown()
        states.streamShouldStop.set(true)
        states.audioShouldStop.set(true)
        runBlocking {
            delay(WAIT_PERIOD)
            if (jobStreamM?.isActive == true) jobStreamM?.cancel()
            if (jobAudioM?.isActive == true) jobAudioM?.cancel()
            jobStreamM?.join()
            jobAudioM?.join()
        }
        serviceLooper.quitSafely()
        ignore { handlerThread.join(WAIT_PERIOD) }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d(TAG, "onTaskRemoved")
        stopSelf()
    }

    // start streaming
    private fun startStream(msg: Message) {
        val sender = msg.replyTo
        val replyData = Bundle()
        // check connection state
        if (states.isStreamStarted.get()) {
            replyData.putString("reply", this.getString(R.string.stream_already_started))
            reply(sender, replyData, COMMAND_START_STREAM, true)
            return
        } else if (jobStreamM?.isActive == true) {
            // avoid duplicate jobs
            reply(sender, replyData, COMMAND_START_STREAM, false)
            return
        }

        // get params before going into the scope
        val mode: Int = msg.data.getInt("MODE")
        val ip: String? = msg.data.getString("IP")
        val port: Int = msg.data.getInt("PORT")

        Log.d(TAG, "startStream [start]")
        // try to start streaming
        jobStreamM = scope.launch {
            managerStream?.shutdown()
            managerStream = MicStreamManager(applicationContext)

            try {
                managerStream?.initialize(mode, ip, port)
            } catch (e: IllegalArgumentException) {
                val debugModes = DebugModes()
                Log.d(TAG, "start stream with mode ${debugModes.dic[mode]} failed:\n${e.message}")
                replyData.putString("reply", applicationContext.getString(R.string.error) + e.message)
                reply(sender, replyData, COMMAND_START_STREAM, false)
                cancel()
                awaitCancellation()
            }
            states.streamShouldStop.set(false)

            sharedBuffer.clear()
            if (managerStream?.start() == true && managerStream?.isConnected() == true) {
                replyData.putString(
                    "reply",
                    applicationContext.getString(R.string.connected_device) +
                    managerStream?.getInfo()
                )
                reply(sender, replyData, COMMAND_START_STREAM, true)
                Log.d(TAG, "startStream [connected]")
            } else {
                replyData.putString("reply", applicationContext.getString(R.string.failed_to_connect))
                reply(sender, replyData, COMMAND_START_STREAM, false)
                managerStream?.shutdown()
                cancel()
                awaitCancellation()
            }
            messageui.showMessage(applicationContext.getString(R.string.start_streaming))
            messageui.showNotification(getString(R.string.notification_text_stream), 0)
            states.isStreamStarted.set(true)
            states.streamShouldStop.set(false)
            while (!states.streamShouldStop.get()) {
                if (managerStream?.isConnected() == true) {
                    managerStream?.stream(sharedBuffer)
                    delay(MicStreamManager.STREAM_DELAY)
                } else {
                    replyData.putString("reply", applicationContext.getString(R.string.device_disconnected))
                    messageui.showMessage(applicationContext.getString(R.string.stop_streaming))
                    reply(sender, replyData, COMMAND_DISC_STREAM, false)
                    break
                }
            }
            messageui.removeNotification(0)
            states.isStreamStarted.set(false)
        }
    }

    // stop streaming
    private fun stopStream(msg: Message) {
        Log.d(TAG, "stopStream")
        val sender = msg.replyTo
        val replyData = Bundle()
        runBlocking {
            states.streamShouldStop.set(true)
            managerStream?.shutdown()
            delay(WAIT_PERIOD)
            jobStreamM?.cancel()
        }
        managerStream = null
        jobStreamM = null
        replyData.putString("reply", applicationContext.getString(R.string.device_disconnected))
        messageui.showMessage(applicationContext.getString(R.string.stop_streaming))
        states.isStreamStarted.set(false)
        messageui.removeNotification(0)
        reply(sender, replyData, COMMAND_STOP_STREAM, true)
    }

    // start mic
    private fun startAudio(msg: Message) {
        val sender = msg.replyTo
        val replyData = Bundle()
        // check audio state
        if (states.isAudioStarted.get()) {
            replyData.putString("reply", this.getString(R.string.microphone_already_started))
            reply(sender, replyData, COMMAND_START_AUDIO, true)
            return
        } else if (jobAudioM?.isActive == true) {
            // avoid duplicate jobs
            reply(sender, replyData, COMMAND_START_AUDIO, false)
            return
        }
        Log.d(TAG, "startAudio [start]")
        // start audio recording
        jobAudioM = scope.launch {
            managerAudio?.shutdown()
            managerAudio = try {
                MicAudioManager(applicationContext)
            } catch (e: IllegalArgumentException) {
                replyData.putString("reply", application.getString(R.string.error) + e.message)
                reply(sender, replyData, COMMAND_START_AUDIO, false)
                cancel()
                awaitCancellation()
            }
            // start recording
            sharedBuffer.clear()
            managerAudio?.start()
            messageui.showNotification(getString(R.string.notification_text_audio), 1)
            messageui.showMessage(application.getString(R.string.start_recording))
            replyData.putString("reply", application.getString(R.string.mic_start_recording))
            reply(sender, replyData, COMMAND_START_AUDIO, true)
            Log.d(TAG, "startAudio [recording]")
            // record into buffer
            states.isAudioStarted.set(true)
            states.audioShouldStop.set(false)
            while (!states.audioShouldStop.get()) {
                managerAudio?.record(sharedBuffer)
                delay(MicAudioManager.RECORD_DELAY)
            }
            states.isAudioStarted.set(false)
            messageui.removeNotification(1)
        }
    }

    // stop mic
    private fun stopAudio(msg: Message) {
        Log.d(TAG, "stopAudio")
        val sender = msg.replyTo
        val replyData = Bundle()
        runBlocking {
            states.audioShouldStop.set(true)
            managerAudio?.shutdown()
            delay(WAIT_PERIOD)
            jobAudioM?.cancel()
        }
        managerAudio = null
        jobAudioM = null
        replyData.putString("reply", application.getString(R.string.recording_stopped))
        messageui.showMessage(application.getString(R.string.stop_recording))
        messageui.removeNotification(1)
        states.isAudioStarted.set(false)
        reply(sender, replyData, COMMAND_STOP_AUDIO, true)
    }


    private fun getStatus(msg: Message) {
        val sender = msg.replyTo
        val replyData = Bundle()
        replyData.putBoolean("isStreamStarted", states.isStreamStarted.get())
        replyData.putBoolean("isAudioStarted", states.isAudioStarted.get())
        val reply = Message()
        reply.data = replyData
        reply.what = COMMAND_GET_STATUS
        sender.send(reply)
    }
}