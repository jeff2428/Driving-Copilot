import Foundation
import CoreLocation

// MARK: - 測速照相類型
public enum CameraType: String, Codable, CaseIterable {
    case fixed = "固定測速"
    case interval = "區間測速"
    case tech = "科技執法"
    case hotspot = "經常出沒點"
    case custom = "自訂測速黑點"

    public var label: String { rawValue }
}

// MARK: - 測速照相資料結構
public struct SpeedCamera: Identifiable, Codable {
    public let id: String
    public let city: String
    public let region: String
    public let address: String
    public let dept: String
    public let lat: Double
    public let lng: Double
    public let directText: String
    public let speedLimit: Int
    public let type: CameraType
    public var isCustom: Bool = false

    public init(
        id: String,
        city: String,
        region: String,
        address: String,
        dept: String,
        lat: Double,
        lng: Double,
        directText: String,
        speedLimit: Int,
        type: CameraType,
        isCustom: Bool = false
    ) {
        self.id = id
        self.city = city
        self.region = region
        self.address = address
        self.dept = dept
        self.lat = lat
        self.lng = lng
        self.directText = directText
        self.speedLimit = speedLimit
        self.type = type
        self.isCustom = isCustom
    }
}

// MARK: - 路況與事件嚴重度
public enum EventSeverity: String, Codable, CaseIterable {
    case closure = "道路封閉/管制"
    case construction = "道路施工/縮減"
    case hazard = "交通事故/障礙"
    case congestion = "車多壅塞"

    public var label: String { rawValue }

    public var priority: Int {
        switch self {
        case .closure: return 1
        case .construction: return 2
        case .hazard: return 3
        case .congestion: return 4
        }
    }

    public var colorHex: String {
        switch self {
        case .closure: return "#FF2E4C"
        case .construction: return "#FF9F1C"
        case .hazard: return "#B5179E"
        case .congestion: return "#FACC15"
        }
    }

    public var glyph: String {
        switch self {
        case .closure: return "🚫"
        case .construction: return "🚧"
        case .hazard: return "⚠️"
        case .congestion: return "🚗"
        }
    }
}

public enum RoadCategory: String, Codable {
    case freeway = "國道高速"
    case expressway = "省道/快速道路"
    case local = "一般市區/縣道"
}

// MARK: - 警廣/公總即時路況事件
public struct RoadEvent: Identifiable, Codable {
    public var id: String { uid }
    public let uid: String
    public let roadName: String
    public let areaName: String
    public let direction: String
    public let lat: Double
    public let lng: Double
    public let rawComment: String
    public let voiceSummary: String
    public let severity: EventSeverity
    public let category: RoadCategory
    public let isCleared: Bool
    public let isActiveNow: Bool
    public let scheduleNote: String
    public let happenTime: String
    public let sourceDetail: String
    public var isCustom: Bool = false

    public init(
        uid: String,
        roadName: String,
        areaName: String,
        direction: String,
        lat: Double,
        lng: Double,
        rawComment: String,
        voiceSummary: String,
        severity: EventSeverity,
        category: RoadCategory,
        isCleared: Bool,
        isActiveNow: Bool,
        scheduleNote: String,
        happenTime: String,
        sourceDetail: String,
        isCustom: Bool = false
    ) {
        self.uid = uid
        self.roadName = roadName
        self.areaName = areaName
        self.direction = direction
        self.lat = lat
        self.lng = lng
        self.rawComment = rawComment
        self.voiceSummary = voiceSummary
        self.severity = severity
        self.category = category
        self.isCleared = isCleared
        self.isActiveNow = isActiveNow
        self.scheduleNote = scheduleNote
        self.happenTime = happenTime
        self.sourceDetail = sourceDetail
        self.isCustom = isCustom
    }
}

// MARK: - 包含幾何計算評分的測速桿與路況
public struct ScoredCamera: Identifiable {
    public var id: String { camera.id }
    public let camera: SpeedCamera
    public let distanceMeters: Double
    public let relativeAngleDeg: Double
    public let crossTrackMeters: Double

    public init(camera: SpeedCamera, distanceMeters: Double, relativeAngleDeg: Double) {
        self.camera = camera
        self.distanceMeters = distanceMeters
        self.relativeAngleDeg = relativeAngleDeg
        self.crossTrackMeters = GeoUtils.crossTrackDistanceMeters(distanceMeters: distanceMeters, relativeAngleDeg: relativeAngleDeg)
    }
}

public struct ScoredRoadEvent: Identifiable {
    public var id: String { event.uid }
    public let event: RoadEvent
    public let distanceMeters: Double
    public let relativeAngleDeg: Double
    public let crossTrackMeters: Double

