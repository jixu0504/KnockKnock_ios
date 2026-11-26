#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
gen_presets.py — 生成绝区零（ZZZ）预设种子数据 ZzzPresets.kt

输出：
  shared/src/commonMain/kotlin/com/rhodes/privatechat/shared/data/ZzzPresets.kt
  - 62 个 Operator（id=zzz001..zzz062，avatarUri=knock:NNN）
  - 21 个阵营群 ChatSession 种子（id=group_f01..group_f21，按阵营首次出现顺序）

数据源（只读，默认 ../zzz_characters，可用第一个命令行参数覆盖）：
  - characters.json          62 角色：id/中文名/阵营/稀有度/属性/特性/性别
  - 联系人配置总表.csv        联系人字段：备注名/个性签名/对你的称呼/典型回复/口头禅/雷区/文档
  - 人设档案/NNN_名字.md      「二、人设解读」：性格内核/行为与语言习惯/背景与处境
  - 绝区零世界观设定文档.md   「五、势力版图」：21 势力一句话描述（→ 群 rules，见 FACTION_RULES）

用法：
  python3 tools/zzz/gen_presets.py [数据源目录]
"""

import csv
import json
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_DATA_DIR = REPO_ROOT.parent / "zzz_characters"
OUT_PATH = REPO_ROOT / "shared" / "src" / "commonMain" / "kotlin" / "com" / "rhodes" / \
    "privatechat" / "shared" / "data" / "ZzzPresets.kt"

# 21 阵营 → 群 rules 一句话描述，摘自《绝区零世界观设定文档.md》「五、势力版图」定位列。
# 键必须与 characters.json 的 faction 字段完全一致（脚本会断言全覆盖）。
FACTION_RULES = {
    "都市秩序部": "新艾利都治安局下设的都市秩序部，赛维里安任秩序督察，负责城市日常秩序。",
    "治安局·都市秩序部": "新艾利都治安局下设的执法部门，维持街区日常秩序，公文口吻是标配。",
    "治安局·刑侦特勤组": "新艾利都治安局的刑侦特勤队伍，朱鸢任组长，青衣、赛斯为成员。",
    "防卫军·奥波勒斯小队": "新艾利都防卫军军方小队，隶属黑曜石营，队长「鬼火」在旧都陷落后被转入武器之内。",
    "防卫军·白银小队": "新艾利都防卫军的另一支小队，处理高危事务。",
    "对空六课": "对空洞事务特别行动部第六课，专责空洞的特勤部队级单位，课内有被称为「虚狩」的顶尖战力。",
    "达识结社": "秘密组织，成员中曾有初代「虚狩」。",
    "白祇重工": "老牌重工企业，曾濒临破产，被年轻的珂蕾妲接手重组，如今重回业界前列。",
    "维多利亚家政": "以家政服务为表、处理麻烦为里的组织，莱卡恩是实质领导者兼对外负责人，丽娜是女仆长兼资历最深者。",
    "坎卜斯黑枝": "设有「裁决官」的机构，负责「处刑」与内部审查，TOPS 的人宁可绕道走。",
    "罗斯凯利法·弗林特工坊": "罗斯凯利法传统势力圈下辖的特工坊，讲究血统、名声与公务体系，家族声望即信用。",
    "罗斯凯利法·空域巡戍局": "罗斯凯利法传统势力圈下辖的空域巡戍机构，讲究血统、名声与公务体系，家族声望即信用。",
    "罗斯凯利法·外务筹策局": "罗斯凯利法传统势力圈下辖的外务机构，统筹罗斯凯利法与艾利都的往来协作。",
    "天琴座": "围绕顶级艺人运转的经纪与安保体系。",
    "狡兔屋": "什么都接的万事屋，靠妮可的信誉和安比的刀撑起来。",
    "卡吕冬之子": "外环的机车族团伙，核心仪式是「常胜冠军」——团战中一对一解决对面最强者，象征物是红围巾，可以死但不能输。",
    "怪啖屋": "以「以骸故事」为爱好的社群，成员多有网名，兼职绳匠与投稿作者。",
    "云岿山": "有师承与剑统的门派式组织，至宝为「青溟剑」。",
    "反舌鸟": "怪盗组织，首领雨果表面是画廊收藏家，莱卡恩是他的前搭档。",
    "妄想天使": "偶像团体，有队长、主唱、门面的分工，粉丝叫「梦想家」。",
    "法厄同": "传奇绳匠账号，也是哲与铃兄妹的身份，在绳网上已停止活跃，仍有人四处打听他们的下落。",
}

FALLBACK_RULE = "{faction}成员的内部群聊，日常闲聊与任务沟通。"

# 从「行为与语言习惯」提炼 activity 短语用的动作性提示词
ACT_HINTS = (
    "喜欢", "喜爱", "爱", "常", "经常", "每天", "每日", "习惯", "收集", "保养", "擦拭",
    "摆弄", "捣鼓", "研究", "巡逻", "练", "弹", "唱", "听", "喝", "吃", "逛", "散步",
    "写", "读", "画", "拍", "记账", "数钱", "睡", "赖", "加班", "出外勤", "浇", "种",
    "打游戏", "玩游戏", "观察", "念叨", "吐槽", "随身", "揣", "看", "修", "改", "驾驶",
    "跑", "钓", "做", "买", "卖", "讨价", "喝不惯", "睡不着", "起得", "熬夜",
)

# 从「行为与语言习惯」（其次「性格内核」）提炼 emotion 用的情绪词
EMO_WORDS = (
    "从容", "冷静", "镇定", "温和", "温柔", "平静", "平稳", "淡漠", "冷淡", "松弛",
    "慵懒", "散漫", "紧绷", "紧张", "兴奋", "雀跃", "欢脱", "活泼", "开朗", "元气",
    "阴郁", "沉默", "寡言", "戒备", "警惕", "审慎", "严谨", "认真", "一丝不苟",
    "骄傲", "高傲", "自信", "淡定", "急躁", "急性子", "火爆", "佛系", "安静",
    "克制", "得体", "疏离", "神秘", "随意", "拘谨", "害羞", "腼腆", "别扭",
    "毒舌", "戏谑", "玩味", "严肃", "庄重", "礼貌", "客气", "微笑", "笑眯眯",
    "明朗", "莽", "憨",
)

CLAUSE_SPLIT = re.compile(r"[；;。：:，,、！!？?\n]|——")
SENT_SPLIT = re.compile(r"(?<=[。！？])")


def clean_lines(text: str):
    """去掉 markdown 装饰（引用符/加粗/列表符），返回非空行列表。"""
    out = []
    for line in text.splitlines():
        line = line.strip()
        if not line:
            continue
        line = re.sub(r"^>\s*", "", line)
        line = line.replace("**", "").replace("※", "")
        line = line.strip()
        if line:
            out.append(line)
    return out


def section(md: str, start_pat: str, end_pat: str) -> str:
    m = re.search(start_pat, md, re.M)
    if not m:
        return ""
    rest = md[m.end():]
    m2 = re.search(end_pat, rest, re.M)
    return rest[: m2.start()] if m2 else rest


def parse_profile(md_text: str):
    """解析人设档案「二、人设解读」→ (性格内核, 习惯bullets, 背景与处境) 纯文本。"""
    body = section(md_text, r"^##\s*二、人设解读\s*$", r"^##\s")
    core_sec = section(body, r"^###\s*性格内核\s*$", r"^###\s")
    habit_sec = section(body, r"^###\s*行为与语言习惯\s*$", r"^###\s")
    bg_sec = section(body, r"^###\s*背景与处境\s*$", r"^###\s")

    def prose(sec_text: str) -> str:
        parts = []
        for line in clean_lines(sec_text):
            line = re.sub(r"^-\s*", "", line)
            if parts and parts[-1][-1] not in "。！？…」』”":
                parts[-1] += "。"
            parts.append(line)
        return "".join(parts)

    habit_lines = clean_lines(habit_sec)
    bullets = [re.sub(r"^-\s*", "", l).rstrip("。") for l in habit_lines if l.startswith("-")]
    if not bullets:
        flat = prose(habit_sec)
        bullets = [s.rstrip("。") for s in SENT_SPLIT.split(flat) if s.strip()]
    return prose(core_sec), bullets, prose(bg_sec)


def parse_contact_table(md_text: str) -> dict:
    """兜底：解析「四、联系人适配」表格（CSV 单元格为空时使用）。"""
    sec = section(md_text, r"^##\s*四、联系人适配\s*$", r"^##\s")
    out = {}
    for line in sec.splitlines():
        m = re.match(r"^\|\s*([^|]+?)\s*\|\s*([^|]+?)\s*\|$", line.strip())
        if not m:
            continue
        key, val = m.group(1).strip(), m.group(2).strip()
        if key in ("项目", "---") or set(key) <= {"-", " ", ":"}:
            continue
        out[key] = val
    return out


def cut(text: str, budget: int) -> str:
    """按预算截断，优先落在句读边界；截不尽时以 … 收尾。"""
    text = text.strip()
    if len(text) <= budget:
        return text
    window = text[: budget + 1]
    for seps in ("。！？", "；", "，、："):
        idx = max(window.rfind(ch) for ch in seps)
        if idx >= budget * 0.5:
            return window[: idx + 1]
    return window[:budget].rstrip("，、；：") + "…"


def ensure_end(text: str) -> str:
    text = text.strip()
    if text and text[-1] not in "。！？…":
        text = text.rstrip("，、；：") + "。"
    return text


def strip_end(text: str) -> str:
    """规范字段末尾标点：已以句读结束则原样，否则补一个「。」，避免出现「。。」。"""
    text = text.strip().rstrip("。")
    if text and text[-1] not in "。！？…":
        text += "。"
    return text


def first_sentence(text: str) -> str:
    parts = SENT_SPLIT.split(text.strip())
    return parts[0].strip() if parts and parts[0].strip() else text.strip()


def pick_activity(habits_text: str) -> str:
    for clause in CLAUSE_SPLIT.split(habits_text):
        c = clause.strip(" 「」“”")
        if not 2 <= len(c) <= 14 or re.search(r"[不没别勿]|拒绝|讨厌|禁止", c):
            continue
        if any(h in c for h in ACT_HINTS):
            return c
    return ""


def pick_emotion(habits_text: str, core_text: str) -> str:
    for source in (habits_text, core_text):
        best, best_pos = "", len(source) + 1
        for w in EMO_WORDS:
            pos = source.find(w)
            if pos != -1 and pos < best_pos:
                best, best_pos = w, pos
        if best:
            return best
    return ""


def kesc(s: str) -> str:
    """Kotlin 字符串转义：反斜杠、双引号、$；并压平所有空白控制符。"""
    s = s.replace("\r", "").replace("\n", "").replace("\t", "")
    s = re.sub(r"\s{2,}", " ", s)
    # 去掉 CJK 字符之间的残留空格（markdown 加粗标记 **xxx** 后常带一个空格）
    s = re.sub(r"(?<=[^\x00-\x7f])\s+(?=[^\x00-\x7f])", "", s)
    return s.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$")


def kstr(s: str) -> str:
    return '"' + kesc(s) + '"'


def csv_field(row: dict, keyword: str, contact: dict, contact_key: str) -> str:
    for key, val in row.items():
        if keyword in key:
            val = (val or "").strip()
            if val:
                return val
            break
    return contact.get(contact_key, "").strip()


def main() -> int:
    data_dir = Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_DATA_DIR
    if not data_dir.is_dir():
        print(f"数据源目录不存在：{data_dir}", file=sys.stderr)
        return 1

    chars = json.loads((data_dir / "characters.json").read_text(encoding="utf-8"))
    chars.sort(key=lambda c: c["id"])
    assert len(chars) == 62, f"characters.json 应为 62 个角色，实际 {len(chars)}"

    with open(data_dir / "联系人配置总表.csv", encoding="utf-8-sig", newline="") as f:
        csv_rows = {r["序号"].strip(): r for r in csv.DictReader(f)}

    faction_order, faction_members = [], {}
    op_lines, warnings = [], []

    for c in chars:
        num = f"{c['id']:03d}"
        oid, name, faction = f"zzz{num}", c["name"].strip(), c["faction"].strip()
        row = csv_rows.get(num)
        if row is None:
            warnings.append(f"{num} {name}: CSV 缺行")
            row = {}
        profile_rel = (row.get("文档") or "").strip() or f"人设档案/{num}_{name}.md"
        profile_path = data_dir / profile_rel
        if not profile_path.is_file():
            warnings.append(f"{num} {name}: 人设档案缺失 {profile_rel}")
            md_text = ""
        else:
            md_text = profile_path.read_text(encoding="utf-8")
        core_text, habit_bullets, bg_text = parse_profile(md_text)
        if not core_text:
            warnings.append(f"{num} {name}: 「性格内核」为空")
        contact = parse_contact_table(md_text)

        note = csv_field(row, "备注名", contact, "备注名") or name
        signature = csv_field(row, "个性签名", contact, "个性签名")
        address = csv_field(row, "对你的称呼", contact, "对你的称呼") or "绳匠"
        replies = csv_field(row, "典型回复", contact, "典型回复")
        reply1 = [s.strip() for s in replies.split("；") if s.strip()]
        reply1 = reply1[0] if reply1 else ""
        catchphrase = csv_field(row, "口头禅", contact, "口头禅 / 关键词")
        taboo = csv_field(row, "雷区", contact, "雷区 / 注意")

        title = f"{faction}·{c['specialty']}代理人" if c.get("specialty") else f"{faction}代理人"
        description = cut(f"{note}｜{signature}", 60) if signature else cut(note, 60)

        habits_text = "；".join(habit_bullets)
        activity = pick_activity(habits_text)
        emotion = pick_emotion(habits_text, core_text)

        prefix = f"你是{name}，{faction}的代理人。"
        body = ensure_end(cut(core_text, 200)) + ensure_end(cut(habits_text, 150)) \
            + ensure_end(cut(bg_text, 100))
        suffix = (
            f"你对用户的称呼是{strip_end(address)}"
            f"口头禅/关键词：{strip_end(catchphrase)}"
            f"雷区：{strip_end(taboo)}"
            f"说话风格参照典型回复：{strip_end(reply1)}"
        )
        private_prompt = prefix + body + suffix

        summary = ensure_end(cut(first_sentence(core_text), 70))
        if habit_bullets:
            first_bullet = habit_bullets[0].strip(" 「」“”")
            if len(first_bullet) >= 2:
                summary += ensure_end(cut(first_bullet, 45))
        group_prompt = prefix + cut(summary, max(0, 120 - len(prefix)))

        op_lines.append(
            "        Operator("
            f"id = {kstr(oid)}, name = {kstr(name)}, title = {kstr(title)}, "
            f"description = {kstr(description)}, gender = {kstr(c.get('gender', ''))}, "
            f"location = {kstr(faction)}, activity = {kstr(activity)}, emotion = {kstr(emotion)}, "
            "intimacy = 0, "
            f"privatePrompt = {kstr(private_prompt)}, groupPrompt = {kstr(group_prompt)}, "
            f"avatarUri = {kstr('knock:' + num)}, "
            'lmb = 0, attack = 0.5f, defense = 0.5f, meldPref = "", activityLevel = 0.5f, '
            'voiceName = "", voiceSpeed = "", voicePitch = ""),'
        )

        if faction not in faction_order:
            faction_order.append(faction)
        faction_members.setdefault(faction, []).append(oid)

    missing_rules = [f for f in faction_order if f not in FACTION_RULES]
    extra_rules = [f for f in FACTION_RULES if f not in faction_order]
    assert not missing_rules, f"以下阵营缺 rules 描述：{missing_rules}"
    if extra_rules:
        warnings.append(f"FACTION_RULES 中多余阵营：{extra_rules}")

    group_lines = []
    for i, faction in enumerate(faction_order, 1):
        gid = f"group_f{i:02d}"
        rule = FACTION_RULES.get(faction) or FALLBACK_RULE.format(faction=faction)
        members = ",".join(faction_members[faction])
        group_lines.append(
            "        ChatSession("
            f"id = {kstr(gid)}, operatorId = {kstr(gid)}, operatorName = {kstr(faction)}, "
            f"members = {kstr(members)}, rules = {kstr(rule)}, lastTime = 0L),"
        )

    header = """package com.rhodes.privatechat.shared.data

