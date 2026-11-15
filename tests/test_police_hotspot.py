import unittest
import os
import math

class TestFreewayPoliceHotspot(unittest.TestCase):
    """
    對標 doc/task/police-hotspot.md 與 freeway_police_hotspot_plan.md
    測試國道警察經常出沒點之圖資完整性、三重防誤報演算法、超速分級仲裁與向量解除
    """

    def test_freeway_hotspots_dataset_integrity(self):
        csv_path = os.path.join("cloud_data", "taiwan_cameras_latest.csv")
        self.assertTrue(os.path.exists(csv_path), "taiwan_cameras_latest.csv 必須存在")
        
        with open(csv_path, "r", encoding="utf-8") as f:
            lines = f.readlines()
        
        freeway_hotspots = [
            l for l in lines 
            if ("國道" in l or "台61" in l or "台74" in l) and 
               ("經常出沒" in l or "雷射" in l or "避車彎" in l or "移動" in l)
        ]
        self.assertGreaterEqual(len(freeway_hotspots), 30, "國道警察執法出沒點應至少有 30 處以上")
        
        # 驗證抽樣點位欄位格式
        sample = freeway_hotspots[0].strip().split(",")
        self.assertGreaterEqual(len(sample), 9)
        limit = int(sample[8])
        self.assertTrue(60 <= limit <= 120, f"國道速限應介於 60~120 之間，實際為 {limit}")

    def test_triple_filter_opposite_direction_rejection(self):
        # 模擬南下車輛（航向 180°），遇到北上避車彎取締點（directText='北上經常出沒取締'）
        driver_bearing = 180.0
        cam_address = "國道一號北上 12.8K 汐止路段避車彎"
        cam_direct = "北上經常出沒取締"
        
        is_compatible = self._check_direction_compatible(driver_bearing, cam_address, cam_direct)
        self.assertFalse(is_compatible, "南下行駛時必須 100% 阻絕北上對向避車彎警車提醒")

        # 模擬南下車輛（航向 180°），遇到南下避車彎取締點
        cam_address_south = "國道一號南下 158.6K 大安溪橋南端避車彎"
        cam_direct_south = "南下經常出沒取締"
        is_compatible_south = self._check_direction_compatible(driver_bearing, cam_address_south, cam_direct_south)
        self.assertTrue(is_compatible_south, "南下行駛時應相容南下避車彎取締點")

    def test_triple_filter_crosstrack_corridor_35m(self):
        # 國道警車出沒點橫向偏距門檻限定 35m，防止平行側車道誤觸
        # 距相機 500m，若偏角 5° -> 偏距 = 500 * sin(5°) ≈ 43.5m > 35m 應剔除
        dist = 500.0
        angle_deg = 5.0
        cross_track = dist * math.sin(math.radians(angle_deg))
        self.assertGreater(cross_track, 35.0)
        self.assertFalse(self._is_within_corridor(dist, cross_track, is_freeway_hotspot=True))

        # 若偏角 2° -> 偏距 = 500 * sin(2°) ≈ 17.4m <= 35m 應保留
        angle_deg_in = 2.0
        cross_track_in = dist * math.sin(math.radians(angle_deg_in))
        self.assertLessEqual(cross_track_in, 35.0)
        self.assertTrue(self._is_within_corridor(dist, cross_track_in, is_freeway_hotspot=True))

    def test_overspeed_tiered_speech_and_arbitration(self):
        # 1. 未超速：95 km/h (速限 100) -> 柔和提醒，無緊急音，無強制清空佇列
        res_normal = self._arbitrate_hotspot(current_speed=95, speed_limit=100, distance=850.0)
        self.assertEqual(res_normal["priority"], 3)
        self.assertFalse(res_normal["is_urgent"])
        self.assertFalse(res_normal["flush_queue"])
        self.assertIn("前方 850 公尺有警車經常出沒點", res_normal["text"])
        self.assertNotIn("已超速", res_normal["text"])

        # 2. 超速：115 km/h (速限 100) -> 最高緊急優先級 1，緊急插播，紅藍警燈微光邊框
        res_over = self._arbitrate_hotspot(current_speed=115, speed_limit=100, distance=400.0)
        self.assertEqual(res_over["priority"], 1)
        self.assertTrue(res_over["is_urgent"])
        self.assertTrue(res_over["flush_queue"])
        self.assertTrue(res_over["police_flash"])
        self.assertIn("警告！您已超速，前方 400 公尺有警車經常出沒點，速限 100，請立即減速！", res_over["text"])

    def test_vector_pass_gate_instant_clearing(self):
        # 當車身通過避車彎（距離拉遠且相對角轉向車尾 > 90°）
        is_passed = self._check_vector_passed(current_dist=25.0, min_dist=8.0, rel_angle=140.0)
        self.assertTrue(is_passed, "通過避車彎後必須瞬間觸發向量解除門閥")

    # ---- 輔助判斷函式 (對齊 Android Kotlin 實作) ----
    def _check_direction_compatible(self, driver_bearing, address, direct_text):
        is_southbound = 135.0 <= driver_bearing <= 225.0
        is_northbound = (driver_bearing <= 45.0 or driver_bearing >= 315.0)
        
        target_south = ("南下" in address or "南向" in address or "往南" in direct_text or "南下" in direct_text)
        target_north = ("北上" in address or "北向" in address or "往北" in direct_text or "北上" in direct_text)
        
        if is_southbound and target_north and not target_south:
            return False
        if is_northbound and target_south and not target_north:
            return False
        return True

    def _is_within_corridor(self, dist, cross_track, is_freeway_hotspot):
        max_cross_track = 35.0 if is_freeway_hotspot else 75.0
        return cross_track <= max_cross_track

    def _arbitrate_hotspot(self, current_speed, speed_limit, distance):
        is_over = current_speed > speed_limit
        if is_over:
            return {
                "priority": 1,
                "is_urgent": True,
                "flush_queue": True,
                "police_flash": True,
                "text": f"警告！您已超速，前方 {int(distance)} 公尺有警車經常出沒點，速限 {speed_limit}，請立即減速！"
            }
        else:
            return {
                "priority": 3,
                "is_urgent": False,
                "flush_queue": False,
                "police_flash": False,
                "text": f"注意，前方 {int(distance)} 公尺有警車經常出沒點，速限 {speed_limit}"
            }

    def _check_vector_passed(self, current_dist, min_dist, rel_angle):
        return current_dist > min_dist and rel_angle > 90.0

if __name__ == "__main__":
    unittest.main()
