#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""规范化头像尺寸，导出 CSV / HTML 图鉴 / 总览图。"""
import csv, json, os
from PIL import Image, ImageDraw

OUT = os.path.dirname(os.path.abspath(__file__))
AV = os.path.join(OUT, "avatars")
SIZE = 250

data = json.load(open(os.path.join(OUT, "characters.json"), encoding="utf-8"))

# 0) 修复信息框解析串行：值形如 "|其他字段=值" 时，说明本字段为空、后面紧跟另一字段
import re
KEYS = {"gender": "性别", "race": "种族", "birthday": "生日", "version": "实装版本",
        "released": "实装日期", "limited": "限定", "rarity": "稀有度", "element": "属性",
        "specialty": "特性", "faction": "阵营", "full_name": "全名", "name_en": "英文名称"}
for c in data:
    for k in list(KEYS):
        v = str(c.get(k, ""))
        m = re.match(r"^\|([^=|]+)=(.*)$", v)
        if m:
            nk, nv = m.group(1).strip(), m.group(2).strip()
            c[k] = ""
            for dk, dn in KEYS.items():
                if dn == nk and not str(c.get(dk, "")).strip():
                    c[dk] = nv
    if not re.match(r"^\d{1,2}月\d{1,2}日$", str(c.get("birthday", ""))):
        c["birthday"] = ""
    if c.get("gender") not in ("男", "女"):
        c["gender"] = ""

# 1) 统一 250x250
for c in data:
    if not c["avatar"]:
        continue
    p = os.path.join(OUT, c["avatar"])
    im = Image.open(p).convert("RGBA")
    if im.size != (SIZE, SIZE):
        im = im.resize((SIZE, SIZE), Image.LANCZOS)
        im.save(p)
for c in data:
    if c["name"] in ("赛维里安", "菲欧妮"):
        c["note"] = "头像由官方立绘裁切"

# 2) CSV（utf-8-sig 便于 Excel 直接打开）
cols = [("id", "序号"), ("name", "中文名"), ("full_name", "全名"), ("name_en", "英文名"),
        ("rarity", "稀有度"), ("element", "属性"), ("specialty", "特性"), ("faction", "阵营"),
        ("gender", "性别"), ("race", "种族"), ("birthday", "生日"), ("version", "实装版本"),
        ("released", "实装日期"), ("limited", "限定"), ("avatar", "头像文件"), ("page", "来源页")]
with open(os.path.join(OUT, "characters.csv"), "w", encoding="utf-8-sig", newline="") as f:
    w = csv.writer(f)
    w.writerow([v for _, v in cols])
    for c in data:
        w.writerow([c.get(k, "") for k, _ in cols])

json.dump(data, open(os.path.join(OUT, "characters.json"), "w", encoding="utf-8"),
          ensure_ascii=False, indent=2)

# 3) 总览图
from PIL import ImageFont
def load_font(px):
    for p in ("/System/Library/Fonts/PingFang.ttc",
              "/System/Library/Fonts/Supplemental/Arial Unicode.ttf",
              "/System/Library/Fonts/Hiragino Sans GB.ttc"):
        if os.path.exists(p):
            try:
                return ImageFont.truetype(p, px)
            except Exception:
                pass
    return ImageFont.load_default()

