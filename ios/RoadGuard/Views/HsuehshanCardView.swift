import SwiftUI

/// 國道5號 雪山隧道進洞前車道即時建議資訊卡片
public struct HsuehshanCardView: View {
    @State private var selectedDirection: String = "S" // S=南下往宜蘭, N=北上往台北
    @State private var advice: HsuehshanAdvice = HsuehshanAdvice()
    @State private var isLoading: Bool = false

    private let voiceEngine = VoiceAlertEngine.shared

    public init() {}

    public var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            // 標頭與方向切換
            HStack {
                HStack(spacing: 8) {
                    Image(systemName: "tunnel")
                        .font(.system(size: 18, weight: .bold))
                        .foregroundColor(.blue)
                    Text("雪山隧道車道即時建議")
                        .font(.headline)
                        .foregroundColor(.white)
                }

                Spacer()

                // 南下 / 北上切換 Segmented Control
                Picker("行向", selection: $selectedDirection) {
                    Text("南下 (往宜蘭)").tag("S")
                    Text("北上 (往台北)").tag("N")
                }
                .pickerStyle(SegmentedPickerStyle())
                .frame(width: 170)
                .onChange(of: selectedDirection) { newDir in
                    fetchAdvice(dir: newDir)
                }
            }

            // 車道推薦大徽章
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 4) {
                    Text("系統即時推薦")
                        .font(.caption)
                        .foregroundColor(.gray)
                    Text(advice.recommendedLaneName)
                        .font(.system(size: 24, weight: .black))
                        .foregroundColor(.green)
                }

                Spacer()

                VStack(alignment: .trailing, spacing: 4) {
                    Text("推薦理由")
                        .font(.caption)
                        .foregroundColor(.gray)
                    Text(advice.reason)
                        .font(.subheadline)
                        .fontWeight(.semibold)
                        .foregroundColor(.white)
                        .multilineTextAlignment(.trailing)
                }
            }
            .padding(12)
            .background(Color.green.opacity(0.12))
            .cornerRadius(10)
            .overlay(
                RoundedRectangle(cornerRadius: 10)
                    .stroke(Color.green.opacity(0.3), lineWidth: 1)
            )

            // 雙線車速與通過時間對比
            HStack(spacing: 12) {
                // 內側車道
                VStack(spacing: 6) {
                    Text("內側車道 (左線)")
                        .font(.caption)
                        .foregroundColor(.gray)
                    Text(advice.innerSpeedKmh != nil ? "\(advice.innerSpeedKmh!) km/h" : "-- km/h")
                        .font(.title3)
                        .fontWeight(.bold)
                        .foregroundColor(advice.recommendedLane == "inner" ? .green : .white)
                    if let min = advice.innerTravelMin {
                        Text(String(format: "約 %.1f 分鐘", min))
                            .font(.caption2)
                            .foregroundColor(.secondary)
                    }
                }
                .frame(maxWidth: .infinity)
                .padding(10)
                .background(Color.white.opacity(0.06))
                .cornerRadius(8)

                // 外側車道
                VStack(spacing: 6) {
                    Text("外側車道 (右線)")
                        .font(.caption)
                        .foregroundColor(.gray)
                    Text(advice.outerSpeedKmh != nil ? "\(advice.outerSpeedKmh!) km/h" : "-- km/h")
                        .font(.title3)
                        .fontWeight(.bold)
                        .foregroundColor(advice.recommendedLane == "outer" ? .green : .white)
                    if let min = advice.outerTravelMin {
                        Text(String(format: "約 %.1f 分鐘", min))
                            .font(.caption2)
                            .foregroundColor(.secondary)
                    }
                }
                .frame(maxWidth: .infinity)
                .padding(10)
                .background(Color.white.opacity(0.06))
                .cornerRadius(8)
            }

            // 隧道內事故路況警示 (若有)
            if let alert = advice.alertEvent, !alert.isEmpty {
                HStack(spacing: 8) {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .foregroundColor(.yellow)
                    Text("隧道內路況：\(alert)")
                        .font(.caption)
                        .foregroundColor(.yellow)
                        .lineLimit(2)
                }
                .padding(8)
                .background(Color.yellow.opacity(0.15))
                .cornerRadius(6)
            }

            // 底部操作列：試聽播報與資料更新時間
            HStack {
                Text("更新時間：\(advice.dataTime) (高公局路況資料庫)")
                    .font(.caption2)
                    .foregroundColor(.gray)

                Spacer()

                Button(action: {
                    voiceEngine.announceHsuehshanTunnel(advice: advice, force: true)
                }) {
                    HStack(spacing: 4) {
                        Image(systemName: "speaker.wave.2.fill")
                        Text("試聽進洞播報")
                    }
                    .font(.caption)
                    .fontWeight(.bold)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 6)
                    .background(Color.blue)
                    .foregroundColor(.white)
                    .cornerRadius(8)
                }
            }
        }
        .padding(16)
        .background(Color(uiColor: .secondarySystemBackground))
        .cornerRadius(14)
        .onAppear {
            fetchAdvice(dir: selectedDirection)
        }
    }

    private func fetchAdvice(dir: String) {
        advice = HsuehshanTunnelClient.shared.getAdvice(direction: dir, distanceMeters: 1200.0, isApproaching: false)
    }
}
