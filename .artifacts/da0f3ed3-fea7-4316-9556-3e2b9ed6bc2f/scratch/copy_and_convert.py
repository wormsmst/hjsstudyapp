import os
import shutil
import json
import pandas as pd
import re

src = "C:/Users/규태/OneDrive/바탕 화면/사례문제.xlsx"
if not os.path.exists(src):
    src = os.path.expanduser("~/OneDrive/바탕 화면/사례문제.xlsx")

dst = "temp_사례문제.xlsx"
shutil.copy(src, dst)
print("Copied Excel to temp file.")

def fix_newlines(text):
    if not text:
        return text
    text = str(text).replace("\\n", "\n")
    old_text = ""
    while old_text != text:
        old_text = text
        text = re.sub(r'([가-힣])\s*\n\s*([가-힣])', r'\1\2', text)
    return text

df = pd.read_excel(dst)
items = []
for idx, row in df.iterrows():
    row_dict = {str(k).strip(): str(v).strip() for k, v in row.items() if pd.notna(v)}

    title = row_dict.get("title", row_dict.get("제목", f"사례문제 {idx+1}"))
    subject = row_dict.get("subject", row_dict.get("과목", "민법"))
    question = fix_newlines(row_dict.get("question", row_dict.get("문제", "")))
    explanation = fix_newlines(row_dict.get("explanation", row_dict.get("해설", row_dict.get("답안", ""))))

    if "절차" in subject:
        subject = "행정절차론"
    elif "사무" in subject:
        subject = "사무관리론"
    elif "실무" in subject or "쟁송" in subject:
        subject = "행정사실무법"
    else:
        subject = "민법"

    items.append({
        "id": f"excel_case_{idx+1}",
        "subject": subject,
        "title": title if title and title != "nan" else f"사례문제 {idx+1}",
        "question": question if question and question != "nan" else "문제가 없습니다.",
        "explanation": explanation if explanation and explanation != "nan" else "모범답안을 참고하세요.",
        "issues": []
    })

out_path = "app/src/main/assets/mock_exams.json"
with open(out_path, "w", encoding="utf-8") as f:
    json.dump(items, f, ensure_ascii=False, indent=2)

if os.path.exists(dst):
    os.remove(dst)

print(f"Successfully converted {len(items)} items with explanations!")
