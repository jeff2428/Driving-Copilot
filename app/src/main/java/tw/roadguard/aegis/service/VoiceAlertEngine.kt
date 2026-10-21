package tw.roadguard.aegis.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import tw.roadguard.aegis.model.CameraType
import tw.roadguard.aegis.model.EventSeverity
import tw.roadguard.aegis.model.RoadEvent
import tw.roadguard.aegis.model.SpeedCamera
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class VoiceAlertEngine(context: Context) : TextToSpeech.OnInitListener {

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val prefs = appContext.getSharedPreferences("roadguard_voice_prefs", Context.MODE_PRIVATE)

    var isVoiceEnabled: Boolean
        get() = prefs.getBoolean("voice_enabled", true)
        set(value) = prefs.edit().putBoolean("voice_enabled", value).apply()

    var isPassChimeEnabled: Boolean
        get() = prefs.getBoolean("pass_chime_enabled", true)
        set(value) = prefs.edit().putBoolean("pass_chime_enabled", value).apply()

    var overspeedToleranceKmh: Int
        get() = prefs.getInt("overspeed_tol", 10)
        set(value) = prefs.edit().putInt("overspeed_tol", value).apply()

    var isHotspotAlertEnabled: Boolean
        get() = prefs.getBoolean("hotspot_alert_enabled", true)
        set(value) = prefs.edit().putBoolean("hotspot_alert_enabled", value).apply()

    var isTunnelLaneAlertEnabled: Boolean
        get() = prefs.getBoolean("tunnel_lane_alert_enabled", true)
        set(value) = prefs.edit().putBoolean("tunnel_lane_alert_enabled", value).apply()

    // 紀錄已播報過的階段 (1=遠距初報, 2=近距提醒)
    private val announcedEventStages = ConcurrentHashMap<String, Int>()
    private val announcedCameraStages = ConcurrentHashMap<String, Int>()
    private val lastCameraDistances = ConcurrentHashMap<String, Double>()
    private var announcedTunnelDirection: String? = null
    private var lastOverspeedBeepTime = 0L

    private val audioFocusRequest: AudioFocusRequest = AudioFocusRequest.Builder(
        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
    ).setAudioAttributes(
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    ).build()

    init {
        try {
            tts = TextToSpeech(appContext, this)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val res = tts?.setLanguage(Locale.TAIWAN)
            if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.setLanguage(Locale.TRADITIONAL_CHINESE)
            }
            tts?.setSpeechRate(1.06f)
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    abandonAudioFocus()
                }

                override fun onError(utteranceId: String?) {
                    abandonAudioFocus()
                }
            })
            isTtsReady = true
        }
    }

    private fun requestDuckingFocus() {
        try {
            audioManager.requestAudioFocus(audioFocusRequest)
        } catch (_: Exception) {
        }
    }

    private fun abandonAudioFocus() {
        try {
            audioManager.abandonAudioFocusRequest(audioFocusRequest)
        } catch (_: Exception) {
        }
    }

    fun speakNow(text: String, urgentTone: Boolean = false) {
        if (!isVoiceEnabled) return
        requestDuckingFocus()
        if (urgentTone) {
            playToneSequence(intArrayOf(ToneGenerator.TONE_PROP_ACK, ToneGenerator.TONE_PROP_BEEP2), 140)
        } else {
            playSingleTone(ToneGenerator.TONE_PROP_BEEP, 130)
        }
        mainHandler.postDelayed({
            if (isTtsReady) {
                tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "UTT-${System.currentTimeMillis()}")
            } else {
                abandonAudioFocus()
            }
        }, 280L)
    }

    private var lastNavSpokenText: String = ""
    private var lastNavSpokenTime: Long = 0L

    fun speakNavigation(text: String) {
        if (!isVoiceEnabled) return
        val now = System.currentTimeMillis()
        if (text == lastNavSpokenText && now - lastNavSpokenTime < 4000L) {
            return
        }
        lastNavSpokenText = text
        lastNavSpokenTime = now

        requestDuckingFocus()
        if (isTtsReady) {
            tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "NAV-${System.currentTimeMillis()}")
        } else {
            abandonAudioFocus()
        }
    }

    /**
     * 神盾測速照相兩階段語音預警（依道路速限與車速動態調整預警距離，並檢查是否正在接近中）
     */
    fun checkAndAnnounceCamera(camera: SpeedCamera, distanceMeters: Double, currentSpeedKmh: Int) {
        if (!isVoiceEnabled) return
        if (camera.type == CameraType.HOTSPOT && !isHotspotAlertEnabled) return

        val prevDist = lastCameraDistances.put(camera.id, distanceMeters)
        // 若距離明顯正在拉遠 (> 6 公尺)，代表並非接近中，不觸發新語音階段
        val isMovingAway = prevDist != null && distanceMeters > prevDist + 6.0
        if (isMovingAway) return

        val (stage1Dist, stage2Dist, overspeedBeepDist) = when {
            camera.speedLimit >= 80 || currentSpeedKmh >= 80 -> Triple(900.0, 380.0, 550.0)
            camera.speedLimit >= 60 || currentSpeedKmh >= 60 -> Triple(600.0, 260.0, 400.0)
            else -> Triple(420.0, 180.0, 280.0)
        }

        val distInt = ((distanceMeters / 50).toInt() * 50).coerceAtLeast(50)
        val stage = when {
            distanceMeters <= stage2Dist -> 2
            distanceMeters <= stage1Dist -> 1
            else -> 0
        }
        if (stage == 0) return

        val prevStage = announcedCameraStages[camera.id] ?: 0
        if (stage > prevStage) {
            announcedCameraStages[camera.id] = stage
            val isOver = currentSpeedKmh > (camera.speedLimit + overspeedToleranceKmh)
            val isHotspot = camera.type == CameraType.HOTSPOT
            val msg = if (stage == 1) {
                if (isHotspot) {
                    "注意，前方 $distInt 公尺，經常出沒移動式測速，速限 ${camera.speedLimit} 公里"
                } else {
                    "注意，前方 $distInt 公尺，有${camera.type.label}，速限 ${camera.speedLimit} 公里"
                }
            } else {
                if (isOver) {
                    if (isHotspot) {
                        "警告！前方 $distInt 公尺經常出沒移動測速，速限 ${camera.speedLimit} 公里，您目前已超速，請立即減速！"
                    } else {
                        "警告！前方 $distInt 公尺測速照相，速限 ${camera.speedLimit} 公里，您目前已超速，請立即減速！"
                    }
                } else {
                    if (isHotspot) {
                        "接近經常出沒測速點，速限 ${camera.speedLimit} 公里"
                    } else {
                        "接近${camera.type.label}，速限 ${camera.speedLimit} 公里"
                    }
                }
            }
            speakNow(msg, urgentTone = isOver)
        }

        // 若持續超速且在近距離內，每 4.5 秒發出神盾急促超速音
        val now = System.currentTimeMillis()
        if (currentSpeedKmh > (camera.speedLimit + overspeedToleranceKmh) &&
            distanceMeters <= overspeedBeepDist &&
            now - lastOverspeedBeepTime > 4500L
        ) {
            lastOverspeedBeepTime = now
            playToneSequence(
                intArrayOf(
                    ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD,
                    ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD
                ),
                160
            )
        }
    }

    /**
     * 通過測速照相桿時的「叮咚」解除音
     */
    fun playCameraPassedChime() {
        if (!isVoiceEnabled || !isPassChimeEnabled) return
        playToneSequence(
            intArrayOf(ToneGenerator.TONE_PROP_BEEP, ToneGenerator.TONE_PROP_ACK),
            150
        )
    }

    /**
     * 道路施工與封閉管制兩階段語音預警
     */
    fun checkAndAnnounceRoadEvent(event: RoadEvent, distanceMeters: Double) {
        if (!isVoiceEnabled || event.isCleared || !event.isActiveNow) return
        val stage = when {
            distanceMeters <= 550.0 -> 2
            distanceMeters <= 1800.0 -> 1
            else -> 0
        }
        if (stage == 0) return

        val prevStage = announcedEventStages[event.uid] ?: 0
        if (stage > prevStage) {
            announcedEventStages[event.uid] = stage
            val distStr = if (distanceMeters >= 1000.0) {
                String.format(Locale.TAIWAN, "%.1f 公里", distanceMeters / 1000.0)
            } else {
                "${((distanceMeters / 50).toInt() * 50).coerceAtLeast(100)} 公尺"
            }

            val prefix = when (event.severity) {
                EventSeverity.CLOSURE -> "道路封閉警告！前方 $distStr，"
                EventSeverity.CONSTRUCTION -> "注意施工！前方 $distStr，"
                EventSeverity.HAZARD -> "路況注意！前方 $distStr，"
                EventSeverity.CONGESTION -> "前方 $distStr，"
            }
            val tail = if (event.severity == EventSeverity.CLOSURE) "，請提早準備改道。" else "，請小心駕駛。"
            speakNow(
                text = prefix + event.voiceSummary + tail,
                urgentTone = event.severity == EventSeverity.CLOSURE
            )
        }
    }

    /**
     * 雪山隧道進洞前車道建議播報 (內側 vs 外側即時車速與推薦)
     */
    fun announceHsuehshanTunnel(advice: tw.roadguard.aegis.model.HsuehshanAdvice, force: Boolean = false) {
        if (!isVoiceEnabled) return
        if (!force && !isTunnelLaneAlertEnabled) return

        val speed = if (advice.recommendedLane == "inner") advice.innerSpeedKmh else advice.outerSpeedKmh
        val tunnelMin = advice.tunnelTravelMin?.let { kotlin.math.round(it).toInt() } ?: 9

        val builder = StringBuilder()
        builder.append("即將進入雪山隧道${advice.directionLabel}。")

        if (advice.diffKmh >= 3) {
            builder.append("目前${advice.recommendedLaneName}較順暢，時速快 ${advice.diffKmh} 公里，建議行駛${advice.recommendedLaneName}！")
        } else if (advice.reason.isNotBlank() && !advice.reason.contains("雙白線") && !advice.reason.contains("連線中")) {
            builder.append("建議行駛${advice.recommendedLaneName}，${advice.reason}。")
        } else {
            builder.append("目前兩線車速相當，建議行駛${advice.recommendedLaneName}。")
        }

        if (speed != null && speed > 0) {
            builder.append("平均時速約 $speed 公里，預估 $tunnelMin 分鐘通過。")
        }

        if (!advice.alertEvent.isNullOrBlank()) {
            builder.append("請注意！雪隧內有路況通報：${advice.alertEvent}，請提前減速。")
        }

        builder.append("雪隧內全線禁止變換車道，請在進洞前選定車道。")

        speakNow(builder.toString(), urgentTone = false)
    }

    fun checkAndAnnounceHsuehshanTunnel(advice: tw.roadguard.aegis.model.HsuehshanAdvice) {
        if (!isVoiceEnabled || !isTunnelLaneAlertEnabled) return
        if (!advice.isApproachingEntrance) return

        // 一次進洞只播報一次
        if (announcedTunnelDirection == advice.direction) return
        announcedTunnelDirection = advice.direction

        announceHsuehshanTunnel(advice)
    }

    fun hasAnnouncedTunnel(direction: String): Boolean = announcedTunnelDirection == direction

    fun resetTunnelAnnounced() {
        announcedTunnelDirection = null
    }

    fun resetMemory() {
        announcedCameraStages.clear()
        announcedEventStages.clear()
        lastCameraDistances.clear()
        resetTunnelAnnounced()
    }

    private fun playSingleTone(toneType: Int, durationMs: Int) {
        Thread {
            try {
                val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 90)
                tg.startTone(toneType, durationMs)
                Thread.sleep(durationMs + 50L)
                tg.release()
            } catch (_: Exception) {
            }
        }.start()
    }

    private fun playToneSequence(tones: IntArray, eachDurationMs: Int) {
        Thread {
            try {
                val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 95)
                for (t in tones) {
                    tg.startTone(t, eachDurationMs)
                    Thread.sleep(eachDurationMs + 90L)
                }
                tg.release()
            } catch (_: Exception) {
            }
        }.start()
    }

    fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {
        }
    }
}
