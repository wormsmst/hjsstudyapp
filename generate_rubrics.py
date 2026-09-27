import json
import os
import google.generativeai as genai

# 사용법: 환경 변수에 GEMINI_API_KEY를 설정하거나 아래에 키를 직접 입력하세요.
API_KEY = os.environ.get("GEMINI_API_KEY", "")

if not API_KEY:
    print("⚠️ 경고: GEMINI_API_KEY가 설정되지 않았습니다. 기본 규칙 기반 키워드로 대체됩니다.")

def generate_rubric_file():
    assets_dir = "app/src/main/assets"
    cards_path = os.path.join(assets_dir, "cards.json")
    mocks_path = os.path.join(assets_dir, "mock_exams.json")
    output_path = os.path.join(assets_dir, "rubric_keywords.json")

    cards = []
    if os.path.exists(cards_path):
        with open(cards_path, "r", encoding="utf-8") as f:
            cards = json.load(f)

    mocks = []
    if os.path.exists(mocks_path):
        with open(mocks_path, "r", encoding="utf-8") as f:
            mocks = json.load(f)

    rubrics = {}

    if API_KEY:
        genai.configure(api_key=API_KEY)
        model = genai.GenerativeModel("gemini-1.5-flash")

        print("🤖 Gemini AI를 사용하여 모든 주제의 핵심 채점 키워드(루브릭)를 생성 중입니다...")
        for card in cards:
            title = card.get("topicTitle") or card.get("title")
            back = card.get("back", "")
            if not title or not back:
                continue

            prompt = f"다음 학습 주제와 본문을 참고하여, 실제 행정사 2차 서술형 시험에서 채점위원이 채점할 때 반드시 들어가야 할 **필수 핵심 키워드/쟁점 4~5가지**를 짧은 명사형태(예: '청약자의 의사표시', '승낙의 통지 불필요')로만 쉼표(,)로 구분해서 출력해 줘. 다른 설명은 절대 하지 마.\n\n주제: {title}\n본문: {back[:1500]}"

            try:
                response = model.generate_content(prompt)
                text = response.text.strip()
                keywords = [k.strip() for k in text.replace("\n", ",").split(",") if k.strip()]
                if len(keywords) >= 3:
                    rubrics[title] = keywords[:5]
                    print(f" [성공] {title}: {keywords[:5]}")
            except Exception as e:
                print(f" [실패] {title}: {e}")

    # 만약 AI 생성이 안되었거나 키가 없는 항목은 기본 규칙 기반으로 채워줌
    for card in cards:
        title = card.get("topicTitle") or card.get("title")
        if title and title not in rubrics:
            rubrics[title] = ["의의 및 취지", "요건 및 절차", "법적 효과", "판례의 태도"]

    with open(output_path, "w", encoding="utf-8") as f:
        json.dump(rubrics, f, ensure_ascii=False, indent=2)

    print(f"\n✅ 완료! 총 {len(rubrics)}개 주제의 채점 키워드가 {output_path}에 저장되었습니다.")

if __name__ == "__main__":
    generate_rubric_file()
