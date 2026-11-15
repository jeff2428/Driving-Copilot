import unittest

class AlertPriority:
    EMERGENCY_OVERSPEED = 1
    INTERVAL_END = 2
    TECH_CAMERA = 3
    ROAD_CLOSURE = 4
    ROAD_WORK = 5

class AlertArbitrator:
    """
    對標 doc/proposal.md 2.3 與 2.4 節之警報仲裁器
    """
    @staticmethod
    def arbitrate(current_speed_kmh, speed_limit_kmh, has_camera, has_closure, is_in_call, call_mode):
        """
        is_in_call: 是否通話中
        call_mode: 'MUTE_VISUAL' | 'DUCKING' | 'NORMAL'
        """
        is_overspeed = current_speed_kmh > speed_limit_kmh

        if is_in_call and call_mode == 'MUTE_VISUAL':
            return {
                "voice_allowed": False,
                "visual_flash": True,
                "priority": AlertPriority.EMERGENCY_OVERSPEED if is_overspeed else AlertPriority.TECH_CAMERA,
                "text": "通話中視覺警示：前方測速照相" + (" (已超速！)" if is_overspeed else "")
            }

        if is_overspeed and has_camera:
            return {
                "voice_allowed": True,
                "urgent_tone": True,
                "flush_queue": True,
                "priority": AlertPriority.EMERGENCY_OVERSPEED,
                "text": "警告！您已超速，前方有測速照相，請立即減速！"
            }
        elif has_camera and has_closure:
            return {
                "voice_allowed": True,
                "urgent_tone": False,
                "flush_queue": False,
                "priority": AlertPriority.TECH_CAMERA,
                "text": "前方200公尺有測速照相，該路段並有道路封閉，請減速小心行駛。"
            }
        elif has_camera:
            return {
                "voice_allowed": True,
                "urgent_tone": False,
                "flush_queue": False,
                "priority": AlertPriority.TECH_CAMERA,
                "text": "前方有測速照相，速限 " + str(speed_limit_kmh)
            }
        else:
            return {
                "voice_allowed": True,
                "urgent_tone": False,
                "flush_queue": False,
                "priority": AlertPriority.ROAD_WORK,
                "text": "前方路段有路況施工"
            }

    @staticmethod
    def arbitrate_zone_end(current_speed_kmh, speed_limit_kmh, has_camera, is_interval_end, is_in_call, call_mode):
        is_overspeed = current_speed_kmh > speed_limit_kmh
        if is_in_call and call_mode == 'MUTE_VISUAL':
            return {
                "voice_allowed": False,
                "visual_flash": True,
                "priority": AlertPriority.EMERGENCY_OVERSPEED if is_overspeed else AlertPriority.INTERVAL_END,
                "text": "通話中視覺警示：通過區間測速終點"
            }
        return {
            "voice_allowed": True,
            "urgent_tone": is_overspeed,
            "flush_queue": is_overspeed,
            "priority": AlertPriority.EMERGENCY_OVERSPEED if is_overspeed else AlertPriority.INTERVAL_END,
            "text": "通過區間測速終點"
        }

class ProximityBeepCalculator:
    """
    對標 doc/data-contracts.md § 3 逼近音階梯計算器
    """
    @staticmethod
    def get_stage(distance_meters, is_overspeed):
        if distance_meters > 150.0:
            return None
        elif distance_meters > 100.0:
            return {"interval_ms": 1000, "is_double_tone": False}
        elif distance_meters > 50.0:
            return {"interval_ms": 500, "is_double_tone": False}
        else:
            return {"interval_ms": 200 if is_overspeed else 250, "is_double_tone": is_overspeed}

class TestAlertArbitration(unittest.TestCase):
    def test_overspeed_forces_emergency_priority(self):
        # 超速 115 km/h (速限 100), 同時有相機與道路封閉
        res = AlertArbitrator.arbitrate(
            current_speed_kmh=115,
            speed_limit_kmh=100,
            has_camera=True,
            has_closure=True,
            is_in_call=False,
            call_mode='MUTE_VISUAL'
        )
        self.assertEqual(res["priority"], AlertPriority.EMERGENCY_OVERSPEED)
        self.assertTrue(res["flush_queue"])
        self.assertTrue(res["urgent_tone"])
        self.assertIn("您已超速", res["text"])

    def test_normal_speed_fuses_alerts(self):
        # 正常車速 90 km/h (速限 100), 同時有相機與道路封閉 -> 智慧融合
        res = AlertArbitrator.arbitrate(
            current_speed_kmh=90,
            speed_limit_kmh=100,
            has_camera=True,
            has_closure=True,
            is_in_call=False,
            call_mode='MUTE_VISUAL'
        )
        self.assertEqual(res["priority"], AlertPriority.TECH_CAMERA)
        self.assertFalse(res["flush_queue"])
        self.assertIn("道路封閉", res["text"])

    def test_in_call_mute_visual_mode(self):
        # 通話中且設定為預設 MUTE_VISUAL
        res = AlertArbitrator.arbitrate(
            current_speed_kmh=90,
            speed_limit_kmh=100,
            has_camera=True,
            has_closure=False,
            is_in_call=True,
            call_mode='MUTE_VISUAL'
        )
        self.assertFalse(res["voice_allowed"])
        self.assertTrue(res["visual_flash"])

    def test_in_call_ducking_mode(self):
        # 通話中但設定為 DUCKING -> 允許發聲
        res = AlertArbitrator.arbitrate(
            current_speed_kmh=105,
            speed_limit_kmh=100,
            has_camera=True,
            has_closure=False,
            is_in_call=True,
            call_mode='DUCKING'
        )
        self.assertTrue(res["voice_allowed"])

    def test_interval_end_priority(self):
        # 依據 doc/data-contracts.md § 2.2: 區間終點為 Priority 2
        res = AlertArbitrator.arbitrate_zone_end(
            current_speed_kmh=80,
            speed_limit_kmh=80,
            has_camera=True,
            is_interval_end=True,
            is_in_call=False,
            call_mode='NORMAL'
        )
        self.assertEqual(res["priority"], AlertPriority.INTERVAL_END)

    def test_proximity_beep_stages(self):
        # 依據 doc/data-contracts.md § 3 逼近音階梯
        self.assertEqual(ProximityBeepCalculator.get_stage(120.0, is_overspeed=False)["interval_ms"], 1000)
        self.assertEqual(ProximityBeepCalculator.get_stage(75.0, is_overspeed=False)["interval_ms"], 500)
        self.assertEqual(ProximityBeepCalculator.get_stage(30.0, is_overspeed=False)["interval_ms"], 250)
        self.assertEqual(ProximityBeepCalculator.get_stage(30.0, is_overspeed=True)["interval_ms"], 200)
        self.assertTrue(ProximityBeepCalculator.get_stage(30.0, is_overspeed=True)["is_double_tone"])
        self.assertIsNone(ProximityBeepCalculator.get_stage(200.0, is_overspeed=False))

if __name__ == "__main__":
    unittest.main()
