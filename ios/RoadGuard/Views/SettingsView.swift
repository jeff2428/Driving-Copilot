import SwiftUI

/// 神盾路況行車助手 設定頁面
public struct SettingsView: View {
    @ObservedObject var repository = OpenDataRepository.shared
    @ObservedObject var voiceEngine = VoiceAlertEngine.shared

    @State private var voiceEnabled: Bool = true
    @State private var passChimeEnabled: Bool = true
    @State private var hotspotAlertEnabled: Bool = true
    @State private var tunnelAlertEnabled: Bool = true
    @State private var overspeedTolerance: Int = 10
    @State private var autoDailyUpdate: Bool = true
    @State private var githubRepo: String = "jeff2428/Driving-Copilot"

    @State private var isSyncing: Bool = false
    @State private var syncStatusMessage: String? = nil

    @Environment(\.dismiss) private var dismiss

    public init() {}

    public var body: some View {
        NavigationView {
            Form {
                // 語音與播報偏好
                Section(header: Text("語音與警報設定")) {
                    Toggle("語音播報總開關", isOn: $voiceEnabled)
                        .onChange(of: voiceEnabled) { val in
                            voiceEngine.isVoiceEnabled = val
                        }

                    Toggle("通過測速桿叮咚音效", isOn: $passChimeEnabled)
                        .onChange(of: passChimeEnabled) { val in
                            voiceEngine.isPassChimeEnabled = val
                        }

                    Toggle("經常出沒移動式測速預警", isOn: $hotspotAlertEnabled)
                        .onChange(of: hotspotAlertEnabled) { val in
                            voiceEngine.isHotspotAlertEnabled = val
                        }

                    Toggle("雪山隧道進洞前車道建議", isOn: $tunnelAlertEnabled)
                        .onChange(of: tunnelAlertEnabled) { val in
                            voiceEngine.isTunnelLaneAlertEnabled = val
                        }

                    Picker("超速警報容許值", selection: $overspeedTolerance) {
                        Text("嚴格 (0 km/h)").tag(0)
                        Text("+5 km/h").tag(5)
                        Text("+10 km/h (法規寬限)").tag(10)
                        Text("+15 km/h").tag(15)
                    }
                    .onChange(of: overspeedTolerance) { val in
                        voiceEngine.overspeedToleranceKmh = val
                    }
                }

                // 雲端圖資與自動更新
                Section(header: Text("圖資與雲端同步 (GitHub CDN / 警政署)")) {
                    Toggle("每日自動更新最新圖資", isOn: $autoDailyUpdate)
                        .onChange(of: autoDailyUpdate) { val in
                            repository.autoDailyUpdateEnabled = val
                        }

                    HStack {
                        Text("GitHub 倉庫")
                        Spacer()
                        TextField("owner/repo", text: $githubRepo)
                            .multilineTextAlignment(.trailing)
                            .foregroundColor(.gray)
                            .onSubmit {
                                repository.githubRepoSlug = githubRepo
                            }
                    }

                    Button(action: {
                        performCloudSync()
                    }) {
                        HStack {
                            if isSyncing {
                                ProgressView()
                                    .padding(.trailing, 6)
                                Text("雲端圖資同步中...")
                            } else {
                                Image(systemName: "arrow.triangle.2.circlepath")
                                Text("立即同步最新測速與路況")
                            }
                        }
                    }
                    .disabled(isSyncing)

                    if let msg = syncStatusMessage {
                        Text(msg)
                            .font(.caption)
                            .foregroundColor(.blue)
                    }
                }

                // 目前圖資與系統狀態
                Section(header: Text("圖資狀態")) {
                    HStack {
                        Text("測速照相總數")
                        Spacer()
                        Text("\(repository.cameras.count) 支")
                            .foregroundColor(.secondary)
                    }

                    HStack {
                        Text("即時路況/施工總數")
                        Spacer()
                        Text("\(repository.roadEvents.count) 筆")
                            .foregroundColor(.secondary)
                    }

                    HStack {
                        Text("測速資料版本")
                        Spacer()
                        Text(repository.lastCameraSyncDateStr)
                            .foregroundColor(.secondary)
                    }

                    HStack {
                        Text("最後更新通道")
                        Spacer()
                        Text(repository.lastSyncChannelStr)
                            .foregroundColor(.secondary)
                    }
                }

                // 關於與法律聲明
                Section(header: Text("關於神盾路況行車助手")) {
                    HStack {
                        Text("App 版本")
                        Spacer()
                        Text("v2.0.17 (iOS Native)")
                            .foregroundColor(.secondary)
                    }
                    Text("本系統測速照相取自政府警政署開放資料，施工與路況取自警廣與交通部高公局。雪山隧道車道數據取自交通資料庫。行車請遵守標誌與標線速限，安全第一。")
                        .font(.caption2)
                        .foregroundColor(.gray)
                }
            }
            .navigationTitle("設定")
            .navigationBarItems(trailing: Button("完成") {
                dismiss()
            })
            .onAppear {
                voiceEnabled = voiceEngine.isVoiceEnabled
                passChimeEnabled = voiceEngine.isPassChimeEnabled
                hotspotAlertEnabled = voiceEngine.isHotspotAlertEnabled
                tunnelAlertEnabled = voiceEngine.isTunnelLaneAlertEnabled
                overspeedTolerance = voiceEngine.overspeedToleranceKmh
                autoDailyUpdate = repository.autoDailyUpdateEnabled
                githubRepo = repository.githubRepoSlug
            }
        }
    }

    private func performCloudSync() {
        isSyncing = true
        syncStatusMessage = nil
        Task {
            let (_, message) = await repository.syncAllCloudData(forceCameras: true)
            await MainActor.run {
                isSyncing = false
                syncStatusMessage = message
                RadarEngine.shared.recomputeRadarState()
            }
        }
    }
}
