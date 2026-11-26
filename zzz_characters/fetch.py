#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""抓取《绝区零》全部角色的中文名、英文名、稀有度、属性、阵营与方形头像。"""
import json, os, re, sys, time, html, urllib.parse, urllib.request

OUT = os.path.dirname(os.path.abspath(__file__))
AVDIR = os.path.join(OUT, "avatars")
os.makedirs(AVDIR, exist_ok=True)

UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
API = "https://wiki.biligame.com/zzz/api.php"


def get(url, referer=None, timeout=30, data=None):
    req = urllib.request.Request(url, data=data)
    req.add_header("User-Agent", UA)
    req.add_header("Accept-Language", "zh-CN,zh;q=0.9")
    if referer:
        req.add_header("Referer", referer)
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read()


def api(params):
    return json.loads(get(API + "?" + urllib.parse.urlencode(params)))


# ---------- 1. 从图鉴页解析角色卡片 ----------
def parse_gallery():
    page = get("https://wiki.biligame.com/zzz/" + urllib.parse.quote("角色图鉴"),
               referer="https://wiki.biligame.com/zzz/")
    h = page.decode("utf-8", "ignore")
    rows = []
    for b in re.split(r'(?=<div class="role-box)', h)[1:]:
        name = re.search(r'class="role-name"><a[^>]*>([^<]+)</a>', b)
        if not name:
            continue
        p = dict(re.findall(r'data-param(\d)="([^"]*)"', b))
        img = re.search(r'<img[^>]*?src="(https://patchwiki\.biligame\.com/[^"]+)"[^>]*?>', b)
        # 头像 img 的 alt 一定是 角色头像-xxx.png
        av = re.search(r'<img alt="角色头像-[^"]*"[^>]*?src="([^"]+)"', b) or \
             re.search(r'<img[^>]*?src="([^"]+)"[^>]*?alt="角色头像-', b)
        rows.append({
            "name": html.unescape(name.group(1)).strip(),
            "rarity": p.get("1", ""), "element": p.get("2", ""),
            "faction": p.get("3", ""), "specialty": p.get("4", ""),
            "avatar": av.group(1) if av else "",
        })
    return rows


# ---------- 2. 批量取 wikitext 解析信息框 ----------
def fetch_wikitext(titles):
    out = {}
    for i in range(0, len(titles), 40):
        batch = titles[i:i + 40]
        d = api({"action": "query", "prop": "revisions", "rvprop": "content",
                 "rvslots": "main", "format": "json", "formatversion": "2",
                 "redirects": "1", "titles": "|".join(batch)})
        q = d.get("query", {})
        norm = {}
        for n in q.get("normalized", []):
            norm[n["to"]] = n["from"]
        for r in q.get("redirects", []):
            norm[r["to"]] = r["from"]
        for p in q.get("pages", []):
            title = p.get("title", "")
            rev = (p.get("revisions") or [{}])[0]
            txt = ((rev.get("slots") or {}).get("main") or {}).get("content", "")
            out[norm.get(title, title)] = txt
            out.setdefault(title, txt)
        time.sleep(0.4)
    return out


def parse_infobox(txt):
    i = txt.find("{{角色图鉴")
    if i < 0:
        return {}
    block = txt[i:i + 6000]
    end = block.find("\n}}")
    if end > 0:
        block = block[:end]
    fields = {}
    for m in re.finditer(r'\|\s*([^\n=|]+?)\s*=\s*([^\n]*)', block):
        k, v = m.group(1).strip(), m.group(2).strip()
        if k and k not in fields and not re.search(r'[\[\]{}]', k):
            fields[k] = v
    return fields


# ---------- 3. 头像下载 ----------
def safe(name):
    return re.sub(r'[\\/:*?"<>|]', "_", name.replace("「", "").replace("」", ""))


def download_avatar(url, path):
    if os.path.exists(path) and os.path.getsize(path) > 2000:
        return True
    try:
        data = get(url, referer="https://wiki.biligame.com/zzz/", timeout=40)
        if len(data) < 2000:
            return False
        with open(path, "wb") as f:
            f.write(data)
        return True
    except Exception as e:
        print("  ! 下载失败", url, e)
        return False


def crop_head(src_path, dst_path, size=250):
    """从透明背景立绘中裁出头部方形头像。"""
    from PIL import Image
    im = Image.open(src_path).convert("RGBA")
    W, H = im.size
    alpha = im.split()[-1]
    bbox = alpha.getbbox() or (0, 0, W, H)
    x0, y0, x1, y1 = bbox
    bh = y1 - y0
    # 头顶往下 18% 作为头部区域，取其水平范围
    band = alpha.crop((x0, y0, x1, y0 + max(2, int(bh * 0.18))))
    bb = band.getbbox()
    hx0, hx1 = (x0 + bb[0], x0 + bb[2]) if bb else (x0, x1)
    side = int(max(hx1 - hx0, bh * 0.16) * 1.30)
    cx = (hx0 + hx1) // 2
    top = y0 - int(bh * 0.02)
    left = cx - side // 2
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    crop = im.crop((left, top, left + side, top + side)).resize((size, size), Image.LANCZOS)
    canvas.alpha_composite(crop)
    canvas.save(dst_path)


def main():
    chars = parse_gallery()
    print("图鉴解析到角色:", len(chars))
    titles = [c["name"] for c in chars]
    wt = fetch_wikitext(titles)

    data = []
    for idx, c in enumerate(chars, 1):
        info = parse_infobox(wt.get(c["name"], ""))
        row = {
            "id": idx,
            "name": c["name"],
            "full_name": info.get("全名", ""),
            "name_en": info.get("英文名称", ""),
            "rarity": c["rarity"] or info.get("稀有度", ""),
            "element": c["element"] or info.get("属性", ""),
            "specialty": c["specialty"] or info.get("特性", ""),
            "faction": c["faction"] or info.get("阵营", ""),
            "gender": info.get("性别", ""),
            "race": info.get("种族", ""),
            "birthday": info.get("生日", ""),
            "version": info.get("实装版本", "").strip(),
            "released": info.get("实装日期", ""),
            "limited": info.get("限定", ""),
            "page": "https://wiki.biligame.com/zzz/" + urllib.parse.quote(c["name"].replace(" ", "_")),
        }
        fn = "%03d_%s.png" % (idx, safe(c["name"]))
        path = os.path.join(AVDIR, fn)
        ok = False
        if c["avatar"]:
            ok = download_avatar(c["avatar"], path)
        row["avatar"] = "avatars/" + fn if ok else ""
        row["note"] = "" if ok else "官方方形头像未公开，暂缺"
        data.append(row)
        print(("  ✓ " if ok else "  × ") + c["name"] + ("  [" + row["name_en"] + "]" if row["name_en"] else ""))

    with open(os.path.join(OUT, "characters.json"), "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)

    miss = [d for d in data if not d["avatar"]]
    print("\n完成: %d 位角色, %d 张头像, 缺 %d" % (len(data), len(data) - len(miss), len(miss)))
    for m in miss:
        print("  缺:", m["name"], m["name_en"])
    return data


if __name__ == "__main__":
    main()