import com.rhodes.privatechat.shared.model.ChatSession
import com.rhodes.privatechat.shared.model.Operator

/**
 * 绝区零（ZZZ）预设种子数据：62 位代理人 + 21 个阵营群。
 *
 * 本文件由 tools/zzz/gen_presets.py 自动生成，请勿手改；重新生成：
 *   python3 tools/zzz/gen_presets.py
 *
 * 数据来源（只读，仓库外 ../zzz_characters/）：
 * - characters.json：id/中文名/阵营/稀有度/属性/特性/性别（→ name/title/gender/location）
 * - 联系人配置总表.csv：备注名/个性签名/对你的称呼/典型回复/口头禅/雷区（→ description 与 prompt 尾段）
 * - 人设档案/NNN_名字.md「二、人设解读」：性格内核/行为与语言习惯/背景与处境（→ activity/emotion 与 prompt 主体）
 * - 绝区零世界观设定文档.md「五、势力版图」：21 势力一句话描述（→ 群 rules）
 *
 * 头像：avatarUri 一律 "knock:NNN"，iOS 侧按前缀解析到 Resources/AvatarsKnock/knock_NNN.png。
 * 群种子 lastTime=0，入库时由 SessionRepository.initPresetGroups 统一替换为当前时间。
 */
object ZzzPresets {

