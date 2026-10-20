import SwiftUI

@main
struct RoadGuardApp: App {
    @StateObject private var radarEngine = RadarEngine.shared

    init() {
        // 預熱圖資與雪隧客戶端
        OpenDataRepository.shared.initializeFromLocalAssets()
        HsuehshanTunnelClient.shared.refreshAsync()
    }

    var body: some Scene {
        WindowGroup {
            MainView()
                .preferredColorScheme(.dark) // 專為行車與夜間儀表設計之深色高對比主題
        }
    }
}