    public init(event: RoadEvent, distanceMeters: Double, relativeAngleDeg: Double) {
        self.event = event
        self.distanceMeters = distanceMeters
        self.relativeAngleDeg = relativeAngleDeg
        self.crossTrackMeters = GeoUtils.crossTrackDistanceMeters(distanceMeters: distanceMeters, relativeAngleDeg: relativeAngleDeg)
    }
}

// MARK: - 雪山隧道即時車道建議
public struct HsuehshanAdvice: Codable {
    public let direction: String // "S" or "N"
    public let directionLabel: String // "南下 (往宜蘭)" or "北上 (往台北)"
    public let towards: String
    public let distanceToEntranceMeters: Double
    public let recommendedLane: String // "outer" or "inner"
    public let recommendedLaneName: String // "外側車道" or "內側車道"
    public let recommendedLaneSide: String // "右線" or "左線"
    public let reason: String
    public let diffKmh: Int
    public let innerSpeedKmh: Int?
    public let outerSpeedKmh: Int?
    public let innerTravelMin: Double?
    public let outerTravelMin: Double?
    public let tunnelTravelMin: Double?
    public let alertEvent: String?
    public let isApproachingEntrance: Bool
    public let dataTime: String

    public init(
        direction: String = "S",
        directionLabel: String = "南下 (往宜蘭)",
        towards: String = "往宜蘭",
        distanceToEntranceMeters: Double = 0.0,
        recommendedLane: String = "outer",
        recommendedLaneName: String = "外側車道",
        recommendedLaneSide: String = "右線",
        reason: String = "外側車道較順暢",
        diffKmh: Int = 0,
        innerSpeedKmh: Int? = nil,
        outerSpeedKmh: Int? = nil,
        innerTravelMin: Double? = nil,
        outerTravelMin: Double? = nil,
        tunnelTravelMin: Double? = 8.5,
        alertEvent: String? = nil,
        isApproachingEntrance: Bool = false,
        dataTime: String = "--:--"
    ) {
        self.direction = direction
        self.directionLabel = directionLabel
        self.towards = towards
        self.distanceToEntranceMeters = distanceToEntranceMeters
        self.recommendedLane = recommendedLane
        self.recommendedLaneName = recommendedLaneName
        self.recommendedLaneSide = recommendedLaneSide
        self.reason = reason
        self.diffKmh = diffKmh
        self.innerSpeedKmh = innerSpeedKmh
        self.outerSpeedKmh = outerSpeedKmh
        self.innerTravelMin = innerTravelMin
        self.outerTravelMin = outerTravelMin
        self.tunnelTravelMin = tunnelTravelMin
        self.alertEvent = alertEvent
        self.isApproachingEntrance = isApproachingEntrance
        self.dataTime = dataTime
    }
}

// MARK: - 雷達全域狀態快照
public struct RadarSnapshot {
    public var lat: Double = 24.1605
    public var lng: Double = 120.6311
    public var speedKmh: Int = 0
    public var bearingDeg: Double = 0.0
    public var bearingLabel: String = "北"
    public var isDrivingActive: Bool = false
    public var isSimulating: Bool = false
    public var simRouteName: String = ""
    public var nearestCamera: ScoredCamera? = nil
    public var isOverspeed: Bool = false
    public var inIntervalZone: Bool = false
    public var intervalAvgSpeedKmh: Int = 0
    public var intervalRemainingSec: Int = 0
    public var nearestRoadEvent: ScoredRoadEvent? = nil
    public var nearbyEvents: [ScoredRoadEvent] = []
    public var nearbyCameras: [ScoredCamera] = []
    public var mapVisibleCameras: [SpeedCamera] = []
    public var mapVisibleEvents: [RoadEvent] = []
    public var lastUpdatedTime: String = "--:--"
    public var totalCamerasCount: Int = 0
    public var totalActiveEventsCount: Int = 0
    public var tunnelLaneAdvice: HsuehshanAdvice? = nil

