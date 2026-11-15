import unittest
import os
import re

class TestStoreCompliance(unittest.TestCase):
    def test_privacy_policy_exists_and_contains_disclosures(self):
        policy_path = os.path.join("doc", "privacy-policy.html")
        self.assertTrue(os.path.exists(policy_path), f"{policy_path} 必須存在")
        with open(policy_path, "r", encoding="utf-8") as f:
            content = f.read()
        self.assertIn("<!DOCTYPE html>", content)
        self.assertIn("ACCESS_BACKGROUND_LOCATION", content)
        self.assertIn("SYSTEM_ALERT_WINDOW", content)
        self.assertIn("FOREGROUND_SERVICE_LOCATION", content)
        self.assertIn("個人資料保護法", content)

    def test_store_listing_exists_and_meets_standards(self):
        listing_path = os.path.join("doc", "store-listing.md")
        self.assertTrue(os.path.exists(listing_path), f"{listing_path} 必須存在")
        with open(listing_path, "r", encoding="utf-8") as f:
            content = f.read()
        self.assertIn("Google Play", content)
        self.assertIn("App Store", content)
        self.assertIn("顯著揭露聲明", content)
        self.assertIn("Prominent Disclosure", content)

    def test_android_manifest_compliance(self):
        manifest_path = os.path.join("app", "src", "main", "AndroidManifest.xml")
        self.assertTrue(os.path.exists(manifest_path))
        with open(manifest_path, "r", encoding="utf-8") as f:
            content = f.read()
        self.assertIn("android.permission.ACCESS_BACKGROUND_LOCATION", content, "必須宣告背景定位權限")
        self.assertIn('android:foregroundServiceType="location"', content, "前台服務必須宣告 location 類型")

if __name__ == "__main__":
    unittest.main()