cw, ch, cols_n = 132, 168, 10
rows_n = (len(data) + cols_n - 1) // cols_n
sheet = Image.new("RGB", (cols_n * cw, rows_n * ch), (16, 16, 18))
dr = ImageDraw.Draw(sheet)
f14, f11 = load_font(14), load_font(11)
for i, c in enumerate(data):
    x, y = (i % cols_n) * cw, (i // cols_n) * ch
    if c["avatar"]:
        im = Image.open(os.path.join(OUT, c["avatar"])).convert("RGBA").resize((116, 116), Image.LANCZOS)
        bg = Image.new("RGBA", (116, 116), (16, 16, 18, 255))
        bg.alpha_composite(im)
        sheet.paste(bg.convert("RGB"), (x + 8, y + 8))
        acc = (216, 243, 74) if c["rarity"] == "S" else (167, 139, 250)
        dr.rectangle([x + 8, y + 8, x + 10, y + 124], fill=acc)
    nm = c["name"] if len(c["name"]) <= 7 else c["name"][:7] + "…"
    dr.text((x + 9, y + 130), nm, fill=(235, 235, 238), font=f14)
    dr.text((x + 9, y + 145), "%s · %s" % (c["element"], c["specialty"]), fill=(130, 130, 140), font=f11)
sheet.save(os.path.join(OUT, "所有角色总览.png"))

# 4) HTML 图鉴
payload = json.dumps([{
    "id": c["id"], "name": c["name"], "full": c["full_name"], "en": c["name_en"],
    "rarity": c["rarity"], "el": c["element"], "sp": c["specialty"],
    "fac": c["faction"], "ver": c["version"], "bd": c["birthday"],
    "img": c["avatar"], "note": c.get("note", ""), "page": c["page"],
} for c in data], ensure_ascii=False)

HTML = """<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>绝区零 · 全角色图鉴（%(n)d 位）</title>
<style>
:root{--bg:#0b0b0d;--card:#151519;--line:#2a2a30;--txt:#e8e8ea;--dim:#8b8b94;--acc:#d8f34a}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--txt);
 font-family:-apple-system,"PingFang SC","Microsoft YaHei",sans-serif}
header{padding:26px 22px 12px;border-bottom:1px solid var(--line);
 background:linear-gradient(180deg,#151519,#0b0b0d)}
h1{margin:0;font-size:21px;letter-spacing:2px}
h1 b{color:var(--acc)}
.sub{color:var(--dim);font-size:12.5px;margin-top:6px}
.bar{display:flex;gap:8px;flex-wrap:wrap;padding:14px 22px;position:sticky;top:0;
 background:rgba(11,11,13,.94);backdrop-filter:blur(8px);z-index:9;border-bottom:1px solid var(--line)}
input,select{background:#1b1b21;color:var(--txt);border:1px solid var(--line);
 border-radius:8px;padding:8px 11px;font-size:13px;outline:none}
input{flex:1;min-width:180px}
input:focus,select:focus{border-color:var(--acc)}
.count{color:var(--dim);font-size:12.5px;align-self:center;white-space:nowrap}
.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(146px,1fr));
 gap:12px;padding:18px 22px 40px}
.card{background:var(--card);border:1px solid var(--line);border-radius:12px;
 overflow:hidden;transition:.18s;cursor:default}
.card:hover{transform:translateY(-3px);border-color:#3d3d46;box-shadow:0 8px 24px rgba(0,0,0,.5)}
.card img{width:100%%;aspect-ratio:1;object-fit:cover;display:block;background:#0e0e11}
.meta{padding:8px 10px 10px}
.nm{font-size:14px;font-weight:600;display:flex;align-items:center;gap:5px}
.r{font-size:10px;padding:1px 5px;border-radius:4px;font-weight:700}
.rS{background:#f2c14e;color:#2a1e00}.rA{background:#a78bfa;color:#1a1030}
.en{color:var(--dim);font-size:10.5px;margin-top:2px;white-space:nowrap;
 overflow:hidden;text-overflow:ellipsis}
.tags{display:flex;gap:4px;margin-top:6px;flex-wrap:wrap}
.tag{font-size:10px;padding:1px 6px;border-radius:20px;background:#22222a;color:#b9b9c4}
.dot{width:7px;height:7px;border-radius:50%%;display:inline-block;margin-right:3px;vertical-align:1px}
footer{padding:0 22px 40px;color:#5d5d66;font-size:11.5px;line-height:1.9}
</style></head><body>
<header>
  <h1>绝区零 · 全角色头像图鉴 <b>%(n)d</b></h1>
  <div class="sub">数据抓取于 %(date)s ｜ 中文名/稀有度/属性/阵营来自 B 站 Wiki，头像为官方方形头像</div>
</header>
<div class="bar">
  <input id="q" placeholder="搜索角色名 / 英文名 / 阵营…">
  <select id="rar"><option value="">全部稀有度</option><option>S</option><option>A</option></select>
  <select id="el"><option value="">全部属性</option></select>
  <select id="fac"><option value="">全部阵营</option></select>
  <span class="count" id="cnt"></span>
</div>
<div class="grid" id="g"></div>
<footer>
  共 %(n)d 位角色 · 头像 %(imgs)d 张（250×250 PNG）· 排序按上线版本从新到旧<br>
  图片来源：wiki.biligame.com/zzz（米哈游《绝区零》官方素材，仅限个人学习/展示使用）<br>
  赛维里安、菲欧妮为最新版本角色，Wiki 暂无方形头像，已用官方立绘裁切补位。
</footer>
<script>
const D = %(data)s;
const EC = {"电":"#b18cff","火":"#ff6b4a","冰":"#6be1ff","物理":"#d8d8de","以太":"#ff7ad9",
            "风":"#7ef2a5","烈霜":"#9fd8ff","玄墨":"#8a8a9c","霜":"#9fd8ff"};
const $ = s => document.querySelector(s);
const el = $("#el"), fac = $("#fac");
[...new Set(D.map(d=>d.el))].forEach(v=>el.add(new Option(v,v)));
[...new Set(D.map(d=>d.fac))].forEach(v=>fac.add(new Option(v,v)));
function render(){
  const q = $("#q").value.trim().toLowerCase(), r = $("#rar").value, e = el.value, f = fac.value;
  const list = D.filter(d => (!r||d.rarity===r) && (!e||d.el===e) && (!f||d.fac===f) &&
    (!q || (d.name+d.full+d.en+d.fac+d.sp).toLowerCase().includes(q)));
  $("#cnt").textContent = list.length + " / " + D.length;
  $("#g").innerHTML = list.map(d=>`
   <div class="card" title="${d.full} ${d.en}${d.note?'｜'+d.note:''}">
     ${d.img?`<img src="${d.img}" alt="${d.name}" loading="lazy">`:''}
     <div class="meta">
       <div class="nm">${d.name}${d.rarity?`<span class="r r${d.rarity}">${d.rarity}</span>`:''}</div>
       <div class="en">${d.en||'&nbsp;'}</div>
       <div class="tags">
         ${d.el?`<span class="tag"><span class="dot" style="background:${EC[d.el]||'#777'}"></span>${d.el}</span>`:''}
         ${d.sp?`<span class="tag">${d.sp}</span>`:''}
       </div>
       <div class="tags"><span class="tag">${d.fac}</span><span class="tag">v${d.ver}</span></div>
     </div></div>`).join("");
}
["#q","#rar","#el","#fac"].forEach(s=>$(s).addEventListener("input",render));
render();
</script></body></html>"""

imgs = sum(1 for c in data if c["avatar"])
html = HTML % {"n": len(data), "imgs": imgs, "data": payload,
               "date": "2026-11-26"}
open(os.path.join(OUT, "index.html"), "w", encoding="utf-8").write(html)
print("已生成: characters.csv / characters.json / index.html / 所有角色总览.png")