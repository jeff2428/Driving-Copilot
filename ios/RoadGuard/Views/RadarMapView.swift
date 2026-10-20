import SwiftUI
import WebKit

/// 整合 Leaflet.js 的 iOS 原生行車雷達電子地圖
public struct RadarMapView: UIViewRepresentable {
    @ObservedObject var radarEngine: RadarEngine = RadarEngine.shared

    public init(radarEngine: RadarEngine = RadarEngine.shared) {
        self.radarEngine = radarEngine
    }

    public func makeCoordinator() -> Coordinator {
        Coordinator(self)
    }

    public func makeUIView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        config.allowsInlineMediaPlayback = true

        let webView = WKWebView(frame: .zero, configuration: config)
        webView.isOpaque = false
        webView.backgroundColor = .clear
        webView.scrollView.isScrollEnabled = false
        webView.navigationDelegate = context.coordinator
        context.coordinator.webView = webView

        loadLeafletHtml(webView: webView)
        return webView
    }

    public func updateUIView(_ uiView: WKWebView, context: Context) {
        context.coordinator.pushSnapshot(radarEngine.snapshot)
    }

    private func loadLeafletHtml(webView: WKWebView) {
        guard let htmlUrl = Bundle.main.url(forResource: "radar_map", withExtension: "html"),
              let rawHtml = try? String(contentsOf: htmlUrl, encoding: .utf8) else {
            return
        }

        let css = (Bundle.main.url(forResource: "leaflet", withExtension: "css").flatMap { try? String(contentsOf: $0, encoding: .utf8) }) ?? ""
        let js = (Bundle.main.url(forResource: "leaflet", withExtension: "js").flatMap { try? String(contentsOf: $0, encoding: .utf8) }) ?? ""

        let fullHtml = rawHtml
            .replacingOccurrences(of: "/*LEAFLET_CSS*/", with: css)
            .replacingOccurrences(of: "/*LEAFLET_JS*/", with: js)

        webView.loadHTMLString(fullHtml, baseURL: URL(string: "https://roadguard.tw/"))
    }

    public final class Coordinator: NSObject, WKNavigationDelegate {
        var parent: RadarMapView
        weak var webView: WKWebView?
        private var isLoaded: Bool = false
        private var pendingSnapshot: RadarSnapshot?

        init(_ parent: RadarMapView) {
            self.parent = parent
        }

        public func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            isLoaded = true
            if let snap = pendingSnapshot {
                pushSnapshot(snap)
                pendingSnapshot = nil
            }
        }

        func pushSnapshot(_ snap: RadarSnapshot) {
            guard isLoaded, let webView = webView else {
                pendingSnapshot = snap
                return
            }

            // 組裝 JavaScript updateRadarMap 酬載
            var camList: [[String: Any]] = []
            for sc in snap.nearbyCameras {
                camList.append([
                    "id": sc.camera.id,
                    "lat": sc.camera.lat,
                    "lng": sc.camera.lng,
                    "limit": sc.camera.speedLimit,
                    "type": sc.camera.type.label,
                    "address": sc.camera.address,
                    "direct": sc.camera.directText,
                    "interval": sc.camera.type == .interval,
                    "hotspot": sc.camera.type == .hotspot,
                    "hot": sc.camera.id == snap.nearestCamera?.camera.id,
                    "sig": "\(sc.camera.id)-\(sc.camera.id == snap.nearestCamera?.camera.id)"
                ])
            }

            var evList: [[String: Any]] = []
            for se in snap.nearbyEvents {
                let sevKey: String
                switch se.event.severity {
                case .closure: sevKey = "CLOSURE"
                case .construction: sevKey = "CONSTRUCTION"
                case .hazard: sevKey = "HAZARD"
                case .congestion: sevKey = "CONGESTION"
                }
                evList.append([
                    "id": se.event.uid,
                    "lat": se.event.lat,
                    "lng": se.event.lng,
                    "road": se.event.roadName,
                    "severity": sevKey,
                    "label": se.event.severity.label,
                    "comment": se.event.rawComment,
                    "schedule": se.event.scheduleNote,
                    "hot": se.event.uid == snap.nearestRoadEvent?.event.uid,
                    "sig": "\(se.event.uid)-\(se.event.uid == snap.nearestRoadEvent?.event.uid)"
                ])
            }

            let payload: [String: Any] = [
                "lat": snap.lat,
                "lng": snap.lng,
                "speed": snap.speedKmh,
                "bearing": snap.bearingDeg,
                "cameras": camList,
                "events": evList
            ]

            guard let jsonData = try? JSONSerialization.data(withJSONObject: payload),
                  let jsonStr = String(data: jsonData, encoding: .utf8) else {
                return
            }

            webView.evaluateJavaScript("window.updateRadarMap(\(jsonStr));", completionHandler: nil)
        }

        func recenter() {
            webView?.evaluateJavaScript("window.recenter();", completionHandler: nil)
        }
    }
}
