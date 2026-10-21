import Foundation
import Combine
import CoreLocation

/// 神盾雙雷達高精度前向走廊運算核心（測速照相、施工封路、雪隧進洞引導）
public final class RadarEngine: ObservableObject {
    public static let shared = RadarEngine()

    private let locationManager = RoadGuardLocationManager.shared
    private let repository = OpenDataRepository.shared
    private let voiceEngine = VoiceAlertEngine.shared
    private let liveActivityManager = LiveActivityManager.shared

    @Published public var snapshot: RadarSnapshot = RadarSnapshot()
    @Published public var isDrivingActive: Bool = false
    @Published public var isSimulating: Bool = false
    @Published public var simScenarioIndex: Int = 0
    @Published public var simRouteName: String = ""

    public var forwardConeOnly: Bool = true
    public var currentLat: Double = 24.1605
    public var currentLng: Double = 120.6311
    public var currentSpeedKmh: Int = 0
    public var currentBearingDeg: Double = 0.0
    private var hasValidBearing: Bool = false

    // 追蹤接近中的測速桿與通過防回頭冷卻 (65 秒)
    private var approachingCameraId: String? = nil
    private var approachingCameraMinDist: Double = .greatestFiniteMagnitude
    private var passedCameraCooldownMap: [String: Date] = [:]

    // 追蹤雪隧進洞提醒是否已完成
    private var completedTunnelApproachDir: String? = nil

    // 實境模擬試駕計時器
    private var simTimer: Timer?
    private var simStep: Int = 0

    private var cancellables = Set<AnyCancellable>()

    private init() {
        locationManager.onLocationUpdate = { [weak self] lat, lng, speed, bearing in
            guard let self = self, !self.isSimulating else { return }
            self.currentLat = lat
            self.currentLng = lng
            self.currentSpeedKmh = speed
            self.currentBearingDeg = bearing
            self.hasValidBearing = self.locationManager.hasValidBearing
            self.recomputeRadarState()
        }
    }

    private var lastStartDrivingTime: Date = .distantPast

    public func startDriving() {
        let now = Date()
        if isDrivingActive && now.timeIntervalSince(lastStartDrivingTime) < 5.0 {
            return
        }
        let wasActive = isDrivingActive
        isDrivingActive = true
        lastStartDrivingTime = now

        locationManager.startUpdating()
        voiceEngine.resetMemory()
        approachingCameraId = nil
        approachingCameraMinDist = .greatestFiniteMagnitude
        passedCameraCooldownMap.removeAll()
        completedTunnelApproachDir = nil

        liveActivityManager.startLiveActivity(initialSpeed: currentSpeedKmh)
        if !wasActive {
            voiceEngine.speakNow(text: "神盾路況行車助手已啟動，祝您行車平安！", urgentTone: false)
        }
        recomputeRadarState()
    }

    public func stopDriving() {
        isDrivingActive = false
        if isSimulating {
            stopDemoSimulation()
        }
        locationManager.stopUpdating()
        liveActivityManager.endLiveActivity()
        recomputeRadarState()
    }

    // MARK: - 實境模擬試駕

