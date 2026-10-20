import Foundation
import CoreLocation
import Combine

/// CoreLocation 背景導航高精度定位管理員
public final class RoadGuardLocationManager: NSObject, ObservableObject, CLLocationManagerDelegate {
    public static let shared = RoadGuardLocationManager()

    private let locationManager = CLLocationManager()

    @Published public var currentLat: Double = 24.1605
    @Published public var currentLng: Double = 120.6311
    @Published public var currentSpeedKmh: Int = 0
    @Published public var currentBearingDeg: Double = 0.0
    @Published public var hasValidBearing: Bool = false
    @Published public var isAuthorized: Bool = false
    @Published public var authorizationStatus: CLAuthorizationStatus = .notDetermined

    public var onLocationUpdate: ((Double, Double, Int, Double) -> Void)?

    override private init() {
        super.init()
        locationManager.delegate = self
        locationManager.desiredAccuracy = kCLLocationAccuracyBestForNavigation
        locationManager.activityType = .automotiveNavigation
        locationManager.distanceFilter = 1.0 // 每移動 1 公尺回報一次

        // 支援 iOS 背景持續導航定位
        locationManager.allowsBackgroundLocationUpdates = true
        locationManager.pausesLocationUpdatesAutomatically = false
        locationManager.showsBackgroundLocationIndicator = true

        self.authorizationStatus = locationManager.authorizationStatus
        self.isAuthorized = (authorizationStatus == .authorizedAlways || authorizationStatus == .authorizedWhenInUse)
    }

    public func requestPermissions() {
        if authorizationStatus == .notDetermined {
            locationManager.requestWhenInUseAuthorization()
            locationManager.requestAlwaysAuthorization()
        }
    }

    public func startUpdating() {
        requestPermissions()
        locationManager.startUpdatingLocation()
        locationManager.startUpdatingHeading()
    }

    public func stopUpdating() {
        locationManager.stopUpdatingLocation()
        locationManager.stopUpdatingHeading()
    }

    // MARK: - CLLocationManagerDelegate

    public func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        let status = manager.authorizationStatus
        self.authorizationStatus = status
        self.isAuthorized = (status == .authorizedAlways || status == .authorizedWhenInUse)
        if isAuthorized {
            locationManager.startUpdatingLocation()
        }
    }

    public func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let location = locations.last else { return }
        let lat = location.coordinate.latitude
        let lng = location.coordinate.longitude

        // 速度轉換：m/s 轉 km/h
        let rawSpeed = location.speed
        let speedKmh = rawSpeed > 0 ? Int(rawSpeed * 3.6) : 0

        // 航向角更新：低速 (<= 3 km/h) 或停等紅燈時保留最後有效車頭方向，避免方向歸零或晃動
        var bearing = currentBearingDeg
        if location.course >= 0 && (speedKmh > 3 || !hasValidBearing) {
            bearing = location.course
            hasValidBearing = true
        }

        self.currentLat = lat
        self.currentLng = lng
        self.currentSpeedKmh = speedKmh
        self.currentBearingDeg = bearing

        onLocationUpdate?(lat, lng, speedKmh, bearing)
    }

    public func locationManager(_ manager: CLLocationManager, didUpdateHeading newHeading: CLHeading) {
        // 當車輛靜止且 GPS course 無效時，可輔助使用電子羅盤真北航向
        if currentSpeedKmh <= 3 && newHeading.trueHeading >= 0 && !hasValidBearing {
            self.currentBearingDeg = newHeading.trueHeading
            self.hasValidBearing = true
            onLocationUpdate?(currentLat, currentLng, currentSpeedKmh, currentBearingDeg)
        }
    }

    public func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        // 定位失敗時靜默忽略或記錄
    }
}
