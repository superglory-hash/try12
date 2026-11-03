package com.seoul.fmonair

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
class RadioPlaybackService : MediaLibraryService() {

    companion object {
        private const val ROOT_ID = "seoul_fm_root"
        // 상단 상태표시줄 아이콘이 숨겨지지 않도록 새 채널 ID 사용
        private const val NOTIFICATION_CHANNEL_ID = "seoul_fm_onair_status_v2"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_PLAY_STATION = "com.seoul.fmonair.ACTION_PLAY_STATION"
        const val ACTION_NEXT_STATION = "com.seoul.fmonair.ACTION_NEXT_STATION"
        const val ACTION_PREV_STATION = "com.seoul.fmonair.ACTION_PREV_STATION"
        const val ACTION_TOGGLE_PLAY = "com.seoul.fmonair.ACTION_TOGGLE_PLAY"
        const val ACTION_STOP_PLAYBACK = "com.seoul.fmonair.ACTION_STOP_PLAYBACK"
        const val ACTION_EXIT_APP = "com.seoul.fmonair.ACTION_EXIT_APP"
        const val ACTION_ENSURE_NOTIFICATION = "com.seoul.fmonair.ACTION_ENSURE_NOTIFICATION"
        const val EXTRA_STATION_ID = "extra_station_id"

        private val _currentStationFlow = MutableStateFlow(StationRepository.initialStations[2])
        val currentStationFlow: StateFlow<RadioStation> = _currentStationFlow

        private val _currentProgramFlow = MutableStateFlow(
            StationRepository.getScheduledProgramTitle(StationRepository.initialStations[2])
        )
        val currentProgramFlow: StateFlow<String> = _currentProgramFlow

        private val _isPlayingFlow = MutableStateFlow(false)
        val isPlayingFlow: StateFlow<Boolean> = _isPlayingFlow

        private val _streamStatusFlow = MutableStateFlow("대기 중")
        val streamStatusFlow: StateFlow<String> = _streamStatusFlow

        private val _liveMetadataTitleFlow = MutableStateFlow<String?>(null)
        val liveMetadataTitleFlow: StateFlow<String?> = _liveMetadataTitleFlow

        // 상단 알림에서 [앱 종료] 클릭 시 MainActivity까지 완전히 닫기 위한 신호
        private val _shouldExitAppFlow = MutableStateFlow(false)
        val shouldExitAppFlow: StateFlow<Boolean> = _shouldExitAppFlow

        fun resetExitSignal() {
            _shouldExitAppFlow.value = false
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var player: ExoPlayer? = null
    private var mediaLibrarySession: MediaLibrarySession? = null
    private var programRefreshJob: Job? = null
    private var hasRetriedFallback: Boolean = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0.0.0 Mobile Safari/537.36")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(10_000)

        val exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(httpDataSourceFactory))
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        exoPlayer.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlayingFlow.value = isPlaying
                if (isPlaying) {
                    _streamStatusFlow.value = "ON-AIR 실시간 스트리밍 중"
                    promoteToForeground(
                        buildPlaybackNotification(_currentStationFlow.value, _currentProgramFlow.value, true)
                    )
                    startProgramAutoRefresh()
                } else {
                    if (_streamStatusFlow.value != "재생 중지됨") {
                        _streamStatusFlow.value = "일시정지됨"
                    }
                    programRefreshJob?.cancel()
                    val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    notificationManager.notify(
                        NOTIFICATION_ID,
                        buildPlaybackNotification(_currentStationFlow.value, _currentProgramFlow.value, false)
                    )
                }
                RadioWidgetReceiver.notifyWidgetUpdate(this@RadioPlaybackService)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> {
                        _streamStatusFlow.value = "스트림 토큰 해석 및 버퍼링 중..."
                        promoteToForeground(
                            buildPlaybackNotification(_currentStationFlow.value, _currentProgramFlow.value, true)
                        )
                    }
                    Player.STATE_READY -> {
                        if (exoPlayer.isPlaying) {
                            _streamStatusFlow.value = "ON-AIR 실시간 스트리밍 중"
                            promoteToForeground(
                                buildPlaybackNotification(_currentStationFlow.value, _currentProgramFlow.value, true)
                            )
                        }
                    }
                    Player.STATE_ENDED, Player.STATE_IDLE -> {}
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val station = _currentStationFlow.value
                if (!hasRetriedFallback && station.fallbackStreamUrl.isNotBlank()) {
                    hasRetriedFallback = true
                    _streamStatusFlow.value = "${station.name} 예비 스트림 서버로 전환 중..."
                    val liveProgram = _currentProgramFlow.value
                    val metadata = MediaMetadata.Builder()
                        .setTitle("${station.name} (${station.frequencyText})")
                        .setArtist("🎙️ $liveProgram")
                        .setStation(station.name)
                        .setSubtitle(liveProgram)
                        .setIsBrowsable(false)
                        .setIsPlayable(true)
                        .build()

                    val fallbackItem = MediaItem.Builder()
                        .setMediaId(station.id)
                        .setUri(Uri.parse(station.fallbackStreamUrl))
                        .setMediaMetadata(metadata)
                        .build()

                    exoPlayer.setMediaItem(fallbackItem)
                    exoPlayer.prepare()
                    exoPlayer.play()
                } else {
                    _streamStatusFlow.value = "스트림 연결 실패 (우측 상단 연필 아이콘에서 URL 점검 가능)"
                }
            }

            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                val title = mediaMetadata.title?.toString()
                if (!title.isNullOrBlank() && title != _currentStationFlow.value.name) {
                    _liveMetadataTitleFlow.value = title
                }
            }
        })

        player = exoPlayer

        val openAppIntent = Intent(this, MainActivity::class.java)
        val sessionActivityPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaLibrarySession = MediaLibrarySession.Builder(this, exoPlayer, LibrarySessionCallback())
            .setSessionActivity(sessionActivityPendingIntent)
            .build()
    }

    private fun promoteToForeground(notification: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // IMPORTANCE_DEFAULT + 무음 설정으로 상단 상태표시줄에 아이콘이 항상 표시되도록 설정
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "수도권 FM 라디오 상단 재생 컨트롤",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "홈 화면 이동 시 상단 상태표시줄에 재생 상태 표시 및 중지/종료 버튼 제공"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(true)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildPlaybackNotification(
        station: RadioStation,
        programTitle: String,
        isPlaying: Boolean
    ): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPi = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // 1. 일시정지 / 재생 버튼
        val toggleIntent = Intent(this, RadioPlaybackService::class.java).apply { action = ACTION_TOGGLE_PLAY }
        val togglePi = PendingIntent.getService(
            this,
            1,
            toggleIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // 2. 재생 완전 중지 버튼
        val stopIntent = Intent(this, RadioPlaybackService::class.java).apply { action = ACTION_STOP_PLAYBACK }
        val stopPi = PendingIntent.getService(
            this,
            2,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // 3. 프로그램 완전 종료 버튼
        val exitIntent = Intent(this, RadioPlaybackService::class.java).apply { action = ACTION_EXIT_APP }
        val exitPi = PendingIntent.getService(
            this,
            3,
            exitIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val statusPrefix = if (isPlaying) "🔴 [재생중]" else "⏸ [일시정지]"
        val statusLabel = if (isPlaying) "ON-AIR 실시간 재생 중" else "일시정지됨 (대기 중)"

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("$statusPrefix ${station.name} (${station.frequencyText})")
            .setContentText("🎙️ $programTitle")
            .setSubText(statusLabel)
            .setSmallIcon(if (isPlaying) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause)
            .setContentIntent(openPi)
            .setOngoing(isPlaying)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .setBigContentTitle("$statusPrefix ${station.name} · ${station.frequencyText}")
                    .bigText("🎙️ 현재 프로그램: $programTitle\n하단 버튼으로 재생을 중지하거나 앱을 완전히 종료할 수 있습니다.")
            )
            .addAction(
                if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (isPlaying) "일시정지" else "다시재생",
                togglePi
            )
            .addAction(
                android.R.drawable.ic_delete,
                "재생중지",
                stopPi
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "앱 종료",
                exitPi
            )
            .build()
    }

    private fun startProgramAutoRefresh() {
        programRefreshJob?.cancel()
        programRefreshJob = serviceScope.launch {
            while (isActive) {
                val station = _currentStationFlow.value
                val latestProgram = StationRepository.resolveCurrentProgramTitle(station)
                if (_currentProgramFlow.value != latestProgram) {
                    _currentProgramFlow.value = latestProgram
                    val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    notificationManager.notify(
                        NOTIFICATION_ID,
                        buildPlaybackNotification(station, latestProgram, _isPlayingFlow.value)
                    )
                    RadioWidgetReceiver.notifyWidgetUpdate(this@RadioPlaybackService)
                }
                delay(60_000L)
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return mediaLibrarySession
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val stations = StationRepository.loadStations(this)

        when (intent?.action) {
            ACTION_PLAY_STATION -> {
                val stationId = intent.getStringExtra(EXTRA_STATION_ID)
                val target = stations.find { it.id == stationId } ?: _currentStationFlow.value
                resolveAndPlayStation(target)
            }
            ACTION_NEXT_STATION -> {
                val idx = stations.indexOfFirst { it.id == _currentStationFlow.value.id }
                val next = stations[(idx + 1).mod(stations.size)]
                resolveAndPlayStation(next)
            }
            ACTION_PREV_STATION -> {
                val idx = stations.indexOfFirst { it.id == _currentStationFlow.value.id }
                val prev = stations[(idx - 1 + stations.size).mod(stations.size)]
                resolveAndPlayStation(prev)
            }
            ACTION_TOGGLE_PLAY -> {
                val p = player
                if (p != null) {
                    if (p.isPlaying) {
                        p.pause()
                    } else {
                        if (p.currentMediaItem == null || p.playbackState == Player.STATE_IDLE) {
                            resolveAndPlayStation(_currentStationFlow.value)
                        } else {
                            p.play()
                        }
                    }
                }
            }
            ACTION_STOP_PLAYBACK -> {
                programRefreshJob?.cancel()
                player?.stop()
                _isPlayingFlow.value = false
                _streamStatusFlow.value = "재생 중지됨"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_DETACH)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(false)
                }
                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.notify(
                    NOTIFICATION_ID,
                    buildPlaybackNotification(_currentStationFlow.value, _currentProgramFlow.value, false)
                )
                RadioWidgetReceiver.notifyWidgetUpdate(this)
            }
            ACTION_EXIT_APP -> {
                programRefreshJob?.cancel()
                player?.stop()
                player?.clearMediaItems()
                _isPlayingFlow.value = false
                _streamStatusFlow.value = "종료됨"
                RadioWidgetReceiver.notifyWidgetUpdate(this)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.cancel(NOTIFICATION_ID)

                // MainActivity 종료 트리거 후 서비스 완전 종료
                _shouldExitAppFlow.value = true
                stopSelf()
            }
            ACTION_ENSURE_NOTIFICATION -> {
                // 홈 버튼을 눌러 백그라운드로 나갈 때 상단 알림 즉시 갱신 및 표시
                val station = _currentStationFlow.value
                val program = _currentProgramFlow.value
                val playing = _isPlayingFlow.value || player?.isPlaying == true
                if (playing) {
                    promoteToForeground(buildPlaybackNotification(station, program, true))
                } else {
                    val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    notificationManager.notify(
                        NOTIFICATION_ID,
                        buildPlaybackNotification(station, program, false)
                    )
                }
            }
        }
        return START_STICKY
    }

    private fun resolveAndPlayStation(station: RadioStation) {
        hasRetriedFallback = false
        _currentStationFlow.value = station
        val initialProgram = StationRepository.getScheduledProgramTitle(station)
        _currentProgramFlow.value = initialProgram
        _streamStatusFlow.value = "${station.broadcaster} 온에어 주소 해석 중..."

        // 재생 시작 즉시 상단 상태표시줄에 알림 등록
        promoteToForeground(buildPlaybackNotification(station, initialProgram, true))
        RadioWidgetReceiver.notifyWidgetUpdate(this)

        serviceScope.launch {
            val resolvedUrl = StationRepository.resolvePlayableStreamUrl(station)
            val liveProgram = StationRepository.resolveCurrentProgramTitle(station)
            _currentProgramFlow.value = liveProgram
            promoteToForeground(buildPlaybackNotification(station, liveProgram, true))
            RadioWidgetReceiver.notifyWidgetUpdate(this@RadioPlaybackService)

            val metadata = MediaMetadata.Builder()
                .setTitle("${station.name} (${station.frequencyText})")
                .setArtist("🎙️ $liveProgram")
                .setStation(station.name)
                .setSubtitle(liveProgram)
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .build()

            val mediaItem = MediaItem.Builder()
                .setMediaId(station.id)
                .setUri(Uri.parse(resolvedUrl))
                .setMediaMetadata(metadata)
                .build()

            player?.apply {
                setMediaItem(mediaItem)
                prepare()
                play()
            }
        }
    }

    private inner class LibrarySessionCallback : MediaLibrarySession.Callback {
        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val rootMetadata = MediaMetadata.Builder()
                .setTitle("수도권 온에어 FM 라디오")
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .build()

            val rootItem = MediaItem.Builder()
                .setMediaId(ROOT_ID)
                .setMediaMetadata(rootMetadata)
                .build()

            return Futures.immediateFuture(LibraryResult.ofItem(rootItem, params))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val stations = StationRepository.loadStations(this@RadioPlaybackService)
            val items = stations.map { station ->
                val programTitle = StationRepository.getScheduledProgramTitle(station)
                val meta = MediaMetadata.Builder()
                    .setTitle("${station.name} (${station.frequencyText})")
                    .setArtist("${station.broadcaster} · $programTitle")
                    .setStation(station.name)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .build()

                MediaItem.Builder()
                    .setMediaId(station.id)
                    .setMediaMetadata(meta)
                    .build()
            }
            return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(items), params))
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> {
            val future = SettableFuture.create<MutableList<MediaItem>>()
            val stations = StationRepository.loadStations(this@RadioPlaybackService)

            serviceScope.launch {
                val resolvedList = mutableListOf<MediaItem>()
                for (reqItem in mediaItems) {
                    val station = stations.find { it.id == reqItem.mediaId } ?: stations.first()
                    _currentStationFlow.value = station
                    val streamUrl = StationRepository.resolvePlayableStreamUrl(station)
                    val liveProgram = StationRepository.resolveCurrentProgramTitle(station)
                    _currentProgramFlow.value = liveProgram

                    val meta = MediaMetadata.Builder()
                        .setTitle("${station.name} (${station.frequencyText})")
                        .setArtist("🎙️ $liveProgram")
                        .setStation(station.name)
                        .setSubtitle(liveProgram)
                        .setIsBrowsable(false)
                        .setIsPlayable(true)
                        .build()

                    resolvedList.add(
                        reqItem.buildUpon()
                            .setUri(streamUrl)
                            .setMediaMetadata(meta)
                            .build()
                    )
                }
                future.set(resolvedList)
            }
            return future
        }
    }

    override fun onDestroy() {
        programRefreshJob?.cancel()
        mediaLibrarySession?.run {
            player.release()
            release()
            mediaLibrarySession = null
        }
        serviceScope.cancel()
        super.onDestroy()
    }
}
