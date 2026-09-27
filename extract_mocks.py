import os
import sys
import subprocess
import json
import re

# Ensure pypdf is installed
try:
    import pypdf
except ImportError:
    print("Installing pypdf...")
    subprocess.check_call([sys.executable, "-m", "pip", "install", "pypdf"])
    import pypdf

TARGET_DIR = r"C:\Users\규태\OneDrive\행정사시험\2차\모의고사\26년 행정사 2차 모의고사\26년 행정사2차 모의고사"
OUTPUT_JSON = r"C:\androidstudio\project\AdminMemoApp\app\src\main\assets\mock_exams.json"

def extract_text_from_pdf(pdf_path):
    text = ""
    try:
        reader = pypdf.PdfReader(pdf_path)
        for page in reader.pages:
            t = page.extract_text()
            if t:
                text += t + "\n"
    except Exception as e:
        print(f"Error reading {pdf_path}: {e}")
    return text

def parse_mock_exams():
    if not os.path.exists(TARGET_DIR):
        print(f"Target directory not found: {TARGET_DIR}")
        return

    exams = []

    for root, dirs, files in os.walk(TARGET_DIR):
        rel_path = os.path.relpath(root, TARGET_DIR)
        subject = rel_path.split(os.sep)[0] if rel_path != "." else "기타"
        if subject == ".":
            subject = "기타"

        for file in files:
            if file.lower().endswith(".pdf"):
                pdf_path = os.path.join(root, file)
                print(f"Processing [{subject}] {file}...")
                content = extract_text_from_pdf(pdf_path)

                # Basic parsing into question / answer blocks
                exams.append({
                    "id": f"mock_{len(exams)+1}",
                    "subject": subject,
                    "title": file.replace(".pdf", ""),
                    "fileName": file,
                    "content": content.strip()
                })

    os.makedirs(os.path.dirname(OUTPUT_JSON), exist_ok=True)
    with open(OUTPUT_JSON, "w", encoding="utf-8") as f:
        json.dump(exams, f, ensure_ascii=False, indent=2)

    print(f"Successfully extracted {len(exams)} mock exam files to {OUTPUT_JSON}")

if __name__ == "__main__":
    parse_mock_exams()
