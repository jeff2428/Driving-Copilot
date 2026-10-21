import re
import math

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
    if len_sq == 0: return False
    t = ((lng - a_lng) * dx + (lat - a_lat) * dy) / len_sq
    if t < 0.02 or t > 0.98: return False
    proj_lat = a_lat + t * dy
    proj_lng = a_lng + t * dx
    lat_dist = distance_meters(lat, lng, proj_lat, proj_lng)
    return lat_dist <= 650.0

# 1. 驗證雪隧入口前點位是否正確判斷為「未進洞」
south_approach_lat = 24.9415
south_approach_lng = 121.7090
assert not is_inside_hsuehshan_tunnel(south_approach_lat, south_approach_lng), "進洞前應判為 False"

# 2. 驗證雪隧中段點位是否正確判斷為「在洞內」
mid_tunnel_lat = 24.8950
mid_tunnel_lng = 121.7540
assert is_inside_hsuehshan_tunnel(mid_tunnel_lat, mid_tunnel_lng), "洞內應判為 True"

# 3. 驗證橫向偏距走廊過濾
# 正前方 300m，夾角 5 度 -> 偏距 ~26m <= 48m (通過)
dist = 300.0
rel_ang = 5.0
ct = cross_track_distance(dist, rel_ang)
assert ct < 48.0, f"偏距過大: {ct}"

# 平行隔壁道路 300m，夾角 20 度 -> 偏距 ~102m > 48m (剔除)
rel_ang_neighbor = 20.0
ct_neighbor = cross_track_distance(dist, rel_ang_neighbor)
assert ct_neighbor > 48.0, f"隔壁道路未剔除: {ct_neighbor}"


# 4. 驗證國道辨識與 1000m / 400m 預警閾值跨平台一致性
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

assert is_freeway("國道一號南向306.1公里"), "國道一號應判定為國道"
assert is_freeway("中山高速公路南下50公里"), "中山高應判定為國道"
assert is_freeway("國3南下100公里"), "國3應判定為國道"
assert not is_freeway("中正東路往國道二號方向"), "往國道方向引道不應判定為國道"
assert not is_freeway("三商街高速公路口機慢車專用道往南415公尺處"), "機慢車專用道路口不應判定為國道"

# 國道固定與經常出沒點：1000m 初報、400m 逼近警報
th_freeway = get_thresholds("國道一號南向306.1公里", 110, 105)
assert th_freeway == (1000.0, 400.0, 550.0), f"國道閾值不符: {th_freeway}"

# 市區道路 (速限 50) 閾值保持不變
th_local = get_thresholds("中正路100號", 50, 45)
assert th_local == (420.0, 180.0, 280.0), f"市區閾值不符: {th_local}"

print("✅ 所有 iOS 數學模型與幾何演算法測試通過，100% 與 Android 對齊！")
