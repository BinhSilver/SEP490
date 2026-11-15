"""Sentence boundaries for bilingual JLPT captions; option numbers stay with their text."""
import re

def _option_starts(text, japanese):
    if japanese:
        pattern = r'(?:^|(?<=[\s。！？!?]))([1-4１-４])(?:番[、，]|[.．、](?!\d)|(?=「))\s*'
    else:
        pattern = r'(?:^|(?<=[\s.!?]))(?:(?:Số|Phương án)\s+)?([1-4])(?:[.,、]\s*)(?!\d)'
    found = list(re.finditer(pattern, text, re.I))
    numbers = [int(m[1]) for m in found]
    return [m.start() for m in found] if numbers in ([1, 2, 3], [1, 2, 3, 4]) else []

def sentence_units(text, japanese=True, split_options=True):
    """Return exact, ordered source slices, without splitting punctuation inside quotations."""
    boundaries = {0, len(text)}
    options = _option_starts(text, japanese) if split_options else []
    boundaries.update(options)
    wrapper_positions = {}
    if japanese:
        for a, b in zip(options, options[1:] + [len(text)]):
            block = text[a:b].rstrip()
            if re.match(r'^[1-4１-４]「', block) and block.endswith('」'):
                wrapper_positions[a+1] = '. '
                wrapper_positions[a+len(block)-1] = ''
    stack = []
    closing = {'「': '」', '『': '』', '（': '）', '(': ')', '“': '”', '‘': '’'}
    start = 0
    for i, char in enumerate(text):
        in_options = bool(options) and i >= options[0]
        if char in closing and not in_options:
            stack.append(closing[char])
        elif stack and char == stack[-1]:
            stack.pop()
        if stack:
            continue
        terminal = char in ('。！？!?' if japanese else '.!?')
        if not terminal:
            continue
        if not japanese and char == '.':
            if i > 0 and i + 1 < len(text) and text[i-1].isdigit() and text[i+1].isdigit():
                continue
            # "1." labels an alternative, not an empty sentence.
            if re.fullmatch(r'\s*\d+', text[start:i]):
                continue
            if re.search(r'(?:^|\s)(?:(?:Số|Phương án)\s+)?[1-4]\.$', text[:i+1], re.I):
                continue
            if i + 1 < len(text) and not text[i+1].isspace():
                continue
        end = i + 1
        while end < len(text) and text[end] in '。！？!?」』”’':
            end += 1
        boundaries.add(end)
        start = end
    positions = sorted(boundaries)
    result = []
    for a, b in zip(positions, positions[1:]):
        while a < b and text[a].isspace(): a += 1
        while b > a and text[b-1].isspace(): b -= 1
        if a < b:
            displayed = ''.join(wrapper_positions.get(i, text[i]) for i in range(a, b))
            result.append({'startChar': a, 'endChar': b, 'sourceText': text[a:b], 'text': displayed})
    return result
