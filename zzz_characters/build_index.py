#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""从人设文档中提取"联系人适配"字段，生成配置总表 CSV + HTML 卡片页 + 索引。"""
import csv, glob, json, os, re

OUT = os.path.dirname(os.path.abspath(__file__))
DOC = os.path.join(OUT, "人设档案")
data = {c["name"]: c for c in json.load(open(os.path.join(OUT, "characters.json"), encoding="utf-8"))}

FIELDS = ["备注名", "个性签名", "分组标签", "对你的称呼", "开场白示例", "典型回复", "口头禅 / 关键词", "雷区 / 注意"]


def cell(md, key):
    """从 markdown 表格里取某一行的值。"""
    m = re.search(r'\|\s*\**%s\**\s*\|(.+?)\|\s*$' % re.escape(key), md, re.M)
    if not m:
        return ""
    v = m.group(1).strip().replace("**", "")
    return v


rows = []
for path in sorted(glob.glob(os.path.join(DOC, "[0-9][0-9][0-9]_*.md"))):
    md = open(path, encoding="utf-8").read()
    base = os.path.basename(path)[:-3]
    num, _, name = base.partition("_")
    c = data.get(name, {})
    row = {"序号": num, "中文名": name,
           "英文名": c.get("name_en", ""), "阵营": c.get("faction", ""),
           "稀有度": c.get("rarity", ""), "属性": c.get("element", ""),
           "头像": c.get("avatar", ""), "文档": "人设档案/" + base + ".md"}
    for f in FIELDS:
        row[f] = cell(md, f)
    rows.append(row)

rows.sort(key=lambda r: r["序号"])
with open(os.path.join(OUT, "联系人配置总表.csv"), "w", encoding="utf-8-sig", newline="") as f:
    w = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
    w.writeheader()
    w.writerows(rows)

# ---- 索引 markdown（按阵营分组）----
from collections import defaultdict
group = defaultdict(list)
for r in rows:
    group[r["阵营"]].append(r)
lines = ["# 人设档案总索引", "",
         "共 %d 位代理人，按阵营分组。点击文件名查看完整人设文档。" % len(rows), "",
         "配套文件：[绝区零世界观设定文档](../绝区零世界观设定文档.md) ｜ "
         "[联系人配置总表](../联系人配置总表.csv) ｜ [写作规范](写作规范.md)", ""]
for fac in sorted(group, key=lambda k: -len(group[k])):
    lines.append("## %s（%d）" % (fac, len(group[fac])))
    lines.append("")
    lines.append("| 序号 | 角色 | 备注名 | 个性签名 | 文档 |")
    lines.append("| --- | --- | --- | --- | --- |")
    for r in group[fac]:
        lines.append("| %s | %s | %s | %s | [%s](%s_%s.md) |" % (
            r["序号"], r["中文名"], r["备注名"] or r["中文名"],
            (r["个性签名"] or "")[:34], r["序号"] + "_" + r["中文名"], r["序号"], r["中文名"]))
    lines.append("")
open(os.path.join(DOC, "00_总索引.md"), "w", encoding="utf-8").write("\n".join(lines))

# ---- HTML 联系人卡片 ----
payload = json.dumps([{
    "n": r["中文名"], "en": r["英文名"], "f": r["阵营"], "r": r["稀有度"], "el": r["属性"],
    "img": r["头像"], "alias": r["备注名"], "sign": r["个性签名"], "grp": r["分组标签"],
    "call": r["对你的称呼"], "hi": r["开场白示例"], "kw": r["口头禅 / 关键词"],
    "avoid": r["雷区 / 注意"], "doc": r["文档"],
} for r in rows], ensure_ascii=False)

