import os
import pandas as pd

excel_path = "C:/Users/규태/OneDrive/바탕 화면/사례문제.xlsx"
if not os.path.exists(excel_path):
    excel_path = os.path.expanduser("~/OneDrive/바탕 화면/사례문제.xlsx")

df = pd.read_excel(excel_path)
print("Columns:", df.columns.tolist())
for col in df.columns:
    print(f"Column '{col}' sample:", df[col].dropna().head(2).tolist())
