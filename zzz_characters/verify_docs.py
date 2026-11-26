#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""校验 62 篇人设文档：文件名规整、结构完整、官方原文逐字引用、联系人字段齐全。"""
import glob, json, os, re, unicodedata

OUT = os.path.dirname(os.path.abspath(__file__))
DOC = os.path.join(OUT, "人设档案")
chars = {c["id"]: c for c in json.load(open(os.path.join(OUT, "characters.json"), encoding="utf-8"))}
prof = {p["name"]: p for p in json.load(open(os.path.join(OUT, "profiles_enriched.json"), encoding="utf-8"))}


def safe(n):
    return n.replace("「", "").replace("」", "").replace("&", "_").strip()


def norm(t):
    """比对用归一化：去空白、统一标点。"""
    t = t.replace("　", " ")
    t = re.sub(r'[\s]+', '', t)
    return t


# 1) 文件名规整
renamed = []
for path in sorted(glob.glob(os.path.join(DOC, "[0-9][0-9][0-9]_*.md"))):
    base = os.path.basename(path)[:-3]
    num, _, nm = base.partition("_")
    want = "%03d_%s" % (int(num), safe(chars[int(num)]["name"]))
    if base != want:
        os.rename(path, os.path.join(DOC, want + ".md"))
        renamed.append((base, want))
print("改名:", renamed if renamed else "无")

# 2) 逐篇校验
REQ = ["## 一、官方档案", "## 二、人设解读", "### 性格内核", "### 行为与语言习惯",
       "### 背景与处境", "## 三、人际关系", "## 四、联系人适配", "| 备注名 |", "| 个性签名 |",
       "| 分组标签 |", "| 对你的称呼 |", "| 开场白示例 |", "| 典型回复 |",
       "| 口头禅 / 关键词 |", "| 雷区 / 注意 |"]
BAD_WORDS = ["综上所述", "总而言之", "值得一提的是", "根据资料显示"]
issues = []
stats = {"ok": 0, "words": []}
for i in sorted(chars):
    c, path = chars[i], os.path.join(DOC, "%03d_%s.md" % (i, safe(chars[i]["name"])))
    if not os.path.exists(path):
        issues.append((c["name"], "文件缺失"))
        continue
    md = open(path, encoding="utf-8").read()
    nm = c["name"]
    # 结构
    for k in REQ:
        if k not in md:
            issues.append((nm, "缺结构:" + k))
    # 禁用词
    for b in BAD_WORDS:
        if b in md:
            issues.append((nm, "作文腔:" + b))
    # 原文引用校验
    p = prof.get(nm, {})
    arch = p.get("arch", {})
    nmd = norm(md)
    for label, txt in [("引言", arch.get("quote", "")), ("档案文案", arch.get("bio", ""))]:
        for seg in [s for s in re.split(r'[\n。！？]', txt or "") if len(norm(s)) >= 8]:
            if norm(seg) not in nmd:
                issues.append((nm, "原文未逐字命中[%s]:%s" % (label, seg[:24])))
                break
    for e in arch.get("evals", []):
        key = norm((e.get("title") or "") or (e.get("desc") or ""))[:30]
        if len(key) >= 8 and key not in nmd:
            issues.append((nm, "评价未命中:" + (e.get("title") or e.get("desc"))[:24]))
    # 字数
    body = re.sub(r'[|#>\-*\s]', '', md)
    stats["words"].append(len(body))
    stats["ok"] += 1

print("校验文档:", stats["ok"], "| 正文长度 min/avg/max:",
      min(stats["words"]), sum(stats["words"]) // len(stats["words"]), max(stats["words"]))
print("问题数:", len(issues))
from collections import Counter
cnt = Counter(kind.split(":")[0] for _, kind in issues)
for k, v in cnt.most_common():
    print(" -", k, v)
for x in issues[:25]:
    print("   ", x)