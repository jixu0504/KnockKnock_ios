#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
make_minimal_backup.py — RhodesLink iOS 移植 Phase 0 金样备份包构造器（T10 backup-restore-loop）

按 Android 1.14.27（tag v1.14.27-android-baseline @ 3642d2c）BackupFileWriter 的字节规则
构造一个最小完整备份包 minimal-1.14.27.zip：

  1 角色（golden）+ 1 私聊会话（session_golden）+ 7 条消息（文本/ai_json/图片/ai_json 内嵌图引用）
  + 1 个 1×1 PNG 媒体文件。

行为 oracle（全部只读引用，本脚本不改 Android 源码）：
  - 容器/路径/常量     app/src/main/java/com/rhodes/privatechat/data/backup/BackupFileWriter.kt:18-24,46-98
  - 分页规则           BackupFileWriter.kt:49-58 + BackupPagePlanner.kt:13-84
  - manifest/载荷模型   BackupModels.kt:36-98
  - 媒体 mediaId 规则   BackupMediaCollector.kt:24-30,71-72
  - 会话 ID 规则        shared/.../data/SessionRepository.kt:43 ("session_$operatorId")
  - 图片消息 JSON       app/.../viewmodel/ChatViewModel.kt:1859-1863 {"imageUri","caption","visionSummary"}
  - 校验流程           BackupFileReader.kt:23-100,102-121,132-207,238-244

用法：
  python3 make_minimal_backup.py                 # 重新生成并自校验
  python3 make_minimal_backup.py --verify <zip>  # 只校验（镜像 BackupFileReader 关键检查）