    public init(
        lat: Double = 24.1605,
        lng: Double = 120.6311,
        speedKmh: Int = 0,
        bearingDeg: Double = 0.0,
        bearingLabel: String = "北",
        isDrivingActive: Bool = false,
        isSimulating: Bool = false,
        simRouteName: String = "",
        nearestCamera: ScoredCamera? = nil,
        isOverspeed: Bool = false,
        inIntervalZone: Bool = false,
        intervalAvgSpeedKmh: Int = 0,
        intervalRemainingSec: Int = 0,
        nearestRoadEvent: ScoredRoadEvent? = nil,
        nearbyEvents: [ScoredRoadEvent] = [],
        nearbyCameras: [ScoredCamera] = [],
        mapVisibleCameras: [SpeedCamera] = [],
        mapVisibleEvents: [RoadEvent] = [],
        lastUpdatedTime: String = "--:--",
        totalCamerasCount: Int = 0,
        totalActiveEventsCount: Int = 0,
        tunnelLaneAdvice: HsuehshanAdvice? = nil
    ) {
        self.lat = lat
        self.lng = lng
        self.speedKmh = speedKmh
        self.bearingDeg = bearingDeg
        self.bearingLabel = bearingLabel
        self.isDrivingActive = isDrivingActive
        self.isSimulating = isSimulating
        self.simRouteName = simRouteName
        self.nearestCamera = nearestCamera
        self.isOverspeed = isOverspeed
        self.inIntervalZone = inIntervalZone
        self.intervalAvgSpeedKmh = intervalAvgSpeedKmh
        self.intervalRemainingSec = intervalRemainingSec
        self.nearestRoadEvent = nearestRoadEvent
        self.nearbyEvents = nearbyEvents
        self.nearbyCameras = nearbyCameras
        self.mapVisibleCameras = mapVisibleCameras
        self.mapVisibleEvents = mapVisibleEvents
        self.lastUpdatedTime = lastUpdatedTime
        self.totalCamerasCount = totalCamerasCount
        self.totalActiveEventsCount = totalActiveEventsCount
        self.tunnelLaneAdvice = tunnelLaneAdvice
    }
}

// MARK: - 高幾何數學運算核心
public struct GeoUtils {
    private static let earthRadiusM: Double = 6371000.0

