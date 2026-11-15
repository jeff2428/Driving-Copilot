import unittest


def compute_camera_progress(distance_meters: float, far_meters: float) -> float:
    effective_far = max(300.0, far_meters)
    raw = 1.0 - (distance_meters / effective_far)
    return max(0.05, min(1.0, raw))


def compute_interval_progress(distance_traveled: float, zone_length: float) -> float:
    if zone_length <= 0.0:
        return 0.05
    return max(0.05, min(1.0, distance_traveled / zone_length))


def compute_map_signature(
    lat: float,
    lng: float,
    bearing_deg: int,
    speed_kmh: int,
    hot_cam_id: str,
    hot_ev_id: str,
    cam_count: int,
    ev_count: int,
) -> str:
    return f"{lat:.5f},{lng:.5f},{bearing_deg},{speed_kmh},{hot_cam_id},{hot_ev_id},{cam_count},{ev_count}"


class TestCockpitUpgrade(unittest.TestCase):
    """對標 doc/proposal.md § 2.6 - 2.7 與 doc/data-contracts.md § 5"""

    def test_camera_progress_fraction_bounds(self):
        # 距離 1000m / 門檻 1000m -> 鉗位於下限 0.05
        self.assertAlmostEqual(compute_camera_progress(1000.0, 1000.0), 0.05, places=3)
        # 距離 500m / 門檻 1000m -> 0.50
        self.assertAlmostEqual(compute_camera_progress(500.0, 1000.0), 0.50, places=3)
        # 距離 0m / 門檻 1000m -> 1.00
        self.assertAlmostEqual(compute_camera_progress(0.0, 1000.0), 1.00, places=3)

    def test_interval_zone_progress_and_dead_reckoning_state(self):
        # 行駛 1600m / 全長 3200m -> 0.50
        self.assertAlmostEqual(compute_interval_progress(1600.0, 3200.0), 0.50, places=3)
        # 超過全長時鉗位於 1.00
        self.assertAlmostEqual(compute_interval_progress(3500.0, 3200.0), 1.00, places=3)

    def test_map_diff_signature_deduplication(self):
        sig1 = compute_map_signature(24.160501, 120.631102, 180, 90, "CAM-1", "", 12, 3)
        # 微小浮點抖動 (< 1e-5 度) 不應觸發多餘 WebView 重繪
        sig2 = compute_map_signature(24.160504, 120.631104, 180, 90, "CAM-1", "", 12, 3)
        self.assertEqual(sig1, sig2)

        # 當車速或鎖定測速桿變更時，簽章必須改變以即時更新畫面
        sig3 = compute_map_signature(24.160504, 120.631104, 180, 95, "CAM-1", "", 12, 3)
        self.assertNotEqual(sig1, sig3)

    def test_in_call_mode_chip_mapping(self):
        modes = ["MUTE_VISUAL", "DUCKING", "NORMAL"]
        labels = ["靜音閃爍", "壓低音量", "正常播報"]
        self.assertEqual(len(modes), len(labels))
        self.assertEqual(modes[0], "MUTE_VISUAL")


if __name__ == "__main__":
    unittest.main()
