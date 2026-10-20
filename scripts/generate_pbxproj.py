import os
import uuid

def gen_id(name):
    # Deterministic 24-char hex ID based on name
    return uuid.uuid5(uuid.NAMESPACE_DNS, name).hex[:24].upper()

files_sources = [
    ("RoadGuardApp.swift", "RoadGuard/App/RoadGuardApp.swift"),
    ("Models.swift", "RoadGuard/Models/Models.swift"),
    ("HsuehshanTunnelClient.swift", "RoadGuard/Data/HsuehshanTunnelClient.swift"),
    ("OpenDataRepository.swift", "RoadGuard/Data/OpenDataRepository.swift"),
    ("RoadGuardLocationManager.swift", "RoadGuard/Services/RoadGuardLocationManager.swift"),
    ("VoiceAlertEngine.swift", "RoadGuard/Services/VoiceAlertEngine.swift"),
    ("LiveActivityManager.swift", "RoadGuard/Services/LiveActivityManager.swift"),
    ("RadarEngine.swift", "RoadGuard/Services/RadarEngine.swift"),
    ("MainView.swift", "RoadGuard/Views/MainView.swift"),
    ("RadarMapView.swift", "RoadGuard/Views/RadarMapView.swift"),
    ("HsuehshanCardView.swift", "RoadGuard/Views/HsuehshanCardView.swift"),
    ("SettingsView.swift", "RoadGuard/Views/SettingsView.swift"),
]

files_resources = [
    ("cloud_manifest.json", "RoadGuard/Resources/cloud_manifest.json"),
    ("leaflet.css", "RoadGuard/Resources/leaflet.css"),
    ("leaflet.js", "RoadGuard/Resources/leaflet.js"),
    ("radar_map.html", "RoadGuard/Resources/radar_map.html"),
    ("taiwan_cameras_seed.csv", "RoadGuard/Resources/taiwan_cameras_seed.csv"),
    ("taiwan_road_events_seed.json", "RoadGuard/Resources/taiwan_road_events_seed.json"),
]

proj_id = gen_id("RoadGuard.Project")
target_id = gen_id("RoadGuard.Target")
sources_phase_id = gen_id("RoadGuard.SourcesPhase")
resources_phase_id = gen_id("RoadGuard.ResourcesPhase")
frameworks_phase_id = gen_id("RoadGuard.FrameworksPhase")
app_product_id = gen_id("RoadGuard.AppProduct")

main_group_id = gen_id("RoadGuard.MainGroup")
roadguard_group_id = gen_id("RoadGuard.Group.RoadGuard")
app_group_id = gen_id("RoadGuard.Group.App")
models_group_id = gen_id("RoadGuard.Group.Models")
data_group_id = gen_id("RoadGuard.Group.Data")
services_group_id = gen_id("RoadGuard.Group.Services")
views_group_id = gen_id("RoadGuard.Group.Views")
resources_group_id = gen_id("RoadGuard.Group.Resources")
products_group_id = gen_id("RoadGuard.Group.Products")

target_config_list_id = gen_id("RoadGuard.TargetConfigList")
target_debug_config_id = gen_id("RoadGuard.TargetDebugConfig")
target_release_config_id = gen_id("RoadGuard.TargetReleaseConfig")

proj_config_list_id = gen_id("RoadGuard.ProjectConfigList")
proj_debug_config_id = gen_id("RoadGuard.ProjectDebugConfig")
proj_release_config_id = gen_id("RoadGuard.ProjectReleaseConfig")

info_plist_file_id = gen_id("RoadGuard.File.InfoPlist")

content = """// !$*UTF8*$!
{
	archiveVersion = 1;
	classes = {
	};
	objectVersion = 56;
	objects = {

/* Begin PBXBuildFile section */
"""

for fname, fpath in files_sources:
    bf_id = gen_id(f"BuildFile.{fname}")
    fr_id = gen_id(f"FileRef.{fname}")
    content += f"\t\t{bf_id} /* {fname} in Sources */ = {{isa = PBXBuildFile; fileRef = {fr_id} /* {fname} */; }};\n"

for fname, fpath in files_resources:
    bf_id = gen_id(f"BuildFile.{fname}")
    fr_id = gen_id(f"FileRef.{fname}")
    content += f"\t\t{bf_id} /* {fname} in Resources */ = {{isa = PBXBuildFile; fileRef = {fr_id} /* {fname} */; }};\n"

