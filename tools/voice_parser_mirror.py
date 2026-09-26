"""VoiceParser 的镜像测试（tools/voice_parser_mirror.py）

app/src/main/java/com/retropro/feature/voice/VoiceParser.kt 的抽取逻辑是纯字符串处理，
这个文件 1:1 复刻它，用同一批用例跑 —— 落地前先在这里验证，避免「二十五」被算成 27 这类问题。

跑法（需要 Python 3，无第三方依赖）：
    python tools/voice_parser_mirror.py

改 VoiceParser 的正则或中文数字逻辑时，**必须**同步改这里并全部跑绿。
（没有接 JUnit 是因为本项目还没配测试依赖；接了之后把它迁成单元测试。）
"""
import re

CN_DIGIT = {
    '零': 0, '〇': 0,
    '一': 1, '壹': 1,
    '二': 2, '两': 2, '贰': 2,
    '三': 3, '叁': 3,
    '四': 4, '肆': 4,
    '五': 5, '伍': 5,
    '六': 6, '陆': 6,
    '七': 7, '柒': 7,
    '八': 8, '捌': 8,
    '九': 9, '玖': 9,
}
CN_UNIT_CHARS = "零〇一壹二两贰三叁四肆五伍六陆七柒八捌九玖十百千"


def cn_to_int(s):
    if not s:
        return None
    if all(c in CN_DIGIT for c in s):
        return int("".join(str(CN_DIGIT[c]) for c in s))
    section = 0
    number = 0
    for ch in s:
        if ch == '十':
            section += (1 if number == 0 else number) * 10
            number = 0
        elif ch == '百':
            section += (1 if number == 0 else number) * 100
            number = 0
        elif ch == '千':
            section += (1 if number == 0 else number) * 1000
            number = 0
        else:
            d = CN_DIGIT.get(ch)
            if d is None:
                return None
            number = d
    section += number
    return section if section > 0 else None


def normalize_numerals(text):
    out = []
    i = 0
    while i < len(text):
        if text[i] in CN_UNIT_CHARS:
            j = i
            while j < len(text) and text[j] in CN_UNIT_CHARS:
                j += 1
            chunk = text[i:j]
            v = cn_to_int(chunk)
            out.append(str(v) if v is not None else chunk)
            i = j
        else:
            out.append(text[i])
            i += 1
    return "".join(out)


HOUR_MIN = re.compile(r"(\d+)\s*(?:个)?\s*(?:小时|钟头)\s*(\d+)?\s*(?:分钟|分)?")
HALF_WITH_LEAD = re.compile(r"(\d+)\s*个\s*半\s*(?:个)?\s*(?:小时|钟头)")
HALF = re.compile(r"半\s*(?:个)?\s*(?:小时|钟头)")
MINUTES = re.compile(r"(\d+)\s*(?:分钟|分)")
FEE_UNIT = re.compile(r"(\d+(?:\.\d+)?)\s*(?:块钱|元钱|块|元)")
FEE_SPENT = re.compile(r"花(?:了|掉)?\s*(\d+(?:\.\d+)?)")
VENUE_AFTER_PREFIX = re.compile(r"(?:在|去了|去|到|和|跟|约了)\s*([\u4e00-\u9fa5]{2,10}?(?:体育馆|羽毛球馆|球馆|俱乐部|运动中心))")
VENUE_ONLY = re.compile(r"^([\u4e00-\u9fa5]{2,10}?(?:体育馆|羽毛球馆|球馆|俱乐部|运动中心))")
VENUE_NOISE = re.compile(r"^(?:今天|昨天|前天|早上|上午|中午|下午|晚上|刚刚|刚才|然后|后来|又|去|在)+")
ANY_DIGIT = re.compile(r"\d")


def extract_duration(text):
    m = HALF_WITH_LEAD.search(text)
    if m:
        return int(m.group(1)) * 60 + 30
    if HALF.search(text) and not ANY_DIGIT.search(text):
        return 30
    m = HOUR_MIN.search(text)
    if m:
        h = int(m.group(1))
        mi = int(m.group(2)) if m.group(2) else 0
        return h * 60 + mi
    m = MINUTES.search(text)
    if m:
        return int(m.group(1))
    return None


def extract_fee(text):
    m = FEE_UNIT.search(text)
    if m:
        return float(m.group(1))
    m = FEE_SPENT.search(text)
    if m:
        return float(m.group(1))
    return None


def extract_venue(text):
    m = VENUE_AFTER_PREFIX.search(text) or VENUE_ONLY.search(text)
    if not m:
        return None
    cleaned = VENUE_NOISE.sub("", m.group(1))
    return cleaned if len(cleaned) >= 4 else None


def parse(text):
    raw = text.strip()
    if not raw:
        return {"durationMin": None, "feeYuan": None, "venue": None}
    t = normalize_numerals(raw)
    return {
        "durationMin": extract_duration(t),
        "feeYuan": extract_fee(t),
        "venue": extract_venue(t),
    }


CASES = [
    # (输入, 期望时长, 期望费用, 期望场馆)
    ("今天在城东体育馆打了两小时花了40块", 120, 40.0, "城东体育馆"),
    ("打了一个半小时，花了三十块", 90, 30.0, None),
    ("两个小时", 120, None, None),
    ("半小时", 30, None, None),
    ("十五分钟", 15, None, None),
    ("1小时40分钟", 100, None, None),
    ("二十五分钟", 25, None, None),
    ("一百二十分钟", 120, None, None),
    ("在羽毛球馆打了120分钟", 120, None, "羽毛球馆"),
    ("花了 68.5 元", None, 68.5, None),
    ("在奥体中心球馆", None, None, "奥体中心球馆"),
    ("三局两胜赢了他", None, None, None),
    ("这球打得太烂了", None, None, None),
    ("去了流星俱乐部", None, None, "流星俱乐部"),
    ("在奥体运动中心打的", None, None, "奥体运动中心"),
    ("城东体育馆打了两小时", 120, None, "城东体育馆"),
    ("今天城东体育馆", None, None, "城东体育馆"),
    ("", None, None, None),
    ("去了XX俱乐部", None, None, None),  # 注：「XX」是拉丁字母，不匹配中文场馆名
]

if __name__ == "__main__":
    fails = 0
    for text, ed, ef, ev in CASES:
        got = parse(text)
        ok = got["durationMin"] == ed and got["feeYuan"] == ef and got["venue"] == ev
        if not ok:
            fails += 1
        print(f"{'PASS' if ok else 'FAIL'} | {text!r:44} -> {got}")
        if not ok:
            print(f"       期望 时长={ed} 费用={ef} 场馆={ev}")
    print()
    print(f"通过 {len(CASES) - fails}/{len(CASES)}")

    # 单独验算中文数字
    print()
    for s, e in [("三", 3), ("十", 10), ("十五", 15), ("二十", 20), ("二十五", 25),
                 ("一百", 100), ("一百二十", 120), ("一百零五", 105), ("两千", 2000)]:
        g = cn_to_int(s)
        print(f"{'PASS' if g == e else 'FAIL'} cn_to_int({s}) = {g} (期望 {e})")
