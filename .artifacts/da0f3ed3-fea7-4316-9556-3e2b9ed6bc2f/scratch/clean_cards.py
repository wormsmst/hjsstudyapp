import json
import os

path = os.path.abspath("../../../../app/src/main/assets/cards.json")
print("Loading cards from:", path)

if not os.path.exists(path):
    # Try alternate path relative to workspace root
    path = "app/src/main/assets/cards.json"

with open(path, "r", encoding="utf-8") as f:
    cards = json.load(f)

original_count = len(cards)
cleaned = []
for c in cards:
    t = c.get("type", "")
    title = c.get("title", "")
    front = c.get("front", "")
    if t == "mnemonic" or title.startswith("두문자") or "두문자 '" in front:
        continue
    cleaned.append(c)

print(f"Original count: {original_count}, Cleaned count: {len(cleaned)}")

with open(path, "w", encoding="utf-8") as f:
    json.dump(cleaned, f, ensure_ascii=False)

print("cards.json cleaned successfully!")
