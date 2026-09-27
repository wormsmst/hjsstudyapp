import json
import os

assets_path = "app/src/main/assets/mock_exams.json"
if not os.path.exists(assets_path):
    assets_path = "../../../app/src/main/assets/mock_exams.json"

with open(assets_path, "r", encoding="utf-8") as f:
    items = json.load(f)

filtered = []
removed_count = 0
for it in items:
    subject = it.get("subject", "")
    if "사무관리" in subject:
        removed_count += 1
        continue
    filtered.append(it)

with open(assets_path, "w", encoding="utf-8") as f:
    json.dump(filtered, f, ensure_ascii=False, indent=2)

print(f"Removed {removed_count} 사무관리론 mock exam items. Remaining items: {len(filtered)}")