content += """/* End PBXBuildFile section */

/* Begin PBXFileReference section */
"""

content += f"\t\t{app_product_id} /* RoadGuard.app */ = {{isa = PBXFileReference; explicitFileType = wrapper.application; includeInIndex = 0; path = RoadGuard.app; sourceTree = BUILT_PRODUCTS_DIR; }};\n"
content += f"\t\t{info_plist_file_id} /* Info.plist */ = {{isa = PBXFileReference; lastKnownFileType = text.plist.xml; path = Info.plist; sourceTree = \"<group>\"; }};\n"

for fname, fpath in files_sources:
    fr_id = gen_id(f"FileRef.{fname}")
    content += f"\t\t{fr_id} /* {fname} */ = {{isa = PBXFileReference; lastKnownFileType = sourcecode.swift; path = {fname}; sourceTree = \"<group>\"; }};\n"

for fname, fpath in files_resources:
    fr_id = gen_id(f"FileRef.{fname}")
    ftype = "text.json" if fname.endswith(".json") else ("text.css" if fname.endswith(".css") else ("sourcecode.javascript" if fname.endswith(".js") else ("text.html" if fname.endswith(".html") else "text")))
    content += f"\t\t{fr_id} /* {fname} */ = {{isa = PBXFileReference; lastKnownFileType = {ftype}; path = {fname}; sourceTree = \"<group>\"; }};\n"

content += """/* End PBXFileReference section */

/* Begin PBXFrameworksBuildPhase section */
"""
content += f"""\t\t{frameworks_phase_id} /* Frameworks */ = {{
\t\t\tisa = PBXFrameworksBuildPhase;
\t\t\tbuildActionMask = 2147483647;
\t\t\tfiles = (
\t\t\t);
\t\t\trunOnlyForDeploymentPostprocessing = 0;
\t\t}};
/* End PBXFrameworksBuildPhase section */

/* Begin PBXGroup section */
"""

content += f"""\t\t{main_group_id} = {{
\t\t\tisa = PBXGroup;
\t\t\tchildren = (
\t\t\t\t{roadguard_group_id} /* RoadGuard */,
\t\t\t\t{products_group_id} /* Products */,
\t\t\t);
\t\t\tsourceTree = "<group>";
\t\t}};
\t\t{products_group_id} /* Products */ = {{
\t\t\tisa = PBXGroup;
\t\t\tchildren = (
\t\t\t\t{app_product_id} /* RoadGuard.app */,
\t\t\t);
\t\t\tname = Products;
\t\t\tsourceTree = "<group>";
\t\t}};
\t\t{roadguard_group_id} /* RoadGuard */ = {{
\t\t\tisa = PBXGroup;
\t\t\tchildren = (
\t\t\t\t{app_group_id} /* App */,
\t\t\t\t{models_group_id} /* Models */,
\t\t\t\t{data_group_id} /* Data */,
\t\t\t\t{services_group_id} /* Services */,
\t\t\t\t{views_group_id} /* Views */,
\t\t\t\t{resources_group_id} /* Resources */,
\t\t\t\t{info_plist_file_id} /* Info.plist */,
\t\t\t);
\t\t\tpath = RoadGuard;
\t\t\tsourceTree = "<group>";
\t\t}};
"""

# App group
content += f"""\t\t{app_group_id} /* App */ = {{
\t\t\tisa = PBXGroup;
\t\t\tchildren = (
\t\t\t\t{gen_id("FileRef.RoadGuardApp.swift")} /* RoadGuardApp.swift */,
\t\t\t);
\t\t\tpath = App;
\t\t\tsourceTree = "<group>";
\t\t}};
"""

# Models group
content += f"""\t\t{models_group_id} /* Models */ = {{
\t\t\tisa = PBXGroup;
\t\t\tchildren = (
\t\t\t\t{gen_id("FileRef.Models.swift")} /* Models.swift */,
\t\t\t);
\t\t\tpath = Models;
\t\t\tsourceTree = "<group>";
\t\t}};
"""

# Data group
content += f"""\t\t{data_group_id} /* Data */ = {{
\t\t\tisa = PBXGroup;
\t\t\tchildren = (
\t\t\t\t{gen_id("FileRef.HsuehshanTunnelClient.swift")} /* HsuehshanTunnelClient.swift */,
\t\t\t\t{gen_id("FileRef.OpenDataRepository.swift")} /* OpenDataRepository.swift */,
\t\t\t);
\t\t\tpath = Data;
\t\t\tsourceTree = "<group>";
\t\t}};
"""

