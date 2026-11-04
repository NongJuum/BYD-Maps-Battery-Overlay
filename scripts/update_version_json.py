import json
import re

with open("app/build.gradle", "r", encoding="utf-8") as f:
    gradle = f.read()

vcode_match = re.search(r'versionCode\s+(\d+)', gradle)
vname_match = re.search(r"versionName\s+['\"]([^'\"]+)['\"]", gradle)

if vcode_match and vname_match:
    vcode = int(vcode_match.group(1))
    vname = vname_match.group(1)
    
    with open("version.json", "r", encoding="utf-8") as f:
        data = json.load(f)
        
    data["versionCode"] = vcode
    data["versionName"] = vname
    
    with open("version.json", "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2, ensure_ascii=False)
        f.write("\n")
    print(f"Updated version.json to versionCode={vcode}, versionName={vname}")
else:
    raise SystemExit("Could not find versionCode or versionName in app/build.gradle")
