import json
import os

for fname in ["cards.json", "mock_exams.json"]:
    path = os.path.join("app/src/main/assets", fname)
    if not os.path.exists(path):
        path = os.path.join("../../../app/src/main/assets", fname)
    if os.path.exists(path):
        with open(path, "r", encoding="utf-8") as f:
            data = json.load(f)
        subs = set(item.get("subject", "") for item in data)
        print(f"{fname} subjects:", subs)
