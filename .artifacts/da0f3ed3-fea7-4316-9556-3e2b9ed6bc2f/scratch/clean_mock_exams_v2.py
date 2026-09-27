import json
import os
import re

assets_path = "app/src/main/assets/mock_exams.json"
if not os.path.exists(assets_path):
    assets_path = "../../../app/src/main/assets/mock_exams.json"

with open(assets_path, "r", encoding="utf-8") as f:
    items = json.load(f)

cleaned_items = []
for it in items:
    question = it.get("question", "").strip()
    explanation = it.get("explanation", "").strip()
    title = it.get("title", "")

    # Rule 1: Delete cards where question is empty or not a valid problem
    if not question or len(question) < 15:
        continue
    if not any(marker in question for marker in ["【문제", "[문제", "〈문제", "물음", "설문", "사례", "검토하시오", "설명하시오"]):
        continue
    if any(kw in title for kw in ["정오표", "요약서", "강의계획서", "강평", "정리", "노트", "교재", "목차", "답안"]):
        if not ("【문제" in question or "물음" in question):
            continue

    # Rule 2: Clean explanation field
    if explanation and explanation != "모범답안을 참고하세요.":
        lines = explanation.split("\n")
        filtered_expl_lines = []
        skip_header_mode = True
        for line in lines:
            stripped = line.strip()
            is_header_footer = (
                "박문각" in stripped or
                "합격의법학원" in stripped or
                "서울법학원" in stripped or
                "행정사" in stripped or
                "모의고사" in stripped or
                "진도별" in stripped or
                "실전모의" in stripped or
                "김묘엽" in stripped or
                "김중연" in stripped or
                "백운정" in stripped or
                "조민기" in stripped or
                "교수" in stripped or
                "강사" in stripped or
                "www." in stripped or
                "법학원" in stripped or
                "대비" in stripped or
                "모범답안" in stripped or
                "예시답안" in stripped or
                "채점" in stripped or
                re.match(r"^-\s*\d+\s*-$", stripped) or
                len(stripped) < 3
            )
            if skip_header_mode and is_header_footer:
                continue
            if "Ⅰ." in stripped or "1." in stripped or "논점" in stripped or "사안" in stripped or "【문제" in stripped:
                skip_header_mode = False

            if not is_header_footer or not skip_header_mode:
                filtered_expl_lines.append(line)

        explanation = "\n".join(filtered_expl_lines).strip()
        if not explanation:
            explanation = "모범답안을 참고하세요."

    it["question"] = question
    it["explanation"] = explanation
    cleaned_items.append(it)

out_path = "app/src/main/assets/mock_exams.json"
with open(out_path, "w", encoding="utf-8") as f:
    json.dump(cleaned_items, f, ensure_ascii=False, indent=2)

print(f"Refined mock_exams.json: kept {len(cleaned_items)} valid cards.")
