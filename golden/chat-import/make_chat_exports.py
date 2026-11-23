#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
make_chat_exports.py — RhodesLinkChatExport v1 金样构造器（T30 impl-a5-import）

按规格卡 ios-port/specs/chat-import-impl-spec.md 冻结的 v1 格式构造四类确定性聊天导出金样：

  chat-text-v1.zip     纯文本会话（text + ai_json 无图；含 1 存档 + 1 历史片段，覆盖引用重映射）
  chat-image-v1.zip    图片消息（type=image 的 content JSON 内嵌 imageUri）+ 头像媒体（2 件媒体）
  chat-ai-json-v1.zip  ai_json 内嵌图（segments[image].imageUri）+ 1 件媒体
  chat-2000-v1.zip     2000 条长会话（1000 条 ai_json，1000 个 displayEvents；无媒体）

容器（与 Android 备份管线同族，便于 iOS 复用 T10 校验思路）：
  manifest.json + conversation.json + media/images/<sha256(content)[:24]>.<ext>
  - manifest 复用 BackupManifest 形状（BackupModels.kt:36-51）：format="rhodes-chat"、formatVersion=1、
    scope="conversation"、layout="single_payload"、pages=[]；
  - files=[{path,size,sha256}]（manifest.json 自身不入清单，Android BackupFileWriter.kt:46-97 同口径）；
  - mediaId = "image/<sha256(content) 全 64 hex>"（T6 裁决格式，chat-import-可行性.md:161）；
    archivePath = "media/images/<sha256(content)[:24]>.<ext>"（沿用 BackupMediaCollector.kt:24-30 的 24 hex 约定）；
  - conversation.json 投影 app/data/ExportPayload.kt:86-113 的 SessionExport/MessageExport 字段序，
    operator 复用 app OperatorExport 22 字段（Operator.kt 同形），媒体索引带 size/sha256 双校验。

用法：
  python3 make_chat_exports.py                  # 重新生成全部金样 + 逐包自校验 + 打印 sha256
  python3 make_chat_exports.py --verify <zip>   # 只校验指定包（镜像 iOS ChatExportReader 关键检查）

行为 oracle（只读引用，本脚本不改 Android 源码）：
  - 容器/清单/逐条目 SHA-256   app/.../data/backup/BackupFileWriter.kt:18-26,46-98；BackupFileReader.kt:32-74
  - 会话/消息导出字段          app/.../data/ExportPayload.kt:86-113
  - 会话 ID 规则               shared/.../data/SessionRepository.kt:42-43（"session_<operatorId>"）
  - 消息排序                   ChatMessages.sq:24（ORDER BY timestamp ASC, id ASC）
  - displayEvents 字段         shared/.../data/MessageRepository.kt:34-40（messageId/segmentIndex/sessionId/revealOrder）
  - 图片消息 content JSON      app/.../viewmodel/ChatViewModel.kt:1859-1863 {"imageUri","caption","visionSummary"}
  - ai_json 内嵌图             ChatViewModel AI 回复 segments[{type:"image",content,imageUri}]
  - mediaId 内容派生           本卡 v1 约定（T6 裁决），区别于 BackupMediaCollector 的路径派生
  - 存档 messagesJson 形状     app/.../viewmodel/ChatViewModel.kt:349（List<ChatMessage> JSON）
