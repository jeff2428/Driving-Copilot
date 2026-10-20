import Foundation
import Combine

/// 臺灣全區開放資料庫（測速照相、警廣即時路況、雲端同步與自訂點位）
public final class OpenDataRepository: ObservableObject {
    public static let shared = OpenDataRepository()

    private let pbsOpenDataUrl = "https://rtr.pbs.gov.tw/NMP103_PbsWS/resources/roadData/opendata"
    private let npaCamerasCsvUrl = "https://opdadm.moi.gov.tw/api/v1/no-auth/resource/api/dataset/EA5E6FCD-B82D-43B7-A5CF-E9893253187E/resource/AA22AE1B-D30A-4B93-83E4-5ABB9D5CB989/download"

    private let cacheCamerasFilename = "cameras_cache.csv"
    private let cacheRoadEventsFilename = "road_events_cache.json"
    private let cacheManifestFilename = "manifest_cache.json"

    private let defaultRepo = "jeff2428/Driving-Copilot"
    private let userDefaults = UserDefaults.standard

    @Published public var cameras: [SpeedCamera] = []
    @Published public var roadEvents: [RoadEvent] = []
    @Published public var customCameras: [SpeedCamera] = []
    @Published public var customRoadEvents: [RoadEvent] = []

    @Published public var lastSyncTimeStr: String = "載入中..."
    @Published public var lastCameraSyncDateStr: String = "內建題庫 (可線上更新)"
    @Published public var dataVersionStr: String = "2026.10.17"
    @Published public var lastSyncChannelStr: String = "內建警政署＋警廣圖資"

    public var autoDailyUpdateEnabled: Bool {
        get { userDefaults.object(forKey: "auto_daily_update") as? Bool ?? true }
        set { userDefaults.set(newValue, forKey: "auto_daily_update") }
    }

    public var githubRepoSlug: String {
        get {
            let val = userDefaults.string(forKey: "github_repo_slug")?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            return val.isEmpty ? defaultRepo : val
        }
        set { userDefaults.set(newValue.trimmingCharacters(in: .whitespacesAndNewlines), forKey: "github_repo_slug") }
    }

