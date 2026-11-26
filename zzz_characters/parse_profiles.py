#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把抓到的 wikitext 解析成结构化人设素材 profiles.json"""
import json, re, os

OUT = os.path.dirname(os.path.abspath(__file__))
NAV = ["立绘", "属性数据", "角色晋阶材料", "意象影画", "技能", "技能升级材料", "角色搭配推荐",
       "配装推荐", "驱动盘套装", "词条推荐", "音擎推荐", "技能升级推荐", "阵容搭配", "角色相关",
       "官方介绍", "详细情报", "合作备注", "剧情相关", "活动相关", "官方贺图", "生日贺图", "节日贺图"]


def strip_wiki(t):
    """把 wiki 标记洗成纯文本。"""
    if not t:
        return ""
    t = re.sub(r'<br\s*/?>', '\n', t, flags=re.I)
    t = re.sub(r'</?[a-zA-Z][^>]*>', '', t)
    t = re.sub(r'\{\{折叠框[^|]*\|', '{{COLLAPSE|', t)
    t = re.sub(r'\{\{图标/小\|([^}]*)\}\}', r'【\1】', t)
    t = re.sub(r'\{\{颜色\|[^|]*\|([^}]*)\}\}', r'\1', t)
    t = re.sub(r'\{\{[^{}]*\}\}', '', t)
    t = re.sub(r'\[\[(?:[^|\]]*\|)?([^\]]*)\]\]', r'\1', t)
    t = re.sub(r"'''?", '', t)
    t = t.replace("&nbsp;", " ").replace("&amp;", "&")
    t = re.sub(r'\[\[文件:[^\]]*\]\]', '', t)
    t = re.sub(r'[ \t]+\n', '\n', t)
    t = re.sub(r'\n{3,}', '\n\n', t)
    return t.strip()


def get_template_field(block, key):
    """取模板中 key=value（支持多行，值到下一个顶格 | 或 }} 为止）。"""
    m = re.search(r'\|\s*%s\s*=\s*(.*?)(?=\n\s*\|\s*[^\n=|]+\s*=|\n\}\})' % re.escape(key), block, re.S)
    return m.group(1).strip() if m else ""


def split_sections(txt):
    """按 ==X== / ===X=== 切分。"""
    parts, cur, buf = {}, "开头", []
    for line in txt.split("\n"):
        m = re.match(r'^\s*={2,4}\s*(.+?)\s*={2,4}\s*$', line)
        if m:
            parts[cur] = "\n".join(buf)
            cur, buf = m.group(1).strip(), []
        else:
            buf.append(line)
    parts[cur] = "\n".join(buf)
    return parts


def main():
    raw = json.load(open("/tmp/zzz_raw.json", encoding="utf-8"))
    chars = json.load(open(os.path.join(OUT, "characters.json"), encoding="utf-8"))
    prof = []
    stats = {"arch": 0, "detail": 0, "coop": 0}
    for c in chars:
        txt = raw.get(c["name"], "")
        secs = split_sections(txt)
        arch = {}
        intro = secs.get("官方介绍", "")
        m = re.search(r'\{\{代理人档案(.*)', intro, re.S)
        if m:
            b = m.group(1)
            arch = {"quote": get_template_field(b, "档案1"),
                    "file_name": get_template_field(b, "档案人"),
                    "bio": get_template_field(b, "文案")}
            evals = []
            for i in range(1, 5):
                k, v = get_template_field(b, "评价%d" % i), get_template_field(b, "评价人%d" % i)
                if k or v:
                    evals.append({"title": k, "text": v})
            arch["evals"] = evals
            stats["arch"] += 1
        detail = strip_wiki(secs.get("详细情报", ""))
        coop = strip_wiki(secs.get("合作备注", ""))
        if detail:
            stats["detail"] += 1
        if coop:
            stats["coop"] += 1
        prof.append({
            "id": c["id"], "name": c["name"], "name_en": c["name_en"],
            "full_name": c["full_name"], "rarity": c["rarity"], "element": c["element"],
            "specialty": c["specialty"], "faction": c["faction"], "gender": c["gender"],
            "race": c["race"], "birthday": c["birthday"], "version": c["version"],
            "arch": ({k: strip_wiki(v) for k, v in arch.items() if isinstance(v, str)} |
                     {"evals": [{"title": strip_wiki(e["title"]), "text": strip_wiki(e["text"])}
                                for e in arch.get("evals", [])]}) if arch else {},
            "detail": detail, "coop": coop,
            "story": strip_wiki(secs.get("剧情相关", ""))[:1500],
            "related": strip_wiki(secs.get("角色相关", ""))[:800],
        })
    json.dump(prof, open(os.path.join(OUT, "profiles.json"), "w", encoding="utf-8"),
              ensure_ascii=False, indent=2)
    print("角色:", len(prof), "| 有代理人档案:", stats["arch"],
          "| 有详细情报:", stats["detail"], "| 有合作备注:", stats["coop"])
    no_arch = [p["name"] for p in prof if not p["arch"]]
    print("无档案模板:", no_arch)
    print("\n--- 样例 ---")
    s = prof[0]
    print(json.dumps({k: s[k] for k in ("name", "arch", "detail")}, ensure_ascii=False, indent=1)[:1200])


if __name__ == "__main__":
    main()