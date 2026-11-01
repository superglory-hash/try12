package com.seoul.fmonair

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

object SongAndLyricsService {

    private const val PREFS_HISTORY = "seoul_fm_song_history"
    private const val KEY_HISTORY_JSON = "saved_songs_json"
    private const val KEY_AUDD_TOKEN = "audd_api_token"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    fun getAuddApiToken(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_HISTORY, Context.MODE_PRIVATE)
        return prefs.getString(KEY_AUDD_TOKEN, "test") ?: "test"
    }

    fun saveAuddApiToken(context: Context, token: String) {
        val prefs = context.getSharedPreferences(PREFS_HISTORY, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_AUDD_TOKEN, token.trim().ifEmpty { "test" }).apply()
    }

    suspend fun searchLyricsFromLrcLib(
        query: String,
        stationName: String = "실시간 검색",
        frequencyText: String = "LRCLIB"
    ): List<RecognizedSong> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val results = mutableListOf<RecognizedSong>()
        try {
            val encoded = java.net.URLEncoder.encode(query.trim(), "UTF-8")
            val req = Request.Builder()
                .url("https://lrclib.net/api/search?q=$encoded")
                .header("User-Agent", "SeoulFMRadioApp/2.1 (https://github.com/seoul-fm-radio)")
                .build()

            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (body.startsWith("[")) {
                    val arr = JSONArray(body)
                    val limit = minOf(arr.length(), 12)
                    for (i in 0 until limit) {
                        val obj = arr.getJSONObject(i)
                        val title = obj.optString("trackName", "제목 미상")
                        val artist = obj.optString("artistName", "아티스트 미상")
                        val album = obj.optString("albumName", "")
                        val plain = obj.optString("plainLyrics", "")
                        val syncedRaw = obj.optString("syncedLyrics", "")
                        val parsedSynced = parseLrcText(syncedRaw, plain)

                        val fallbackPlain = if (plain.isNotBlank()) {
                            plain
                        } else {
                            parsedSynced.joinToString(separator = "\n") { it.text }
                        }

                        results.add(
                            RecognizedSong(
                                id = UUID.randomUUID().toString(),
                                title = title,
                                artist = artist,
                                album = album,
                                stationName = stationName,
                                frequencyText = frequencyText,
                                recognizedAt = nowTimeString(),
                                plainLyrics = fallbackPlain,
                                syncedLyrics = parsedSynced,
                                sourceMethod = "LRCLIB 실시간 가사 API"
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext results
    }

    /**
     * 정밀 하이브리드 곡 인식 엔진:
     * 1순위: 실시간 HLS/ICY 스트림 메타데이터 (가수 - 제목)
     * 2순위: 방송사별 실시간 선곡표/온에어 API 직접 호출 (KBS, MBC, SBS 등)
     * 3순위: 실제 오디오 5초 녹음(WAV 인코딩) 후 AudD 음원 인식 API 분석
     */
    suspend fun recognizeCurrentRadioSong(
        context: Context,
        station: RadioStation,
        icyMetadataTitle: String? = null
    ): RecognizedSong = withContext(Dispatchers.IO) {
        var detectedTitle = ""
        var detectedArtist = ""
        var methodLabel = ""

        // [1단계] 스트림 내부 실시간 ICY/HLS 태그 확인
        if (!icyMetadataTitle.isNullOrBlank()) {
            val cleaned = icyMetadataTitle.trim()
            if (cleaned.contains(" - ")) {
                val parts = cleaned.split(" - ", limit = 2)
                detectedArtist = parts[0].trim()
                detectedTitle = parts[1].trim()
                methodLabel = "실시간 스트림 메타데이터"
            } else if (cleaned.contains("-")) {
                val parts = cleaned.split("-", limit = 2)
                detectedArtist = parts[0].trim()
                detectedTitle = parts[1].trim()
                methodLabel = "실시간 스트림 메타데이터"
            }
        }

        // [2단계] 실제 오디오 5초 녹음(WAV) 및 방송사 실시간 온에어/선곡 API 병행 조회
        if (detectedTitle.isBlank()) {
            val stationMeta = fetchBroadcasterRealtimeSong(station)
            if (stationMeta != null) {
                detectedTitle = stationMeta.first
                detectedArtist = stationMeta.second
                methodLabel = "${station.broadcaster} 실시간 선곡 정보"
            }
        }

        // [3단계] 실제 마이크/오디오 출력 5초 샘플링 WAV 캡처 후 AudD API 전송
        if (detectedTitle.isBlank()) {
            val wavBytes = record5SecondsWavAudio()
            if (wavBytes != null && wavBytes.size > 1000) {
                val apiToken = getAuddApiToken(context)
                try {
                    val multipart = MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart("api_token", apiToken)
                        .addFormDataPart("return", "lyrics,apple_music,spotify")
                        .addFormDataPart(
                            "file",
                            "radio_live_sample.wav",
                            wavBytes.toRequestBody("audio/wav".toMediaTypeOrNull())
                        )
                        .build()

                    val request = Request.Builder()
                        .url("https://api.audd.io/")
                        .post(multipart)
                        .build()

                    client.newCall(request).execute().use { response ->
                        val jsonStr = response.body?.string().orEmpty()
                        if (jsonStr.startsWith("{")) {
                            val root = JSONObject(jsonStr)
                            val resultObj = root.optJSONObject("result")
                            if (resultObj != null) {
                                detectedTitle = resultObj.optString("title", "")
                                detectedArtist = resultObj.optString("artist", "")
                                if (detectedTitle.isNotBlank()) {
                                    methodLabel = "5초 실제 오디오 지문 인식 (AudD)"
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            } else {
                // 녹음 권한이 없거나 캡처 실패 시 최소 대기
                delay(2000)
            }
        }

        // [4단계] 찾은 노래 제목+가수로 LRCLIB에서 실시간 싱크 가사 조회
        if (detectedTitle.isNotBlank()) {
            val query = "$detectedTitle $detectedArtist".trim()
            val lrcMatches = searchLyricsFromLrcLib(
                query = query,
                stationName = station.name,
                frequencyText = station.frequencyText
            )
            if (lrcMatches.isNotEmpty()) {
                val best = lrcMatches.first()
                return@withContext best.copy(
                    stationName = station.name,
                    frequencyText = station.frequencyText,
                    recognizedAt = nowTimeString(),
                    sourceMethod = methodLabel
                )
            }

            val guideLines = listOf(
                LyricLine(0f, "🎵 인식된 곡: $detectedArtist - $detectedTitle"),
                LyricLine(3.5f, "채널: ${station.name} (${station.frequencyText})"),
                LyricLine(7.0f, "방식: $methodLabel"),
                LyricLine(10.5f, "LRCLIB에 등록된 싱크 가사가 없어 곡 정보만 표시합니다."),
                LyricLine(14.0f, "상단 검색창에서 제목을 수정해 다시 가사를 검색해 보세요.")
            )
            return@withContext RecognizedSong(
                id = UUID.randomUUID().toString(),
                title = detectedTitle,
                artist = detectedArtist,
                album = "${station.name} ON-AIR",
                stationName = station.name,
                frequencyText = station.frequencyText,
                recognizedAt = nowTimeString(),
                plainLyrics = guideLines.joinToString(separator = "\n") { it.text },
                syncedLyrics = guideLines,
                sourceMethod = methodLabel
            )
        }

        // 모든 단계에서 노래가 감지되지 않은 경우 (DJ 멘트 중이거나 광고 시간일 때)
        // 가짜 랜덤 노래를 보여주지 않고 정직하게 안내합니다.
        val noticeLines = listOf(
            LyricLine(0f, "🎙️ 현재 DJ 멘트/광고 중이거나 음원이 감지되지 않았습니다."),
            LyricLine(3.5f, "1) 노래가 선명하게 나올 때 스피커 볼륨을 높여 [BGM 인식]을 눌러주세요."),
            LyricLine(7.0f, "2) 또는 바로 위 [${station.broadcaster} 선곡표] 버튼을 눌러 방송국 공식 선곡표를 확인하세요."),
            LyricLine(10.5f, "3) 상단 검색창에 들리는 가사 한 줄이나 제목을 입력하면 즉시 가사가 검색됩니다.")
        )
        return@withContext RecognizedSong(
            id = UUID.randomUUID().toString(),
            title = "현재 곡 미감지 (멘트/광고 또는 볼륨 부족)",
            artist = station.name,
            album = station.defaultProgram,
            stationName = station.name,
            frequencyText = station.frequencyText,
            recognizedAt = nowTimeString(),
            plainLyrics = noticeLines.joinToString(separator = "\n") { it.text },
            syncedLyrics = noticeLines,
            sourceMethod = "음원 신호 대기 중"
        )
    }

    /**
     * 방송사 온에어 API에서 현재 프로그램 및 곡 메타데이터 추출
     */
    private fun fetchBroadcasterRealtimeSong(station: RadioStation): Pair<String, String>? {
        try {
            when (station.broadcaster) {
                "KBS" -> {
                    val req = Request.Builder()
                        .url(station.endpointOrStreamUrl)
                        .header("User-Agent", "Mozilla/5.0")
                        .build()
                    client.newCall(req).execute().use { resp ->
                        val body = resp.body?.string().orEmpty()
                        if (body.startsWith("{")) {
                            val json = JSONObject(body)
                            val items = json.optJSONArray("channel_item")
                            if (items != null && items.length() > 0) {
                                for (i in 0 until items.length()) {
                                    val obj = items.getJSONObject(i)
                                    val song = obj.optString("song_title", "").trim()
                                    val artist = obj.optString("artist_name", "").trim()
                                    if (song.isNotEmpty()) {
                                        return song to artist.ifEmpty { "KBS On-Air" }
                                    }
                                }
                            }
                        }
                    }
                }
                "MBC" -> {
                    val req = Request.Builder()
                        .url("https://miniapi.imbc.com/BroadCast/OnAirList")
                        .header("User-Agent", "Mozilla/5.0")
                        .build()
                    client.newCall(req).execute().use { resp ->
                        val body = resp.body?.string().orEmpty()
                        if (body.contains("SomItem") || body.contains("SongTitle")) {
                            val json = JSONObject(body)
                            val song = json.optString("SongTitle", "").trim()
                            val singer = json.optString("Singer", "").trim()
                            if (song.isNotEmpty()) return song to singer
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
        return null
    }

    /**
     * 안드로이드 AudioRecord를 이용해 실제 4.5초간 8000Hz 16-bit Mono PCM 녹음 후 WAV 헤더 결합
     */
    @SuppressLint("MissingPermission")
    private fun record5SecondsWavAudio(): ByteArray? {
        val sampleRate = 8000
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        if (minBuf <= 0) return null

        var recorder: AudioRecord? = null
        return try {
            recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                minBuf * 2
            )
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                return null
            }

            recorder.startRecording()
            val pcmOut = ByteArrayOutputStream()
            val buffer = ByteArray(minBuf)
            val endTime = System.currentTimeMillis() + 4500L

            while (System.currentTimeMillis() < endTime) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read > 0) {
                    pcmOut.write(buffer, 0, read)
                }
            }
            recorder.stop()
            recorder.release()
            recorder = null

            val pcmBytes = pcmOut.toByteArray()
            if (pcmBytes.isEmpty()) null else encodePcmToWav(pcmBytes, sampleRate, 1, 16)
        } catch (e: Exception) {
            try {
                recorder?.release()
            } catch (_: Exception) {
            }
            null
        }
    }

    private fun encodePcmToWav(pcmData: ByteArray, sampleRate: Int, channels: Int, bitsPerSample: Int): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val totalDataLen = pcmData.size + 36
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)

        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(totalDataLen)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1.toShort()) // PCM = 1
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort((channels * bitsPerSample / 8).toShort())
        header.putShort(bitsPerSample.toShort())
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(pcmData.size)

        val out = ByteArrayOutputStream(44 + pcmData.size)
        out.write(header.array())
        out.write(pcmData)
        return out.toByteArray()
    }

    fun parseLrcText(syncedLrc: String, plainLyrics: String): List<LyricLine> {
        val lines = mutableListOf<LyricLine>()

        if (syncedLrc.isNotBlank()) {
            syncedLrc.lineSequence().forEach { rawLine ->
                val line = rawLine.trim()
                if (line.startsWith("[") && line.contains("]")) {
                    val closeIdx = line.indexOf(']')
                    val timeTag = line.substring(1, closeIdx)
                    val lyricText = line.substring(closeIdx + 1).trim()
                    val colonIdx = timeTag.indexOf(':')
                    if (colonIdx > 0 && lyricText.isNotEmpty()) {
                        val minVal = timeTag.substring(0, colonIdx).toFloatOrNull() ?: 0f
                        val secVal = timeTag.substring(colonIdx + 1).toFloatOrNull() ?: 0f
                        lines.add(LyricLine(minVal * 60f + secVal, lyricText))
                    }
                }
            }
        }

        if (lines.isEmpty() && plainLyrics.isNotBlank()) {
            plainLyrics.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEachIndexed { idx, text ->
                    lines.add(LyricLine(idx * 3.5f, text))
                }
        }

        return lines
    }

    fun loadSavedSongs(context: Context): List<RecognizedSong> {
        val prefs = context.getSharedPreferences(PREFS_HISTORY, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_HISTORY_JSON, null) ?: return emptyList()
        val list = mutableListOf<RecognizedSong>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val plain = o.optString("plainLyrics", "")
                list.add(
                    RecognizedSong(
                        id = o.optString("id"),
                        title = o.optString("title"),
                        artist = o.optString("artist"),
                        album = o.optString("album"),
                        stationName = o.optString("stationName"),
                        frequencyText = o.optString("frequencyText"),
                        recognizedAt = o.optString("recognizedAt"),
                        plainLyrics = plain,
                        syncedLyrics = parseLrcText("", plain),
                        sourceMethod = o.optString("sourceMethod", "선곡표/LRCLIB")
                    )
                )
            }
        } catch (_: Exception) {
        }
        return list
    }

    fun saveSongToHistory(context: Context, song: RecognizedSong): List<RecognizedSong> {
        val current = loadSavedSongs(context).toMutableList()
        current.removeAll { it.title.equals(song.title, ignoreCase = true) && it.artist.equals(song.artist, ignoreCase = true) }
        current.add(0, song)
        persistSongs(context, current.take(50))
        return current.take(50)
    }

    fun deleteSongFromHistory(context: Context, songId: String): List<RecognizedSong> {
        val current = loadSavedSongs(context).filterNot { it.id == songId }
        persistSongs(context, current)
        return current
    }

    fun clearAllHistory(context: Context): List<RecognizedSong> {
        persistSongs(context, emptyList())
        return emptyList()
    }

    private fun persistSongs(context: Context, songs: List<RecognizedSong>) {
        val arr = JSONArray()
        songs.forEach { s ->
            val obj = JSONObject().apply {
                put("id", s.id)
                put("title", s.title)
                put("artist", s.artist)
                put("album", s.album)
                put("stationName", s.stationName)
                put("frequencyText", s.frequencyText)
                put("recognizedAt", s.recognizedAt)
                put("plainLyrics", s.plainLyrics)
                put("sourceMethod", s.sourceMethod)
            }
            arr.put(obj)
        }
        context.getSharedPreferences(PREFS_HISTORY, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_HISTORY_JSON, arr.toString())
            .apply()
    }

    private fun nowTimeString(): String {
        val sdf = SimpleDateFormat("MM.dd HH:mm", Locale.KOREA)
        return sdf.format(Date())
    }
}