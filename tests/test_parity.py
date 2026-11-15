import unittest
import math
import re

EARTH_RADIUS = 6371000.0

def distance_meters(lat1, lng1, lat2, lng2):
    d_lat = math.radians(lat2 - lat1)
    d_lng = math.radians(lng2 - lng1)
    a = math.sin(d_lat / 2)**2 + math.cos(math.radians(lat1)) * math.cos(math.radians(lat2)) * math.sin(d_lng / 2)**2
    c = 2 * math.atan2(math.sqrt(a), math.sqrt(1 - a))
    return EARTH_RADIUS * c

def bearing_deg(lat1, lng1, lat2, lng2):
    phi1 = math.radians(lat1)
    phi2 = math.radians(lat2)
    d_lambda = math.radians(lng2 - lng1)
    y = math.sin(d_lambda) * math.cos(phi2)
    x = math.cos(phi1) * math.sin(phi2) - math.sin(phi1) * math.cos(phi2) * math.cos(d_lambda)
    deg = math.degrees(math.atan2(y, x))
    return (deg % 360.0 + 360.0) % 360.0

def angle_diff(deg1, deg2):
    diff = abs(deg1 - deg2) % 360.0
    return 360.0 - diff if diff > 180.0 else diff

def cross_track_distance(dist, rel_angle):
    return abs(dist * math.sin(math.radians(rel_angle)))

def is_inside_hsuehshan_tunnel(lat, lng):
    a_lat, a_lng = 24.9365, 121.7142
    b_lat, b_lng = 24.8512, 121.7960
    dx = b_lng - a_lng
    dy = b_lat - a_lat
    len_sq = dx * dx + dy * dy
    if len_sq == 0:
        return False
    t = ((lng - a_lng) * dx + (lat - a_lat) * dy) / len_sq
    if t < 0.02 or t > 0.98:
        return False
    proj_lat = a_lat + t * dy
    proj_lng = a_lng + t * dx
    lat_dist = distance_meters(lat, lng, proj_lat, proj_lng)
    return lat_dist <= 650.0

FREEWAY_REGEX = re.compile(r"國道[一二三四五六七八九十0-9]|國[一二三四五六七八九十0-9]|高速公路")

def is_freeway(address, city=""):
    if "路口" in address or "機慢車" in address or "方向" in address:
        return False
    if city.startswith("國道"):
        return True
    return bool(FREEWAY_REGEX.search(address))

def get_thresholds(address, speed_limit, speed_kmh, city=""):
    if is_freeway(address, city):
        return (1000.0, 400.0, 550.0)
    elif speed_limit >= 80 or speed_kmh >= 80:
        return (900.0, 380.0, 550.0)
    elif speed_limit >= 60 or speed_kmh >= 60:
        return (600.0, 260.0, 400.0)
    else:
        return (420.0, 180.0, 280.0)

def is_camera_passed_by_vector(current_dist, rel_angle_deg, min_approached_dist, is_freeway_or_elevated):
    approach_limit = 80.0 if is_freeway_or_elevated else 45.0
    if min_approached_dist > approach_limit:
        return False
    rad = math.radians(rel_angle_deg)
    along_track = current_dist * math.cos(rad)
    cross_track = current_dist * math.sin(rad)
    passed_orthogonal = (along_track <= -5.0) and (abs(cross_track) <= 40.0)
    passed_side = (rel_angle_deg > 95.0) and (current_dist <= 35.0)
    return passed_orthogonal or passed_side

ELEVATED_REGEX = re.compile(r"高架|快速|台6[1-8]|台7[2-8]|台8[2-8]|市民大道高架|建國高架|新生高架|洲美|環河快|環東|水源快速")

def is_elevated_road(address):
    if any(k in address for k in ["路口", "街口", "巷口", "平面", "機慢車", "橋下"]):
        return False
    return bool(ELEVATED_REGEX.search(address))

def parse_tech_type(text):
    if "闖紅燈" in text or "紅燈" in text:
        return "取締闖紅燈"
    if "不停讓" in text or "未禮讓" in text or "行人" in text:
        return "取締未禮讓行人"
    if "雙白線" in text or "跨越" in text:
        return "取締跨越雙白線"
    if any(k in text for k in ["標誌", "標線", "轉彎", "左轉"]):
        return "取締違規轉彎與標線"
    if any(k in text for k in ["違停", "停車", "臨停"]):
        return "取締違規停車"
    if "公車" in text or "專用道" in text:
        return "取締佔用專用道"
    return "多功能科技執法"

class TestGeometricParity(unittest.TestCase):
    def test_hsuehshan_tunnel_boundary(self):
        # 進洞前
        self.assertFalse(is_inside_hsuehshan_tunnel(24.9415, 121.7090))
        # 洞內中段
        self.assertTrue(is_inside_hsuehshan_tunnel(24.8950, 121.7540))

    def test_cross_track_filter(self):
        # 正前方 300m，夾角 5 度 -> 偏距 ~26m <= 48m (通過)
        ct = cross_track_distance(300.0, 5.0)
        self.assertLess(ct, 48.0)
        # 平行隔壁道路 300m，夾角 20 度 -> 偏距 ~102m > 48m (剔除)
        ct_neighbor = cross_track_distance(300.0, 20.0)
        self.assertGreater(ct_neighbor, 48.0)

    def test_freeway_recognition_and_thresholds(self):
        self.assertTrue(is_freeway("國道一號南向306.1公里"))
        self.assertTrue(is_freeway("中山高速公路南下50公里"))
        self.assertTrue(is_freeway("國3南下100公里"))
        self.assertFalse(is_freeway("中正東路往國道二號方向"))
        self.assertFalse(is_freeway("三商街高速公路口機慢車專用道往南415公尺處"))

        # 國道固定與經常出沒點
        self.assertEqual(get_thresholds("國道一號南向306.1公里", 110, 105), (1000.0, 400.0, 550.0))
        # 市區道路
        self.assertEqual(get_thresholds("中正路100號", 50, 45), (420.0, 180.0, 280.0))

    def test_vector_pass_gate(self):
        # 山路急彎 (北宜公路 33K) 不得誤鳴通過
        self.assertFalse(is_camera_passed_by_vector(120.0, 85.0, 110.0, False))
        # 真實通過相機門架
        self.assertTrue(is_camera_passed_by_vector(10.0, 145.0, 8.0, False))

    def test_elevated_road(self):
        self.assertTrue(is_elevated_road("市民大道高架東向12K"))
        self.assertTrue(is_elevated_road("台64線快速公路西向18K"))
        self.assertFalse(is_elevated_road("市民大道四段與延吉街口"))
        self.assertFalse(is_elevated_road("建國南路二段平面車道"))

    def test_tech_enforcement_disambiguation(self):
        self.assertEqual(parse_tech_type("中正路與中山路口(取締闖紅燈)"), "取締闖紅燈")
        self.assertEqual(parse_tech_type("忠孝東路五段(未禮讓行人取締)"), "取締未禮讓行人")
        self.assertEqual(parse_tech_type("信義路公車專用道"), "取締佔用專用道")
        self.assertEqual(parse_tech_type("復興北路地下道(跨越雙白線取締)"), "取締跨越雙白線")

if __name__ == "__main__":
    unittest.main()
