package com.seoul.fmonair

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.TimeUnit

object StationRepository {

    private const val PREFS_NAME = "seoul_fm_radio_prefs"
    private const val KEY_FAVORITES = "favorite_station_ids"
    private const val KEY_CUSTOM_URL_PREFIX = "custom_url_"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    val initialStations: List<RadioStation> = listOf(
        RadioStation(
            id = "kbs_1radio",
            name = "KBS 1라디오",
            broadcaster = "KBS",
            frequencyMhz = 97.3f,
            frequencyText = "97.3 MHz",
            category = StationCategory.KBS,
            badgeColorHex = 0xFF2563EB,
            resolverType = StreamResolverType.KBS_API,
            endpointOrStreamUrl = "https://cfpwwwapi.kbs.co.kr/api/v1/landing/live/channel_code/21",
            channelCode = "21",
            defaultProgram = "KBS 뉴스 & 시사 포커스",
            scheduleHours = "24시간 시사/뉴스 LIVE",
            playlistWebUrl = "https://program.kbs.co.kr/1radio/radio/main.html"
        ),
        RadioStation(
            id = "kbs_happyfm",
            name = "KBS 해피FM",
            broadcaster = "KBS",
            frequencyMhz = 106.1f,
            frequencyText = "106.1 MHz",
            category = StationCategory.KBS,
            badgeColorHex = 0xFF3B82F6,
            resolverType = StreamResolverType.KBS_API,
            endpointOrStreamUrl = "https://cfpwwwapi.kbs.co.kr/api/v1/landing/live/channel_code/22",
            channelCode = "22",
            defaultProgram = "행복한 대중음악 채널 해피FM",
            scheduleHours = "05:00 ~ 03:00 대중가요",
            playlistWebUrl = "https://program.kbs.co.kr/2radio/radio/main.html"
        ),
        RadioStation(
            id = "kbs_coolfm",
            name = "KBS 쿨FM",
            broadcaster = "KBS",
            frequencyMhz = 89.1f,
            frequencyText = "89.1 MHz",
            category = StationCategory.KBS,
            badgeColorHex = 0xFF06B6D4,
            resolverType = StreamResolverType.KBS_API,
            endpointOrStreamUrl = "https://cfpwwwapi.kbs.co.kr/api/v1/landing/live/channel_code/25",
            channelCode = "25",
            defaultProgram = "가요광장 / 볼륨을 높여요",
            scheduleHours = "24시간 K-POP & 팝",
            playlistWebUrl = "https://program.kbs.co.kr/2fm/radio/main.html"
        ),
        RadioStation(
            id = "kbs_classicfm",
            name = "KBS 클래식FM",
            broadcaster = "KBS",
            frequencyMhz = 93.1f,
            frequencyText = "93.1 MHz",
            category = StationCategory.KBS,
            badgeColorHex = 0xFF6366F1,
            resolverType = StreamResolverType.KBS_API,
            endpointOrStreamUrl = "https://cfpwwwapi.kbs.co.kr/api/v1/landing/live/channel_code/24",
            channelCode = "24",
            defaultProgram = "출발 FM과 함께 / 세상의 모든 음악",
            scheduleHours = "24시간 정통 클래식",
            playlistWebUrl = "https://program.kbs.co.kr/1fm/radio/main.html"
        ),
        RadioStation(
            id = "mbc_sfm",
            name = "MBC 표준FM",
            broadcaster = "MBC",
            frequencyMhz = 95.9f,
            frequencyText = "95.9 MHz",
            category = StationCategory.MBC,
            badgeColorHex = 0xFF8B5CF6,
            resolverType = StreamResolverType.MBC_API,
            endpointOrStreamUrl = "https://sminiplay.imbc.com/aacplay.ashx?agent=webapp&channel=sfm",
            channelCode = "sfm",
            defaultProgram = "시선집중 / 여성시대 / 별이 빛나는 밤에",
            scheduleHours = "24시간 종합 시사·오락",
            playlistWebUrl = "https://www.imbc.com/broad/radio/"
        ),
        RadioStation(
            id = "mbc_fm4u",
            name = "MBC FM4U",
            broadcaster = "MBC",
            frequencyMhz = 91.9f,
            frequencyText = "91.9 MHz",
            category = StationCategory.MBC,
            badgeColorHex = 0xFFA855F7,
            resolverType = StreamResolverType.MBC_API,
            endpointOrStreamUrl = "https://sminiplay.imbc.com/aacplay.ashx?agent=webapp&channel=mfm",
            channelCode = "mfm",
            defaultProgram = "굿모닝FM / 정오의 희망곡 / 배철수의 음악캠프",
            scheduleHours = "24시간 논스톱 뮤직",
            playlistWebUrl = "https://www.imbc.com/broad/radio/"
        ),
        RadioStation(
            id = "sbs_powerfm",
            name = "SBS 파워FM",
            broadcaster = "SBS",
            frequencyMhz = 107.7f,
            frequencyText = "107.7 MHz",
            category = StationCategory.SBS,
            badgeColorHex = 0xFFF97316,
            resolverType = StreamResolverType.SBS_API,
            endpointOrStreamUrl = "https://apis.sbs.co.kr/play-api/1.0/livestream/powerpc/powerfm?protocol=hls&ssl=Y",
            channelCode = "powerfm",
            defaultProgram = "김영철의 파워FM / 두시탈출 컬투쇼",
            scheduleHours = "24시간 뮤직 & 엔터",
            playlistWebUrl = "https://www.sbs.co.kr/radio"
        ),
        RadioStation(
            id = "sbs_lovefm",
            name = "SBS 러브FM",
            broadcaster = "SBS",
            frequencyMhz = 103.5f,
            frequencyText = "103.5 MHz",
            category = StationCategory.SBS,
            badgeColorHex = 0xFFEA580C,
            resolverType = StreamResolverType.SBS_API,
            endpointOrStreamUrl = "https://apis.sbs.co.kr/play-api/1.0/livestream/lovepc/lovefm?protocol=hls&ssl=Y",
            channelCode = "lovefm",
            defaultProgram = "이숙영의 러브FM / 뜨거우면 지상렬",
            scheduleHours = "24시간 시사 & 7080 가요",
            playlistWebUrl = "https://www.sbs.co.kr/radio"
        ),
        RadioStation(
            id = "ebs_fm",
            name = "EBS FM",
            broadcaster = "EBS",
            frequencyMhz = 104.5f,
            frequencyText = "104.5 MHz",
            category = StationCategory.NEWS_EDU,
            badgeColorHex = 0xFF10B981,
            resolverType = StreamResolverType.STATIC_HLS,
            endpointOrStreamUrl = "https://ebsonair.ebs.co.kr/fmradiofamilypc/familypc1m/playlist.m3u8",
            defaultProgram = "모닝 스페셜 / 세상의 모든 지혜",
            scheduleHours = "05:00 ~ 02:00 어학·교양",
            playlistWebUrl = "https://www.ebs.co.kr/radio/home"
        ),
        RadioStation(
            id = "tbs_fm",
            name = "TBS FM",
            broadcaster = "TBS",
            frequencyMhz = 95.1f,
            frequencyText = "95.1 MHz",
            category = StationCategory.NEWS_EDU,
            badgeColorHex = 0xFF0EA5E9,
            resolverType = StreamResolverType.STATIC_HLS,
            endpointOrStreamUrl = "https://cdnfm.tbs.seoul.kr/tbs/_definst_/tbs_fm_web_360.smil/playlist.m3u8",
            defaultProgram = "수도권 교통정보 & 시민 라디오",
            scheduleHours = "24시간 수도권 교통·음악",
            playlistWebUrl = "https://tbs.seoul.kr"
        ),
        RadioStation(
            id = "tbs_efm",
            name = "TBS eFM",
            broadcaster = "TBS",
            frequencyMhz = 101.3f,
            frequencyText = "101.3 MHz",
            category = StationCategory.NEWS_EDU,
            badgeColorHex = 0xFF0284C7,
            resolverType = StreamResolverType.STATIC_HLS,
            endpointOrStreamUrl = "https://cdnefm.tbs.seoul.kr/tbs/_definst_/tbs_efm_web_360.smil/playlist.m3u8",
            defaultProgram = "Seoul Foreign Language Radio",
            scheduleHours = "24시간 영어·외국어 방송",
            playlistWebUrl = "https://tbs.seoul.kr/efm"
        ),
        RadioStation(
            id = "ytn_radio",
            name = "YTN 라디오",
            broadcaster = "YTN",
            frequencyMhz = 94.5f,
            frequencyText = "94.5 MHz",
            category = StationCategory.NEWS_EDU,
            badgeColorHex = 0xFFEF4444,
            resolverType = StreamResolverType.STATIC_HLS,
            endpointOrStreamUrl = "https://radiolive.ytn.co.kr/radio/_definst_/20211118_fmlive/playlist.m3u8",
            defaultProgram = "YTN 24시간 뉴스 FM",
            scheduleHours = "24시간 실시간 속보·시사",
            playlistWebUrl = "https://radio.ytn.co.kr"
        ),
        RadioStation(
            id = "gugak_fm",
            name = "국악방송 FM",
            broadcaster = "국악",
            frequencyMhz = 99.1f,
            frequencyText = "99.1 MHz",
            category = StationCategory.MUSIC_RELIGION,
            badgeColorHex = 0xFFD97706,
            resolverType = StreamResolverType.STATIC_HLS,
            endpointOrStreamUrl = "https://mgugaklive.nowcdn.co.kr/gugakradio/gugakradio.stream/playlist.m3u8",
            defaultProgram = "창호에 드리는 햇살 / 우리 소리",
            scheduleHours = "24시간 전통·퓨전 국악",
            playlistWebUrl = "https://www.igbf.kr"
        ),
        RadioStation(
            id = "cbs_sfm",
            name = "CBS 표준FM",
            broadcaster = "CBS",
            frequencyMhz = 98.1f,
            frequencyText = "98.1 MHz",
            category = StationCategory.MUSIC_RELIGION,
            badgeColorHex = 0xFF14B8A6,
            resolverType = StreamResolverType.STATIC_HLS,
            endpointOrStreamUrl = "https://maac.cbs.co.kr/cbs981/_definst_/cbs981.stream/playlist.m3u8",
            defaultProgram = "김현정의 뉴스쇼 / 한판승부",
            scheduleHours = "24시간 시사 & 교양",
            playlistWebUrl = "https://www.cbs.co.kr/radio"
        ),
        RadioStation(
            id = "cbs_musicfm",
            name = "CBS 음악FM",
            broadcaster = "CBS",
            frequencyMhz = 93.9f,
            frequencyText = "93.9 MHz",
            category = StationCategory.MUSIC_RELIGION,
            badgeColorHex = 0xFF0D9488,
            resolverType = StreamResolverType.STATIC_HLS,
            endpointOrStreamUrl = "https://maac.cbs.co.kr/cbs939/_definst_/cbs939.stream/playlist.m3u8",
            defaultProgram = "아름다운 당신에게 / 배미향의 저녁스케치",
            scheduleHours = "24시간 아름다운 팝 & 가요",
            playlistWebUrl = "https://www.cbs.co.kr/radio"
        ),
        RadioStation(
            id = "febc_fm",
            name = "FEBC 극동방송",
            broadcaster = "극동",
            frequencyMhz = 106.9f,
            frequencyText = "106.9 MHz",
            category = StationCategory.MUSIC_RELIGION,
            badgeColorHex = 0xFFEC4899,
            resolverType = StreamResolverType.STATIC_HLS,
            endpointOrStreamUrl = "https://mlive1.febc.net/live/seoulfm/playlist.m3u8",
            defaultProgram = "사랑의 뜰안 / 찬양과 말씀",
            scheduleHours = "24시간 복음·찬양 방송",
            playlistWebUrl = "https://seoul.febc.net"
        ),
        RadioStation(
            id = "bbs_fm",
            name = "BBS 불교방송",
            broadcaster = "불교",
            frequencyMhz = 101.9f,
            frequencyText = "101.9 MHz",
            category = StationCategory.MUSIC_RELIGION,
            badgeColorHex = 0xFFF59E0B,
            resolverType = StreamResolverType.STATIC_HLS,
            endpointOrStreamUrl = "https://bbslive.nowcdn.co.kr/bbsradio/bbsradio.stream/playlist.m3u8",
            defaultProgram = "아침저널 / 무명을 밝히고",
            scheduleHours = "04:00 ~ 02:00 명상·힐링·음악",
            playlistWebUrl = "https://www.bbsi.co.kr"
        ),
        RadioStation(
            id = "cpbc_fm",
            name = "CPBC 가톨릭평화방송",
            broadcaster = "평화",
            frequencyMhz = 105.3f,
            frequencyText = "105.3 MHz",
            category = StationCategory.MUSIC_RELIGION,
            badgeColorHex = 0xFF64748B,
            resolverType = StreamResolverType.STATIC_HLS,
            endpointOrStreamUrl = "https://onair.cpbc.co.kr/live/radio/playlist.m3u8",
            defaultProgram = "열린세상 오늘 / 음악이 있는 저녁풍경",
            scheduleHours = "05:00 ~ 02:00 평화·생활음악",
            playlistWebUrl = "https://www.cpbc.co.kr"
        )
    )