HTML = """<!DOCTYPE html><html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>绝区零 · 联系人配置总览</title><style>
:root{--bg:#0b0b0d;--card:#151519;--line:#2a2a30;--txt:#e8e8ea;--dim:#8b8b94;--acc:#d8f34a}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--txt);
font-family:-apple-system,"PingFang SC","Microsoft YaHei",sans-serif}
header{padding:26px 22px 14px;border-bottom:1px solid var(--line);background:linear-gradient(180deg,#151519,#0b0b0d)}
h1{margin:0;font-size:21px;letter-spacing:2px}h1 b{color:var(--acc)}
.sub{color:var(--dim);font-size:12.5px;margin-top:6px}
.bar{display:flex;gap:8px;flex-wrap:wrap;padding:14px 22px;position:sticky;top:0;
background:rgba(11,11,13,.95);backdrop-filter:blur(8px);z-index:9;border-bottom:1px solid var(--line)}
input,select{background:#1b1b21;color:var(--txt);border:1px solid var(--line);border-radius:8px;
padding:8px 11px;font-size:13px;outline:none}input{flex:1;min-width:190px}
input:focus,select:focus{border-color:var(--acc)}.cnt{color:var(--dim);font-size:12.5px;align-self:center}
.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(330px,1fr));gap:14px;padding:18px 22px 44px}
.card{display:flex;gap:12px;background:var(--card);border:1px solid var(--line);border-radius:12px;padding:12px}
.card img{width:78px;height:78px;border-radius:10px;object-fit:cover;flex:0 0 auto;background:#0e0e11}
.info{min-width:0;flex:1}
.nm{font-size:15px;font-weight:600;display:flex;align-items:center;gap:6px}
.r{font-size:10px;padding:1px 5px;border-radius:4px;font-weight:700}
.rS{background:#f2c14e;color:#2a1e00}.rA{background:#a78bfa;color:#1a1030}
.alias{color:var(--acc);font-size:11.5px;margin-top:2px}
.sign{font-size:12px;color:#c9c9d2;margin-top:5px;line-height:1.5}
.kv{font-size:11px;color:var(--dim);margin-top:5px;line-height:1.6;word-break:break-all}
.kv span{color:#9a9aa6}
footer{padding:0 22px 44px;color:#5d5d66;font-size:11.5px;line-height:1.9}
</style></head><body>
<header><h1>绝区零 · 联系人配置总览 <b>%(n)d</b></h1>
<div class="sub">备注名 / 个性签名 / 分组 / 称呼 / 口头禅 / 雷区 —— 可直接对照配置</div></header>
<div class="bar"><input id="q" placeholder="搜索角色名 / 阵营 / 备注名 / 口头禅…">
<select id="f"><option value="">全部阵营</option></select><span class="cnt" id="c"></span></div>
<div class="grid" id="g"></div>
<footer>配套：人设档案/（每人一份完整文档）· 绝区零世界观设定文档.md · 联系人配置总表.csv</footer>
<script>const D=%(data)s;
const f=document.getElementById("f");
[...new Set(D.map(d=>d.f))].forEach(v=>f.add(new Option(v,v)));
function render(){const q=document.getElementById("q").value.trim().toLowerCase(),fv=f.value;
 const L=D.filter(d=>(!fv||d.f===fv)&&(!q||(d.n+d.en+d.f+d.alias+d.kw+d.sign+d.grp).toLowerCase().includes(q)));
 document.getElementById("c").textContent=L.length+" / "+D.length;
 document.getElementById("g").innerHTML=L.map(d=>`<div class="card">
 ${d.img?`<img src="${d.img}" alt="${d.n}" loading="lazy">`:''}
 <div class="info"><div class="nm">${d.n}${d.r?`<span class="r r${d.r}">${d.r}</span>`:''}
 <span style="font-size:10.5px;color:#7b7b88;font-weight:400">${d.f}</span></div>
 <div class="alias">备注名：${d.alias||d.n} · 称呼你：${d.call||'—'}</div>
 <div class="sign">${d.sign||''}</div>
 <div class="kv"><span>分组</span> ${d.grp||'—'}<br><span>口头禅</span> ${d.kw||'—'}<br>
 <span>雷区</span> ${d.avoid||'—'}</div></div></div>`).join("");}
document.getElementById("q").addEventListener("input",render);f.addEventListener("input",render);
render();</script></body></html>"""
open(os.path.join(OUT, "联系人卡片.html"), "w", encoding="utf-8").write(HTML % {"n": len(rows), "data": payload})
print("已生成: 联系人配置总表.csv / 人设档案/00_总索引.md / 联系人卡片.html | 条目:", len(rows))
print("缺失备注名的:", [r["中文名"] for r in rows if not r["备注名"]][:10])