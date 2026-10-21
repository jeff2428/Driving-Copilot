import Foundation
import AVFoundation
import AudioToolbox
import Combine

/// iOS 原生背景語音壓低 (Audio Ducking) 與神盾路況預警語音引擎
public final class VoiceAlertEngine: NSObject, ObservableObject, AVSpeechSynthesizerDelegate {
    public static let shared = VoiceAlertEngine()

    private let synthesizer = AVSpeechSynthesizer()
    private let userDefaults = UserDefaults.standard
    private let voiceQueue = DispatchQueue(label: "tw.roadguard.aegis.voiceQueue")

    @Published public var isSpeaking: Bool = false

    // 使用者偏好設定
    public var isVoiceEnabled: Bool {
        get { userDefaults.object(forKey: "voice_enabled") as? Bool ?? true }
        set { userDefaults.set(newValue, forKey: "voice_enabled") }
    }

    public var isPassChimeEnabled: Bool {
        get { userDefaults.object(forKey: "pass_chime_enabled") as? Bool ?? true }
        set { userDefaults.set(newValue, forKey: "pass_chime_enabled") }
    }

    public var overspeedToleranceKmh: Int {
        get { userDefaults.object(forKey: "overspeed_tol") as? Int ?? 10 }
        set { userDefaults.set(newValue, forKey: "overspeed_tol") }
    }

    public var isHotspotAlertEnabled: Bool {
        get { userDefaults.object(forKey: "hotspot_alert_enabled") as? Bool ?? true }
        set { userDefaults.set(newValue, forKey: "hotspot_alert_enabled") }
    }

    public var isTunnelLaneAlertEnabled: Bool {
        get { userDefaults.object(forKey: "tunnel_lane_alert_enabled") as? Bool ?? true }
        set { userDefaults.set(newValue, forKey: "tunnel_lane_alert_enabled") }
    }

    // 播報階段紀錄
    private var announcedCameraStages: [String: Int] = [:]
    private var lastCameraDistances: [String: Double] = [:]
    private var announcedEventStages: [String: Int] = [:]
    private var announcedTunnelDirection: String? = nil
    private var lastOverspeedBeepTime: Date = .distantPast

    private let voiceLock = NSLock()

    override private init() {
        super.init()
        synthesizer.delegate = self
        setupAudioSession()
    }

    private func setupAudioSession() {
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(
            .playback,
            mode: .spokenAudio,
            options: [.duckOthers, .interruptSpokenAudioAndMixWithOthers]
        )
    }

    private func activateAudioDucking() {
        let session = AVAudioSession.sharedInstance()
        try? session.setActive(true)
    }

    private func deactivateAudioDucking() {
        let session = AVAudioSession.sharedInstance()
        try? session.setActive(false, options: .notifyOthersOnDeactivation)
    }

    // MARK: - AVSpeechSynthesizerDelegate

