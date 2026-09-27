import json
import os
import re

assets_path = "app/src/main/assets/mock_exams.json"
if not os.path.exists(assets_path):
    assets_path = "../../../app/src/main/assets/mock_exams.json"

with open(assets_path, "r", encoding="utf-8") as f:
    items = json.load(f)

def fix_newlines(text):
    if not text:
        return text
    old_text = ""
    while old_text != text:
        old_text = text
        text = re.sub(r'([가-힣])\s*\n\s*([가-힣])', r'\1\2', text)
    return text

filtered = []
removed_samu = 0
for it in items:
    subject = it.get("subject", "")
    if subject == "사무":
        removed_samu += 1
        continue

    it["question"] = fix_newlines(it.get("question", ""))
    it["explanation"] = fix_newlines(it.get("explanation", ""))
    filtered.append(it)

with open(assets_path, "w", encoding="utf-8") as f:
    json.dump(filtered, f, ensure_ascii=False, indent=2)

print(f"Removed {removed_samu} '사무' items. Remaining items: {len(filtered)}. Fixed word-splitting newlines.")