    public func startDemoSimulation(scenarioIdx: Int = 0) {
        isDrivingActive = true
        simScenarioIndex = scenarioIdx % 6
        simStep = 0
        isSimulating = true
        hasValidBearing = true

        voiceEngine.resetMemory()
        approachingCameraId = nil
        approachingCameraMinDist = .greatestFiniteMagnitude
        passedCameraCooldownMap.removeAll()
        completedTunnelApproachDir = nil

        switch simScenarioIndex {
        case 0:
            simRouteName = "模擬 1：台中環中路/台74線 (市政路右轉道封閉施工 + 固定測速 60)"
            currentLat = 24.1485
            currentLng = 120.6311
            currentBearingDeg = 0.0 // 往北
            currentSpeedKmh = 74   // 故意超速以展示警報
            ensureDemoTargetsExist(
                camLat: 24.1552, camLng: 120.6311, limit: 60, camType: .fixed,
                camAddr: "臺中市西屯區環中路三段(近朝馬路)固定測速",
                evLat: 24.1605, evLng: 120.6311, isClosure: true,
                evRoad: "環中路往台灣大道(市政路前)",
                evDesc: "右轉專用道封閉施工，請提早變換至內側車道或改道黎明路"
            )
        case 1:
            simRouteName = "模擬 2：國道1號五楊高架南下 (機場系統匝道施工緩撞車 + 國道測速 100)"
            currentLat = 25.0815
            currentLng = 121.4419
            currentBearingDeg = 180.0 // 往南
            currentSpeedKmh = 108
            ensureDemoTargetsExist(
                camLat: 25.0745, camLng: 121.4419, limit: 100, camType: .fixed,
                camAddr: "國道一號五楊高架南下 50.2K 固定式測速照相",
                evLat: 25.0698, evLng: 121.4419, isClosure: false,
                evRoad: "汐止楊梅-國道1號高架南下",
                evDesc: "機場系統出口匝道前，外側車道施工緩撞車作業中，請注意減速避讓"
            )
        case 2:
            simRouteName = "模擬 3：台61線西濱快速道路 (區間測速 80 + 半半施工單車道管制)"
            currentLat = 23.8235
            currentLng = 120.2830
            currentBearingDeg = 180.0 // 往南
            currentSpeedKmh = 86
            ensureDemoTargetsExist(
                camLat: 23.8168, camLng: 120.2830, limit: 80, camType: .interval,
                camAddr: "台61線西濱快速道路 (區間測速起點)",
                evLat: 23.8112, evLng: 120.2830, isClosure: true,
                evRoad: "(西濱快速)-台61線南下 214K",
                evDesc: "公路設施改善工程半半施工，封閉外側車道僅維持單車道通行"
            )
        case 3:
            simRouteName = "模擬 4：新北坪林/北宜公路 33K (經常出沒移動式測速 40 + 彎道工程)"
            currentLat = 24.9185
            currentLng = 121.6654
            currentBearingDeg = 180.0 // 往南
            currentSpeedKmh = 52
            ensureDemoTargetsExist(
                camLat: 24.9125, camLng: 121.6654, limit: 40, camType: .hotspot,
                camAddr: "台9線北宜公路 33.1K 財茂彎路段【經常出沒移動式測速】",
                evLat: 24.9085, evLng: 121.6654, isClosure: false,
                evRoad: "北宜公路坪林段",
                evDesc: "路面改善工程佔用外側車道，請減速慢行注意安全"
            )
        case 4:
            simRouteName = "模擬 5：國道5號南下 14.5K (進雪隧前車道建議 ➔ 進洞後自動停止並切換雪隧科技執法)"
            currentLat = 24.9415
            currentLng = 121.7090
            currentBearingDeg = 135.0 // 往東南
            currentSpeedKmh = 85
            ensureDemoTargetsExist(
                camLat: 24.9330, camLng: 121.7177, limit: 90, camType: .tech,
                camAddr: "國道五號南向15.8公里(雪山隧道內科技執法)",
                evLat: 24.9295, evLng: 121.7212, isClosure: false,
                evRoad: "國道5號雪山隧道南下",
                evDesc: "雪山隧道內雙白線禁止變換車道，請保持安全車距"
            )
        default:
            simRouteName = "模擬 6：國道5號北上 28.8K (進雪隧前車道建議 ➔ 進洞後自動停止並切換雪隧科技執法)"
            currentLat = 24.8458
            currentLng = 121.8015
            currentBearingDeg = 318.0 // 往西北
            currentSpeedKmh = 84
            ensureDemoTargetsExist(
                camLat: 24.8548, camLng: 121.7924, limit: 90, camType: .tech,
                camAddr: "國道五號北向27.5公里(雪山隧道內科技執法)",
                evLat: 24.8580, evLng: 121.7892, isClosure: false,
                evRoad: "國道5號雪山隧道北上",
                evDesc: "雪山隧道內雙白線禁止變換車道，請保持安全車距"
            )
        }

        liveActivityManager.startLiveActivity(initialSpeed: currentSpeedKmh)
        voiceEngine.speakNow(text: "開始實境模擬試駕：\(simRouteName)", urgentTone: false)

        simTimer?.invalidate()
        simTimer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [weak self] _ in
            self?.advanceSimulationStep()
        }
    }

    public func stopDemoSimulation() {
        isSimulating = false
        simRouteName = ""
        currentSpeedKmh = 0
        simTimer?.invalidate()
        simTimer = nil
        recomputeRadarState()
    }

    private func advanceSimulationStep() {
        simStep += 1
        let rad = currentBearingDeg * .pi / 180.0
        let deltaDeg = 0.00020 // 每秒推進約 22 公尺 (約 80 km/h)
        currentLat += cos(rad) * deltaDeg
        currentLng += sin(rad) * deltaDeg

        // 模擬接近測速桿後自動微調車速
        if (18...28).contains(simStep) {
            currentSpeedKmh = max(36, currentSpeedKmh - 2)
        } else if simStep > 45 {
            simStep = 0
            startDemoSimulation(scenarioIdx: (simScenarioIndex + 1) % 6)
            return
        }
        recomputeRadarState()
    }

    private func ensureDemoTargetsExist(
        camLat: Double, camLng: Double, limit: Int, camType: CameraType, camAddr: String,
        evLat: Double, evLng: Double, isClosure: Bool, evRoad: String, evDesc: String
    ) {
        repository.cameras.removeAll { $0.id.hasPrefix("SIM-") }
        repository.roadEvents.removeAll { $0.uid.hasPrefix("SIM-") }

        let dirStr: String
        if currentBearingDeg >= 300.0 || currentBearingDeg <= 60.0 {
            dirStr = "往北"
        } else if currentBearingDeg >= 120.0 && currentBearingDeg <= 240.0 {
            dirStr = "往南"
        } else {
            dirStr = "雙向"
        }

        let simCam = SpeedCamera(
            id: "SIM-CAM-\(simScenarioIndex)",
            city: "實境試駕",
            region: "測試路段",
            address: camAddr,
            dept: "警政署測速資料",
            lat: camLat,
            lng: camLng,
            directText: dirStr,
            speedLimit: limit,
            type: camType
        )

        let sev: EventSeverity = isClosure ? .closure : .construction
        let simEv = RoadEvent(
            uid: "SIM-EV-\(simScenarioIndex)",
            roadName: evRoad,
            areaName: evRoad,
            direction: dirStr,
            lat: evLat,
            lng: evLng,
            rawComment: evDesc,
            voiceSummary: "\(evRoad)，\(sev.label)：\(evDesc)",
            severity: sev,
            category: limit >= 80 ? .freeway : .local,
            isCleared: false,
            isActiveNow: true,
            scheduleNote: "目前施工管制中",
            happenTime: "剛剛通報",
            sourceDetail: "警廣即時通報"
        )

        repository.cameras.insert(simCam, at: 0)
        repository.roadEvents.insert(simEv, at: 0)
    }

    // MARK: - 核心雙雷達運算

    public func recomputeRadarState() {
        let now = Date()
        let canAlert = isDrivingActive || isSimulating
        let insideHsuehshan = GeoUtils.isInsideHsuehshanTunnel(lat: currentLat, lng: currentLng)

        // 1. 清理過期的已通過測速桿冷卻 (65 秒)
        passedCameraCooldownMap = passedCameraCooldownMap.filter { now.timeIntervalSince($0.value) <= 65.0 }

        // 2. 檢查追蹤中的測速桿是否已通過車身
        if let trackedId = approachingCameraId {
            if let trackedCam = repository.cameras.first(where: { $0.id == trackedId }) {
                let curDist = GeoUtils.distanceMeters(lat1: currentLat, lng1: currentLng, lat2: trackedCam.lat, lng2: trackedCam.lng)
                let curBearing = GeoUtils.bearingDeg(lat1: currentLat, lng1: currentLng, lat2: trackedCam.lat, lng2: trackedCam.lng)
                let curRelAngle = GeoUtils.angleDiff(currentBearingDeg, curBearing)

                let passedByAngle = approachingCameraMinDist <= 220.0 && curRelAngle > 72.0
                let passedByDistIncrease = approachingCameraMinDist <= 140.0 && curDist > approachingCameraMinDist + 16.0

                if passedByAngle || passedByDistIncrease {
                    if canAlert {
                        voiceEngine.playCameraPassedChime()
                    }
                    passedCameraCooldownMap[trackedCam.id] = now
                    approachingCameraId = nil
                    approachingCameraMinDist = .greatestFiniteMagnitude
                }
            } else {
                approachingCameraId = nil
                approachingCameraMinDist = .greatestFiniteMagnitude
            }
        }

        // 3. 篩選車頭正前方走廊內的候選測速桿與地圖可見標示
        let useDirectionalFilter = forwardConeOnly && (hasValidBearing || currentSpeedKmh > 3 || isSimulating)
        var candidateCameras: [ScoredCamera] = []
        var mapVisibleCams: [(camera: SpeedCamera, distance: Double)] = []

        for cam in repository.cameras {
            let dLat = abs(cam.lat - currentLat)
            let dLng = abs(cam.lng - currentLng)

            // 地圖物理地標採樣（半徑 4.5 公里，不受前向角度或車道方向過濾，忠實呈現真實世界地圖地標對應）
            if dLat <= 0.05 && dLng <= 0.05 {
                let distMap = GeoUtils.distanceMeters(lat1: currentLat, lng1: currentLng, lat2: cam.lat, lng2: cam.lng)
                if distMap <= 4500.0 {
                    mapVisibleCams.append((camera: cam, distance: distMap))
                }
            }
            if cam.type == .hotspot && !voiceEngine.isHotspotAlertEnabled { continue }
            if passedCameraCooldownMap[cam.id] != nil { continue }

            if dLat > 0.035 || dLng > 0.035 { continue }

            let dist = GeoUtils.distanceMeters(lat1: currentLat, lng1: currentLng, lat2: cam.lat, lng2: cam.lng)
            if dist > 3000.0 { continue }

            let targetBearing = GeoUtils.bearingDeg(lat1: currentLat, lng1: currentLng, lat2: cam.lat, lng2: cam.lng)
            let relAngle = GeoUtils.angleDiff(currentBearingDeg, targetBearing)
            let crossTrack = GeoUtils.crossTrackDistanceMeters(distanceMeters: dist, relativeAngleDeg: relAngle)

            // 國道/快速道路 vs 平面道路立體分流
            let isFreewayCam = CameraAlertPolicy.isFreeway(camera: cam) || cam.address.contains("高架") ||
                              cam.address.contains("快速") || cam.speedLimit >= 80
            let isRampCam = cam.address.contains("匝道") || cam.address.contains("出口") ||
                            cam.address.contains("入口") || cam.address.contains("交流道")

            if currentSpeedKmh >= 82 && cam.speedLimit <= 50 && !isFreewayCam && !isRampCam {
                continue
            }
            if !isSimulating && !insideHsuehshan && currentSpeedKmh >= 1 && currentSpeedKmh <= 48 &&
               cam.speedLimit >= 100 && CameraAlertPolicy.isFreeway(camera: cam) && !isRampCam &&
               approachingCameraId != cam.id {
                continue
            }

            if useDirectionalFilter {
                // (A) 比對拍攝方向
                if !GeoUtils.isCameraDirectionCompatible(driverBearing: currentBearingDeg, address: cam.address, directText: cam.directText) {
                    continue
                }
                // (B) 走廊與橫向偏距過濾
                let maxConeAngle: Double = dist <= 350.0 ? 35.0 : (dist <= 750.0 ? 40.0 : 45.0)
                let maxCrossTrack: Double = dist <= 350.0 ? 48.0 : (dist <= 750.0 ? 75.0 : 105.0)

                if relAngle > maxConeAngle || crossTrack > maxCrossTrack {
                    continue
                }
            }

            candidateCameras.append(ScoredCamera(camera: cam, distanceMeters: dist, relativeAngleDeg: relAngle))
        }

        // 依前向距離加權橫向偏距排序
        candidateCameras.sort {
            let score1 = $0.distanceMeters + GeoUtils.crossTrackDistanceMeters(distanceMeters: $0.distanceMeters, relativeAngleDeg: $0.relativeAngleDeg) * 1.8
            let score2 = $1.distanceMeters + GeoUtils.crossTrackDistanceMeters(distanceMeters: $1.distanceMeters, relativeAngleDeg: $1.relativeAngleDeg) * 1.8
            return score1 < score2
        }

        mapVisibleCams.sort { $0.distance < $1.distance }
        let mapVisibleCamerasList = Array(mapVisibleCams.prefix(75).map { $0.camera })

        let nearestCam = candidateCameras.first { sc in
            let maxLockRadius = CameraAlertPolicy.lockRadiusMeters(camera: sc.camera, speedKmh: currentSpeedKmh)
            return sc.distanceMeters <= maxLockRadius
        }

        if canAlert && approachingCameraId != nil {
            let stillSame = nearestCam?.camera.id == approachingCameraId
            if !stillSame && approachingCameraMinDist <= 220.0 {
                voiceEngine.playCameraPassedChime()
                passedCameraCooldownMap[approachingCameraId!] = now
                approachingCameraId = nil
                approachingCameraMinDist = .greatestFiniteMagnitude
            }
        }

        if canAlert, let nearest = nearestCam {
            if nearest.camera.id != approachingCameraId {
                approachingCameraId = nearest.camera.id
                approachingCameraMinDist = nearest.distanceMeters
            } else if nearest.distanceMeters < approachingCameraMinDist {
                approachingCameraMinDist = nearest.distanceMeters
            }
            voiceEngine.checkAndAnnounceCamera(camera: nearest.camera, distanceMeters: nearest.distanceMeters, currentSpeedKmh: currentSpeedKmh)
        }

        // 4. 掃描前方施工與路況
        let dynamicEventRadius: Double = currentSpeedKmh >= 85 ? 2800.0 : (currentSpeedKmh >= 60 ? 1800.0 : 1200.0)
        var candidateEvents: [ScoredRoadEvent] = []
        var mapVisibleEvs: [(event: RoadEvent, distance: Double)] = []

        for ev in repository.roadEvents {
            if ev.isCleared { continue }
            let dLat = abs(ev.lat - currentLat)
            let dLng = abs(ev.lng - currentLng)

            // 地圖物理路況事件採樣（半徑 8 公里，完整呈現地圖周遭道路封閉與施工標示）
            if dLat <= 0.08 && dLng <= 0.08 {
                let distMap = GeoUtils.distanceMeters(lat1: currentLat, lng1: currentLng, lat2: ev.lat, lng2: ev.lng)
                if distMap <= 8000.0 {
                    mapVisibleEvs.append((event: ev, distance: distMap))
                }
            }

            let dist = GeoUtils.distanceMeters(lat1: currentLat, lng1: currentLng, lat2: ev.lat, lng2: ev.lng)
            if dist > 25000.0 { continue }

            let targetBearing = GeoUtils.bearingDeg(lat1: currentLat, lng1: currentLng, lat2: ev.lat, lng2: ev.lng)
            let relAngle = GeoUtils.angleDiff(currentBearingDeg, targetBearing)
            let crossTrack = GeoUtils.crossTrackDistanceMeters(distanceMeters: dist, relativeAngleDeg: relAngle)

            let isAheadInCorridor: Bool
            if useDirectionalFilter {
                isAheadInCorridor = relAngle <= 48.0 && crossTrack <= 95.0 && GeoUtils.isDirectionCompatible(driverBearing: currentBearingDeg, directionText: ev.direction)
            } else {
                isAheadInCorridor = relAngle <= 65.0
            }

            if isAheadInCorridor || dist <= 5000.0 {
                candidateEvents.append(ScoredRoadEvent(event: ev, distanceMeters: dist, relativeAngleDeg: relAngle))
            }
        }

        candidateEvents.sort {
            if ($0.distanceMeters > dynamicEventRadius) != ($1.distanceMeters > dynamicEventRadius) {
                return $0.distanceMeters <= dynamicEventRadius
            }
            if $0.event.severity.priority != $1.event.severity.priority {
                return $0.event.severity.priority < $1.event.severity.priority
            }
            return $0.distanceMeters < $1.distanceMeters
        }

        mapVisibleEvs.sort { $0.distance < $1.distance }
        let mapVisibleEventsList = Array(mapVisibleEvs.prefix(35).map { $0.event })

        let alertEvent = candidateEvents.first { se in
            let crossTrack = GeoUtils.crossTrackDistanceMeters(distanceMeters: se.distanceMeters, relativeAngleDeg: se.relativeAngleDeg)
            return se.distanceMeters <= dynamicEventRadius &&
                   se.event.isActiveNow &&
                   (!useDirectionalFilter || (se.relativeAngleDeg <= 48.0 && crossTrack <= 95.0 && GeoUtils.isDirectionCompatible(driverBearing: currentBearingDeg, directionText: se.event.direction)))
        }

        if canAlert, let ev = alertEvent {
            voiceEngine.checkAndAnnounceRoadEvent(event: ev.event, distanceMeters: ev.distanceMeters)
        }

        let isOverspeed = nearestCam != nil && currentSpeedKmh > (nearestCam!.camera.speedLimit + voiceEngine.overspeedToleranceKmh)

        // 5. 國道5號 雪山隧道進洞前車道建議
        let distToSouthEntrance = GeoUtils.distanceMeters(lat1: currentLat, lng1: currentLng, lat2: GeoUtils.hsuehshanSouthEntranceLat, lng2: GeoUtils.hsuehshanSouthEntranceLng)
        let distToNorthEntrance = GeoUtils.distanceMeters(lat1: currentLat, lng1: currentLng, lat2: GeoUtils.hsuehshanNorthEntranceLat, lng2: GeoUtils.hsuehshanNorthEntranceLng)

        let bearingToSouth = GeoUtils.bearingDeg(lat1: currentLat, lng1: currentLng, lat2: GeoUtils.hsuehshanSouthEntranceLat, lng2: GeoUtils.hsuehshanSouthEntranceLng)
        let bearingToNorth = GeoUtils.bearingDeg(lat1: currentLat, lng1: currentLng, lat2: GeoUtils.hsuehshanNorthEntranceLat, lng2: GeoUtils.hsuehshanNorthEntranceLng)
        let angleToSouth = GeoUtils.angleDiff(currentBearingDeg, bearingToSouth)
        let angleToNorth = GeoUtils.angleDiff(currentBearingDeg, bearingToNorth)

        if insideHsuehshan {
            completedTunnelApproachDir = GeoUtils.angleDiff(currentBearingDeg, 138.0) <= 90.0 ? "S" : "N"
        } else {
            if voiceEngine.hasAnnouncedTunnel(direction: "S") && (distToSouthEntrance < 320.0 || angleToSouth > 65.0) {
                completedTunnelApproachDir = "S"
            }
            if voiceEngine.hasAnnouncedTunnel(direction: "N") && (distToNorthEntrance < 320.0 || angleToNorth > 65.0) {
                completedTunnelApproachDir = "N"
            }
        }

        if !insideHsuehshan && distToSouthEntrance > 4500.0 && distToNorthEntrance > 4500.0 {
            completedTunnelApproachDir = nil
            voiceEngine.resetTunnelAnnounced()
        }

        let isApproachingSouth = !insideHsuehshan &&
            completedTunnelApproachDir != "S" &&
            distToSouthEntrance >= 320.0 && distToSouthEntrance <= 2500.0 &&
            angleToSouth <= 48.0 &&
            GeoUtils.angleDiff(currentBearingDeg, 138.0) <= 55.0

        let isApproachingNorth = !insideHsuehshan &&
            completedTunnelApproachDir != "N" &&
            distToNorthEntrance >= 320.0 && distToNorthEntrance <= 2500.0 &&
            angleToNorth <= 48.0 &&
            (GeoUtils.angleDiff(currentBearingDeg, 320.0) <= 55.0 || GeoUtils.angleDiff(currentBearingDeg, 345.0) <= 50.0)

        let tunnelAdvice: HsuehshanAdvice?
        if isApproachingSouth {
            tunnelAdvice = HsuehshanTunnelClient.shared.getAdvice(direction: "S", distanceMeters: distToSouthEntrance, isApproaching: true)
        } else if isApproachingNorth {
            tunnelAdvice = HsuehshanTunnelClient.shared.getAdvice(direction: "N", distanceMeters: distToNorthEntrance, isApproaching: true)
        } else {
            tunnelAdvice = nil
        }

        if canAlert, let advice = tunnelAdvice {
            voiceEngine.checkAndAnnounceHsuehshanTunnel(advice: advice)
        }

        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm:ss"
        let updateTime = formatter.string(from: now)

        let newSnapshot = RadarSnapshot(
            lat: currentLat,
            lng: currentLng,
            speedKmh: currentSpeedKmh,
            bearingDeg: currentBearingDeg,
            bearingLabel: GeoUtils.compassLabel(currentBearingDeg),
            isDrivingActive: isDrivingActive,
            isSimulating: isSimulating,
            simRouteName: simRouteName,
            nearestCamera: nearestCam,
            isOverspeed: isOverspeed,
            inIntervalZone: nearestCam?.camera.type == .interval && (nearestCam?.distanceMeters ?? 9999) <= 1200.0,
            intervalAvgSpeedKmh: currentSpeedKmh,
            intervalRemainingSec: 0,
            nearestRoadEvent: alertEvent,
            nearbyEvents: Array(candidateEvents.prefix(30)),
            nearbyCameras: Array(candidateCameras.prefix(25)),
            mapVisibleCameras: mapVisibleCamerasList,
            mapVisibleEvents: mapVisibleEventsList,
            lastUpdatedTime: updateTime,
            totalCamerasCount: repository.cameras.count,
            totalActiveEventsCount: repository.roadEvents.count,
            tunnelLaneAdvice: tunnelAdvice
        )

        DispatchQueue.main.async {
            self.snapshot = newSnapshot
            self.liveActivityManager.updateRadarState(snapshot: newSnapshot)
        }
    }
}
