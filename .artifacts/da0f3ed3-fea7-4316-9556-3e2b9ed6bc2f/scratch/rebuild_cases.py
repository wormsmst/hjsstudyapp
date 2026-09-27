import json
import os
import re

assets_path = "app/src/main/assets/mock_exams.json"
if not os.path.exists(assets_path):
    assets_path = "../../../app/src/main/assets/mock_exams.json"

with open(assets_path, "r", encoding="utf-8") as f:
    items = json.load(f)

cases = []
for idx, it in enumerate(items):
    content = it.get("content", "")
    title = it.get("title", "")
    subject = it.get("subject", "민법")

    if "사례" in title or "【문제" in content or "<사실관계>" in content or "물음" in content or "설문" in content or "[문제" in content:
        lines = content.split("\n")
        cleaned_lines = []
        skip_header = True
        for line in lines:
            if skip_header and ("행정사" in line or "모의고사" in line or "교수" in line or "학원" in line or "법학원" in line or "www." in line or "정오표" in line or "강의계획서" in line or len(line.strip()) < 5):
                continue
            skip_header = False
            cleaned_lines.append(line)

        real_content = "\n".join(cleaned_lines).strip()
        if not real_content:
            real_content = content

        parts = re.split(r"(해설|모범답안|예시답안|채점기준)", real_content)
        question_text = parts[0].strip()
        answer_text = "".join(parts[1:]).strip() if len(parts) > 1 else "모범답안을 참고하세요."

        if len(question_text) < 15:
            question_text = real_content
            answer_text = "모범답안을 참고하세요."

        cases.append({
            "id": f"case_{idx+1}",
            "subject": subject,
            "title": title,
            "question": question_text,
            "answer": answer_text,
            "issues": it.get("issues", [])
        })

out_path = "app/src/main/assets/case_exams.json"
with open(out_path, "w", encoding="utf-8") as f:
    json.dump(cases, f, ensure_ascii=False, indent=2)

print(f"Rebuilt {len(cases)} case exams with clean question text.")