    private var documentsDirectory: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
    }

    private init() {
        initializeFromLocalAssets()
    }

    /// 初始化載入本機內建或快取的資料
    public func initializeFromLocalAssets() {
        loadManifestVersion()
        loadCamerasFromAssetOrCache()
        loadRoadEventsFromAssetOrCache()
    }

    private func loadManifestVersion() {
        let cacheManifestUrl = documentsDirectory.appendingPathComponent(cacheManifestFilename)
        let data: Data?
        if FileManager.default.fileExists(atPath: cacheManifestUrl.path),
           let cachedData = try? Data(contentsOf: cacheManifestUrl) {
            data = cachedData
        } else if let bundleUrl = Bundle.main.url(forResource: "cloud_manifest", withExtension: "json"),
                  let bundleData = try? Data(contentsOf: bundleUrl) {
            data = bundleData
        } else {
            data = nil
        }

        if let data = data,
           let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
            if let ver = obj["version"] as? String, !ver.isEmpty {
                self.dataVersionStr = ver
            }
            if let updated = obj["updatedAt"] as? String, !updated.isEmpty {
                self.lastCameraSyncDateStr = updated
            }
        }
    }

    private func loadCamerasFromAssetOrCache() {
        let cacheUrl = documentsDirectory.appendingPathComponent(cacheCamerasFilename)
        var lines: [String] = []

        if FileManager.default.fileExists(atPath: cacheUrl.path),
           let content = try? String(contentsOf: cacheUrl, encoding: .utf8),
           content.count > 10_000 {
            let formatter = DateFormatter()
            formatter.dateFormat = "yyyy/MM/dd HH:mm"
            if let attrs = try? FileManager.default.attributesOfItem(atPath: cacheUrl.path),
               let modDate = attrs[.modificationDate] as? Date {
                self.lastCameraSyncDateStr = formatter.string(from: modDate)
            }
            lines = content.components(separatedBy: .newlines)
        } else if let bundleUrl = Bundle.main.url(forResource: "taiwan_cameras_seed", withExtension: "csv"),
                  let content = try? String(contentsOf: bundleUrl, encoding: .utf8) {
            lines = content.components(separatedBy: .newlines)
        }

        let parsed = parseCamerasCsv(lines: lines)
        if !parsed.isEmpty {
            DispatchQueue.main.async {
                self.cameras = parsed + self.customCameras
            }
        }
    }

    private func loadRoadEventsFromAssetOrCache() {
        let cacheUrl = documentsDirectory.appendingPathComponent(cacheRoadEventsFilename)
        var jsonStr: String = ""

        if FileManager.default.fileExists(atPath: cacheUrl.path),
           let content = try? String(contentsOf: cacheUrl, encoding: .utf8),
           content.count > 1_000 {
            jsonStr = content
        } else if let bundleUrl = Bundle.main.url(forResource: "taiwan_road_events_seed", withExtension: "json"),
                  let content = try? String(contentsOf: bundleUrl, encoding: .utf8) {
            jsonStr = content
        }

        let parsed = parsePbsRoadEventsJson(jsonStr: jsonStr)
        if !parsed.isEmpty {
            DispatchQueue.main.async {
                self.roadEvents = self.customRoadEvents + parsed
                let formatter = DateFormatter()
                formatter.dateFormat = "HH:mm:ss"
                self.lastSyncTimeStr = formatter.string(from: Date())
            }
        }
    }

    /// 雙通道雲端自動同步
    public func syncAllCloudData(forceCameras: Bool = true) async -> (Bool, String) {
        let slug = githubRepoSlug.trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "https://github.com/", with: "")
            .trimmingCharacters(in: CharacterSet(charactersIn: "/"))

        if slug.contains("/") {
            let cdnCandidates = [
                "https://cdn.jsdelivr.net/gh/\(slug)@main/cloud_data",
                "https://raw.githubusercontent.com/\(slug)/main/cloud_data"
            ]

            for base in cdnCandidates {
                if let manifestText = await fetchUrlText("\(base)/manifest.json", timeout: 10.0),
                   let manifestData = manifestText.data(using: .utf8),
                   let manifest = try? JSONSerialization.jsonObject(with: manifestData) as? [String: Any],
                   let csvText = await fetchUrlText("\(base)/taiwan_cameras_latest.csv", timeout: 15.0),
                   let evText = await fetchUrlText("\(base)/taiwan_road_events_latest.json", timeout: 15.0) {

                    let parsedCams = parseCamerasCsv(lines: csvText.components(separatedBy: .newlines))
                    let parsedEvs = parsePbsRoadEventsJson(jsonStr: evText)

                    if parsedCams.count > 500 && !parsedEvs.isEmpty {
                        let manifestUrl = documentsDirectory.appendingPathComponent(cacheManifestFilename)
                        let camUrl = documentsDirectory.appendingPathComponent(cacheCamerasFilename)
                        let evUrl = documentsDirectory.appendingPathComponent(cacheRoadEventsFilename)

                        try? manifestText.write(to: manifestUrl, atomically: true, encoding: .utf8)
                        try? csvText.write(to: camUrl, atomically: true, encoding: .utf8)
                        try? evText.write(to: evUrl, atomically: true, encoding: .utf8)

                        let remoteVer = (manifest["version"] as? String) ?? ""
                        let now = Date()
                        let timeFormatter = DateFormatter()
                        timeFormatter.dateFormat = "HH:mm:ss"

                        await MainActor.run {
                            self.dataVersionStr = remoteVer.isEmpty ? "最新雲端" : remoteVer
                            self.lastCameraSyncDateStr = (manifest["updatedAt"] as? String) ?? self.dataVersionStr
                            self.lastSyncTimeStr = timeFormatter.string(from: now)
                            self.lastSyncChannelStr = "GitHub Actions CDN (\(slug))"
                            self.cameras = parsedCams + self.customCameras
                            self.roadEvents = self.customRoadEvents + parsedEvs
                        }
                        return (true, "已從 GitHub 雲端同步 v\(self.dataVersionStr)（測速 \(parsedCams.count) 支・路況 \(parsedEvs.count) 筆）")
                    }
                }
            }
        }

        // 通道 B：直連警政署與警廣 Open Data
        let evOk = await refreshLiveRoadEventsAsync()
        let camOk = forceCameras ? await refreshLiveCamerasAsync() : true
        if evOk || camOk {
            let formatter = DateFormatter()
            formatter.dateFormat = "yyyy.MM.dd.HHmm"
            let nowStr = formatter.string(from: Date())
            await MainActor.run {
                self.dataVersionStr = nowStr
                self.lastSyncChannelStr = "內政部警政署＋警廣 OpenData 直連"
            }
            return (true, "已同步最新圖資 v\(nowStr)（測速 \(cameras.count) 支・路況 \(roadEvents.count) 筆）")
        }

        return (false, "目前使用離線快取圖資 v\(dataVersionStr)（測速 \(cameras.count) 支・路況 \(roadEvents.count) 筆）")
    }

    private func fetchUrlText(_ urlStr: String, timeout: TimeInterval) async -> String? {
        guard let url = URL(string: urlStr) else { return nil }
        var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: timeout)
        request.httpMethod = "GET"
        request.setValue("Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) RoadGuard-Aegis-iOS/2.0", forHTTPHeaderField: "User-Agent")

        do {
            let (data, response) = try await URLSession.shared.data(for: request)
            guard let httpResponse = response as? HTTPURLResponse, httpResponse.statusCode == 200 else {
                return nil
            }
            return String(data: data, encoding: .utf8)
        } catch {
            return nil
        }
    }

    public func refreshLiveRoadEventsAsync() async -> Bool {
        guard let jsonText = await fetchUrlText(pbsOpenDataUrl, timeout: 12.0) else { return false }
        if jsonText.contains("\"result\"") {
            let cacheUrl = documentsDirectory.appendingPathComponent(cacheRoadEventsFilename)
            try? jsonText.write(to: cacheUrl, atomically: true, encoding: .utf8)
            let parsed = parsePbsRoadEventsJson(jsonStr: jsonText)
            if !parsed.isEmpty {
                let formatter = DateFormatter()
                formatter.dateFormat = "HH:mm:ss"
                let timeStr = formatter.string(from: Date())
                await MainActor.run {
                    self.roadEvents = self.customRoadEvents + parsed
                    self.lastSyncTimeStr = timeStr
                }
                return true
            }
        }
        return false
    }

    public func refreshLiveCamerasAsync() async -> Bool {
        guard let csvText = await fetchUrlText(npaCamerasCsvUrl, timeout: 15.0) else { return false }
        let lines = csvText.components(separatedBy: .newlines)
        let parsed = parseCamerasCsv(lines: lines)
        if parsed.count > 500 {
            let cacheUrl = documentsDirectory.appendingPathComponent(cacheCamerasFilename)
            try? csvText.write(to: cacheUrl, atomically: true, encoding: .utf8)
            let formatter = DateFormatter()
            formatter.dateFormat = "yyyy/MM/dd HH:mm"
            let timeStr = formatter.string(from: Date())
            await MainActor.run {
                self.lastCameraSyncDateStr = timeStr
                self.cameras = parsed + self.customCameras
            }
            return true
        }
        return false
    }

    public func parseCamerasCsv(lines: [String]) -> [SpeedCamera] {
        var result: [SpeedCamera] = []
        result.reserveCapacity(2500)
        var index = 0

        for rawLine in lines {
            let line = rawLine.trimmingCharacters(in: .whitespacesAndNewlines)
            if line.isEmpty { continue }
            if line.hasPrefix("\u{FEFF}CityName") || line.hasPrefix("CityName") || line.hasPrefix("設置縣市") {
                continue
            }
            let cols = line.components(separatedBy: ",")
            if cols.count < 9 { continue }
            let city = cols[0].trimmingCharacters(in: .whitespaces)
            let region = cols[1].trimmingCharacters(in: .whitespaces)
            let address = cols[2].trimmingCharacters(in: .whitespaces)
            let dept = cols[3].trimmingCharacters(in: .whitespaces)
            guard let lng = Double(cols[5].trimmingCharacters(in: .whitespaces)),
                  let lat = Double(cols[6].trimmingCharacters(in: .whitespaces)) else { continue }
            if lat < 21.5 || lat > 26.5 || lng < 118.0 || lng > 122.5 { continue }

            let directText = cols[7].trimmingCharacters(in: .whitespaces)
            let limitDigits = cols[8].filter { $0.isNumber }
            let limitVal = Int(limitDigits) ?? 60
            let speedLimit = max(20, min(120, limitVal))

            let cameraType: CameraType
            if address.contains("區間") || directText.contains("區間") {
                cameraType = .interval
            } else if address.contains("科技執法") || directText.contains("科技執法") || address.contains("不停讓") {
                cameraType = .tech
            } else if address.contains("經常出沒") || directText.contains("經常出沒") ||
                      address.contains("移動") || directText.contains("移動") ||
                      address.contains("流動") || directText.contains("流動") ||
                      address.contains("三腳架") || directText.contains("三腳架") ||
                      address.contains("雷射") || directText.contains("雷射") {
                cameraType = .hotspot
            } else {
                cameraType = .fixed
            }

            index += 1
            result.append(
                SpeedCamera(
                    id: "NPA-\(index)",
                    city: city,
                    region: region,
                    address: address,
                    dept: dept,
                    lat: lat,
                    lng: lng,
                    directText: directText,
                    speedLimit: speedLimit,
                    type: cameraType
                )
            )
        }
        return result
    }

    public func parsePbsRoadEventsJson(jsonStr: String) -> [RoadEvent] {
        guard let startIdx = jsonStr.firstIndex(of: "{") else { return [] }
        let validJsonStr = String(jsonStr[startIdx...])
        guard let data = validJsonStr.data(using: .utf8),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let arr = root["result"] as? [[String: Any]] else {
            return []
        }

        var list: [RoadEvent] = []
        list.reserveCapacity(1000)

        let calendar = Calendar(identifier: .gregorian)
        let currentHour = calendar.component(.hour, from: Date())

        for (i, item) in arr.enumerated() {
            let uid = (item["UID"] as? String) ?? "PBS-\(i)"
            let roadType = ((item["roadtype"] as? String) ?? "").trimmingCharacters(in: .whitespaces)
            let areaNm = ((item["areaNm"] as? String) ?? "").trimmingCharacters(in: .whitespaces)
            let road = ((item["road"] as? String) ?? "").trimmingCharacters(in: .whitespaces)
            let direction = ((item["direction"] as? String) ?? "").trimmingCharacters(in: .whitespaces)
            let rawComment = ((item["comment"] as? String) ?? "")
                .replacingOccurrences(of: "<br>", with: " ")
                .replacingOccurrences(of: "<br/>", with: " ")
                .trimmingCharacters(in: .whitespacesAndNewlines)
            let srcDetail = ((item["srcdetail"] as? String) ?? "").trimmingCharacters(in: .whitespaces)
            let happenTime = String(((item["happentime"] as? String) ?? "").prefix(5))

            let x1Str = (item["x1"] as? String) ?? ""
            let y1Str = (item["y1"] as? String) ?? ""
            guard let lng = Double(x1Str.trimmingCharacters(in: .whitespaces)),
                  let lat = Double(y1Str.trimmingCharacters(in: .whitespaces)) else { continue }
            if lat < 21.5 || lat > 26.5 || lng < 118.0 || lng > 122.5 { continue }

            let isCleared = rawComment.contains("已排除") ||
                            rawComment.contains(":排除") ||
                            rawComment.contains("：排除") ||
                            rawComment.contains("自行駛離") ||
                            rawComment.contains("恢復通行") ||
                            rawComment.contains("解除管制") ||
                            rawComment.contains("狀況排除")

            let combinedText = "\(roadType) \(areaNm) \(road) \(rawComment)"
            let severity: EventSeverity
            if combinedText.contains("封閉") ||
               combinedText.contains("管制") ||
               combinedText.contains("阻斷") ||
               combinedText.contains("禁止通行") ||
               combinedText.contains("全線封閉") ||
               combinedText.contains("單線雙向") ||
               roadType == "交通管制" ||
               roadType == "災變" {
                severity = .closure
            } else if roadType == "道路施工" ||
                      combinedText.contains("施工") ||
                      combinedText.contains("緩撞車") ||
                      combinedText.contains("車道縮減") ||
                      combinedText.contains("工程") ||
                      combinedText.contains("銑鋪") ||
                      combinedText.contains("養護") {
                severity = .construction
            } else if roadType == "事故" ||
                      roadType == "交通障礙" ||
                      roadType == "號誌故障" ||
                      combinedText.contains("事故") ||
                      combinedText.contains("散落") ||
                      combinedText.contains("掉落") ||
                      combinedText.contains("故障車") {
                severity = .hazard
            } else {
                severity = .congestion
            }

            let roadTitle: String
            if !road.isEmpty && !areaNm.isEmpty {
                roadTitle = "\(areaNm) \(road)"
            } else if !areaNm.isEmpty {
                roadTitle = areaNm
            } else if !road.isEmpty {
                roadTitle = road
            } else {
                roadTitle = "一般路段"
            }

            let category: RoadCategory
            if roadTitle.contains("國道") || roadTitle.contains("高速公路") || roadTitle.contains("五楊") || roadTitle.contains("雪山隧道") {
                category = .freeway
            } else if roadTitle.contains("台") || roadTitle.contains("快速") || roadTitle.contains("西濱") || roadTitle.contains("公路") {
                category = .expressway
            } else {
                category = .local
            }

            let isActiveNow = true
            let scheduleNote = "目前管制中"

            // 語音摘要
            let dirPrompt = direction.isEmpty || direction == "雙向" ? "" : "\(direction)"
            let voiceSummary = "\(roadTitle)\(dirPrompt)，\(severity.label)：\(rawComment.prefix(45))"

            list.append(
                RoadEvent(
                    uid: uid,
                    roadName: roadTitle,
                    areaName: areaNm,
                    direction: direction,
                    lat: lat,
                    lng: lng,
                    rawComment: rawComment,
                    voiceSummary: voiceSummary,
                    severity: severity,
                    category: category,
                    isCleared: isCleared,
                    isActiveNow: isActiveNow,
                    scheduleNote: scheduleNote,
                    happenTime: happenTime.isEmpty ? "--:--" : happenTime,
                    sourceDetail: srcDetail.isEmpty ? "警廣即時路況" : srcDetail
                )
            )
        }
        return list
    }
}
