@echo off
chcp 65001 > nul
echo ================================================
echo 행정사 2차 모의고사 PDF 자동 추출 프로그램
echo ================================================
echo.

python extract_mocks.py
if errorlevel 1 (
    echo.
    echo [알림] 'python' 명령어를 찾을 수 없거나 실행할 수 없습니다. 'py' 명령어로 시도합니다...
    py extract_mocks.py
    if errorlevel 1 (
        echo.
        echo ================================================
        echo [오류] PC에 파이썬(Python)이 설치되어 있지 않습니다!
        echo 1. 웹브라우저 주소창에 https://www.python.org 를 입력해 접속하세요.
        echo 2. 파이썬을 다운로드하여 설치할 때 반드시
        echo    "Add python.exe to PATH" (환경변수 추가)에 체크하고 설치해 주세요.
        echo ================================================
    )
)

echo.
echo 작업이 끝났습니다. 확인 후 아무 키나 누르면 창이 닫힙니다.
pause