    public static func distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double) -> Double {
        let dLat = (lat2 - lat1) * .pi / 180.0
        let dLng = (lng2 - lng1) * .pi / 180.0
        let a = sin(dLat / 2) * sin(dLat / 2) +
                cos(lat1 * .pi / 180.0) * cos(lat2 * .pi / 180.0) * sin(dLng / 2) * sin(dLng / 2)
        let c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return earthRadiusM * c
    }

    public static func bearingDeg(lat1: Double, lng1: Double, lat2: Double, lng2: Double) -> Double {
        let phi1 = lat1 * .pi / 180.0
        let phi2 = lat2 * .pi / 180.0
        let dLambda = (lng2 - lng1) * .pi / 180.0
        let y = sin(dLambda) * cos(phi2)
        let x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLambda)
        let deg = atan2(y, x) * 180.0 / .pi
        return (deg.truncatingRemainder(dividingBy: 360.0) + 360.0).truncatingRemainder(dividingBy: 360.0)
    }

    public static func angleDiff(_ deg1: Double, _ deg2: Double) -> Double {
        let diff = abs(deg1 - deg2).truncatingRemainder(dividingBy: 360.0)
        return diff > 180.0 ? 360.0 - diff : diff
    }

    public static func compassLabel(_ deg: Double) -> String {
        let norm = (deg.truncatingRemainder(dividingBy: 360.0) + 360.0).truncatingRemainder(dividingBy: 360.0)
        switch norm {
        case 0..<22.5, 337.5...360: return "北"
        case 22.5..<67.5: return "東北"
        case 67.5..<112.5: return "東"
        case 112.5..<157.5: return "東南"
        case 157.5..<202.5: return "南"
        case 202.5..<247.5: return "西南"
        case 247.5..<292.5: return "西"
        default: return "西北"
        }
    }

    /// 計算橫向偏離距離 (Cross-Track Distance, 公尺)
    public static func crossTrackDistanceMeters(distanceMeters: Double, relativeAngleDeg: Double) -> Double {
        return abs(distanceMeters * sin(relativeAngleDeg * .pi / 180.0))
    }

    /// 計算前向縱向距離 (Along-Track Distance, 公尺)
    public static func alongTrackDistanceMeters(distanceMeters: Double, relativeAngleDeg: Double) -> Double {
        return distanceMeters * cos(relativeAngleDeg * .pi / 180.0)
    }

    /// 國道5號 雪山隧道南下坪林端洞口 (15.2K)
    public static let hsuehshanSouthEntranceLat: Double = 24.9385
    public static let hsuehshanSouthEntranceLng: Double = 121.7121

    /// 國道5號 雪山隧道北上頭城端洞口 (28.1K)
    public static let hsuehshanNorthEntranceLat: Double = 24.8485
    public static let hsuehshanNorthEntranceLng: Double = 121.7988

    /// 判斷座標是否位於雪山隧道內部 (坪林端 15.2K 與 頭城端 28.1K 之間走廊)
    public static func isInsideHsuehshanTunnel(lat: Double, lng: Double) -> Bool {
        let aLat = 24.9365
        let aLng = 121.7142
        let bLat = 24.8512
        let bLng = 121.7960
        let dx = bLng - aLng
        let dy = bLat - aLat
        let lenSq = dx * dx + dy * dy
        if lenSq == 0.0 { return false }
        let t = ((lng - aLng) * dx + (lat - aLat) * dy) / lenSq
        if t < 0.02 || t > 0.98 { return false }
        let projLat = aLat + t * dy
        let projLng = aLng + t * dx
        let lateralDist = distanceMeters(lat1: lat, lng1: lng, lat2: projLat, lng2: projLng)
        return lateralDist <= 650.0
    }

    private struct ParsedDir {
        var singleNorth: Bool = false
        var singleSouth: Bool = false
        var singleEast: Bool = false
        var singleWest: Bool = false
        var axisNorthSouth: Bool = false
        var axisEastWest: Bool = false

        var hasSingle: Bool { singleNorth || singleSouth || singleEast || singleWest }
    }

    private static func parseDirectionText(_ text: String) -> ParsedDir {
        if text.isEmpty { return ParsedDir() }
        let isBidirNS = text.contains("南北雙向") || text.contains("南北向") || text.contains("往南北") || text == "南北"
        let isBidirEW = text.contains("東西雙向") || text.contains("東西向") || text.contains("往東西") || text == "東西"
        let isGenericBidir = text.contains("雙向") || text.contains("全線") || text.contains("全向")

        if isBidirNS { return ParsedDir(axisNorthSouth: true) }
        if isBidirEW { return ParsedDir(axisEastWest: true) }
        if isGenericBidir { return ParsedDir() }

        let goingNorth = text.contains("北上") || text.contains("往北") ||
                text.contains("南向北") || text.contains("南往北") ||
                (text.contains("北向") && !text.contains("北向南"))
        let goingSouth = text.contains("南下") || text.contains("往南") ||
                text.contains("北向南") || text.contains("北往南") ||
                (text.contains("南向") && !text.contains("南向北"))
        let goingEast = text.contains("東行") || text.contains("往東") ||
                text.contains("西向東") || text.contains("西往東") ||
                (text.contains("東向") && !text.contains("東向西"))
        let goingWest = text.contains("西行") || text.contains("往西") ||
                text.contains("東向西") || text.contains("東往西") ||
                (text.contains("西向") && !text.contains("西向東"))

        if goingNorth && goingSouth { return ParsedDir(axisNorthSouth: true) }
        if goingEast && goingWest { return ParsedDir(axisEastWest: true) }

        return ParsedDir(
            singleNorth: goingNorth,
            singleSouth: goingSouth,
            singleEast: goingEast,
            singleWest: goingWest
        )
    }

    /// 同時結合測速桿「設置地址」與「拍攝方向」進行高精度相容比對
    public static func isCameraDirectionCompatible(driverBearing: Double, address: String, directText: String) -> Bool {
        let b = (driverBearing.truncatingRemainder(dividingBy: 360.0) + 360.0).truncatingRemainder(dividingBy: 360.0)
        let dDir = parseDirectionText(directText)
        let aDir = parseDirectionText(address)

        let effective: ParsedDir
        if dDir.hasSingle {
            effective = dDir
        } else if aDir.hasSingle {
            effective = aDir
        } else if dDir.axisNorthSouth || aDir.axisNorthSouth {
            effective = ParsedDir(axisNorthSouth: true)
        } else if dDir.axisEastWest || aDir.axisEastWest {
            effective = ParsedDir(axisEastWest: true)
        } else {
            effective = ParsedDir()
        }

        let isFreeway5 = address.contains("國道五號") || address.contains("國道5號") || address.contains("雪山隧道")

        if effective.hasSingle {
            if isFreeway5 {
                if effective.singleNorth {
                    return angleDiff(b, 325.0) <= 65.0 || angleDiff(b, 0.0) <= 58.0
                }
                if effective.singleSouth {
                    return angleDiff(b, 140.0) <= 65.0 || angleDiff(b, 180.0) <= 58.0
                }
            }
            if effective.singleNorth && angleDiff(b, 0.0) <= 58.0 { return true }
            if effective.singleSouth && angleDiff(b, 180.0) <= 58.0 { return true }
            if effective.singleEast && angleDiff(b, 90.0) <= 58.0 { return true }
            if effective.singleWest && angleDiff(b, 270.0) <= 58.0 { return true }
            return false
        }

        if effective.axisNorthSouth {
            return angleDiff(b, 0.0) <= 56.0 || angleDiff(b, 180.0) <= 56.0
        }
        if effective.axisEastWest {
            return angleDiff(b, 90.0) <= 56.0 || angleDiff(b, 270.0) <= 56.0
        }

        return true
    }

    public static func isDirectionCompatible(driverBearing: Double, directionText: String) -> Bool {
        return isCameraDirectionCompatible(driverBearing: driverBearing, address: "", directText: directionText)
    }

    public static func pointToSegmentMeters(
        pLat: Double, pLng: Double,
        aLat: Double, aLng: Double,
        bLat: Double, bLng: Double
    ) -> Double {
        let dx = bLng - aLng
        let dy = bLat - aLat
        if dx == 0.0 && dy == 0.0 {
            return distanceMeters(lat1: pLat, lng1: pLng, lat2: aLat, lng2: aLng)
        }
        let t = max(0.0, min(1.0, ((pLng - aLng) * dx + (pLat - aLat) * dy) / (dx * dx + dy * dy)))
        let projLat = aLat + t * dy
        let projLng = aLng + t * dx
        return distanceMeters(lat1: pLat, lng1: pLng, lat2: projLat, lng2: projLng)
    }
}

