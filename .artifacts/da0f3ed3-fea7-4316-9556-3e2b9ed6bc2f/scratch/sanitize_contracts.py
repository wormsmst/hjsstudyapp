import json
import os

assets_dir = "app/src/main/assets"
if not os.path.exists(assets_dir):
    assets_dir = "../../../app/src/main/assets"

for fname in ["cards.json", "mock_exams.json"]:
    path = os.path.join(assets_dir, fname)
    if os.path.exists(path):
        with open(path, "r", encoding="utf-8") as f:
            data = json.load(f)

        modified = False
        for item in data:
            subj = item.get("subject", "")
            if "계약법" in subj or "민법-계약법" in subj or "민법(계약)" in subj:
                item["subject"] = "민법"
                modified = True

        if modified:
            with open(path, "w", encoding="utf-8") as f:
                json.dump(data, f, ensure_ascii=False, indent=2)
            print(f"Sanitized {fname}")
        else:
            print(f"No contract subject found in {fname}")