"""
import hashlib
import json
import os
import sys
import zipfile
import zlib

# ---------------------------------------------------------------- 常量（v1 格式冻结值）
CHAT_FORMAT = "rhodes-chat"
FORMAT_VERSION = 1
SCOPE = "conversation"
MANIFEST_PATH = "manifest.json"
CONVERSATION_PATH = "conversation.json"
MEDIA_ROOT = "media"
APP_VERSION = "1.14.27"
SCHEMA_VERSION = 1
CREATED_AT = 1750000000000            # 固定 createdAt（可复现）
T0 = 1749999000000
FIXED_ZIP_DATE = (2026, 1, 1, 0, 0, 0)
EXCLUDED = ["chat_archives_default", "memories", "diaries", "moments",
            "knowledge_bases", "relationships", "settings", "credentials"]
PACKAGE = "com.buzidongshouhuoji.privatechat"
HERE = os.path.dirname(os.path.abspath(__file__))


# ---------------------------------------------------------------- 基础工具
def sha256_hex(data):
    return hashlib.sha256(data).hexdigest()


def enc(obj):
    """与 BackupFileWriter.kt:27（encodeDefaults=true）+ kotlinx 紧凑输出一致的确定性 JSON。"""
    return json.dumps(obj, ensure_ascii=False, separators=(",", ":")).encode("utf-8")


def is_safe_archive_path(path):
    """BackupFileReader.kt:238-244。"""
    n = path.replace("\\", "/")
    return (n.strip() != "" and not n.startswith("/") and ":/" not in n and
            all(part not in ("", ".", "..") for part in n.split("/")))


def png_1x1(rgb):
    """确定性最小合法 PNG（用于头像/聊天图/内嵌图三种不同内容哈希）。"""
    def chunk(tag, data):
        return (len(data).to_bytes(4, "big") + tag + data +
                (zlib.crc32(tag + data) & 0xFFFFFFFF).to_bytes(4, "big"))
    sig = b"\x89PNG\r\n\x1a\n"
    ihdr = bytes([0, 0, 0, 1, 0, 0, 0, 1, 8, 2, 0, 0, 0])   # 1x1, 8bit, RGB
    idat = zlib.compress(b"\x00" + bytes(rgb), 9)
    return sig + chunk(b"IHDR", ihdr) + chunk(b"IDAT", idat) + chunk(b"IEND", b"")


PNG_RED = png_1x1((220, 70, 70))
PNG_GREEN = png_1x1((70, 180, 110))
PNG_BLUE = png_1x1((70, 110, 220))


# ---------------------------------------------------------------- 数据模型构造（字段序 = Android ExportPayload.kt）
def operator(op_id, name, avatar_uri=""):
    """OperatorExport 22 字段（app/data/ExportPayload.kt:46-59；shared/model/Operator.kt 同形）。"""
    return {
        "id": op_id, "name": name, "title": "聊天导入金样角色",
        "description": "用于 iOS 聊天记录导入验证的确定性金样角色。",
        "gender": "女", "avatarUri": avatar_uri, "location": "宿舍", "activity": "休息",
        "emotion": "平静", "intimacy": 0,
        "privatePrompt": "你是聊天导入金样角色，用于验证跨平台会话迁移。", "groupPrompt": "",
        "memoryInjection": "", "userRelation": "朋友", "lmb": 10000,
        "attack": 0.5, "defense": 0.5, "meldPref": "medium", "activityLevel": 0.5,
        "voiceName": "", "voiceSpeed": "", "voicePitch": "",
    }


def session(op_id, name, last_message, last_time, avatar_uri=""):
    """SessionExport 12 字段（ExportPayload.kt:86-92）；id 规则 SessionRepository.kt:43。"""
    return {
        "id": "session_" + op_id, "operatorId": op_id, "operatorName": name,
        "lastMessage": last_message, "lastTime": last_time, "mode": "online",
        "isPinned": False, "unreadCount": 0, "members": "", "rules": "",
        "avatarUri": avatar_uri, "mutedMembers": "",
    }


def msg(msg_id, session_id, sender_name, content, mtype, is_me, ts, sender_id=""):
    """MessageExport 15 字段（ExportPayload.kt:102-109）。"""
    return {
        "id": msg_id, "sessionId": session_id, "senderId": sender_id,
        "senderName": sender_name, "content": content, "type": mtype,
        "mode": "online", "emotion": "", "activity": "", "location": "",
        "narration": "", "segmentGroup": "", "intimacyChange": 0,
        "timestamp": ts, "isMe": is_me,
    }


def ai_json(segments, emotion="平静", location="宿舍", narration=""):
    """ai_json content（ChatMessage.type=ai_json 的 segments 形状；图片段带 imageUri）。"""
    return json.dumps({
        "emotion": emotion, "location": location, "narration": narration,
        "segments": segments,
    }, ensure_ascii=False)


def text_seg(content):
    return {"type": "dialogue", "content": content, "speaker": ""}


def narration_seg(content):
    return {"type": "narration", "content": content, "speaker": ""}


def image_seg(uri):
    return {"type": "image", "content": "[图片]", "speaker": "", "imageUri": uri}


def image_content(uri, caption, vision_summary):
    """图片消息 content JSON（ChatViewModel.kt:1859-1863 字段形状）。"""
    return json.dumps({"imageUri": uri, "caption": caption, "visionSummary": vision_summary},
                      ensure_ascii=False)


def media_item(content_bytes, ext, original_uri):
    """对话媒体清单项：mediaId 内容派生（T6 裁决），archivePath 沿用 24 hex 约定。"""
    full = sha256_hex(content_bytes)
    return {
        "mediaId": "image/" + full,
        "archivePath": MEDIA_ROOT + "/images/" + full[:24] + "." + ext,
        "mimeType": "image/png" if ext == "png" else "application/octet-stream",
        "originalUri": original_uri,
        "size": len(content_bytes),
        "sha256": full,
        "_bytes": content_bytes,
    }


def public_media(item):
    out = dict(item)
    out.pop("_bytes", None)
    return out


def display_events_for(messages):
    """为每条 ai_json 消息的每个 segment 生成 displayEvent（revealOrder=时间戳，镜像 addDisplayEventIfAbsent
    的 revealedAt 语义；MessageRepository.kt:170-181）。segment 数解析自 content.segments。"""
    events = []
    for m in messages:
        if m["type"] != "ai_json":
            continue
        segments = json.loads(m["content"]).get("segments") or []
        for index in range(len(segments)):
            events.append({
                "messageId": m["id"], "segmentIndex": index,
                "sessionId": m["sessionId"], "revealOrder": m["timestamp"] + index,
            })
    return events


def archive(session, messages, title, previous_summary):
    """1 条聊天存档：messagesJson = List<ChatMessage> JSON（ChatViewModel.kt:349）；stateJson=ChatArchiveContext 形状。"""
    state = json.dumps({"turnState": None, "previousSummary": previous_summary,
                        "sourceMessagesJson": ""}, ensure_ascii=False)
    return {
        "id": "archive_" + session["operatorId"] + "_1",
        "sessionId": session["id"], "operatorId": session["operatorId"],
        "title": title, "note": "聊天导入金样存档", "mode": "online",
        "messagesJson": json.dumps(messages, ensure_ascii=False),
        "summary": "金样存档摘要", "stateJson": state,
        "status": "ready", "createdAt": messages[-1]["timestamp"],
        "updatedAt": messages[-1]["timestamp"],
    }


def history_segment(session, messages, title, reason):
    return {
        "id": "segment_" + session["operatorId"] + "_1",
        "sessionId": session["id"], "title": title, "reason": reason,
        "messagesJson": json.dumps(messages, ensure_ascii=False),
        "createdAt": messages[-1]["timestamp"],
    }


# ---------------------------------------------------------------- 四类夹具
def fixture_text():
    op_id = "chat_text"
    name = "文本会话角色"
    op = operator(op_id, name)
    sess = session(op_id, name, "很好听。", T0 + 7 * 60000)
    messages = [
        msg(1, sess["id"], "我", "你好呀，今天过得怎么样？", "text", True, T0 + 0 * 60000),
        msg(2, sess["id"], name, ai_json([text_seg("还不错，刚刚看完一本旧书。"),
                                          narration_seg("她合上书，抬眼看向你。")]), "ai_json", False, T0 + 1 * 60000),
        msg(3, sess["id"], "我", "什么书？", "text", True, T0 + 2 * 60000),
        msg(4, sess["id"], name, ai_json([text_seg("一本讲远方的散文集，要不要听我念一段？")]),
            "ai_json", False, T0 + 3 * 60000),
        msg(5, sess["id"], "我", "好啊。", "text", True, T0 + 4 * 60000),
        msg(6, sess["id"], name, ai_json([text_seg("（她轻声念了一段，屋里安静下来。）"),
                                          narration_seg("声音在安静里慢慢落下。")]), "ai_json", False, T0 + 5 * 60000),
        msg(7, sess["id"], "我", "很好听。", "text", True, T0 + 6 * 60000),
        msg(8, sess["id"], name, ai_json([text_seg("那就下次再念给你听。")]), "ai_json", False, T0 + 7 * 60000),
    ]
    display = display_events_for(messages)
    archives = [archive(sess, messages[:4], "旧书与远方", "之前聊到过天气。")]
    history = [history_segment(sess, messages[:2], "初见", "会话开始后的历史片段。")]
    return op, sess, messages, display, archives, history, []


def fixture_image():
    op_id = "chat_image"
    name = "图片会话角色"
    avatar_uri = "file:///data/user/0/{pkg}/files/images/chat_avatar_1x1_green.png".format(pkg=PACKAGE)
    chat_uri = "file:///data/user/0/{pkg}/files/chat_images/chat_1x1_red.png".format(pkg=PACKAGE)
    op = operator(op_id, name, avatar_uri=avatar_uri)
    sess = session(op_id, name, "这张照片真好看。", T0 + 5 * 60000, avatar_uri=avatar_uri)
    messages = [
        msg(1, sess["id"], "我", "发你一张照片。", "text", True, T0 + 0 * 60000),
        msg(2, sess["id"], name, ai_json([text_seg("好呀，我看看。")]), "ai_json", False, T0 + 1 * 60000),
        msg(3, sess["id"], "我", image_content(chat_uri, "我拍的红色小图", "一张纯红色极小图片。"),
            "image", True, T0 + 2 * 60000),
        msg(4, sess["id"], name, ai_json([text_seg("这张照片真好看，颜色很正。"),
                                          narration_seg("她把图片放大看了看。")]), "ai_json", False, T0 + 3 * 60000),
        msg(5, sess["id"], "我", "喜欢就好。", "text", True, T0 + 4 * 60000),
        msg(6, sess["id"], name, ai_json([text_seg("嗯，我收下了。")]), "ai_json", False, T0 + 5 * 60000),
    ]
    media = [
        media_item(PNG_GREEN, "png", avatar_uri),
        media_item(PNG_RED, "png", chat_uri),
    ]
    return op, sess, messages, display_events_for(messages), [], [], media


def fixture_ai_json():
    op_id = "chat_aijson"
    name = "图文回复角色"
    gift_uri = "file:///data/user/0/{pkg}/files/chat_images/chat_1x1_blue.png".format(pkg=PACKAGE)
    op = operator(op_id, name)
    sess = session(op_id, name, "谢谢你！", T0 + 4 * 60000)
    messages = [
        msg(1, sess["id"], "我", "上次说的小礼物，给你。", "text", True, T0 + 0 * 60000),
        msg(2, sess["id"], name, ai_json([
            text_seg("诶？我看看是什么。"),
            narration_seg("她点开图片，眼睛亮了一下。"),
            image_seg(gift_uri),
            text_seg("谢谢你，我很喜欢！"),
        ], emotion="开心"), "ai_json", False, T0 + 1 * 60000),
        msg(3, sess["id"], "我", "喜欢就好。", "text", True, T0 + 2 * 60000),
        msg(4, sess["id"], name, ai_json([
            text_seg("我会好好收着的。"),
            narration_seg("她把手机贴在胸口，笑了起来。"),
        ], emotion="开心", narration="暖意慢慢散开。"), "ai_json", False, T0 + 3 * 60000),
        msg(5, sess["id"], "我", "谢谢你！", "text", True, T0 + 4 * 60000),
    ]
    media = [media_item(PNG_BLUE, "png", gift_uri)]
    return op, sess, messages, display_events_for(messages), [], [], media


def fixture_2000():
    op_id = "chat_2000"
    name = "长会话角色"
    op = operator(op_id, name)
    messages = []
    for i in range(1, 2001):
        ts = T0 + i * 1000
        if i % 2 == 1:
            messages.append(msg(i, "session_" + op_id, "我",
                                "第{}条：今天想聊点什么呢？".format(i), "text", True, ts))
        else:
            messages.append(msg(i, "session_" + op_id, name,
                                ai_json([text_seg("第{}条回复：那就从眼前的小事说起吧。".format(i))]),
                                "ai_json", False, ts))
    sess = session(op_id, name, "第2000条回复：那就从眼前的小事说起吧。", T0 + 2000 * 1000)
    return op, sess, messages, display_events_for(messages), [], [], []


FIXTURES = [
    ("chat-text-v1.zip", fixture_text),
    ("chat-image-v1.zip", fixture_image),
    ("chat-ai-json-v1.zip", fixture_ai_json),
    ("chat-2000-v1.zip", fixture_2000),
]

# 每个夹具固定 backupId（确定性输出）
BACKUP_IDS = {
    "chat-text-v1.zip": "c0a00000-0000-4000-8000-0000000000a1",
    "chat-image-v1.zip": "c0a00000-0000-4000-8000-0000000000a2",
    "chat-ai-json-v1.zip": "c0a00000-0000-4000-8000-0000000000a3",
    "chat-2000-v1.zip": "c0a00000-0000-4000-8000-0000000000a4",
}


# ---------------------------------------------------------------- 打包
def build_conversation(op, sess, messages, display, archives, history, media):
    return {
        "formatVersion": FORMAT_VERSION,
        "exportedAt": CREATED_AT,
        "operator": op,
        "session": sess,
        "messages": sorted(messages, key=lambda m: (m["timestamp"], m["id"])),
        "displayEvents": sorted(display, key=lambda e: (e["revealOrder"], e["messageId"], e["segmentIndex"])),
        "chatArchives": archives,
        "chatHistorySegments": history,
        "mediaIndex": [public_media(m) for m in sorted(media, key=lambda m: m["archivePath"])],
    }


def build_manifest(name, conv, media):
    files = []
    conv_bytes = enc(conv)
    files.append({"path": CONVERSATION_PATH, "size": len(conv_bytes), "sha256": sha256_hex(conv_bytes)})
    for item in sorted(media, key=lambda m: m["archivePath"]):
        files.append({"path": item["archivePath"], "size": item["size"], "sha256": item["sha256"]})
    return {
        "format": CHAT_FORMAT, "formatVersion": FORMAT_VERSION, "scope": SCOPE,
        "appVersion": APP_VERSION, "schemaVersion": SCHEMA_VERSION,
        "createdAt": CREATED_AT, "backupId": BACKUP_IDS[name],
        "mediaIncluded": len(media) > 0,
        "recordCounts": {
            "operators": 1 if conv["operator"] is not None else 0,
            "sessions": 1,
            "messages": len(conv["messages"]),
            "displayEvents": len(conv["displayEvents"]),
            "archives": len(conv["chatArchives"]),
            "historySegments": len(conv["chatHistorySegments"]),
            "mediaFiles": len(media),
        },
        "files": files,
        "excludedCategories": list(EXCLUDED),
        "layout": "single_payload",
        "pages": [],
    }, conv_bytes


def write_fixture(name, builder):
    op, sess, messages, display, archives, history, media = builder()
    conv = build_conversation(op, sess, messages, display, archives, history, media)
    manifest, conv_bytes = build_manifest(name, conv, media)
    out = os.path.join(HERE, name)
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        # conversation.json → media/*（按 archivePath 排序）→ manifest.json（最后写，镜像 BackupFileWriter.kt:96）
        zi = zipfile.ZipInfo(CONVERSATION_PATH, FIXED_ZIP_DATE)
        zi.compress_type = zipfile.ZIP_DEFLATED
        z.writestr(zi, conv_bytes)
        for item in sorted(media, key=lambda m: m["archivePath"]):
            zi = zipfile.ZipInfo(item["archivePath"], FIXED_ZIP_DATE)
            zi.compress_type = zipfile.ZIP_DEFLATED
            z.writestr(zi, item["_bytes"])
        zi = zipfile.ZipInfo(MANIFEST_PATH, FIXED_ZIP_DATE)
        zi.compress_type = zipfile.ZIP_DEFLATED
        z.writestr(zi, enc(manifest))
    return out, manifest, conv


# ---------------------------------------------------------------- 校验（镜像 iOS ChatExportReader）
class CheckFailed(Exception):
    pass


def _number_ids_in_archive_json(raw):
    """收集存档/历史 messagesJson 中出现的所有消息 id（对象键 id/messageId 的数字值）。"""
    try:
        data = json.loads(raw)
    except Exception as exc:  # noqa: BLE001
        raise CheckFailed("存档 messagesJson 无法解析: %s" % exc)
    found = set()

    def walk(node):
        if isinstance(node, dict):
            for key, value in node.items():
                if key in ("id", "messageId") and isinstance(value, int):
                    found.add(value)
                walk(value)
        elif isinstance(node, list):
            for value in node:
                walk(value)

    walk(data)
    return found


def verify(zip_path):
    checks = []
    with zipfile.ZipFile(zip_path) as z:
        infos = z.infolist()
        names = [i.filename for i in infos]
        # 1) 路径安全 / 重复 / 上限（BackupFileReader.kt:36-41）
        if len(names) != len(set(names)):
            raise CheckFailed("ZIP 存在重复路径")
        if len(names) > 1000:
            raise CheckFailed("ZIP 条目数量超过限制")
        total = 0
        for info in infos:
            if not is_safe_archive_path(info.filename):
                raise CheckFailed("ZIP 路径不安全: " + info.filename)
            if info.is_dir():
                raise CheckFailed("角色包/聊天包不允许目录条目: " + info.filename)
            if info.file_size > 100 * 1024 * 1024:
                raise CheckFailed("ZIP 单条目超过 100MB")
            total += info.file_size
        if total > 500 * 1024 * 1024:
            raise CheckFailed("ZIP 解压总大小超过限制")
        entries = {info.filename: z.read(info.filename) for info in infos}
    checks.append(("安全路径/无重复/上限", "%d 条目 / %d 字节" % (len(entries), total)))

    # 2) manifest 结构门禁（ChatExportReader 契约 #2）
    if MANIFEST_PATH not in entries:
        raise CheckFailed("缺少 manifest.json")
    try:
        manifest = json.loads(entries[MANIFEST_PATH].decode("utf-8"))
    except Exception as exc:  # noqa: BLE001
        raise CheckFailed("manifest.json 无法解析: %s" % exc)
    if manifest.get("format") != CHAT_FORMAT:
        raise CheckFailed("不支持的聊天导出类型: %s" % manifest.get("format"))
    if manifest.get("formatVersion") != FORMAT_VERSION:
        raise CheckFailed("不支持的聊天导出格式版本: %s" % manifest.get("formatVersion"))
    if manifest.get("scope") != SCOPE:
        raise CheckFailed("scope 不是 conversation")
    if manifest.get("layout") != "single_payload":
        raise CheckFailed("layout 必须为 single_payload")
    if manifest.get("pages"):
        raise CheckFailed("v1 不允许分页")
    checks.append(("manifest: rhodes-chat v1 conversation", manifest.get("backupId")))

    # 3) files 与 ZIP 严格一致 + 逐条目 size/sha256（BackupFileReader.kt:58-68）
    files = manifest.get("files") or []
    if len({f["path"] for f in files}) != len(files):
        raise CheckFailed("manifest 存在重复文件条目")
    if {f["path"] for f in files} != set(entries.keys()) - {MANIFEST_PATH}:
        raise CheckFailed("manifest 文件清单与 ZIP 内容不一致")
    for f in files:
        data = entries[f["path"]]
        if len(data) != f["size"]:
            raise CheckFailed("文件大小不一致: " + f["path"])
        if sha256_hex(data) != f["sha256"]:
            raise CheckFailed("文件校验失败: " + f["path"])
    checks.append(("files 清单 ↔ ZIP 严格一致 + SHA-256", "%d 文件" % len(files)))

    # 4) conversation.json 结构（ChatExportReader 契约 #3）
    if CONVERSATION_PATH not in entries:
        raise CheckFailed("缺少 conversation.json")
    try:
        conv = json.loads(entries[CONVERSATION_PATH].decode("utf-8"))
    except Exception as exc:  # noqa: BLE001
        raise CheckFailed("conversation.json 无法解析: %s" % exc)
    if conv.get("formatVersion") != FORMAT_VERSION:
        raise CheckFailed("conversation.formatVersion 不是 1")
    sess = conv.get("session")
    if not isinstance(sess, dict) or not sess.get("id") or not sess.get("operatorId"):
        raise CheckFailed("conversation.session 缺失或字段不完整")
    messages = conv.get("messages")
    if not isinstance(messages, list) or not messages:
        raise CheckFailed("conversation.messages 缺失或为空")
    ids = [m["id"] for m in messages]
    if len(ids) != len(set(ids)):
        raise CheckFailed("conversation.messages 存在重复 id")
    if [m["id"] for m in messages] != sorted(ids):
        raise CheckFailed("conversation.messages 必须按 (timestamp,id) 升序导出")
    if any(m.get("timestamp", 0) <= 0 for m in messages):
        raise CheckFailed("conversation.messages timestamp 必须为正")
    if any(m.get("sessionId") != sess["id"] for m in messages):
        raise CheckFailed("消息 sessionId 必须与会话 id 一致（单会话导出）")
    op = conv.get("operator")
    if op is not None and op.get("id") != sess.get("operatorId"):
        raise CheckFailed("operator.id 与 session.operatorId 不一致")
    checks.append(("conversation: 1 会话 / %d 消息 / operator=%s" %
                   (len(messages), "有" if op else "无"), sess["id"]))

    # 5) displayEvents 引用完整 + 揭示顺序保留（契约 #4）
    events = conv.get("displayEvents") or []
    if len({(e["messageId"], e["segmentIndex"]) for e in events}) != len(events):
        raise CheckFailed("displayEvents 存在重复 (messageId,segmentIndex)")
    for e in events:
        if e["messageId"] not in ids:
            raise CheckFailed("displayEvent 引用不存在的消息: %s" % e["messageId"])
        if e["sessionId"] != sess["id"]:
            raise CheckFailed("displayEvent sessionId 不一致")
        if e["revealOrder"] <= 0:
            raise CheckFailed("displayEvent revealOrder 必须为正")
    checks.append(("displayEvents 引用完整 + 顺序值合法", "%d 事件" % len(events)))

    # 6) 存档/历史片段引用（契约 #5：messagesJson 内消息 id 必须可重映射）
    for arch in conv.get("chatArchives") or []:
        if arch.get("sessionId") != sess["id"]:
            raise CheckFailed("存档 sessionId 不一致")
        if not _number_ids_in_archive_json(arch.get("messagesJson", "[]")) <= set(ids):
            raise CheckFailed("存档 messagesJson 引用了不存在的消息 id")
    for hist in conv.get("chatHistorySegments") or []:
        if hist.get("sessionId") != sess["id"]:
            raise CheckFailed("历史片段 sessionId 不一致")
        if not _number_ids_in_archive_json(hist.get("messagesJson", "[]")) <= set(ids):
            raise CheckFailed("历史片段 messagesJson 引用了不存在的消息 id")
    checks.append(("存档/历史片段 messagesJson 引用可重映射",
                   "%d 存档 / %d 片段" % (len(conv.get("chatArchives") or []),
                                         len(conv.get("chatHistorySegments") or []))))

    # 7) mediaIndex ↔ manifest files ↔ ZIP media 条目 三方一致（契约 #6）
    media_index = conv.get("mediaIndex") or []
    if len({m["mediaId"] for m in media_index}) != len(media_index):
        raise CheckFailed("mediaIndex 存在重复 mediaId")
    if len({m["archivePath"] for m in media_index}) != len(media_index):
        raise CheckFailed("mediaIndex 存在重复 archivePath")
    manifest_media = {f["path"]: f for f in files if f["path"].startswith(MEDIA_ROOT + "/")}
    if {m["archivePath"] for m in media_index} != set(manifest_media):
        raise CheckFailed("mediaIndex 与 manifest 媒体文件集合不一致")
    zip_media = {n for n in entries if n.startswith(MEDIA_ROOT + "/")}
    if set(manifest_media) != zip_media:
        raise CheckFailed("manifest 媒体清单与 ZIP media/ 条目不一致")
    for item in media_index:
        data = entries[item["archivePath"]]
        if item["size"] != len(data) or item["sha256"] != sha256_hex(data):
            raise CheckFailed("媒体 size/sha256 不一致: " + item["archivePath"])
        if item["mediaId"] != "image/" + sha256_hex(data):
            raise CheckFailed("mediaId 不是内容派生 image/<sha256>: " + item["mediaId"])
        if manifest_media[item["archivePath"]]["sha256"] != item["sha256"]:
            raise CheckFailed("媒体 sha256 与 manifest 不一致")
    if manifest.get("mediaIncluded") != bool(media_index):
        raise CheckFailed("mediaIncluded 与 mediaIndex 不一致")
    checks.append(("mediaIndex ↔ manifest ↔ ZIP 三方一致", "%d 件媒体" % len(media_index)))

    # 8) recordCounts 自检（契约 #8）
    counts = manifest.get("recordCounts") or {}
    expect = {
        "operators": 1 if conv.get("operator") is not None else 0,
        "sessions": 1,
        "messages": len(messages),
        "displayEvents": len(events),
        "archives": len(conv.get("chatArchives") or []),
        "historySegments": len(conv.get("chatHistorySegments") or []),
        "mediaFiles": len(media_index),
    }
    for key, value in expect.items():
        if counts.get(key) != value:
            raise CheckFailed("recordCounts.%s 不一致（期望 %s，实际 %s）" % (key, value, counts.get(key)))
    checks.append(("recordCounts 自检", str(expect["messages"]) + " 消息"))

    # 9) 与 T10 BackupFileReader 的格式门禁关系（记录，不视为失败）
    compat = ("T10 BackupFileReader 预期拒绝：format=%s（需 rhodes-backup）/ scope=%s（需 full）"
              % (manifest.get("format"), manifest.get("scope")))

    return checks, compat, manifest


# ---------------------------------------------------------------- 主流程
def generate_all():
    results = []
    for name, builder in FIXTURES:
        out, manifest, conv = write_fixture(name, builder)
        checks, compat, _ = verify(out)
        size = os.path.getsize(out)
        digest = sha256_hex(open(out, "rb").read())
        results.append((name, size, digest, manifest, checks, compat))
        print("[gen] %-24s %8d bytes sha256=%s" % (name, size, digest))
    print("\n[verify] 全部金样自校验 PASS（镜像 iOS ChatExportReader 契约 #1-#8）")
    return results


def main(argv):
    if len(argv) >= 3 and argv[1] == "--verify":
        target = argv[2]
        try:
            checks, compat, manifest = verify(target)
        except CheckFailed as exc:
            print("[verify] FAIL: %s" % exc, file=sys.stderr)
            return 1
        for label, detail in checks:
            print("[verify] PASS  %-46s %s" % (label, detail))
        print("[verify] %s" % compat)
        print("[verify] %s: rhodes-chat v1 自校验 PASS（%d 消息）"
              % (os.path.basename(target), manifest["recordCounts"]["messages"]))
        return 0
    results = generate_all()
    print("\n[sha256] 供 README / 测试夹具绑定：")
    for name, size, digest, manifest, _checks, _compat in results:
        print("  %s  %s  %d bytes  messages=%d media=%d" %
              (digest, name, size, manifest["recordCounts"]["messages"],
               manifest["recordCounts"]["mediaFiles"]))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