    fun loadStations(context: Context): List<RadioStation> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return initialStations.map { station ->
            val customUrl = prefs.getString(KEY_CUSTOM_URL_PREFIX + station.id, null)
            station.copy(customStreamUrl = customUrl)
        }
    }

    fun saveCustomStreamUrl(context: Context, stationId: String, customUrl: String?) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (customUrl.isNullOrBlank()) {
            prefs.edit().remove(KEY_CUSTOM_URL_PREFIX + stationId).apply()
        } else {
            prefs.edit().putString(KEY_CUSTOM_URL_PREFIX + stationId, customUrl.trim()).apply()
        }
    }

    fun getFavoriteIds(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_FAVORITES, setOf("kbs_coolfm", "mbc_fm4u", "sbs_powerfm")) ?: emptySet()
    }

    fun toggleFavorite(context: Context, stationId: String): Set<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = getFavoriteIds(context).toMutableSet()
        if (current.contains(stationId)) {
            current.remove(stationId)
        } else {
            current.add(stationId)
        }
        prefs.edit().putStringSet(KEY_FAVORITES, current).apply()
        return current
    }

    /**
     * 한국 표준시(KST) 기준 현재 시간대별 라디오 편성 프로그램 이름 반환
     */
    fun getScheduledProgramTitle(station: RadioStation): String {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Seoul"))
        val hour = cal.get(Calendar.HOUR_OF_DAY)

        return when (station.id) {
            "kbs_1radio" -> when (hour) {
                in 5..6 -> "오늘 아침 1라디오"
                in 7..8 -> "전격시사"
                in 9..10 -> "성공예감 이대호입니다"
                11 -> "라디오 전국일주"
                in 12..13 -> "세상의 모든 정보"
                in 14..15 -> "뉴스 중계탑"
                16 -> "경제쇼"
                in 17..19 -> "주진우 라이브 / 뉴스레터K"
                in 20..21 -> "KBS 열린토론"
                in 22..23 -> "오늘 밤 1라디오"
                else -> "KBS 1라디오 심야 시사·교양"
            }
            "kbs_happyfm" -> when (hour) {
                in 5..6 -> "새아침의 클래식 / 활기찬 새아침"
                in 7..8 -> "조정식의 FM대행진 (해피 동시)"
                in 9..10 -> "주현미의 러브레터"
                in 11..12 -> "박명수의 라디오쇼"
                in 12..13 -> "임백천의 백 뮤직"
                in 14..15 -> "은가은의 빛나는 트로트"
                in 16..17 -> "이각경의 해피타임 4시"
                in 18..19 -> "스윗드림"
                in 20..21 -> "이상호의 드림팝"
                in 22..23 -> "밤을 잊은 그대에게"
                else -> "해피FM 심야 음악여행"
            }
            "kbs_coolfm" -> when (hour) {
                in 5..6 -> "상쾌한 아침"
                in 7..8 -> "조정식의 FM대행진"
                in 9..10 -> "이현우의 음악앨범"
                11 -> "박명수의 라디오쇼"
                in 12..13 -> "이은지의 가요광장"
                in 14..15 -> "하하의 슈퍼라디오"
                in 16..17 -> "윤정수 남창희의 미스터 라디오"
                in 18..19 -> "사랑하기 좋은 날 이금희입니다"
                in 20..21 -> "오마이걸 효정의 볼륨을 높여요"
                in 22..23 -> "몬스타엑스 아이엠의 키스 더 라디오"
                else -> "스테이션 제트 / 심야 뮤직"
            }
            "kbs_classicfm" -> when (hour) {
                6 -> "국악의 향기"
                in 7..8 -> "출발 FM과 함께"
                in 9..10 -> "신윤주의 가정음악"
                11 -> "KBS 음악실"
                in 12..13 -> "생생클래식"
                in 14..15 -> "명연주 명음반"
                in 16..17 -> "노래의 날개 위에"
                in 18..19 -> "세상의 모든 음악"
                in 20..21 -> "FM 실황음악"
                in 22..23 -> "당신의 밤과 음악"
                else -> "클래식FM 심야 실내악"
            }
            "mbc_sfm" -> when (hour) {
                6 -> "아침 & 뉴스"
                in 7..8 -> "김종배의 시선집중"
                in 9..10 -> "여성시대 양희은, 김일중입니다"
                11 -> "라디오 문화살롱"
                in 12..13 -> "손태진의 트로트 라디오"
                in 14..15 -> "박준형, 박영진의 2시만세"
                in 16..17 -> "정선희, 문천식의 지금은 라디오 시대"
                in 18..19 -> "권순표의 뉴스하이킥"
                in 20..21 -> "언제나 좋은날"
                in 22..23 -> "김이나의 별이 빛나는 밤에"
                else -> "낭만가요 / 심야 표준FM"
            }
            "mbc_fm4u" -> when (hour) {
                in 5..6 -> "세상을 여는 아침"
                in 7..8 -> "굿모닝FM 테이입니다"
                in 9..10 -> "오늘 아침 윤상입니다"
                11 -> "브런치카페"
                in 12..13 -> "정오의 희망곡 김신영입니다"
                in 14..15 -> "두시의 데이트 안영미입니다"
                in 16..17 -> "완벽한 하루 이상순입니다"
                in 18..19 -> "배철수의 음악캠프"
                in 20..21 -> "김윤아의 친한친구"
                in 22..23 -> "푸른밤 옥상달빛입니다"
                else -> "FM영화음악 / 심야 논스톱 뮤직"
            }
            "sbs_powerfm" -> when (hour) {
                in 5..6 -> "이인권의 펀펀투데이"
                in 7..8 -> "김영철의 파워FM"
                in 9..10 -> "아름다운 이 아침 봉태규입니다"
                11 -> "박하선의 씨네타운"
                in 12..13 -> "12시엔 주현영"
                in 14..15 -> "두시탈출 컬투쇼"
                in 16..17 -> "황제성의 황제파워"
                in 18..19 -> "박소현의 러브게임"
                in 20..21 -> "웬디의 영스트리트"
                in 22..23 -> "배성재의 텐"
                else -> "딘딘의 뮤직하이 / 애프터클럽"
            }
            "sbs_lovefm" -> when (hour) {
                in 7..8 -> "김태현의 정치쇼"
                in 9..10 -> "이숙영의 러브FM"
                11 -> "박연미의 목돈연구소"
                in 12..13 -> "유민상의 배고픈 라디오"
                in 14..15 -> "그대의 오후"
                in 16..17 -> "뜨거우면 지상렬"
                in 18..19 -> "편상욱의 뉴스브리핑 / 시사특공대"
                in 20..21 -> "유영재의 라디오쇼 / 러브나잇"
                in 22..23 -> "최백호의 낭만시대"
                else -> "러브FM 심야 음악산책"
            }
            "ebs_fm" -> when (hour) {
                in 5..6 -> "EBS 고교 영어듣기 / 어학 강좌"
                in 7..9 -> "모닝 스페셜"
                in 10..11 -> "이지연의 초급 영어 / 오디오 천국"
                in 12..13 -> "영어! 할 수 있다 Can Can Can"
                in 14..16 -> "오디오북 / 행복한 교육세상"
                in 17..19 -> "윤고은의 EBS 북카페"
                in 20..21 -> "펜타곤의 밤의 라디오"
                else -> "EBS 심야 어학 & 낭독"
            }
            "tbs_fm" -> when (hour) {
                in 7..8 -> "TBS 아침 종합 정보"
                in 9..11 -> "함춘호의 포크송 / 시민의 소리"
                in 12..15 -> "배칠수의 음악쇼 / 수도권 교통정보"
                in 16..19 -> "TBS 저녁 교통특보 & 뮤직"
                else -> "TBS FM 실시간 교통 & 음악"
            }
            "tbs_efm" -> when (hour) {
                in 7..8 -> "This Morning (English News)"
                in 9..11 -> "K-Ride & Seoul Life"
                in 12..15 -> "K-Pop Fiesta"
                in 16..19 -> "Primetime Seoul"
                else -> "TBS eFM Global Music Live"
            }
            "ytn_radio" -> when (hour) {
                in 7..8 -> "김영수의 뉴스파이팅"
                in 9..11 -> "생생경제 / 뉴스 정면승부"
                in 12..17 -> "YTN 라디오 정오 종합뉴스 & 이슈"
                in 18..20 -> "신율의 뉴스 정면승부"
                else -> "YTN 24시간 실시간 뉴스 속보"
            }
            "gugak_fm" -> when (hour) {
                in 7..8 -> "창호에 드리는 햇살"
                in 9..10 -> "솔바람 물소리"
                in 11..13 -> "찬란한 시절 / 바투의 상사디야"
                in 14..17 -> "글로벌 국악 / 화음"
                in 18..21 -> "꿈꾸는 아리랑 / 저녁 국악산책"
                else -> "심야 우리 소리 한마당"
            }
            "cbs_sfm" -> when (hour) {
                in 7..8 -> "김현정의 뉴스쇼"
                in 9..10 -> "그대 창가에 알렉스입니다"
                in 11..13 -> "김용신의 그대와 여는 아침 / 유지수의 해피송"
                in 14..15 -> "이봉규의 어떤가요"
                in 16..17 -> "박지환의 뉴스톡"
                in 18..19 -> "박재홍의 한판승부"
                in 20..21 -> "라디오 교회 / 새롭게 하소서"
                else -> "CBS 표준FM 찬양과 말씀"
            }
            "cbs_musicfm" -> when (hour) {
                in 6..8 -> "김용신의 그대와 여는 아침"
                in 9..10 -> "강석우의 아름다운 당신에게"
                11 -> "신지혜의 영화음악"
                in 12..13 -> "김윤주의 랄랄라"
                in 14..15 -> "한동준의 FM POPS"
                in 16..17 -> "박승화의 가요속으로"
                in 18..19 -> "배미향의 저녁스케치"
                in 20..21 -> "김현주의 행복한 동행"
                in 22..23 -> "꿈과 음악 사이에"
                else -> "허윤희의 시작하는 밤"
            }
            "febc_fm" -> when (hour) {
                in 7..8 -> "좋은 아침입니다"
                in 9..11 -> "사랑의 뜰안"
                in 12..15 -> "한낮의 찬양 / 복음의 메아리"
                in 16..19 -> "저녁의 찬양과 말씀"
                else -> "FEBC 극동방송 심야 찬양"
            }
            "bbs_fm" -> when (hour) {
                in 7..8 -> "전영신의 아침저널"
                in 9..11 -> "좋은 아침 성전입니다"
                in 12..15 -> "주병선의 추억의 음악다방"
                in 16..19 -> "무명을 밝히고 / 뉴스파노라마"
                else -> "BBS 명상과 독경의 시간"
            }
            "cpbc_fm" -> when (hour) {
                in 7..8 -> "열린세상 오늘"
                in 9..11 -> "행복을 여는 아침"
                in 12..15 -> "낮 12시 찬미 / 한낮의 가요선물"
                in 16..19 -> "음악이 있는 저녁풍경"
                else -> "CPBC 평화의 밤과 묵상"
            }
            else -> station.defaultProgram
        }
    }

    /**
     * 방송사 실시간 온에어 API에서 현재 방송 중인 프로그램 제목을 조회하고,
     * 응답이 없을 경우 시간대별 편성표 프로그램 이름을 반환합니다.
     */
    suspend fun resolveCurrentProgramTitle(station: RadioStation): String = withContext(Dispatchers.IO) {
        val scheduledFallback = getScheduledProgramTitle(station)
        try {
            when (station.resolverType) {
                StreamResolverType.KBS_API -> {
                    val onairUrl = "https://static.api.kbs.co.kr/mediafactory/v1/schedule/onair_now?rtype=json&channel_code=${station.channelCode}&local_station_code=00"
                    val req = Request.Builder()
                        .url(onairUrl)
                        .header("User-Agent", "Mozilla/5.0")
                        .header("Referer", "https://onair.kbs.co.kr/")
                        .build()
                    httpClient.newCall(req).execute().use { resp ->
                        val body = resp.body?.string()?.trim().orEmpty()
                        if (body.startsWith("[") || body.startsWith("{")) {
                            val titleRegex = Regex("\"program_title\"\\s*:\\s*\"([^\"]+)\"")
                            val match = titleRegex.find(body)
                            val found = match?.groupValues?.getOrNull(1)?.trim()
                            if (!found.isNullOrBlank()) {
                                return@withContext found
                            }
                        }
                    }
                }

                StreamResolverType.MBC_API -> {
                    val req = Request.Builder()
                        .url("https://miniapi.imbc.com/BroadCast/OnAirList")
                        .header("User-Agent", "Mozilla/5.0")
                        .header("Referer", "https://mini.imbc.com/")
                        .build()
                    httpClient.newCall(req).execute().use { resp ->
                        val body = resp.body?.string()?.trim().orEmpty()
                        val targetCode = if (station.channelCode == "sfm") "STFM" else "FM4U"
                        if (body.startsWith("[")) {
                            val arr = JSONArray(body)
                            for (i in 0 until arr.length()) {
                                val obj = arr.getJSONObject(i)
                                val ch = obj.optString("Channel", obj.optString("Type", ""))
                                val title = obj.optString("Title", obj.optString("ProgTitle", "")).trim()
                                if (ch.equals(targetCode, ignoreCase = true) && title.isNotBlank()) {
                                    return@withContext title
                                }
                            }
                        } else if (body.startsWith("{")) {
                            val json = JSONObject(body)
                            val title = json.optString("Title", json.optString("ProgTitle", "")).trim()
                            if (title.isNotBlank()) return@withContext title
                        }
                    }
                }

                StreamResolverType.SBS_API -> {
                    val sbsChannelId = if (station.channelCode == "powerfm") "S07" else "S08"
                    val req = Request.Builder()
                        .url("https://static.apis.sbs.co.kr/play-api/1.0/onair/channel/$sbsChannelId")
                        .header("User-Agent", "Mozilla/5.0")
                        .header("Referer", "https://www.sbs.co.kr/")
                        .build()
                    httpClient.newCall(req).execute().use { resp ->
                        val body = resp.body?.string()?.trim().orEmpty()
                        if (body.startsWith("{")) {
                            val root = JSONObject(body)
                            val onair = root.optJSONObject("onair")
                            val info = onair?.optJSONObject("info")
                            val title = info?.optString("title", "")?.trim()
                            if (!title.isNullOrBlank()) {
                                return@withContext title
                            }
                        }
                    }
                }

                else -> {}
            }
        } catch (_: Exception) {
        }
        return@withContext scheduledFallback
    }

    suspend fun resolvePlayableStreamUrl(station: RadioStation): String = withContext(Dispatchers.IO) {
        if (!station.customStreamUrl.isNullOrBlank()) {
            return@withContext station.customStreamUrl.trim()
        }

        val endpoint = station.endpointOrStreamUrl
        if (station.resolverType == StreamResolverType.STATIC_HLS) {
            return@withContext endpoint
        }

        try {
            val request = Request.Builder()
                .url(endpoint)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
                .header("Accept", "*/*")
                .header("Referer", when (station.resolverType) {
                    StreamResolverType.KBS_API -> "https://onair.kbs.co.kr/"
                    StreamResolverType.MBC_API -> "https://mini.imbc.com/"
                    StreamResolverType.SBS_API -> "https://www.sbs.co.kr/"
                    else -> endpoint
                })
                .build()

            httpClient.newCall(request).execute().use { response ->
                val bodyText = response.body?.string()?.trim().orEmpty()
                if (bodyText.isNotEmpty()) {
                    when (station.resolverType) {
                        StreamResolverType.KBS_API -> {
                            val json = JSONObject(bodyText)
                            val items = json.optJSONArray("channel_item")
                            if (items != null && items.length() > 0) {
                                for (i in 0 until items.length()) {
                                    val item = items.getJSONObject(i)
                                    val serviceUrl = item.optString("service_url", "")
                                    val mediaType = item.optString("media_type", "")
                                    if (serviceUrl.contains(".m3u8") && (mediaType == "audio" || i == 0)) {
                                        return@withContext serviceUrl
                                    }
                                }
                                val firstUrl = items.getJSONObject(0).optString("service_url", "")
                                if (firstUrl.startsWith("http")) return@withContext firstUrl
                            }
                        }
                        StreamResolverType.MBC_API, StreamResolverType.SBS_API -> {
                            val firstToken = bodyText.lineSequence()
                                .map { it.trim() }
                                .firstOrNull { it.startsWith("http") }
                            if (firstToken != null) {
                                return@withContext firstToken
                            }
                            val startIdx = bodyText.indexOf("http")
                            if (startIdx >= 0) {
                                val sub = bodyText.substring(startIdx)
                                val extracted = sub.takeWhile { ch ->
                                    !ch.isWhitespace() && ch !in "\"'<>"
                                }
                                if (extracted.startsWith("http")) {
                                    return@withContext extracted
                                }
                            }
                        }
                        else -> {}
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val corsProxies = listOf(
            "https://api.allorigins.win/raw?url=",
            "https://api.codetabs.com/v1/proxy?quest="
        )
        for (proxy in corsProxies) {
            try {
                val encodedUrl = java.net.URLEncoder.encode(endpoint, "UTF-8")
                val proxyReq = Request.Builder().url(proxy + encodedUrl).build()
                httpClient.newCall(proxyReq).execute().use { resp ->
                    val text = resp.body?.string()?.trim().orEmpty().replace("\\/", "/")
                    val startIdx = text.indexOf("http")
                    if (startIdx >= 0) {
                        val sub = text.substring(startIdx)
                        val candidate = sub.takeWhile { ch ->
                            !ch.isWhitespace() && ch !in "\"'<>"
                        }
                        if (candidate.contains(".m3u8")) {
                            return@withContext candidate
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }

        return@withContext endpoint
    }
}