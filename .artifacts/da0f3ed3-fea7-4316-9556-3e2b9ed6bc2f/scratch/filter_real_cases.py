import json
import os
import re

assets_path = "app/src/main/assets/mock_exams.json"
if not os.path.exists(assets_path):
    assets_path = "../../../app/src/main/assets/mock_exams.json"

with open(assets_path, "r", encoding="utf-8") as f:
    items = json.load(f)

real_cases = []
for idx, it in enumerate(items):
    content = it.get("content", "")
    title = it.get("title", "")
    subject = it.get("subject", "민법")

    if any(keyword in title for keyword in ["정오표", "요약서", "강의계획서", "강평", "최고답안", "답안"]):
        continue

    if ("【문제" in content or "[문제" in content) and ("물음" in content or "설문" in content or "사실관계" in content or "다음" in content):
        parts = re.split(r"(해설|모범답안|예시답안|채점기준)", content)
        question_text = parts[0].strip()
        answer_text = "".join(parts[1:]).strip() if len(parts) > 1 else "모범답안을 참고하세요."

        lines = question_text.split("\n")
        cleaned_lines = [l for l in lines if not any(x in l for x in ["행정사", "모의고사", "교수", "학원", "법학원", "www.", "민법(계약)"])]
        real_question = "\n".join(cleaned_lines).strip()
        if len(real_question) < 30:
            real_question = question_text

        real_cases.append({
            "id": f"real_case_{len(real_cases)+1}",
            "subject": subject,
            "title": title,
            "question": real_question,
            "answer": answer_text,
            "issues": it.get("issues", [])
        })

out_path = "app/src/main/assets/case_exams.json"
with open(out_path, "w", encoding="utf-8") as f:
    json.dump(real_cases, f, ensure_ascii=False, indent=2)

print(f"Filtered down to {len(real_cases)} true case exams in {out_path}")