# Services group
content += f"""\t\t{services_group_id} /* Services */ = {{
\t\t\tisa = PBXGroup;
\t\t\tchildren = (
\t\t\t\t{gen_id("FileRef.RoadGuardLocationManager.swift")} /* RoadGuardLocationManager.swift */,
\t\t\t\t{gen_id("FileRef.VoiceAlertEngine.swift")} /* VoiceAlertEngine.swift */,
\t\t\t\t{gen_id("FileRef.LiveActivityManager.swift")} /* LiveActivityManager.swift */,
\t\t\t\t{gen_id("FileRef.RadarEngine.swift")} /* RadarEngine.swift */,
\t\t\t);
\t\t\tpath = Services;
\t\t\tsourceTree = "<group>";
\t\t}};
"""

# Views group
content += f"""\t\t{views_group_id} /* Views */ = {{
\t\t\tisa = PBXGroup;
\t\t\tchildren = (
\t\t\t\t{gen_id("FileRef.MainView.swift")} /* MainView.swift */,
\t\t\t\t{gen_id("FileRef.RadarMapView.swift")} /* RadarMapView.swift */,
\t\t\t\t{gen_id("FileRef.HsuehshanCardView.swift")} /* HsuehshanCardView.swift */,
\t\t\t\t{gen_id("FileRef.SettingsView.swift")} /* SettingsView.swift */,
\t\t\t);
\t\t\tpath = Views;
\t\t\tsourceTree = "<group>";
\t\t}};
"""

# Resources group
res_children = "".join([f"\t\t\t\t{gen_id(f'FileRef.{fname}')} /* {fname} */,\n" for fname, _ in files_resources])
content += f"""\t\t{resources_group_id} /* Resources */ = {{
\t\t\tisa = PBXGroup;
\t\t\tchildren = (
{res_children}\t\t\t);
\t\t\tpath = Resources;
\t\t\tsourceTree = "<group>";
\t\t}};
"""

content += """/* End PBXGroup section */

/* Begin PBXNativeTarget section */
"""

content += f"""\t\t{target_id} /* RoadGuard */ = {{
\t\t\tisa = PBXNativeTarget;
\t\t\tbuildConfigurationList = {target_config_list_id} /* Build configuration list for PBXNativeTarget "RoadGuard" */;
\t\t\tbuildPhases = (
\t\t\t\t{sources_phase_id} /* Sources */,
\t\t\t\t{frameworks_phase_id} /* Frameworks */,
\t\t\t\t{resources_phase_id} /* Resources */,
\t\t\t);
\t\t\tbuildRules = (
\t\t\t);
\t\t\tdependencies = (
\t\t\t);
\t\t\tname = RoadGuard;
\t\t\tproductName = RoadGuard;
\t\t\tproductReference = {app_product_id} /* RoadGuard.app */;
\t\t\tproductType = "com.apple.product-type.application";
\t\t}};
/* End PBXNativeTarget section */

/* Begin PBXProject section */
"""

content += f"""\t\t{proj_id} /* Project object */ = {{
\t\t\tisa = PBXProject;
\t\t\tattributes = {{
\t\t\t\tBuildIndependentTargetsInParallel = 1;
\t\t\t\tLastSwiftUpdateCheck = 1520;
\t\t\t\tLastUpgradeCheck = 1520;
\t\t\t\tTargetAttributes = {{
\t\t\t\t\t{target_id} = {{
\t\t\t\t\t\tCreatedOnToolsVersion = 15.2;
\t\t\t\t\t}};
\t\t\t\t}};
\t\t\t}};
\t\t\tbuildConfigurationList = {proj_config_list_id} /* Build configuration list for PBXProject "RoadGuard" */;
\t\t\tcompatibilityVersion = "Xcode 14.0";
\t\t\tdevelopmentRegion = en;
\t\t\thasScannedForEncodings = 0;
\t\t\tknownRegions = (
\t\t\t\ten,
\t\t\t\tBase,
\t\t\t);
\t\t\tmainGroup = {main_group_id};
\t\t\tproductRefGroup = {products_group_id} /* Products */;
\t\t\tprojectDirPath = "";
\t\t\tprojectRoot = "";
\t\t\ttargets = (
\t\t\t\t{target_id} /* RoadGuard */,
\t\t\t);
\t\t}};
/* End PBXProject section */

/* Begin PBXResourcesBuildPhase section */
"""

