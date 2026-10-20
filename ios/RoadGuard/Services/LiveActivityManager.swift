import Foundation
import Combine

#if canImport(ActivityKit)
import ActivityKit

public struct RoadGuardActivityAttributes: ActivityAttributes {
    public struct ContentState: Codable, Hashable {
        public var speedKmh: Int
        public var speedLimit: Int?
        public var isOverspeed: Bool
        public var cameraTypeLabel: String?
        public var cameraDistanceMeters: Double?
        public var hsuehshanLane: String?
        public var hsuehshanReason: String?
        public var eventWarning: String?

        public init(
            speedKmh: Int = 0,
            speedLimit: Int? = nil,
            isOverspeed: Bool = false,
            cameraTypeLabel: String? = nil,
            cameraDistanceMeters: Double? = nil,
            hsuehshanLane: String? = nil,
            hsuehshanReason: String? = nil,
            eventWarning: String? = nil
        ) {
            self.speedKmh = speedKmh
            self.speedLimit = speedLimit
            self.isOverspeed = isOverspeed
            self.cameraTypeLabel = cameraTypeLabel
            self.cameraDistanceMeters = cameraDistanceMeters
            self.hsuehshanLane = hsuehshanLane
            self.hsuehshanReason = hsuehshanReason
            self.eventWarning = eventWarning
        }
    }

    public var title: String

    public init(title: String = "神盾路況即時雷達") {
        self.title = title
    }
}
#endif

/// 即時動態與動態島 (Live Activities & Dynamic Island) 控制管理員
public final class LiveActivityManager: ObservableObject {
    public static let shared = LiveActivityManager()

    #if canImport(ActivityKit)
    private var currentActivity: Activity<RoadGuardActivityAttributes>?
    #endif

    @Published public var isLiveActivityRunning: Bool = false

    private init() {}

    public func startLiveActivity(initialSpeed: Int = 0) {
        #if canImport(ActivityKit)
        if #available(iOS 16.2, *) {
            guard ActivityAuthorizationInfo().areActivitiesEnabled else { return }

            if currentActivity != nil {
                return
            }

            let attributes = RoadGuardActivityAttributes(title: "神盾路況即時雷達")
            let initialState = RoadGuardActivityAttributes.ContentState(
                speedKmh: initialSpeed,
                speedLimit: nil,
                isOverspeed: false,
                cameraTypeLabel: nil,
                cameraDistanceMeters: nil,
                hsuehshanLane: nil,
                hsuehshanReason: nil,
                eventWarning: nil
            )

            do {
                let activity = try Activity<RoadGuardActivityAttributes>.request(
                    attributes: attributes,
                    content: .init(state: initialState, staleDate: nil),
                    pushType: nil
                )
                self.currentActivity = activity
                DispatchQueue.main.async {
                    self.isLiveActivityRunning = true
                }
            } catch {
                // 忽略錯誤
            }
        }
        #endif
    }

    public func updateRadarState(snapshot: RadarSnapshot) {
        #if canImport(ActivityKit)
        if #available(iOS 16.2, *) {
            guard let activity = currentActivity else { return }

            let nearestCam = snapshot.nearestCamera
            let tunnelAdvice = snapshot.tunnelLaneAdvice

            var hsuehshanLane: String? = nil
            var hsuehshanReason: String? = nil
            if let advice = tunnelAdvice, advice.isApproachingEntrance {
                hsuehshanLane = "建議走\(advice.recommendedLaneName)"
                hsuehshanReason = advice.reason
            }

            var eventWarning: String? = nil
            if let ev = snapshot.nearestRoadEvent {
                eventWarning = "\(ev.event.severity.glyph) \(ev.event.roadName)"
            }

            let newState = RoadGuardActivityAttributes.ContentState(
                speedKmh: snapshot.speedKmh,
                speedLimit: nearestCam?.camera.speedLimit,
                isOverspeed: snapshot.isOverspeed,
                cameraTypeLabel: nearestCam?.camera.type.label,
                cameraDistanceMeters: nearestCam?.distanceMeters,
                hsuehshanLane: hsuehshanLane,
                hsuehshanReason: hsuehshanReason,
                eventWarning: eventWarning
            )

            Task {
                await activity.update(.init(state: newState, staleDate: nil))
            }
        }
        #endif
    }

    public func endLiveActivity() {
        #if canImport(ActivityKit)
        if #available(iOS 16.2, *) {
            guard let activity = currentActivity else { return }
            Task {
                await activity.end(dismissalPolicy: .immediate)
                DispatchQueue.main.async {
                    self.currentActivity = nil
                    self.isLiveActivityRunning = false
                }
            }
        }
        #endif
    }
}
