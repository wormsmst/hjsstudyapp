import json
import os

assets_cards_path = r"C:\androidstudio\project\AdminMemoApp\app\src\main\assets\cards.json"
edited_path = r"C:\androidstudio\project\AdminMemoApp\edited_cards.json"
user_cards_path = r"C:\androidstudio\project\AdminMemoApp\user_cards.json"

def merge():
    if not os.path.exists(assets_cards_path):
        print(f"Master cards.json not found at {assets_cards_path}")
        return

    with open(assets_cards_path, "r", encoding="utf-8") as f:
        master_cards = json.load(f)

    edited_map = {}
    if os.path.exists(edited_path):
        with open(edited_path, "r", encoding="utf-8") as f:
            edited_map = json.load(f)

    user_list = []
    if os.path.exists(user_cards_path):
        with open(user_cards_path, "r", encoding="utf-8") as f:
            user_list = json.load(f)

    updated_count = 0
    for i, card in enumerate(master_cards):
        cid = card.get("id")
        if cid in edited_map:
            master_cards[i] = edited_map[cid]
            updated_count += 1

    master_ids = {c.get("id") for c in master_cards}
    added_count = 0
    for card in user_list:
        if card.get("id") not in master_ids:
            master_cards.append(card)
            added_count += 1

    with open(assets_cards_path, "w", encoding="utf-8") as f:
        json.dump(master_cards, f, ensure_ascii=False, indent=2)

    print(f"Merge complete! Updated {updated_count} cards and added {added_count} user cards into cards.json.")

if __name__ == "__main__":
    merge()
