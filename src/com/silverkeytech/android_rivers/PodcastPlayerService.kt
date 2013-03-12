/*
Android Rivers is an app to read and discover news using RiverJs, RSS and OPML format.
Copyright (C) 2012 Dody Gunawinata (dodyg@silverkeytech.com)

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see <http://www.gnu.org/licenses/>
*/

package com.silverkeytech.android_rivers

import android.app.Service
import android.app.Service.START_STICKY
import android.content.Intent
import android.media.MediaPlayer
import android.media.MediaPlayer.OnErrorListener
import android.os.Binder
import android.os.IBinder
import android.widget.Toast
import android.net.Uri
import android.util
import android.util.Log
import android.os.PowerManager
import android.widget.RemoteViews
import android.app.Notification
import android.app.PendingIntent
import android.support.v4.app.NotificationCompat
import java.util.Random
import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.media.AudioManager.OnAudioFocusChangeListener
import android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
import android.media.AudioManager.AUDIOFOCUS_GAIN
import android.media.AudioManager.AUDIOFOCUS_LOSS
import android.os.Handler
import android.os.Message
import android.os.Bundle

public open class PodcastPlayerService(): Service(), MediaPlayer.OnErrorListener, OnAudioFocusChangeListener {
    class object{
        public val TAG: String = javaClass<PodcastPlayerService>().getSimpleName()
        public val CURRENT_POSITION : String = "CURRENT_POSITION"
        public val TOTAL_DURATION : String = "TOTAL_DURATION"
    }

    private val binder: IBinder = ServiceBinder()
    private var mediaPlayer: MediaPlayer? = null
    private var lastPlayPosition: Int = 0


    inner class ServiceBinder(): Binder() {
        fun getService(): PodcastPlayerService? {
            return this@PodcastPlayerService
        }
    }

    public override fun onBind(p0: Intent?): IBinder? {
        return binder
    }

    fun prepareNotification(): Notification {
        val notificationIntent = Intent(Intent.ACTION_MAIN)
        notificationIntent.setClass(getApplicationContext(), javaClass<MainWithFragmentsActivity>())

        val contentIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_CANCEL_CURRENT)

        val notification = NotificationCompat.Builder(this)
                .setTicker("Playing $podcastTitle")
        ?.setWhen(System.currentTimeMillis())
        ?.setContentIntent(contentIntent)
        ?.build()

        notification!!.icon = android.R.drawable.star_big_on

        notification.contentView = RemoteViews(getApplicationContext()!!.getPackageName(), R.layout.notification_podcast_player).with {
            this.setImageViewResource(R.id.notification_podcast_player_status_icon, android.R.drawable.btn_star)
            this.setTextViewText(R.id.notification_podcast_player_status_text, getString(R.string.download_starts))
        }

