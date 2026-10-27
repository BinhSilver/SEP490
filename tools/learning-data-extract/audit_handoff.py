"""Read-only handoff inventory. Never executes archives or edits source workbooks.

Usage: python audit_handoff.py SOURCE_DIRECTORY NEW_OUTPUT_DIRECTORY
Outputs are evidence/staging only, never production approval or SQL seeds.
"""
import argparse
from collections import Counter
import hashlib
import io
import json
from pathlib import Path
import zipfile
import xml.etree.ElementTree as ET

import openpyxl


def digest(data):
    return hashlib.sha256(data).hexdigest()


def shape(value):
    if isinstance(value, dict):
        return {k: len(v) if isinstance(v, (list, dict)) else v for k, v in value.items()}
    return {"type": type(value).__name__, "count": len(value) if isinstance(value, list) else None}


def inspect(name, data, texts, tables):
    result = {"name": name, "bytes": len(data), "sha256": digest(data)}
    suffix = Path(name).suffix.lower()
    if suffix == '.json':
        value = json.loads(data.decode('utf-8-sig'))
        result['structure'] = shape(value)
        tables[name] = value
    elif suffix in ('.md', '.txt', '.csv'):
        texts[name] = data.decode('utf-8-sig')
    elif suffix == '.docx':
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            paragraphs = []
            for part in archive.namelist():
                if part.startswith('word/') and part.endswith('.xml'):
                    root = ET.fromstring(archive.read(part))
                    ns = {'w': 'http://schemas.openxmlformats.org/wordprocessingml/2006/main'}
                    for para in root.findall('.//w:p', ns):
                        text = ''.join(t.text or '' for t in para.findall('.//w:t', ns))
                        if text: paragraphs.append(text)
            texts[name] = '\n'.join(paragraphs)
    elif suffix == '.xlsx':
        book = openpyxl.load_workbook(io.BytesIO(data), read_only=True, data_only=False)
        sheets = {}
        for sheet in book:
            rows = [(i, list(row)) for i, row in enumerate(sheet.iter_rows(values_only=True), 1)
                    if any(v is not None for v in row)]
            sheets[sheet.title] = rows
        tables[name] = sheets
        result['sheets'] = {key: {'nonempty_rows': len(rows), 'first_rows': rows[:4]}
                            for key, rows in sheets.items()}
        book.close()
    elif suffix == '.zip':
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            if sum(info.file_size for info in archive.infolist()) > 200_000_000:
                raise ValueError('Archive exceeds audit size limit: ' + name)
            result['entries'] = [inspect(name + '::' + info.filename, archive.read(info), texts, tables)
                                 for info in archive.infolist() if not info.is_dir()]
    else:
        result['inspection'] = 'UNSUPPORTED_BINARY_NOT_EXECUTED'
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    source, output = args.source.resolve(), args.output.resolve()
    if not source.is_dir() or output == source or source in output.parents:
        parser.error('Use a source directory and a separate new output directory.')
    output.mkdir(parents=True, exist_ok=False)
    texts, tables = {}, {}
    inventory = [inspect(str(path.relative_to(source)), path.read_bytes(), texts, tables)
                 for path in sorted(source.rglob('*')) if path.is_file()]
    for filename, value in [('inventory.json', inventory), ('source-texts.json', texts), ('source-tables.json', tables)]:
        (output / filename).write_text(json.dumps(value, ensure_ascii=False, indent=2, default=str), encoding='utf-8')
    print(json.dumps({'files': len(inventory), 'text_documents': len(texts),
                      'structured_documents': len(tables), 'output': str(output)}, ensure_ascii=False))


if __name__ == '__main__':
    main()