// MARK: - 測速照相警報規則與策略 (與 Android CameraAlertPolicy 100% 對齊)
public struct CameraAlertPolicy {
    public struct Thresholds {
        public let farMeters: Double
        public let nearMeters: Double
        public let beepMeters: Double

        public init(farMeters: Double, nearMeters: Double, beepMeters: Double) {
            self.farMeters = farMeters
            self.nearMeters = nearMeters
            self.beepMeters = beepMeters
        }
    }

    private static let freewayRegex = try? NSRegularExpression(pattern: "國道[一二三四五六七八九十0-9]|國[一二三四五六七八九十0-9]|高速公路")

    public static func isFreeway(camera: SpeedCamera) -> Bool {
        let road = camera.address
        if road.contains("路口") || road.contains("機慢車") || road.contains("方向") {
            return false
        }
        if camera.city.hasPrefix("國道") {
            return true
        }
        guard let regex = freewayRegex else {
            return road.contains("國道") || road.contains("高速公路")
        }
        let range = NSRange(location: 0, length: road.utf16.count)
        return regex.firstMatch(in: road, options: [], range: range) != nil
    }

    public static func thresholds(camera: SpeedCamera, speedKmh: Int) -> Thresholds {
        if isFreeway(camera: camera) {
            return Thresholds(farMeters: 1000.0, nearMeters: 400.0, beepMeters: 550.0)
        } else if camera.speedLimit >= 80 || speedKmh >= 80 {
            return Thresholds(farMeters: 900.0, nearMeters: 380.0, beepMeters: 550.0)
        } else if camera.speedLimit >= 60 || speedKmh >= 60 {
            return Thresholds(farMeters: 600.0, nearMeters: 260.0, beepMeters: 400.0)
        } else {
            return Thresholds(farMeters: 420.0, nearMeters: 180.0, beepMeters: 280.0)
        }
    }

    public static func lockRadiusMeters(camera: SpeedCamera, speedKmh: Int) -> Double {
        let originalRadius: Double
        if camera.speedLimit >= 80 || speedKmh >= 82 {
            originalRadius = 1150.0
        } else if camera.speedLimit >= 60 || speedKmh >= 58 {
            originalRadius = 750.0
        } else {
            originalRadius = 500.0
        }
        return max(originalRadius, thresholds(camera: camera, speedKmh: speedKmh).farMeters)
    }

    public static func distanceLabel(distanceMeters: Double) -> String {
        if distanceMeters >= 1000.0 {
            return String(format: "前方 %.1f 公里", distanceMeters / 1000.0)
        } else {
            return "前方 \(max(0, Int(distanceMeters.rounded()))) 公尺"
        }
    }

    public static func message(camera: SpeedCamera, stage: Int, distanceMeters: Double, isOver: Bool) -> String {
        let meters = max(50, Int(distanceMeters / 50.0) * 50)
        let kind = camera.type == .hotspot ? "經常出沒移動式測速" : camera.type.label
        let prefix = (stage == 1) ? "注意，" : (isOver ? "警告！" : "接近，")
        let warning = (stage == 2 && isOver) ? "，您目前已超速，請立即減速！" : ""
        return "\(prefix)前方 \(meters) 公尺，\(kind)，速限 \(camera.speedLimit) 公里\(warning)"
    }
}

