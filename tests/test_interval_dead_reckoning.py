import unittest
import time
import math

class DeadReckoningSimulator:
    """
    對標 doc/proposal.md 2.1 節之隧道內區間測速推估模擬器
    """
    def __init__(self, speed_limit_kmh, zone_length_meters, last_speed_kmh):
        self.speed_limit = speed_limit_kmh
        self.zone_length = zone_length_meters
        self.last_speed_kmh = last_speed_kmh
        self.entry_time = time.time()
        self.distance_traveled = 0.0
        self.is_dead_reckoning = False
        self.gps_lost_time = None

    def on_gps_lost(self, now):
        self.is_dead_reckoning = True
        self.gps_lost_time = now

    def is_expired(self, now):
        if self.gps_lost_time is None:
            return False
        return (now - self.gps_lost_time) > 900.0

    def on_gps_recovered(self, actual_distance_meters, actual_speed_kmh):
        self.is_dead_reckoning = False
        self.gps_lost_time = None
        self.distance_traveled = actual_distance_meters
        self.last_speed_kmh = actual_speed_kmh

    def update_simulation_tick(self, delta_sec):
        if self.is_dead_reckoning:
            # 依據進入隧道前最後時速推估每秒行駛距離: (km/h / 3.6) * dt
            meters_per_sec = self.last_speed_kmh / 3.6
            self.distance_traveled += meters_per_sec * delta_sec

    def get_remaining_safe_seconds(self, elapsed_sec):
        target_total_sec = self.zone_length / (self.speed_limit / 3.6)
        remaining = target_total_sec - elapsed_sec
        return max(0, int(round(remaining)))

    def get_average_speed_kmh(self, elapsed_sec):
        if elapsed_sec <= 0:
            return 0
        return int(round((self.distance_traveled / elapsed_sec) * 3.6))

class SnapshotRestorer:
    """
    對標 doc/data-contracts.md § 1 快照驗證與還原器
    """
    @staticmethod
    def validate_and_restore(snapshot, now):
        if snapshot.get("version") != 1:
            return False
        entry_time = snapshot.get("entry_timestamp", 0)
        # 逾時 2 小時 (7200 秒 = 7,200,000 毫秒) 拒絕還原
        if (now - entry_time) > 7200000:
            return False
        zone_len = snapshot.get("zone_length_meters", 0.0)
        dist = snapshot.get("total_distance_meters", 0.0)
        # 超過 1.5 倍長度判定異常
        if dist > zone_len * 1.5:
            return False
        return True

class TestIntervalDeadReckoning(unittest.TestCase):
    def test_safe_countdown_calculation(self):
        # 速限 90km/h (25 m/s), 隧道全長 3000m -> 安全最低通行秒數 = 120 秒
        sim = DeadReckoningSimulator(speed_limit_kmh=90, zone_length_meters=3000.0, last_speed_kmh=90)
        # 剛進洞 0 秒
        self.assertEqual(sim.get_remaining_safe_seconds(0), 120)
        # 行駛 50 秒
        self.assertEqual(sim.get_remaining_safe_seconds(50), 70)
        # 行駛 120 秒
        self.assertEqual(sim.get_remaining_safe_seconds(120), 0)
        # 行駛 130 秒 (不應出現負數)
        self.assertEqual(sim.get_remaining_safe_seconds(130), 0)

    def test_dead_reckoning_distance_and_avg_speed(self):
        # 速限 80km/h, 以 72km/h (20 m/s) 失聯行駛 60 秒
        sim = DeadReckoningSimulator(speed_limit_kmh=80, zone_length_meters=2000.0, last_speed_kmh=72)
        sim.on_gps_lost(time.time())
        for _ in range(60):
            sim.update_simulation_tick(1.0)
        
        # 60 秒累積行駛應為 1200 公尺
        self.assertAlmostEqual(sim.distance_traveled, 1200.0, delta=1.0)
        # 平均時速應為 72 km/h
        self.assertEqual(sim.get_average_speed_kmh(60), 72)

    def test_dead_reckoning_timeout_at_900_seconds(self):
        # 依據 doc/data-contracts.md 與 doc/error-catalog.md: 斷訊超逾 900 秒必須觸發超時
        sim = DeadReckoningSimulator(speed_limit_kmh=80, zone_length_meters=3000.0, last_speed_kmh=80)
        sim.on_gps_lost(now=1000000.0)
        self.assertFalse(sim.is_expired(now=1000000.0 + 899.0))
        self.assertTrue(sim.is_expired(now=1000000.0 + 901.0))

    def test_gps_reconnection_smooth_recovery(self):
        sim = DeadReckoningSimulator(speed_limit_kmh=80, zone_length_meters=3000.0, last_speed_kmh=80)
        sim.on_gps_lost(now=1000.0)
        sim.update_simulation_tick(10.0)
        self.assertTrue(sim.is_dead_reckoning)
        # GPS 恢復信號
        sim.on_gps_recovered(actual_distance_meters=230.0, actual_speed_kmh=82)
        self.assertFalse(sim.is_dead_reckoning)
        self.assertEqual(sim.distance_traveled, 230.0)

    def test_persistence_snapshot_schema(self):
        snapshot = {
            "version": 1,
            "camera_id": "CAM_INTERVAL_001",
            "speed_limit": 80,
            "zone_length_meters": 3200.0,
            "entry_timestamp": 1729900000000,
            "last_update_timestamp": 1729900050000,
            "total_distance_meters": 1250.5,
            "last_speed_kmh": 85,
            "is_dead_reckoning": False,
            "state": "IN_ZONE"
        }
        # 驗證快照欄位齊全且型態正確
        self.assertEqual(snapshot["version"], 1)
        self.assertIn("camera_id", snapshot)
        self.assertIsInstance(snapshot["entry_timestamp"], int)
        self.assertIsInstance(snapshot["total_distance_meters"], float)
        self.assertIsInstance(snapshot["last_speed_kmh"], int)
        self.assertIsInstance(snapshot["zone_length_meters"], float)

    def test_snapshot_restore_and_validation(self):
        # 測試快照還原器 (SnapshotRestorer) 之有效性校驗
        snapshot_valid = {
            "version": 1,
            "camera_id": "CAM_01",
            "speed_limit": 80,
            "zone_length_meters": 3000.0,
            "entry_timestamp": 1000000,
            "last_update_timestamp": 1050000,
            "total_distance_meters": 1200.0,
            "last_speed_kmh": 80,
            "is_dead_reckoning": True,
            "state": "IN_ZONE"
        }
        restorer = SnapshotRestorer()
        self.assertTrue(restorer.validate_and_restore(snapshot_valid, now=1060000))

        # 測試超時 (> 2 小時 = 7200 秒) 快照拒絕還原
        snapshot_expired = dict(snapshot_valid)
        snapshot_expired["entry_timestamp"] = 1000000
        self.assertFalse(restorer.validate_and_restore(snapshot_expired, now=1000000 + 7201000))

        # 測試異常距離 (> 1.5 倍區間長度) 快照拒絕還原
        snapshot_overflow = dict(snapshot_valid)
        snapshot_overflow["total_distance_meters"] = 3000.0 * 1.6
        self.assertFalse(restorer.validate_and_restore(snapshot_overflow, now=1060000))

if __name__ == "__main__":
    unittest.main()
