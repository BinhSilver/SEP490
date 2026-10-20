import pandas as pd

excel_path = r'C:\Users\LAPTOP LENOVO\Downloads\jlas-learning-path-n5-n4-data-template.xlsx'
xls = pd.ExcelFile(excel_path)
with open('excel_schema.txt', 'w', encoding='utf-8') as f:
    for sheet in xls.sheet_names:
        df = pd.read_excel(xls, sheet_name=sheet, nrows=5)
        f.write(f'Sheet: {sheet}\n')
        f.write(f'{df.head().to_string()}\n\n')