    /** 62 位绝区零代理人（id=zzz001..zzz062，按 characters.json 序号）。 */
    val operators: List<Operator> = listOf(
"""
    mid = """    )

    /** 21 个阵营群种子（id=group_f01..group_f21，按阵营在 characters.json 中首次出现顺序）。 */
    val groups: List<ChatSession> = listOf(
"""
    tail = """    )
}
"""
    out = header + "\n".join(op_lines) + "\n" + mid + "\n".join(group_lines) + "\n" + tail
    OUT_PATH.write_text(out, encoding="utf-8")

    # ---- 校验与摘要 ----
    n_lines = out.count("\n") + 1
    print(f"生成 {OUT_PATH.relative_to(REPO_ROOT)}：{n_lines} 行，operators={len(op_lines)}，groups={len(group_lines)}")
    for c, line in zip(chars, op_lines):
        m = re.search(r'privatePrompt = "(.*?)", groupPrompt', line)
        assert m and len(m.group(1)) > 100, f"{c['id']} privatePrompt 过短"
    for w in warnings:
        print("WARN:", w)
    print("--- 群种子（id → 阵营 → 成员数） ---")
    for i, faction in enumerate(faction_order, 1):
        print(f"group_f{i:02d} → {faction} → {len(faction_members[faction])}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
