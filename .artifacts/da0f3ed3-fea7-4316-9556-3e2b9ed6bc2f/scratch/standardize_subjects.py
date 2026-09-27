import json
import os

def normalize_subject(subj):
    if not subj:
        return "민법"
    if "절차" in subj:
        return "행정절차론"
    if "사무" in subj:
        return "사무관리론"
    if "실무" in subj or "쟁송" in subj or ("행정사" in subj and "실무" in subj):
        return "행정사실무법"
    if "행정사" in subj and "법" not in subj:
        return "행정사실무법"
    if "민법" in subj:
        return "민법"
    return "행정사실무법"

assets_dir = "app/src/main/assets"
if not os.path.exists(assets_dir):
    assets_dir = "../../../app/src/main/assets"

for fname in ["cards.json", "mock_exams.json", "case_exams.json"]:
    fpath = os.path.join(assets_dir, fname)
    if os.path.exists(fpath):
        with open(fpath, "r", encoding="utf-8") as f:
            data = json.load(f)
        for item in data:
            item["subject"] = normalize_subject(item.get("subject", ""))
        with open(fpath, "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False, indent=2)
        print(f"Standardized {fname} subjects.")
