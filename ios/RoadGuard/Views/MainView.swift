import SwiftUI

/// 神盾路況行車助手 主行車儀表與地圖介面
public struct MainView: View {
    @ObservedObject var radarEngine = RadarEngine.shared
    @ObservedObject var locationManager = RoadGuardLocationManager.shared
    @ObservedObject var voiceEngine = VoiceAlertEngine.shared

    @State private var showSettings: Bool = false
    @State private var showTunnelSheet: Bool = false
    @State private var showSimSheet: Bool = false

    public init() {}

    public var body: some View {
        ZStack {
            // 底層：Leaflet 全台即時路況地圖
            RadarMapView(radarEngine: radarEngine)
                .edgesIgnoringSafeArea(.all)

            // 頂層儀表與預警覆蓋層
            VStack(spacing: 0) {
                // 1. 頂部預警橫幅 (測速照相、施工封路、雪隧進洞引導)
                topAlertBanner
                    .padding(.horizontal, 16)
                    .padding(.top, 8)

                Spacer()

                // 2. 中央儀表板 (GPS 時速、速限圓牌、超速光環)
                centralSpeedometer
                    .padding(.bottom, 16)

                // 3. 底部快捷操作列 (開始行車、模擬試駕、雪隧建議、設定)
                bottomControlsBar
                    .padding(.horizontal, 16)
                    .padding(.bottom, 20)
            }
        }
        .sheet(isPresented: $showSettings) {
            SettingsView()
        }
        .sheet(isPresented: $showTunnelSheet) {
            NavigationView {
                ScrollView {
                    HsuehshanCardView()
                        .padding()
                }
                .navigationTitle("雪山隧道即時路況")
                .navigationBarItems(trailing: Button("關閉") { showTunnelSheet = false })
            }
        }
        .actionSheet(isPresented: $showSimSheet) {
            ActionSheet(
                title: Text("選擇實境模擬試駕路線"),
                message: Text("包含測速桿超速預警、通過解除音、道路施工封路、以及雪隧進洞前後切換驗證"),
                buttons: [
                    .default(Text("路線 1：台中環中路 (封閉施工 + 測速 60)")) { radarEngine.startDemoSimulation(scenarioIdx: 0) },
                    .default(Text("路線 2：五楊高架南下 (緩撞車 + 國道測速 100)")) { radarEngine.startDemoSimulation(scenarioIdx: 1) },
                    .default(Text("路線 3：西濱快速道路 (區間測速 80 + 單線管制)")) { radarEngine.startDemoSimulation(scenarioIdx: 2) },
                    .default(Text("路線 4：北宜公路 33K (經常出沒測速 40)")) { radarEngine.startDemoSimulation(scenarioIdx: 3) },
                    .default(Text("路線 5：雪山隧道南下 (進洞前建議 ➔ 進洞切換科技執法)")) { radarEngine.startDemoSimulation(scenarioIdx: 4) },
                    .default(Text("路線 6：雪山隧道北上 (進洞前建議 ➔ 進洞切換科技執法)")) { radarEngine.startDemoSimulation(scenarioIdx: 5) },
                    .destructive(Text("停止模擬試駕")) { radarEngine.stopDemoSimulation() },
                    .cancel()
                ]
            )
        }
    }

    // MARK: - 頂部預警橫幅

    @ViewBuilder
    private var topAlertBanner: some View {
        let snap = radarEngine.snapshot

        if let advice = snap.tunnelLaneAdvice, advice.isApproachingEntrance {
            // 雪山隧道進洞前車道推薦橫幅
            HStack(spacing: 12) {
                Image(systemName: "tunnel")
                    .font(.system(size: 24, weight: .bold))
                    .foregroundColor(.green)

                VStack(alignment: .leading, spacing: 2) {
                    HStack {
                        Text("即將進入雪隧・推薦\(advice.recommendedLaneName)")
                            .font(.headline)
                            .fontWeight(.bold)
                            .foregroundColor(.white)
                        Spacer()
                        Text("倒數 \(Int(advice.distanceToEntranceMeters))m")
                            .font(.subheadline)
                            .foregroundColor(.green)
                    }
                    Text("\(advice.reason)・雙白線請勿變換車道")
                        .font(.caption)
                        .foregroundColor(.white.opacity(0.85))
                }
            }
            .padding(12)
            .background(Color.black.opacity(0.85))
            .cornerRadius(14)
            .overlay(
                RoundedRectangle(cornerRadius: 14)
                    .stroke(Color.green, lineWidth: 2)
            )
            .shadow(radius: 6)

        } else if let cam = snap.nearestCamera {
            // 測速照相預警橫幅
            HStack(spacing: 12) {
                // 類型標籤與速限紅圈
                ZStack {
                    Circle()
                        .fill(Color.white)
                        .frame(width: 44, height: 44)
                    Circle()
                        .stroke(Color.red, lineWidth: 4)
                        .frame(width: 44, height: 44)
                    Text("\(cam.camera.speedLimit)")
                        .font(.system(size: 20, weight: .black))
                        .foregroundColor(.black)
                }

                VStack(alignment: .leading, spacing: 2) {
                    HStack {
                        Text(cam.camera.type.label)
                            .font(.headline)
                            .fontWeight(.bold)
                            .foregroundColor(.white)
                        Spacer()
                        Text("\(Int(cam.distanceMeters)) 公尺")
                            .font(.title3)
                            .fontWeight(.black)
                            .foregroundColor(snap.isOverspeed ? .red : .yellow)
                    }
                    Text(cam.camera.address)
                        .font(.caption)
                        .foregroundColor(.white.opacity(0.8))
                        .lineLimit(1)
                }
            }
            .padding(12)
            .background(snap.isOverspeed ? Color.red.opacity(0.9) : Color.black.opacity(0.85))
            .cornerRadius(14)
            .overlay(
                RoundedRectangle(cornerRadius: 14)
                    .stroke(snap.isOverspeed ? Color.white : Color.red.opacity(0.6), lineWidth: snap.isOverspeed ? 2 : 1)
            )
            .shadow(color: snap.isOverspeed ? Color.red.opacity(0.8) : Color.black.opacity(0.4), radius: 8)

        } else if let ev = snap.nearestRoadEvent {
            // 道路施工/封閉預警橫幅
            HStack(spacing: 12) {
                Text(ev.event.severity.glyph)
                    .font(.system(size: 28))

                VStack(alignment: .leading, spacing: 2) {
                    HStack {
                        Text("\(ev.event.severity.label)・\(Int(ev.distanceMeters))m")
                            .font(.headline)
                            .fontWeight(.bold)
                            .foregroundColor(.white)
                        Spacer()
                    }
                    Text("\(ev.event.roadName)：\(ev.event.rawComment)")
                        .font(.caption)
                        .foregroundColor(.white.opacity(0.85))
                        .lineLimit(1)
                }
            }
            .padding(12)
            .background(Color.black.opacity(0.85))
            .cornerRadius(14)
            .overlay(
                RoundedRectangle(cornerRadius: 14)
                    .stroke(Color.orange, lineWidth: 1.5)
            )
            .shadow(radius: 6)
        }
    }