res_build_files = "".join([f"\t\t\t\t{gen_id(f'BuildFile.{fname}')} /* {fname} in Resources */,\n" for fname, _ in files_resources])
content += f"""\t\t{resources_phase_id} /* Resources */ = {{
\t\t\tisa = PBXResourcesBuildPhase;
\t\t\tbuildActionMask = 2147483647;
\t\t\tfiles = (
{res_build_files}\t\t\t);
\t\t\trunOnlyForDeploymentPostprocessing = 0;
\t\t}};
/* End PBXResourcesBuildPhase section */

/* Begin PBXSourcesBuildPhase section */
"""

src_build_files = "".join([f"\t\t\t\t{gen_id(f'BuildFile.{fname}')} /* {fname} in Sources */,\n" for fname, _ in files_sources])
content += f"""\t\t{sources_phase_id} /* Sources */ = {{
\t\t\tisa = PBXSourcesBuildPhase;
\t\t\tbuildActionMask = 2147483647;
\t\t\tfiles = (
{src_build_files}\t\t\t);
\t\t\trunOnlyForDeploymentPostprocessing = 0;
\t\t}};
/* End PBXSourcesBuildPhase section */

/* Begin XCBuildConfiguration section */
"""

content += f"""\t\t{proj_debug_config_id} /* Debug */ = {{
\t\t\tisa = XCBuildConfiguration;
\t\t\tbuildSettings = {{
\t\t\t\tALWAYS_SEARCH_USER_PATHS = NO;
\t\t\t\tCLANG_ANALYZER_NONNULL = YES;
\t\t\t\tCLANG_ENABLE_MODULES = YES;
\t\t\t\tCLANG_ENABLE_OBJC_ARC = YES;
\t\t\t\tDEBUG_INFORMATION_FORMAT = dwarf;
\t\t\t\tENABLE_TESTABILITY = YES;
\t\t\t\tGCC_OPTIMIZATION_LEVEL = 0;
\t\t\t\tIPHONEOS_DEPLOYMENT_TARGET = 16.1;
\t\t\t\tMTL_ENABLE_DEBUG_INFO = INCLUDE_SOURCE;
\t\t\t\tONLY_ACTIVE_ARCH = YES;
\t\t\t\tSDKROOT = iphoneos;
\t\t\t\tSWIFT_ACTIVE_COMPILATION_CONDITIONS = DEBUG;
\t\t\t\tSWIFT_OPTIMIZATION_LEVEL = "-Onone";
\t\t\t}};
\t\t\tname = Debug;
\t\t}};
\t\t{proj_release_config_id} /* Release */ = {{
\t\t\tisa = XCBuildConfiguration;
\t\t\tbuildSettings = {{
\t\t\t\tALWAYS_SEARCH_USER_PATHS = NO;
\t\t\t\tCLANG_ANALYZER_NONNULL = YES;
\t\t\t\tCLANG_ENABLE_MODULES = YES;
\t\t\t\tCLANG_ENABLE_OBJC_ARC = YES;
\t\t\t\tDEBUG_INFORMATION_FORMAT = "dwarf-with-dsym";
\t\t\t\tENABLE_NS_ASSERTIONS = NO;
\t\t\t\tIPHONEOS_DEPLOYMENT_TARGET = 16.1;
\t\t\t\tMTL_ENABLE_DEBUG_INFO = NO;
\t\t\t\tSDKROOT = iphoneos;
\t\t\t\tSWIFT_COMPILATION_MODE = wholemodule;
\t\t\t\tSWIFT_OPTIMIZATION_LEVEL = "-O";
\t\t\t\tVALIDATE_PRODUCT = YES;
\t\t\t}};
\t\t\tname = Release;
\t\t}};
\t\t{target_debug_config_id} /* Debug */ = {{
\t\t\tisa = XCBuildConfiguration;
\t\t\tbuildSettings = {{
\t\t\t\tASSETCATALOG_COMPILER_APPICON_NAME = AppIcon;
\t\t\t\tCODE_SIGN_STYLE = Manual;
\t\t\t\tCODE_SIGNING_ALLOWED = NO;
\t\t\t\tCODE_SIGNING_REQUIRED = NO;
\t\t\t\tCURRENT_PROJECT_VERSION = 19;
\t\t\t\tDEVELOPMENT_TEAM = "";
\t\t\t\tENABLE_PREVIEWS = YES;
\t\t\t\tGENERATE_INFOPLIST_FILE = NO;
\t\t\t\tINFOPLIST_FILE = RoadGuard/Info.plist;
\t\t\t\tLD_RUNPATH_SEARCH_PATHS = (
\t\t\t\t\t"$(inherited)",
\t\t\t\t\t"@executable_path/Frameworks",
\t\t\t\t);
\t\t\t\tMARKETING_VERSION = 2.0.17;
\t\t\t\tPRODUCT_BUNDLE_IDENTIFIER = tw.roadguard.aegis;
\t\t\t\tPRODUCT_NAME = "$(TARGET_NAME)";
\t\t\t\tSWIFT_EMIT_LOC_STRINGS = YES;
\t\t\t\tSWIFT_VERSION = 5.0;
\t\t\t\tTARGETED_DEVICE_FAMILY = "1,2";
\t\t\t}};
\t\t\tname = Debug;
\t\t}};
\t\t{target_release_config_id} /* Release */ = {{
\t\t\tisa = XCBuildConfiguration;
\t\t\tbuildSettings = {{
\t\t\t\tASSETCATALOG_COMPILER_APPICON_NAME = AppIcon;
\t\t\t\tCODE_SIGN_STYLE = Manual;
\t\t\t\tCODE_SIGNING_ALLOWED = NO;
\t\t\t\tCODE_SIGNING_REQUIRED = NO;
\t\t\t\tCURRENT_PROJECT_VERSION = 19;
\t\t\t\tDEVELOPMENT_TEAM = "";
\t\t\t\tENABLE_PREVIEWS = YES;
\t\t\t\tGENERATE_INFOPLIST_FILE = NO;
\t\t\t\tINFOPLIST_FILE = RoadGuard/Info.plist;
\t\t\t\tLD_RUNPATH_SEARCH_PATHS = (
\t\t\t\t\t"$(inherited)",
\t\t\t\t\t"@executable_path/Frameworks",
\t\t\t\t);
\t\t\t\tMARKETING_VERSION = 2.0.17;
\t\t\t\tPRODUCT_BUNDLE_IDENTIFIER = tw.roadguard.aegis;
\t\t\t\tPRODUCT_NAME = "$(TARGET_NAME)";
\t\t\t\tSWIFT_EMIT_LOC_STRINGS = YES;
\t\t\t\tSWIFT_VERSION = 5.0;
\t\t\t\tTARGETED_DEVICE_FAMILY = "1,2";
\t\t\t}};
\t\t\tname = Release;
\t\t}};
/* End XCBuildConfiguration section */

/* Begin XCConfigurationList section */
"""

