import Foundation

/// 國道5號 雪山隧道即時車道建議客戶端
/// 資料來源：交通部高速公路局「交通資料庫」即時彙整 (https://hsuehshan-tunnel.pages.dev/api/tunnel)
public final class HsuehshanTunnelClient: @unchecked Sendable {
    public static let shared = HsuehshanTunnelClient()

    private let apiUrl = URL(string: "https://hsuehshan-tunnel.pages.dev/api/tunnel")!
    private let cacheDuration: TimeInterval = 25.0 // 快取 25 秒
    private let syncQueue = DispatchQueue(label: "tw.roadguard.hsuehshan.sync")

    private var cachedReport: [String: Any]?
    private var lastFetchTimestamp: Date = .distantPast
    private var isFetching: Bool = false

    private init() {}

    /// 背景非同步預熱或更新雪隧最新車流與車道數據
    public func refreshAsync(force: Bool = false, completion: ((Bool) -> Void)? = nil) {
        var shouldFetch = false
        syncQueue.sync {
            let now = Date()
            if !force && cachedReport != nil && (now.timeIntervalSince(lastFetchTimestamp) < cacheDuration) {
                shouldFetch = false
                return
            }
            if isFetching {
                shouldFetch = false
                return
            }
            isFetching = true
            shouldFetch = true
        }

        guard shouldFetch else {
            completion?(cachedReport != nil)
            return
        }

        Task.detached { [weak self] in
            guard let self = self else { return }
            let success = await self.fetchSync()
            self.syncQueue.sync {
                self.isFetching = false
            }
            completion?(success)
        }
    }

    private func fetchSync() async -> Bool {
        var request = URLRequest(url: apiUrl, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 4.0)
        request.httpMethod = "GET"
        request.setValue("RoadGuard-Aegis-iOS/2.0", forHTTPHeaderField: "User-Agent")
        request.setValue("application/json", forHTTPHeaderField: "Accept")

        do {
            let (data, response) = try await URLSession.shared.data(for: request)
            guard let httpResponse = response as? HTTPURLResponse, httpResponse.statusCode == 200 else {
                return false
            }
            if let json = try JSONSerialization.jsonObject(with: data) as? [String: Any] {
                syncQueue.sync {
                    self.cachedReport = json
                    self.lastFetchTimestamp = Date()
                }
                return true
            }
        } catch {
            // 忽略網路錯誤，使用降級資料
        }
        return false
    }

    /// 取得指定行向 (S=南下往宜蘭, N=北上往台北) 的雪隧即時車道建議
    public func getAdvice(
        direction: String,
        distanceMeters: Double,
        isApproaching: Bool = false
    ) -> HsuehshanAdvice {
        let dirKey = direction.uppercased() == "N" ? "N" : "S"
        let dirLabel = dirKey == "N" ? "北上 (往台北)" : "南下 (往宜蘭)"
        let towards = dirKey == "N" ? "往台北" : "往宜蘭"

        var root: [String: Any]?
        var shouldRefresh = false

        syncQueue.sync {
            shouldRefresh = Date().timeIntervalSince(lastFetchTimestamp) > cacheDuration
            root = cachedReport
        }

        if shouldRefresh {
            refreshAsync()
        }

        guard let root = root else {
            return fallbackAdvice(dirKey: dirKey, dirLabel: dirLabel, towards: towards, distanceMeters: distanceMeters, isApproaching: isApproaching)
        }

        guard let dirObj = root[dirKey] as? [String: Any] else {
            return fallbackAdvice(dirKey: dirKey, dirLabel: dirLabel, towards: towards, distanceMeters: distanceMeters, isApproaching: isApproaching)
        }

        let recObj = dirObj["recommend"] as? [String: Any]
        let innerObj = dirObj["inner"] as? [String: Any]
        let outerObj = dirObj["outer"] as? [String: Any]

        let recLane = (recObj?["lane"] as? String) ?? "outer"
        let recName = recLane == "inner" ? "內側車道" : "外側車道"
        let recSide = recLane == "inner" ? "左線" : "右線"
        var reason = (recObj?["reason"] as? String) ?? "行車較順暢"

        let diffKmh = (recObj?["diffKmh"] as? Int) ?? 0
        if diffKmh >= 3 {
            reason = "時速快 \(diffKmh) 公里"
        }

        let innerSpeed = innerObj?["avgSpeed"] as? Int
        let outerSpeed = outerObj?["avgSpeed"] as? Int

        let innerMin = (innerObj?["travelMin"] as? NSNumber)?.doubleValue
        let outerMin = (outerObj?["travelMin"] as? NSNumber)?.doubleValue

        let chosenMin = recLane == "inner" ? innerMin : outerMin
        let travelMin = chosenMin ?? 8.5

        // 檢查隧道內是否有嚴重事故路況
        var alertEvent: String? = nil
        if let evList = root["events"] as? [[String: Any]] {
            for ev in evList {
                let evDir = (ev["direction"] as? String) ?? ""
                let inTunnel = (ev["inTunnel"] as? Bool) ?? false
                let isConstruction = (ev["isConstruction"] as? Bool) ?? false
                if inTunnel && (evDir == dirKey || evDir == "B") && !isConstruction {
                    alertEvent = ev["description"] as? String
                    break
                }
            }
        }

        var dataTime = (dirObj["dataTime"] as? String) ?? "--:--"
        if dataTime.count >= 16 {
            let start = dataTime.index(dataTime.startIndex, offsetBy: 11)
            let end = dataTime.index(dataTime.startIndex, offsetBy: 16)
            dataTime = String(dataTime[start..<end])
        }

        return HsuehshanAdvice(
            direction: dirKey,
            directionLabel: dirLabel,
            towards: towards,
            distanceToEntranceMeters: distanceMeters,
            recommendedLane: recLane,
            recommendedLaneName: recName,
            recommendedLaneSide: recSide,
            reason: reason,
            diffKmh: diffKmh,
            innerSpeedKmh: innerSpeed,
            outerSpeedKmh: outerSpeed,
            innerTravelMin: innerMin,
            outerTravelMin: outerMin,
            tunnelTravelMin: travelMin,
            alertEvent: alertEvent,
            isApproachingEntrance: isApproaching,
            dataTime: dataTime
        )
    }

    private func fallbackAdvice(
        dirKey: String,
        dirLabel: String,
        towards: String,
        distanceMeters: Double,
        isApproaching: Bool
    ) -> HsuehshanAdvice {
        return HsuehshanAdvice(
            direction: dirKey,
            directionLabel: dirLabel,
            towards: towards,
            distanceToEntranceMeters: distanceMeters,
            recommendedLane: "outer",
            recommendedLaneName: "外側車道",
            recommendedLaneSide: "右線",
            reason: "雙白線禁止變換車道，請依現場車流選定車道",
            diffKmh: 0,
            innerSpeedKmh: nil,
            outerSpeedKmh: nil,
            innerTravelMin: nil,
            outerTravelMin: nil,
            tunnelTravelMin: 8.5,
            alertEvent: nil,
            isApproachingEntrance: isApproaching,
            dataTime: "--:--"
        )
    }
}