"""
import base64
import hashlib
import json
import os
import sys
import zipfile
from datetime import datetime

# ---------------------------------------------------------------- 常量（对齐 Android oracle）
FULL_FORMAT = "rhodes-backup"
FORMAT_VERSION = 2
MANIFEST_PATH = "manifest.json"
PAGES_ROOT = "data/pages"
OUT_NAME = "minimal-1.14.27.zip"

APP_VERSION = "1.14.27"
SCHEMA_VERSION = 1
CREATED_AT = 1750000000000            # 固定 createdAt（可复现；Android 为 System.currentTimeMillis）
BACKUP_ID = "1f3d9b7c-2a4e-4c8b-9d01-0000000000a1"
EXCLUDED = ["credentials", "vector_index", "worker_state", "temporary_files"]

PACKAGE = "com.buzidongshouhuoji.privatechat"          # app/build.gradle.kts applicationId
IMAGE_NAME = "golden_1x1.png"
# Uri.fromFile 存的是调用时的路径（/data/user/0 形式）；collector 哈希的是 canonicalPath
# （真机上 /data/user/0 -> /data/data 符号链接，BackupMediaCollector.kt:64,71-72）。
ORIGINAL_URI = "file:///data/user/0/{pkg}/files/images/{name}".format(pkg=PACKAGE, name=IMAGE_NAME)
CANONICAL_PATH = "/data/data/{pkg}/files/images/{name}".format(pkg=PACKAGE, name=IMAGE_NAME)

STABLE_ID = hashlib.sha256(CANONICAL_PATH.encode("utf-8")).hexdigest()[:24]   # collector :71-72
MEDIA_ID = "local-image/" + STABLE_ID                                          # collector :26
ARCHIVE_PATH = "media/images/" + STABLE_ID + ".png"                             # collector :27
MIME = "image/png"                                                              # collector :74-80

# 1×1 透明 PNG（最小合法字节序列）
PNG_BYTES = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
)

T0 = 1749999000000
FIXED_ZIP_DATE = (2026, 1, 1, 0, 0, 0)


def msg(i, sender_name, content, mtype, is_me, ts):
    """MessageExport 字段按 ExportPayload.kt:102-109 声明顺序（encodeDefaults=true 全量写出）。"""
    return {
        "id": i, "sessionId": "session_golden", "senderId": "", "senderName": sender_name,
        "content": content, "type": mtype, "mode": "online", "emotion": "", "activity": "",
        "location": "", "narration": "", "segmentGroup": "", "intimacyChange": 0,
        "timestamp": ts, "isMe": is_me,
    }


def build_payload_data():
    # ---- 角色（OperatorExport.kt 字段序，Operator.kt:6-29 同名）----
    operator = {
        "id": "golden", "name": "金样演示员", "title": "Phase 0 金样角色",
        "description": "用于 iOS 备份恢复闭环验证的最小金样角色。",
        "gender": "女", "avatarUri": "", "location": "宿舍", "activity": "休息", "emotion": "平静",
        "intimacy": 0, "privatePrompt": "你是金样演示员，用于备份恢复验证。", "groupPrompt": "",
        "memoryInjection": "", "userRelation": "朋友", "lmb": 10000, "attack": 0.5, "defense": 0.5,
        "meldPref": "medium", "activityLevel": 0.5, "voiceName": "", "voiceSpeed": "", "voicePitch": "",
    }

    # ---- 会话（SessionExport.kt:86-92；id 规则 SessionRepository.kt:43）----
    session = {
        "id": "session_golden", "operatorId": "golden", "operatorName": "金样演示员",
        "lastMessage": "嗯，周末一起去吧。", "lastTime": T0 + 6 * 60000, "mode": "online",
        "isPinned": False, "unreadCount": 0, "members": "", "rules": "", "avatarUri": "", "mutedMembers": "",
    }

    # ---- 消息（7 条，覆盖 text / ai_json / image / ai_json 内嵌 imageUri 引用）----
    ai_json_reply_1 = json.dumps({
        "emotion": "平静", "location": "宿舍", "narration": "",
        "segments": [
            {"type": "dialogue", "content": "是啊，风很舒服。要一起去甲板上透透气吗？", "speaker": ""},
            {"type": "narration", "content": "她朝你笑了笑", "speaker": ""},
        ],
    }, ensure_ascii=False)
    ai_json_reply_image = json.dumps({
        "emotion": "开心",
        "segments": [
            {"type": "dialogue", "content": "记得呀，虽然它很小，但颜色很特别。", "speaker": ""},
            # ai_json 内嵌图引用：imageUri 键（解析器 MessageParser.kt:22 ignoreUnknownKeys=true 容忍，
            # 收集器 BackupMediaCollector.kt:86 正则可发现该引用）
            {"type": "image", "content": "[图片]", "speaker": "", "imageUri": ORIGINAL_URI},
        ],
    }, ensure_ascii=False)
    image_content = json.dumps({
        "imageUri": ORIGINAL_URI,
        "caption": "我拍的 1×1 测试图",
        "visionSummary": "一张纯色的极小图片",
    }, ensure_ascii=False)  # ChatViewModel.kt:1859-1863 的字段形状

    messages = [
        msg(1, "我", "今天天气不错，想出去走走。", "text", True, T0 + 0 * 60000),
        msg(2, "金样演示员", ai_json_reply_1, "ai_json", False, T0 + 1 * 60000),
        msg(3, "我", "对了，昨天拍的那张照片你还记得吗？", "text", True, T0 + 2 * 60000),
        msg(4, "我", image_content, "image", True, T0 + 3 * 60000),
        msg(5, "金样演示员", ai_json_reply_image, "ai_json", False, T0 + 4 * 60000),
        msg(6, "金样演示员", "周末要不要一起出去逛逛？我知道一家不错的甜品店。", "text", False, T0 + 5 * 60000),
        msg(7, "我", "嗯，周末一起去吧。", "text", True, T0 + 6 * 60000),
    ]

    media = [{
        "mediaId": MEDIA_ID, "archivePath": ARCHIVE_PATH, "mimeType": MIME,
        "originalUri": ORIGINAL_URI,
    }]
    return operator, session, messages, media


def export_payload(operators=None, sessions=None, messages=None):
    """ExportPayload.kt:18-39 字段序；encodeDefaults=true 时 Android 会全量写出（含 null）。"""
    return {
        "version": 1, "type": "full_backup", "timestamp": CREATED_AT,
        "operators": operators, "relationships": None, "sessions": sessions, "session": None,
        "messages": messages, "memories": None, "anchors": None, "moments": None,
        "momentLikes": None, "momentComments": None, "diaries": None, "memoryItems": None,
        "knowledgeBases": None, "knowledgeBaseChunks": None,
        "operatorKnowledgeBaseAssignments": None, "settings": None,
    }


def backup_payload(content, media=None, display_events=None, chat_archives=None,
                   chat_history_segments=None, gift_records=None, dispatch_records=None,
                   shared_experiences=None, shared_participants=None):
    """BackupModels.kt:86-98 字段序（encodeDefaults=true 全量写出）。"""
    return {
        "content": content,
        "displayEvents": display_events if display_events is not None else [],
        "chatArchives": chat_archives if chat_archives is not None else [],
        "chatHistorySegments": chat_history_segments if chat_history_segments is not None else [],
        "giftRecords": gift_records if gift_records is not None else [],
        "dispatchRecords": dispatch_records if dispatch_records is not None else [],
        "mahjongSave": None,
        "sharedExperiences": shared_experiences if shared_experiences is not None else [],
        "sharedExperienceParticipants": shared_participants if shared_participants is not None else [],
        "media": media if media is not None else [],
    }


def page(category, page_index, payload):
    """BackupPayloadPage（BackupModels.kt:64-69）。"""
    return {"category": category, "pageIndex": page_index, "payload": payload}


def record_count(page_payload):
    """镜像 BackupFileReader.kt:123-130 / BackupFileWriter.kt:101-108 的页记录数公式（媒体不计入）。"""
    c = page_payload["content"]
    return (len(c["operators"] or []) + len(c["relationships"] or []) + len(c["sessions"] or []) +
            len(c["messages"] or []) + len(c["memories"] or []) + len(c["anchors"] or []) +
            len(c["memoryItems"] or []) + len(c["moments"] or []) + len(c["momentLikes"] or []) +
            len(c["momentComments"] or []) + len(c["diaries"] or []) +
            len(c["knowledgeBases"] or []) + len(c["knowledgeBaseChunks"] or []) +
            len(c["operatorKnowledgeBaseAssignments"] or []) +
            len(page_payload["displayEvents"]) + len(page_payload["chatArchives"]) +
            len(page_payload["chatHistorySegments"]) + len(page_payload["giftRecords"]) +
            len(page_payload["dispatchRecords"]) + len(page_payload["sharedExperiences"]) +
            len(page_payload["sharedExperienceParticipants"]) +
            (1 if page_payload["mahjongSave"] is not None else 0) +
            (1 if c["settings"] is not None else 0))


def build_archive():
    operator, session, messages, media = build_payload_data()

    # ---- 分页（BackupPagePlanner.kt:13-30 的最小数据形态）----
    # roles/000000：operators chunk #0，media 随 index==0 的页走（BackupPagePlanner.kt:14-18）
    pages = [
        page("ROLES", 0, backup_payload(export_payload(operators=[operator]), media=media)),
        # chats/000000：sessions（:25-27）
        page("CHATS", 0, backup_payload(export_payload(sessions=[session]))),
        # chats/010000：messages（:28-30）
        page("CHATS", 10000, backup_payload(export_payload(messages=messages))),
    ]

    page_files = []
    for p in pages:
        path = "{root}/{cat}/{idx:06d}.json".format(
            root=PAGES_ROOT, cat=p["category"].lower(), idx=p["pageIndex"])  # BackupFileWriter.kt:52
        page_files.append((path, p, record_count(p["payload"])))

    # ---- manifest（BackupModels.kt:36-51 字段序；files 按写入序）----
    manifest = {
        "format": FULL_FORMAT, "formatVersion": FORMAT_VERSION, "scope": "full",
        "appVersion": APP_VERSION, "schemaVersion": SCHEMA_VERSION, "createdAt": CREATED_AT,
        "backupId": BACKUP_ID, "mediaIncluded": True,
        "recordCounts": {
            "operators": 1, "relationships": 0, "sessions": 1, "messages": len(messages),
            "diaries": 0, "moments": 0, "comments": 0, "displayEvents": 0, "archives": 0,
            "historySegments": 0, "gifts": 0, "dispatches": 0, "sharedExperiences": 0,
            "knowledgeBases": 0, "knowledgeBaseChunks": 0, "knowledgeBaseAssignments": 0,
            "mediaFiles": 1,
        },
        "files": [],
        "excludedCategories": list(EXCLUDED),
        "layout": "paged_payload",
        "pages": [{"category": p["category"], "pageIndex": p["pageIndex"], "path": path,
                   "recordCount": cnt} for path, p, cnt in page_files],
    }

    def enc(obj):
        return json.dumps(obj, ensure_ascii=False, separators=(",", ":")).encode("utf-8")

    # files 清单 = 分页 + 媒体（按写入序；BackupFileWriter.kt:52-79 的 entries 累积顺序）
    for path, p, cnt in page_files:
        b = enc(p)
        manifest["files"].append({"path": path, "size": len(b), "sha256": sha256_hex(b)})
    manifest["files"].append({
        "path": ARCHIVE_PATH, "size": len(PNG_BYTES), "sha256": sha256_hex(PNG_BYTES),
    })

    out = os.path.join(os.path.dirname(os.path.abspath(__file__)), OUT_NAME)
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        for path, p, cnt in page_files:
            zi = zipfile.ZipInfo(path, FIXED_ZIP_DATE)
            zi.compress_type = zipfile.ZIP_DEFLATED
            z.writestr(zi, enc(p))
        zi = zipfile.ZipInfo(ARCHIVE_PATH, FIXED_ZIP_DATE)
        zi.compress_type = zipfile.ZIP_DEFLATED
        z.writestr(zi, PNG_BYTES)
        # manifest 最后写（BackupFileWriter.kt:96）
        zi = zipfile.ZipInfo(MANIFEST_PATH, FIXED_ZIP_DATE)
        zi.compress_type = zipfile.ZIP_DEFLATED
        z.writestr(zi, enc(manifest))
    return out


# ---------------------------------------------------------------- 校验（镜像 BackupFileReader）
class CheckFailed(Exception):
    pass


def sha256_hex(b):
    return hashlib.sha256(b).hexdigest()


def is_safe_archive_path(path):
    """BackupFileReader.kt:238-244。"""
    n = path.replace("\\", "/")
    return (n.strip() != "" and not n.startswith("/") and ":/" not in n and
            all(part not in ("", ".", "..") for part in n.split("/")))


def verify(zip_path):
    checks = []
    with zipfile.ZipFile(zip_path) as z:
        names = z.namelist()
        names_flat = sorted(names)
        if len(names_flat) != len(set(names_flat)):
            raise CheckFailed("ZIP 存在重复路径")
        for n in names_flat:
            if not is_safe_archive_path(n):
                raise CheckFailed("ZIP 路径不安全: " + n)

        checks.append(("安全路径 & 无重复路径", len(names_flat)))

        manifest = json.loads(z.read(MANIFEST_PATH).decode("utf-8"))
        if manifest["format"] != FULL_FORMAT:
            raise CheckFailed("format 不匹配")
        if manifest["formatVersion"] not in (1, FORMAT_VERSION):
            raise CheckFailed("formatVersion 不匹配")
        if manifest["scope"] != "full":
            raise CheckFailed("scope 不匹配")
        checks.append(("manifest: rhodes-backup v2 full", 1))

        # manifest.files 与 ZIP 内容一致（不含 manifest.json 本身）— BackupFileReader.kt:67-74
        declared = manifest["files"]
        if len({f["path"] for f in declared}) != len(declared):
            raise CheckFailed("manifest 文件清单路径重复")
        if {f["path"] for f in declared} != set(names_flat) - {MANIFEST_PATH}:
            raise CheckFailed("manifest 文件清单与 ZIP 内容不一致")
        for f in declared:
            data = z.read(f["path"])
            if len(data) != f["size"]:
                raise CheckFailed("文件大小不一致: " + f["path"])
            if sha256_hex(data) != f["sha256"].lower():
                raise CheckFailed("文件 SHA-256 不一致: " + f["path"])
        checks.append(("逐条目 size+SHA-256 校验", len(declared)))

        # v2 分页布局 — BackupFileReader.kt:102-121
        if manifest["layout"] != "paged_payload":
            raise CheckFailed("v2 布局无效")
        if "data/backup.json" in names_flat:
            raise CheckFailed("v2 备份不能包含单一 payload")
        pages = manifest["pages"]
        if not pages:
            raise CheckFailed("缺少分页清单")
        if len({p["path"] for p in pages}) != len(pages):
            raise CheckFailed("分页清单路径重复")
        by_cat = {}
        for p in pages:
            by_cat.setdefault(p["category"], []).append(p["pageIndex"])
        for cat, idxs in by_cat.items():
            if len(idxs) != len(set(idxs)):
                raise CheckFailed("分页索引重复: " + cat)
        page_paths = {n for n in names_flat if n.startswith(PAGES_ROOT + "/")}
        if {p["path"] for p in pages} != page_paths:
            raise CheckFailed("分页清单与 ZIP 内容不一致")

        # 每页：标识一致 + recordCount 一致 — BackupFileReader.kt:111-119
        merged_payload = None
        for p in sorted(pages, key=lambda e: (e["category"], e["pageIndex"])):
            if not p["path"].startswith(PAGES_ROOT + "/"):
                raise CheckFailed("分页路径无效")
            pg = json.loads(z.read(p["path"]).decode("utf-8"))
            if pg["category"] != p["category"] or pg["pageIndex"] != p["pageIndex"]:
                raise CheckFailed("分页标识不一致: " + p["path"])
            if record_count(pg["payload"]) != p["recordCount"]:
                raise CheckFailed("分页记录数不一致: " + p["path"])
            if pg["payload"]["content"]["type"] != "full_backup":
                raise CheckFailed("分页内容不是完整备份: " + p["path"])
        checks.append(("分页清单/标识/recordCount 一致", len(pages)))

        # 合并后的引用完整性 — BackupFileReader.kt:90-98,132-207（最小子集）
        operators = [op for p in pages for op in
                     (json.loads(z.read(p["path"]).decode("utf-8"))["payload"]["content"]["operators"] or [])]
        sessions = [s for p in pages for s in
                    (json.loads(z.read(p["path"]).decode("utf-8"))["payload"]["content"]["sessions"] or [])]
        messages = [m for p in pages for m in
                    (json.loads(z.read(p["path"]).decode("utf-8"))["payload"]["content"]["messages"] or [])]
        media = [m for p in pages for m in
                 json.loads(z.read(p["path"]).decode("utf-8"))["payload"]["media"]]
        op_ids = {op["id"] for op in operators}
        s_ids = {s["id"] for s in sessions}
        m_ids = [m["id"] for m in messages]
        if len(op_ids) != len(operators):
            raise CheckFailed("备份包含重复角色")
        if len(s_ids) != len(sessions):
            raise CheckFailed("备份包含重复会话")
        if len(m_ids) != len(set(m_ids)):
            raise CheckFailed("备份包含重复聊天消息")
        for s in sessions:
            if not s["operatorId"].startswith("group_") and s["operatorId"] not in op_ids:
                raise CheckFailed("私聊会话引用了不存在的角色: " + s["operatorId"])
        for m in messages:
            if m["sessionId"] not in s_ids:
                raise CheckFailed("聊天消息引用了不存在的会话")
        checks.append(("引用完整性（角色/会话/消息）", len(operators) + len(sessions) + len(messages)))

        # 媒体清单 — BackupFileReader.kt:91-98 + BackupMediaStager.kt:26-28 等价检查
        if manifest["mediaIncluded"] != (len(media) > 0):
            raise CheckFailed("备份媒体清单与标记不一致")
        if len({m["archivePath"] for m in media}) != len(media) or len({m["mediaId"] for m in media}) != len(media):
            raise CheckFailed("媒体清单存在重复路径")
        media_zip = {n for n in names_flat if n.startswith("media/")}
        if {m["archivePath"] for m in media} != media_zip:
            raise CheckFailed("媒体清单与 ZIP 内容不一致")
        for m in media:
            if m["mediaId"] != "local-image/" + m["archivePath"].split("/")[-1].rsplit(".", 1)[0]:
                raise CheckFailed("mediaId 与 archivePath 不一致: " + m["mediaId"])
        # 消息内 imageUri 引用可被收集器正则发现（BackupMediaCollector.kt:86）
        import re
        pat = re.compile(r"\"imageUri\"\s*:\s*\"((?:[^\"\\]|\\.)*)\"")
        refs = {u for m in messages if pat.search(m["content"]) for u in pat.findall(m["content"])}
        checks.append(("媒体清单 vs ZIP/引用发现", len(media) + len(refs)))
        return checks, manifest, media, refs


def main():
    if len(sys.argv) >= 3 and sys.argv[1] == "--verify":
        zip_path = sys.argv[2]
        print("== 校验 {0}".format(zip_path))
        try:
            checks, manifest, media, refs = verify(zip_path)
        except CheckFailed as e:
            print("FAIL: {0}".format(e))
            return 1
        for name, n in checks:
            print("  [OK] {0}: {1}".format(name, n))
        print("PASS: 结构/哈希/分页/引用/媒体 全部通过（镜像 BackupFileReader 关键检查）")
        return 0

    out = build_archive()
    print("== 生成 {0}".format(out))
    checks, manifest, media, refs = verify(out)
    for name, n in checks:
        print("  [OK] {0}: {1}".format(name, n))
    print("== 校验通过记录（供 minimal-1.14.27.md 引用）")
    with zipfile.ZipFile(out) as z:
        for f in manifest["files"]:
            print("  {0}  size={1}  sha256={2}".format(f["path"], f["size"], f["sha256"]))
        mb = z.read(MANIFEST_PATH)
        print("  {0}  size={1}  sha256={2}".format(MANIFEST_PATH, len(mb), sha256_hex(mb)))
    print("  mediaId={0}".format(MEDIA_ID))
    print("  message 内 imageUri 引用数={0}（收集器正则可发现）".format(len(refs)))
    print("PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