    // MARK: - 中央儀表板

    private var centralSpeedometer: some View {
        let snap = radarEngine.snapshot

        return VStack(spacing: 6) {
            ZStack {
                // 外圈時速光環
                Circle()
                    .stroke(snap.isOverspeed ? Color.red : Color.blue.opacity(0.35), lineWidth: 10)
                    .frame(width: 150, height: 150)
                    .background(Circle().fill(Color.black.opacity(0.82)))
                    .shadow(color: snap.isOverspeed ? Color.red : Color.blue.opacity(0.3), radius: 10)

                VStack(spacing: 0) {
                    Text("\(snap.speedKmh)")
                        .font(.system(size: 56, weight: .black, design: .rounded))
                        .foregroundColor(snap.isOverspeed ? .red : .white)

                    Text("km/h")
                        .font(.system(size: 14, weight: .bold))
                        .foregroundColor(.gray)
                }

                // 右上角速限紅圈牌
                if let cam = snap.nearestCamera {
                    VStack {
                        HStack {
                            Spacer()
                            ZStack {
                                Circle().fill(Color.white).frame(width: 38, height: 38)
                                Circle().stroke(Color.red, lineWidth: 3.5).frame(width: 38, height: 38)
                                Text("\(cam.camera.speedLimit)")
                                    .font(.system(size: 16, weight: .black))
                                    .foregroundColor(.black)
                            }
                            .offset(x: 10, y: -10)
                        }
                        Spacer()
                    }
                    .frame(width: 150, height: 150)
                }
            }

            // 車頭方位角膠囊標籤
            HStack(spacing: 4) {
                Image(systemName: "location.north.line.fill")
                    .rotationEffect(.degrees(snap.bearingDeg))
                    .foregroundColor(.blue)
                Text("\(snap.bearingLabel) \(Int(snap.bearingDeg))°")
                    .font(.caption)
                    .fontWeight(.bold)
                    .foregroundColor(.white)
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 4)
            .background(Color.black.opacity(0.7))
            .cornerRadius(12)
        }
    }

    // MARK: - 底部快捷控制列

    private var bottomControlsBar: some View {
        HStack(spacing: 12) {
            // 雪隧路況按鈕
            Button(action: { showTunnelSheet = true }) {
                Image(systemName: "tunnel")
                    .font(.system(size: 18, weight: .bold))
                    .frame(width: 44, height: 44)
                    .background(Color.black.opacity(0.8))
                    .foregroundColor(.white)
                    .clipShape(Circle())
            }

            // 實境試駕模擬按鈕
            Button(action: { showSimSheet = true }) {
                Image(systemName: "gamecontroller.fill")
                    .font(.system(size: 18, weight: .bold))
                    .frame(width: 44, height: 44)
                    .background(radarEngine.isSimulating ? Color.orange : Color.black.opacity(0.8))
                    .foregroundColor(.white)
                    .clipShape(Circle())
            }

            // 開始 / 結束行車主按鈕
            Button(action: {
                if radarEngine.isDrivingActive {
                    radarEngine.stopDriving()
                } else {
                    radarEngine.startDriving()
                }
            }) {
                HStack(spacing: 8) {
                    Image(systemName: radarEngine.isDrivingActive ? "stop.fill" : "play.fill")
                    Text(radarEngine.isDrivingActive ? "結束行車" : "開始行車")
                        .fontWeight(.bold)
                }
                .frame(maxWidth: .infinity)
                .frame(height: 48)
                .background(radarEngine.isDrivingActive ? Color.red : Color.blue)
                .foregroundColor(.white)
                .cornerRadius(24)
                .shadow(radius: 6)
            }

            // 設定按鈕
            Button(action: { showSettings = true }) {
                Image(systemName: "gearshape.fill")
                    .font(.system(size: 18, weight: .bold))
                    .frame(width: 44, height: 44)
                    .background(Color.black.opacity(0.8))
                    .foregroundColor(.white)
                    .clipShape(Circle())
            }
        }
        .padding(8)
        .background(Color.black.opacity(0.65))
        .cornerRadius(30)
    }
}
