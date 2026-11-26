#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""从档案文本中抽取人物关系：他评+正文提及+同阵营。"""
import json, os, re

OUT = os.path.dirname(os.path.abspath(__file__))
prof = json.load(open(os.path.join(OUT, "profiles.json"), encoding="utf-8"))

# 名字别名表：正文里可能只出现简称
ALIAS = {"星见雅": ["雅"], "「11号」": ["11号", "十一号"], "零号·安比": ["零号·安比", "0号安比"],
         "「扳机」": ["扳机", "Trigger"], "「席德」": ["席德"], "简": ["简·杜", "简杜"],
         "耀嘉音": ["嘉音"], "浅羽悠真": ["悠真"], "月城柳": ["柳"], "星徽·比利": ["比利"],
         "伊芙琳": ["伊芙琳·舒瓦利耶", "伊芙琳"], "赛斯": ["赛斯·洛威尔", "赛斯"]}
CANON = {}
for p in prof:
    CANON[p["name"]] = p["name"]
    CANON[p["full_name"]] = p["name"]
    for a in ALIAS.get(p["name"], []):
        CANON[a] = p["name"]
CANON.update({"哲": "哲", "铃": "铃", "法厄同": "法厄同", "妮可·德玛拉": "妮可",
              "安比·德玛拉": "安比", "凯撒·金": "凯撒", "露西亚娜·奥克希斯·提奥多·德·蒙特夫": "露西"})
NAMES = sorted([n for n in CANON if len(n) >= 2], key=len, reverse=True)


def sentences(t):
    return [s.strip() for s in re.split(r'[。！？\n]', t) if s.strip()]


def find_mentions(text, self_name):
    """返回 {对象: [依据句子]}"""
    hits = {}
    for s in sentences(text):
        low = s
        used = set()
        for n in NAMES:
            if n in low and n not in used:
                tgt = CANON[n]
                if tgt == self_name or tgt in ("哲", "铃"):
                    continue
                if not any(n in x for x in hits.get(tgt, [])):
                    hits.setdefault(tgt, [])
                    if len(hits[tgt]) < 2:
                        hits[tgt].append(s[:110])
                used.add(n)
    return hits


def main():
    enriched = []
    for p in prof:
        arch = p.get("arch", {})
        evals = arch.get("evals", [])
        # 1) 他评：评价人是其他角色 -> 关系
        others = []
        for e in evals:
            title = (e.get("title") or "").strip()
            desc = (e.get("text") or "").strip()
            if not title.startswith(("「", "『")):
                others.append({"speaker_raw": "", "speaker": "", "quote": "",
                               "tag": title, "desc": desc[:160]})
                continue
            who, quote = desc, title
            canon = None
            for n in NAMES:
                if n in who:
                    canon = CANON[n]
                    break
            others.append({"speaker_raw": who[:40], "speaker": canon or "",
                           "quote": quote[:160], "tag": "", "desc": ""})
        # 2) 正文提及
        body = "\n".join([arch.get("bio", ""), p.get("detail", ""), p.get("coop", ""), p.get("story", "")])
        mentions = find_mentions(body, p["name"])
        # 3) 同阵营
        mates = [q["name"] for q in prof if q["faction"] == p["faction"] and q["name"] != p["name"]]
        enriched.append({**p, "relations": {
            "evaluators": others,
            "mentions": mentions,
            "faction_mates": mates,
            "same_team_hint": "",
        }})

    json.dump(enriched, open(os.path.join(OUT, "profiles_enriched.json"), "w", encoding="utf-8"),
              ensure_ascii=False, indent=2)

    # 汇总统计 + 关系边
    edges = []
    for e in enriched:
        for m, ev in e["relations"]["mentions"].items():
            edges.append((e["name"], m, ev[0][:60]))
    print("角色:", len(enriched), "| 提及关系边:", len(edges))
    print("\n--- 样例：安比 ---")
    a = next(x for x in enriched if x["name"] == "安比")
    print(json.dumps(a["relations"], ensure_ascii=False, indent=1)[:900])


if __name__ == "__main__":
    main()