content += f"""\t\t{proj_config_list_id} /* Build configuration list for PBXProject "RoadGuard" */ = {{
\t\t\tisa = XCConfigurationList;
\t\t\tbuildConfigurations = (
\t\t\t\t{proj_debug_config_id} /* Debug */,
\t\t\t\t{proj_release_config_id} /* Release */,
\t\t\t);
\t\t\tdefaultConfigurationIsVisible = 0;
\t\t\tdefaultConfigurationName = Release;
\t\t}};
\t\t{target_config_list_id} /* Build configuration list for PBXNativeTarget "RoadGuard" */ = {{
\t\t\tisa = XCConfigurationList;
\t\t\tbuildConfigurations = (
\t\t\t\t{target_debug_config_id} /* Debug */,
\t\t\t\t{target_release_config_id} /* Release */,
\t\t\t);
\t\t\tdefaultConfigurationIsVisible = 0;
\t\t\tdefaultConfigurationName = Release;
\t\t}};
/* End XCConfigurationList section */

	}};
	rootObject = {proj_id} /* Project object */;
}}
"""

out_dir = "ios/RoadGuard.xcodeproj"
os.makedirs(out_dir, exist_ok=True)
out_path = os.path.join(out_dir, "project.pbxproj")
with open(out_path, "w", encoding="utf-8") as f:
    f.write(content)

print(f"Successfully generated {out_path} ({len(content)} bytes)")