    public func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didStart utterance: AVSpeechUtterance) {
        DispatchQueue.main.async {
            self.isSpeaking = true
        }
    }

    public func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) {
        DispatchQueue.main.async {
            self.isSpeaking = false
            self.deactivateAudioDucking()
        }
    }

    public func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didCancel utterance: AVSpeechUtterance) {
        DispatchQueue.main.async {
            self.isSpeaking = false
            self.deactivateAudioDucking()
        }
    }

    // MARK: - 即時語音播報

    private var lastSpokenText: String = ""
    private var lastSpokenTime: Date = .distantPast

    public func speakNow(text: String, urgentTone: Bool = false) {
        guard isVoiceEnabled, !text.isEmpty else { return }
        let now = Date()
        if text == lastSpokenText && now.timeIntervalSince(lastSpokenTime) < 4.0 {
            return
        }
        lastSpokenText = text
        lastSpokenTime = now

        activateAudioDucking()

        if urgentTone {
            // 急迫警報音 (系統警告鈴響)
            AudioServicesPlaySystemSound(1005)
        } else {
            // 一般提醒輕音
            AudioServicesPlaySystemSound(1057)
        }

        voiceQueue.asyncAfter(deadline: .now() + 0.28) { [weak self] in
            guard let self = self else { return }
            let utterance = AVSpeechUtterance(string: text)
            // 臺灣國語傳統發音
            utterance.voice = AVSpeechSynthesisVoice(language: "zh-TW") ?? AVSpeechSynthesisVoice(language: "zh-HK")
            utterance.rate = 0.52 // 平穩流暢的行車播報語速
            utterance.pitchMultiplier = 1.05
            utterance.volume = 1.0

            DispatchQueue.main.async {
                self.synthesizer.speak(utterance)
            }
        }
    }

    /// 通過測速照相桿時的「叮咚」解除音
    public func playCameraPassedChime() {
        guard isVoiceEnabled, isPassChimeEnabled else { return }
        AudioServicesPlaySystemSound(1054) // 輕快的通過解除提示音
    }

    /// 神盾測速照相兩階段語音預警（動態調整距離與超速警報）
    public func checkAndAnnounceCamera(camera: SpeedCamera, distanceMeters: Double, currentSpeedKmh: Int) {
        guard isVoiceEnabled else { return }
        if camera.type == .hotspot && !isHotspotAlertEnabled { return }

        voiceLock.lock()
        let prevDist = lastCameraDistances[camera.id]
        lastCameraDistances[camera.id] = distanceMeters
        voiceLock.unlock()

        // 若距離明顯正在拉遠 (> 6 公尺)，代表並非接近中
        if let prev = prevDist, distanceMeters > prev + 6.0 {
            return
        }

        let (stage1Dist, stage2Dist, overspeedBeepDist): (Double, Double, Double) = {
            if camera.speedLimit >= 80 || currentSpeedKmh >= 80 {
                return (900.0, 380.0, 550.0)
            } else if camera.speedLimit >= 60 || currentSpeedKmh >= 60 {
                return (600.0, 260.0, 400.0)
            } else {
                return (420.0, 180.0, 280.0)
            }
        }()

        let distInt = max(50, Int(distanceMeters / 50.0) * 50)
        let stage: Int
        if distanceMeters <= stage2Dist {
            stage = 2
        } else if distanceMeters <= stage1Dist {
            stage = 1
        } else {
            stage = 0
        }

        if stage == 0 { return }

        voiceLock.lock()
        let prevStage = announcedCameraStages[camera.id] ?? 0
        if stage > prevStage {
            announcedCameraStages[camera.id] = stage
            voiceLock.unlock()

            let isOver = currentSpeedKmh > (camera.speedLimit + overspeedToleranceKmh)
            let isHotspot = camera.type == .hotspot
            let msg: String
            if stage == 1 {
                if isHotspot {
                    msg = "注意，前方 \(distInt) 公尺，經常出沒移動式測速，速限 \(camera.speedLimit) 公里"
                } else {
                    msg = "注意，前方 \(distInt) 公尺，有\(camera.type.label)，速限 \(camera.speedLimit) 公里"
                }
            } else {
                if isOver {
                    if isHotspot {
                        msg = "警告！前方 \(distInt) 公尺經常出沒移動測速，速限 \(camera.speedLimit) 公里，您目前已超速，請立即減速！"
                    } else {
                        msg = "警告！前方 \(distInt) 公尺測速照相，速限 \(camera.speedLimit) 公里，您目前已超速，請立即減速！"
                    }
                } else {
                    if isHotspot {
                        msg = "接近經常出沒測速點，速限 \(camera.speedLimit) 公里"
                    } else {
                        msg = "接近\(camera.type.label)，速限 \(camera.speedLimit) 公里"
                    }
                }
            }
            speakNow(text: msg, urgentTone: isOver)
        } else {
            voiceLock.unlock()
        }

        // 持續超速且在近距離內，每 4.5 秒發出神盾急促超速音
        let now = Date()
        if currentSpeedKmh > (camera.speedLimit + overspeedToleranceKmh),
           distanceMeters <= overspeedBeepDist,
           now.timeIntervalSince(lastOverspeedBeepTime) > 4.5 {
            lastOverspeedBeepTime = now
            AudioServicesPlaySystemSound(1005)
        }
    }

    /// 道路施工與封閉管制兩階段語音預警
    public func checkAndAnnounceRoadEvent(event: RoadEvent, distanceMeters: Double) {
        guard isVoiceEnabled, !event.isCleared, event.isActiveNow else { return }

        let stage: Int
        if distanceMeters <= 550.0 {
            stage = 2
        } else if distanceMeters <= 1800.0 {
            stage = 1
        } else {
            stage = 0
        }
        if stage == 0 { return }

        voiceLock.lock()
        let prevStage = announcedEventStages[event.uid] ?? 0
        if stage > prevStage {
            announcedEventStages[event.uid] = stage
            voiceLock.unlock()

            let distStr = distanceMeters >= 1000.0 ?
                String(format: "%.1f 公里", distanceMeters / 1000.0) :
                "\(max(100, Int(distanceMeters / 50.0) * 50)) 公尺"

            let prefix: String
            switch event.severity {
            case .closure: prefix = "道路封閉警告！前方 \(distStr)，"
            case .construction: prefix = "注意施工！前方 \(distStr)，"
            case .hazard: prefix = "路況注意！前方 \(distStr)，"
            case .congestion: prefix = "前方 \(distStr)，"
            }
            let tail = event.severity == .closure ? "，請提早準備改道。" : "，請小心駕駛。"
            speakNow(text: prefix + event.voiceSummary + tail, urgentTone: event.severity == .closure)
        } else {
            voiceLock.unlock()
        }
    }

    /// 雪山隧道進洞前車道建議播報 (內側 vs 外側即時車速與推薦)
    public func announceHsuehshanTunnel(advice: HsuehshanAdvice, force: Bool = false) {
        guard isVoiceEnabled else { return }
        if !force && !isTunnelLaneAlertEnabled { return }

        let speed = advice.recommendedLane == "inner" ? advice.innerSpeedKmh : advice.outerSpeedKmh
        let tunnelMin = advice.tunnelTravelMin.map { Int(round($0)) } ?? 9

        var text = "即將進入雪山隧道\(advice.directionLabel)。"
        if advice.diffKmh >= 3 {
            text += "目前\(advice.recommendedLaneName)較順暢，時速快 \(advice.diffKmh) 公里，建議行駛\(advice.recommendedLaneName)！"
        } else if !advice.reason.isEmpty && !advice.reason.contains("雙白線") && !advice.reason.contains("連線中") {
            text += "建議行駛\(advice.recommendedLaneName)，\(advice.reason)。"
        } else {
            text += "目前兩線車速相當，建議行駛\(advice.recommendedLaneName)。"
        }

        if let speed = speed, speed > 0 {
            text += "平均時速約 \(speed) 公里，預估 \(tunnelMin) 分鐘通過。"
        }

        if let alert = advice.alertEvent, !alert.isEmpty {
            text += "請注意！雪隧內有路況通報：\(alert)，請提前減速。"
        }

        text += "雪隧內全線禁止變換車道，請在進洞前選定車道。"
        speakNow(text: text, urgentTone: false)
    }

    public func checkAndAnnounceHsuehshanTunnel(advice: HsuehshanAdvice) {
        guard isVoiceEnabled, isTunnelLaneAlertEnabled, advice.isApproachingEntrance else { return }

        voiceLock.lock()
        if announcedTunnelDirection == advice.direction {
            voiceLock.unlock()
            return
        }
        announcedTunnelDirection = advice.direction
        voiceLock.unlock()

        announceHsuehshanTunnel(advice: advice)
    }

    public func hasAnnouncedTunnel(direction: String) -> Bool {
        voiceLock.lock()
        defer { voiceLock.unlock() }
        return announcedTunnelDirection == direction
    }

    public func resetTunnelAnnounced() {
        voiceLock.lock()
        announcedTunnelDirection = nil
        voiceLock.unlock()
    }

    public func resetMemory() {
        voiceLock.lock()
        announcedCameraStages.removeAll()
        lastCameraDistances.removeAll()
        announcedEventStages.removeAll()
        announcedTunnelDirection = nil
        voiceLock.unlock()
    }
}