        return notification
    }

    private val notificationId = Random().nextLong().toInt()
    private var notification : Notification? = null
    private var notificationManager : NotificationManager? = null
    public var podcastTitle : String? = null
        get () = $podcastTitle
        private set (value : String?) = $podcastTitle = value

    private var podcastPath : String? = null

    fun updateText(msg: String) {
        notification!!.contentView!!.setTextViewText(R.id.notification_podcast_player_status_text, msg)
        notificationManager!!.notify(notificationId, notification)
    }

    var audioManager : AudioManager? = null

    //http://developer.android.com/training/managing-audio/audio-focus.html
    public override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Starting Podcast Player Service")
        podcastTitle = intent!!.getStringExtra(Params.PODCAST_TITLE)
        podcastPath = intent.getStringExtra(Params.PODCAST_PATH)
        notification = prepareNotification()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val result = audioManager?.requestAudioFocus(this, AudioManager.STREAM_MUSIC,AudioManager.AUDIOFOCUS_GAIN);

        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            updateText("Sorry, I cannot play $podcastTitle at this moment")
        }
        else{
            updateText("Playing $podcastTitle")

            mediaPlayer = MediaPlayer.create(this, Uri.parse(podcastPath))
            mediaPlayer?.setOnErrorListener(this)
            mediaPlayer?.setLooping(false)
            //mediaPlayer?.setVolume(100.0, 100.0)
            mediaPlayer?.start()

            mediaPlayer!!.setOnCompletionListener(object: MediaPlayer.OnCompletionListener{
                public override fun onCompletion(p0: MediaPlayer?) {
                    updateText("Podcast completed")
                }
            })

            progressThread = Thread(progress)
        }

        progressThread?.start()

        return super<Service>.onStartCommand(intent, flags, startId)
    }

    private var isUpdateProgress : Boolean = true

    private val progress : Runnable = object : Runnable{
        public override fun run() {
            try{
                Log.d(TAG, "Music progress update starts")
                while (isPlaying() && isUpdateProgress){
                    Thread.sleep(500)
                    Log.d(TAG, "Current position ${mediaPlayer!!.getCurrentPosition()}")

                    if (progressHandler != null){
                        val msg = Message()
                        val bundle = Bundle()
                        bundle.putInt(PodcastPlayerService.CURRENT_POSITION, mediaPlayer!!.getCurrentPosition())
                        bundle.putInt(PodcastPlayerService.TOTAL_DURATION, mediaPlayer!!.getDuration())
                        msg.setData(bundle)
                        progressHandler!!.sendMessage(msg)
                    }
                }
                Log.d(TAG, "Music progress update stops")
            }
            catch(e : Exception){
                Log.d(TAG, "Exception in progress thread ${e.getMessage()}")
            }
        }
    }

    private var progressHandler: Handler? = null
    private var progressThread : Thread? = null

    public fun setProgressHandler(handler : Handler?){
        progressHandler = handler
    }

    public override fun onAudioFocusChange(p0: Int) {
        if (p0 == AUDIOFOCUS_LOSS_TRANSIENT){
            this.pauseMusic()
        }
        else if (p0 == AudioManager.AUDIOFOCUS_GAIN) {
            this.resumeMusic()
        } else if (p0 == AudioManager.AUDIOFOCUS_LOSS) {
            audioManager?.abandonAudioFocus(this)
            this.stopMusic()
        }
    }

    public fun isPlaying() : Boolean{
        if (mediaPlayer != null)
            return mediaPlayer!!.isPlaying()
        else
            return false
    }

    public fun isPaused() : Boolean {
        if (mediaPlayer != null)
            return !mediaPlayer!!.isPlaying() && mediaPlayer!!.getCurrentPosition() != 0
        else
            return false
    }

    public fun pauseMusic(): Unit {
        if (mediaPlayer!!.isPlaying()){
            mediaPlayer!!.pause()
            lastPlayPosition = mediaPlayer!!.getCurrentPosition()
            updateText("$podcastTitle is paused")
            Log.d(TAG, "$podcastTitle is paused")
            isUpdateProgress = false
        }
    }

    public fun resumeMusic(): Unit {
        if (!mediaPlayer!!.isPlaying())
        {
            mediaPlayer?.seekTo(lastPlayPosition)
            mediaPlayer?.start()
            updateText("Playing $podcastTitle")
            Log.d(TAG, "Resume Playing $podcastTitle")
            isUpdateProgress = true
            progressThread = Thread(progress)
            progressThread?.start()
        }
    }

    public fun stopMusic(): Unit {
        mediaPlayer?.stop()
        mediaPlayer?.release()
        updateText("$podcastTitle is stopped")
        mediaPlayer = null
        isUpdateProgress = false
        Log.d(TAG, "Stop playing $podcastTitle and Stopping service")
        this.stopSelf()
    }

    public fun getCurrentPosition(): Int?{
        return mediaPlayer?.getCurrentPosition()
    }

    public fun getPodcastLength() : Int?{
        return mediaPlayer?.getDuration()
    }

    public fun seekToPosition(pos : Int){
        lastPlayPosition = pos
    }

    public override fun onDestroy(): Unit {
        super<Service>.onDestroy()
        if (mediaPlayer != null){
            try {
                mediaPlayer!!.stop()
                mediaPlayer!!.release()
            }
            finally {
                mediaPlayer = null
            }
        }
    }

    public override fun onError(mp: MediaPlayer?, what: Int, extra: Int): Boolean {
        Toast.makeText(this, "Music player failed", Toast.LENGTH_SHORT)?.show()
        if (mediaPlayer != null){
            try {
                mediaPlayer!!.stop()
                mediaPlayer!!.release()
            }
            finally {
                mediaPlayer = null
            }
        }
        return false
    }
}
