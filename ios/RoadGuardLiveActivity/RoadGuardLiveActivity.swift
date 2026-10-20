import WidgetKit
import SwiftUI
import ActivityKit

#if canImport(ActivityKit)
@main
struct RoadGuardLiveActivityWidgetBundle: WidgetBundle {
    var body: some Widget {
        RoadGuardLiveActivity()
    }
}

public struct RoadGuardLiveActivity: Widget {
    public init() {}

    public var body: some WidgetConfiguration {
        ActivityConfiguration(for: RoadGuardActivityAttributes.self) { context in
            // 解鎖畫面 / 通知中心即時動態卡片
            LockScreenLiveActivityView(context: context)
        } dynamicIsland: { context in
            // iPhone 14 Pro+ 動態島佈局
            DynamicIsland {
                // 展開型態 (Expanded)
                DynamicIslandExpandedRegion(.leading) {
                    if let limit = context.state.speedLimit {
                        ZStack {
                            Circle().fill(Color.white).frame(width: 38, height: 38)
                            Circle().stroke(Color.red, lineWidth: 3.5).frame(width: 38, height: 38)
                            Text("\(limit)")
                                .font(.system(size: 16, weight: .black))
                                .foregroundColor(.black)
                        }
                    } else {
                        Image(systemName: "location.north.circle.fill")
                            .font(.system(size: 32))
                            .foregroundColor(.blue)
                    }
                }

                DynamicIslandExpandedRegion(.trailing) {
                    VStack(alignment: .trailing, spacing: 0) {
                        Text("\(context.state.speedKmh)")
                            .font(.system(size: 30, weight: .black, design: .rounded))
                            .foregroundColor(context.state.isOverspeed ? .red : .white)
                        Text("km/h")
                            .font(.system(size: 10, weight: .bold))
                            .foregroundColor(.gray)
                    }
                }

                DynamicIslandExpandedRegion(.center) {
                    if let lane = context.state.hsuehshanLane {
                        // 雪隧車道建議
                        VStack(spacing: 2) {
                            Text(lane)
                                .font(.system(size: 15, weight: .black))
                                .foregroundColor(.green)
                            if let reason = context.state.hsuehshanReason {
                                Text(reason)
                                    .font(.caption2)
                                    .foregroundColor(.white.opacity(0.8))
                            }
                        }
                    } else if let type = context.state.cameraTypeLabel, let dist = context.state.cameraDistanceMeters {
                        // 前方測速照相
                        VStack(spacing: 2) {
                            Text("📸 \(type)")
                                .font(.system(size: 14, weight: .bold))
                                .foregroundColor(.white)
                            Text("\(Int(dist)) 公尺")
                                .font(.system(size: 16, weight: .black))
                                .foregroundColor(context.state.isOverspeed ? .red : .yellow)
                        }
                    } else if let ev = context.state.eventWarning {
                        Text(ev)
                            .font(.caption)
                            .foregroundColor(.orange)
                            .lineLimit(1)
                    } else {
                        Text("神盾路況監測中")
                            .font(.subheadline)
                            .foregroundColor(.white.opacity(0.8))
                    }
                }

                DynamicIslandExpandedRegion(.bottom) {
                    if context.state.isOverspeed {
                        HStack {
                            Image(systemName: "exclamationmark.triangle.fill")
                                .foregroundColor(.white)
                            Text("已超速！請立即減速")
                                .font(.caption)
                                .fontWeight(.bold)
                                .foregroundColor(.white)
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 4)
                        .background(Color.red)
                        .cornerRadius(6)
                    }
                }
            } compactLeading: {
                // 動態島膠囊左側：速限紅圈牌
                if let limit = context.state.speedLimit {
                    ZStack {
                        Circle().fill(Color.white).frame(width: 20, height: 20)
                        Circle().stroke(Color.red, lineWidth: 2).frame(width: 20, height: 20)
                        Text("\(limit)")
                            .font(.system(size: 9, weight: .black))
                            .foregroundColor(.black)
                    }
                } else {
                    Image(systemName: "location.north.circle.fill")
                        .foregroundColor(.blue)
                }
            } compactTrailing: {
                // 動態島膠囊右側：GPS 時速 (超速紅字)
                Text("\(context.state.speedKmh)")
                    .font(.system(size: 14, weight: .black, design: .rounded))
                    .foregroundColor(context.state.isOverspeed ? .red : .white)
            } minimal: {
                Text("\(context.state.speedKmh)")
                    .font(.system(size: 12, weight: .black))
                    .foregroundColor(context.state.isOverspeed ? .red : .white)
            }
        }
    }
}

/// 鎖定畫面與通知中心的即時動態卡片
struct LockScreenLiveActivityView: View {
    let context: ActivityViewContext<RoadGuardActivityAttributes>

    var body: some View {
        HStack(spacing: 14) {
            // 左側速限標誌
            if let limit = context.state.speedLimit {
                ZStack {
                    Circle().fill(Color.white).frame(width: 50, height: 50)
                    Circle().stroke(Color.red, lineWidth: 4).frame(width: 50, height: 50)
                    Text("\(limit)")
                        .font(.system(size: 22, weight: .black))
                        .foregroundColor(.black)
                }
            } else {
                Image(systemName: "shield.checkered")
                    .font(.system(size: 36))
                    .foregroundColor(.blue)
            }

            // 中央資訊：測速或雪隧建議
            VStack(alignment: .leading, spacing: 3) {
                if let lane = context.state.hsuehshanLane {
                    Text("🚇 \(lane)")
                        .font(.system(size: 16, weight: .black))
                        .foregroundColor(.green)
                    if let reason = context.state.hsuehshanReason {
                        Text(reason)
                            .font(.caption)
                            .foregroundColor(.gray)
                    }
                } else if let type = context.state.cameraTypeLabel, let dist = context.state.cameraDistanceMeters {
                    HStack {
                        Text(type)
                            .font(.headline)
                            .foregroundColor(.white)
                        Spacer()
                        Text("\(Int(dist))m")
                            .font(.title3)
                            .fontWeight(.black)
                            .foregroundColor(context.state.isOverspeed ? .red : .yellow)
                    }
                    if context.state.isOverspeed {
                        Text("您目前已超速，請立即減速！")
                            .font(.caption)
                            .fontWeight(.bold)
                            .foregroundColor(.red)
                    } else {
                        Text("前方測速照相，請依速限行駛")
                            .font(.caption)
                            .foregroundColor(.gray)
                    }
                } else {
                    Text("神盾路況監測中")
                        .font(.headline)
                        .foregroundColor(.white)
                    Text("雷達掃描中・祝您行車平安")
                        .font(.caption)
                        .foregroundColor(.gray)
                }
            }

            Spacer()

            // 右側目前時速
            VStack(spacing: 0) {
                Text("\(context.state.speedKmh)")
                    .font(.system(size: 38, weight: .black, design: .rounded))
                    .foregroundColor(context.state.isOverspeed ? .red : .white)
                Text("km/h")
                    .font(.caption2)
                    .fontWeight(.bold)
                    .foregroundColor(.gray)
            }
        }
        .padding(14)
        .background(Color.black.opacity(0.88))
    }
}
#endif
