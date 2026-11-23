#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
make_full_backups.py — RhodesLink 备份 v2「全类目」金样构造器（T31 impl-backup-full）

按 Android 1.14.27（tag v1.14.27-android-baseline @ 3642d2c）备份导出管线的字节规则，构造 8 个
确定性完整备份包（`rhodes-backup` v2 分页容器 + 1 个 v1 单 payload 兼容包）：

  full-all-v2.zip        7 类目全字段主金样（roles/chats/memories/social/knowledgeBases/extras/settings + 3 媒体）
  full-memories-v2.zip   memories 三层记忆 + 6 类型锚点 + memoryItems（L1/L2/L3）+ 孤儿记忆/锚点
  full-social-v2.zip     moments/嵌套评论/点赞/日记/共同经历 + 孤儿评论/点赞/参与者
  full-knowledge-v2.zip  知识库 + 切块 + 角色关联
  full-extras-v2.zip     礼物/派遣/麻将 + chats 附属（displayEvents/存档/历史片段）
  full-media-v2.zip      媒体 URI 重写全扫描域（头像/会话头像/消息图/礼物图/存档 JSON/设置）
  full-empty-v2.zip      0 记录（仅 settings={} 页）→ 空包清空语义
  full-v1-single.zip     formatVersion=1 单 payload 兼容包（全类目各 1-2 条）

每包可复跑（固定 createdAt/backupId/timestamp/zip 条目时间）且 `--verify` 自校验：
  - 结构：路径安全/唯一、manifest 三元组、files 集合与 ZIP 严格相等、逐条目 size+sha256；
  - v2 分页：layout/pages 清单/标识/recordCount 公式（BackupFileReader.kt:123-130）；
  - 引用完整性（BackupFileReader.kt:132-207）与预期孤儿 issue 数；
  - 媒体：清单 == ZIP media/ 条目、mediaId == "local-image/"+sha256(canonicalPath)[:24]
    （BackupMediaCollector.kt:71-72，T10 裁决 3 路径派生保留）；
  - 确定性：--verify 重新在内存构造并逐字节比较（防手改/生成器漂移）。

行为 oracle（只读引用，本脚本不改 Android 源码）：
  - 容器/写入序/recordCounts   app/.../data/backup/BackupFileWriter.kt:46-98,101-131
  - 分页规则                    app/.../data/backup/BackupPagePlanner.kt:14-83
  - manifest/payload 模型       app/.../data/backup/BackupModels.kt:36-98
  - 载荷字段序                  app/.../data/ExportPayload.kt:22-43
  - 便携设置白名单              app/.../data/backup/PortableSettings.kt:8-77
  - 媒体 mediaId 规则           app/.../data/backup/BackupMediaCollector.kt:24-30,71-72
  - 会话 ID 规则                shared/.../data/SessionRepository.kt:42-43
  - 读取校验                    app/.../data/backup/BackupFileReader.kt:32-244

用法：
  python3 make_full_backups.py                  # 生成 8 包 + 逐包自校验 + 打印 SHA-256
  python3 make_full_backups.py --verify <zip>   # 只校验指定包（含确定性逐字节比较）
"""
import hashlib
import io
import json
import os
import struct
import sys
import zipfile
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))

# ------------------------------------------------------------------ 常量（冻结值，勿随意改）
FULL_FORMAT = "rhodes-backup"
FORMAT_VERSION = 2
LEGACY_FORMAT_VERSION = 1
SCOPE = "full"
APP_VERSION = "1.14.27"
SCHEMA_VERSION = 1
CREATED_AT = 1750000000000          # 固定 createdAt/timestamp（可复现）
T0 = 1749999000000
FIXED_ZIP_DATE = (2026, 1, 1, 0, 0, 0)
MANIFEST_PATH = "manifest.json"
PAGES_ROOT = "data/pages"
LEGACY_PAYLOAD_PATH = "data/backup.json"
MAX_PAGE_BYTES = 16 * 1024 * 1024
EXCLUDED = ["credentials", "vector_index", "worker_state", "temporary_files"]  # BackupFileWriter.kt:92
PACKAGE = "com.buzidongshouhuoji.privatechat"

# 每包预期出现的 v2 分页类目（BackupPagePlanner.kt 的 BackupCategory；v1 包为 None）
EXPECTED_CATEGORIES = {
    "full-all-v2.zip": {"ROLES", "CHATS", "MEMORIES", "SOCIAL", "KNOWLEDGE_BASES", "EXTRAS", "SETTINGS"},
    "full-memories-v2.zip": {"ROLES", "CHATS", "MEMORIES"},
    "full-social-v2.zip": {"ROLES", "SOCIAL"},
    "full-knowledge-v2.zip": {"ROLES", "KNOWLEDGE_BASES"},
    "full-extras-v2.zip": {"ROLES", "CHATS", "EXTRAS"},
    "full-media-v2.zip": {"ROLES", "CHATS", "EXTRAS", "SETTINGS"},
    "full-empty-v2.zip": {"SETTINGS"},
    "full-v1-single.zip": None,
}

# 每包预期孤儿 issue（BackupFileReader.kt:182-203 的 5 类）
EXPECTED_ISSUES = {
    "full-all-v2.zip": {},
    "full-memories-v2.zip": {"ORPHAN_MEMORY": 1, "ORPHAN_ANCHOR": 1},
    "full-social-v2.zip": {"ORPHAN_MOMENT_COMMENT": 1, "ORPHAN_MOMENT_LIKE": 1,
                           "ORPHAN_SHARED_EXPERIENCE_PARTICIPANT": 1},
    "full-knowledge-v2.zip": {},
    "full-extras-v2.zip": {},
    "full-media-v2.zip": {},
    "full-empty-v2.zip": {},
    "full-v1-single.zip": {},
}

# ------------------------------------------------------------------ 通用工具


def sha256_hex(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def canonical_path(name: str) -> str:
    return "/data/data/{}/files/images/{}".format(PACKAGE, name)


def original_uri(name: str) -> str:
    return "file:///data/user/0/{}/files/images/{}".format(PACKAGE, name)


def make_png(r: int, g: int, b: int) -> bytes:
    """确定性 1x1 RGB PNG（合法 PNG，供媒体字节 sha256/落盘断言）。"""
    def chunk(tag: bytes, data: bytes) -> bytes:
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    ihdr = struct.pack(">IIBBBBB", 1, 1, 8, 2, 0, 0, 0)
    raw = b"\x00" + bytes([r, g, b])
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr)
            + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


PNG = {
    "golden_avatar.png": make_png(233, 120, 140),
    "session_avatar.png": make_png(120, 200, 233),
    "message_image.png": make_png(102, 180, 220),
    "gift_image.png": make_png(250, 200, 90),
}


def media_item(name: str, mime: str = "image/png") -> dict:
    """BackupMediaItem（BackupModels.kt:79-85）+ 路径派生 mediaId（BackupMediaCollector.kt:71-72）。"""
    stable = sha256_hex(canonical_path(name).encode("utf-8"))[:24]
    return {
        "mediaId": "local-image/" + stable,
        "archivePath": "media/images/{}.png".format(stable),
        "mimeType": mime,
        "originalUri": original_uri(name),
    }


def enc(obj) -> bytes:
    """与 Android kotlinx `Json { encodeDefaults = true }` 同形：键序 = 声明序，紧凑分隔符。"""
    return json.dumps(obj, ensure_ascii=False, separators=(",", ":")).encode("utf-8")


# ------------------------------------------------------------------ 记录模型（字段序 = wire 声明序）


def operator(id, name, **kw):
    d = {"id": id, "name": name, "title": "", "description": "", "gender": "",
         "avatarUri": "", "location": "宿舍", "activity": "休息", "emotion": "平静",
         "intimacy": 0, "privatePrompt": "", "groupPrompt": "", "memoryInjection": "",
         "userRelation": "", "lmb": 10000, "attack": 0.5, "defense": 0.5,
         "meldPref": "medium", "activityLevel": 0.5, "voiceName": "",
         "voiceSpeed": "", "voicePitch": ""}
    d.update(kw)
    return d


def relationship(operator_id, related_id, related_name, type_name, **kw):
    d = {"operatorId": operator_id, "relatedOperatorId": related_id,
         "relatedOperatorName": related_name, "type": type_name,
         "intimacy": 0, "isPreset": False, "note": ""}
    d.update(kw)
    return d


def session(id, operator_id, operator_name, **kw):
    d = {"id": id, "operatorId": operator_id, "operatorName": operator_name,
         "lastMessage": "", "lastTime": 0, "mode": "online", "isPinned": False,
         "unreadCount": 0, "members": "", "rules": "", "avatarUri": "", "mutedMembers": ""}
    d.update(kw)
    return d


def message(id, session_id, sender_id, sender_name, content, **kw):
    d = {"id": id, "sessionId": session_id, "senderId": sender_id, "senderName": sender_name,
         "content": content, "type": "text", "mode": "online", "emotion": "", "activity": "",
         "location": "", "narration": "", "segmentGroup": "", "intimacyChange": 0,
         "timestamp": 0, "isMe": False}
    d.update(kw)
    return d


def display_event(message_id, segment_index, session_id, reveal_order):
    return {"messageId": message_id, "segmentIndex": segment_index,
            "sessionId": session_id, "revealOrder": reveal_order}


def archive(id, session_id, operator_id, title, messages_json, **kw):
    d = {"id": id, "sessionId": session_id, "operatorId": operator_id, "title": title,
         "note": "", "mode": "online", "messagesJson": messages_json, "summary": "",
         "stateJson": "{}", "status": "ready", "createdAt": T0, "updatedAt": T0 + 1000}
    d.update(kw)
    return d


def history_segment(id, session_id, title, messages_json, **kw):
    d = {"id": id, "sessionId": session_id, "title": title, "reason": "context_trim",
         "messagesJson": messages_json, "createdAt": T0}
    d.update(kw)
    return d


def memory(id, session_id, operator_id, type_name, content, **kw):
    d = {"id": id, "sessionId": session_id, "operatorId": operator_id, "type": type_name,
         "content": content, "keywords": "", "preferences": "", "taboos": "",
         "createdAt": T0, "expiresAt": 9223372036854775807}
    d.update(kw)
    return d


def anchor(id, session_id, operator_id, type_name, content, **kw):
    d = {"id": id, "sessionId": session_id, "operatorId": operator_id, "type": type_name,
         "content": content, "isPrivate": False, "createdAt": T0,
         "expiresAt": 9223372036854775807, "source": "", "sourceName": "",
         "sourceActor": "", "sourceTarget": "", "importance": "", "knownFrom": ""}
    d.update(kw)
    return d


def memory_item(id, owner_type, owner_id, level, memory_type, source_kind, content, **kw):
    d = {"id": id, "ownerType": owner_type, "ownerId": owner_id, "memoryLevel": level,
         "memoryType": memory_type, "sourceKind": source_kind, "sourceRefId": "",
         "sessionId": "", "content": content, "nickname": "", "importance": 50,
         "privacy": None, "unmetNeed": False, "location": None, "emotionValence": "neutral",
         "eventTime": None, "createdAt": T0, "updatedAt": T0, "expiresAt": 9223372036854775807,
         "status": "active", "scheduledTime": None, "action": "", "careType": "",
         "topicKey": "", "sourceActor": "", "sourceTarget": "", "lastUsedAt": 0,
         "usedCount": 0, "confidence": 0.8, "rawJson": "", "vectorId": ""}
    d.update(kw)
    return d


def moment(id, operator_id, operator_name, content, **kw):
    d = {"id": id, "operatorId": operator_id, "operatorName": operator_name, "content": content,
         "isUserPost": False, "mentionedOperatorIds": "", "likeCount": 0,
         "commentCount": 0, "createdAt": T0}
    d.update(kw)
    return d


def comment(id, moment_id, operator_id, operator_name, content, **kw):
    d = {"id": id, "momentId": moment_id, "operatorId": operator_id, "operatorName": operator_name,
         "content": content, "parentCommentId": 0, "replyToName": "", "createdAt": T0,
         "isRead": False}
    d.update(kw)
    return d


def like(id, moment_id, operator_id, operator_name, **kw):
    d = {"id": id, "momentId": moment_id, "operatorId": operator_id,
         "operatorName": operator_name, "createdAt": T0}
    d.update(kw)
    return d


def diary(id, operator_id, operator_name, content, date, **kw):
    d = {"id": id, "operatorId": operator_id, "operatorName": operator_name,
         "content": content, "date": date, "version": 1, "createdAt": T0}
    d.update(kw)
    return d


def shared_experience(id, source_kind, source_ref_id, group_id, content, **kw):
    d = {"id": id, "sourceKind": source_kind, "sourceRefId": source_ref_id,
         "groupId": group_id, "content": content, "importance": 60, "status": "active",
         "createdAt": T0, "expiresAt": 9223372036854775807}
    d.update(kw)
    return d


def participant(experience_id, operator_id, role="participant"):
    return {"experienceId": experience_id, "operatorId": operator_id, "role": role}


def knowledge_base(id, name, raw_content, **kw):
    d = {"id": id, "name": name, "rawContent": raw_content, "sourceFileName": "",
         "sourceFormat": "md", "sourceType": "user", "chunkingMode": "smart",
         "createdAt": T0, "updatedAt": T0}
    d.update(kw)
    return d


def chunk(id, kb_id, ordinal, content, **kw):
    d = {"id": id, "knowledgeBaseId": kb_id, "ordinal": ordinal, "sourceHeading": "",
         "content": content, "userKeywords": "", "enabled": True,
         "createdAt": T0, "updatedAt": T0}
    d.update(kw)
    return d


def assignment(operator_id, kb_id, **kw):
    d = {"operatorId": operator_id, "knowledgeBaseId": kb_id, "enabled": True, "sortOrder": 0}
    d.update(kw)
    return d


def gift(id, operator_id, image_uri, name, sender, **kw):
    d = {"id": id, "operatorId": operator_id, "imageUri": image_uri, "giftName": name,
         "senderName": sender, "createdAt": T0}
    d.update(kw)
    return d


def dispatch(id, task_type, **kw):
    d = {"id": id, "taskType": task_type, "durationHours": 4, "budget": 1000,
         "netProfit": 0, "operatorIds": "golden", "logChain": "[]", "status": "active",
         "startTime": T0, "endTime": 0, "totalSegments": 4, "segmentInterval": 3600000,
         "items": "[]"}
    d.update(kw)
    return d


def mahjong(save_json="{\"round\":1}", **kw):
    d = {"id": "current", "saveJson": save_json, "ruleType": "classic", "savedAt": T0}
    d.update(kw)
    return d


# ------------------------------------------------------------------ PortableSettings 全量（PortableSettings.kt:8-77）

STRING_KEYS = [
    "user_name", "user_gender", "user_signature", "user_avatar_uri", "appearance_skin",
    "memory_recall_mode", "personal_memory_reference_style", "context_mode",
    "status_location_pool", "status_activity_pool", "status_emotion_pool",
    "sleep_fixed_wake_text", "sleep_wake_text_mode", "support_conversation",
]
BOOLEAN_KEYS = [
    "dark_mode", "source_aware_memory_enabled", "distinguish_private_memory", "unified_memory_enabled",
    "summary_cursor_enabled", "memory_v2_enabled", "moment_memory_v2_enabled",
    "private_memory_generation_enabled", "group_memory_generation_enabled", "moment_memory_generation_enabled",
    "moment_comment_memory_generation_enabled", "diary_memory_generation_enabled",
    "private_summary_generation_enabled", "group_summary_generation_enabled",
    "private_daily_summary_generation_enabled", "group_daily_summary_generation_enabled",
    "private_memory_promotion_enabled", "group_memory_promotion_enabled", "moment_memory_promotion_enabled",
    "moment_comment_memory_promotion_enabled", "diary_memory_promotion_enabled", "group_memory_copy_to_members_enabled",
    "global_public_memory_enabled", "private_recall_private_chat_memory", "private_recall_group_chat_memory",
    "private_recall_moment_memory", "private_recall_moment_comment_memory", "private_recall_relationship_memory",
    "private_recall_diary_memory", "private_recall_manual_memory", "auto_ai_enabled",
    "daily_auto_moment_enabled", "idle_proactive_chat_enabled", "auto_moment_enabled", "auto_diary_enabled",
    "quiet_hours_enabled", "auto_status_refresh", "dispatch_fast_mode", "proactive_permission_all",
]
INT_KEYS = [
    "ai_temperature", "history_messages", "max_context_tokens", "clean_days",
    "memory_recall_candidate_limit", "memory_v2_promote_l1_threshold", "memory_v2_promote_l2_threshold",
    "memory_v2_important_promotion_threshold", "private_group_context_count", "group_member_memory_count",
    "private_memory_extraction_threshold", "group_memory_extraction_threshold", "group_user_event_count",
    "moment_user_post_observer_count", "group_relationship_hint_count", "moment_recent_post_count",
    "moment_user_related_rate", "comment_context_count", "comment_memory_count", "comment_bystander_min",
    "comment_bystander_max", "diary_group_summary_count", "diary_relation_event_count", "daily_diary_operator_limit",
    "daily_proactive_limit", "proactive_quiet_after_user_minutes", "nar_seg_min", "nar_seg_max", "nar_min",
    "nar_max", "dia_seg_min", "dia_seg_max", "dia_min", "dia_max", "dispatch_min_chars", "dispatch_max_chars",
    "moment_min_chars", "moment_max_chars", "diary_min_chars", "diary_max_chars", "group_chat_min_interval",
    "group_chat_max_interval", "group_auto_max_rounds", "daily_intimacy_cap", "lmb", "daily_lmb_count",
    "daily_moment_target", "daily_proactive_chance", "daily_proactive_max", "quiet_hours_start", "quiet_hours_end",
    "proactive_per_operator_daily_max", "proactive_followup_chance", "proactive_min_gap_minutes",
    "sleep_alarm_hour", "sleep_alarm_minute", "sleep_inactivity_minutes", "sleep_dim_after_seconds",
    "sleep_snooze_minutes", "clean_days_messages", "clean_days_anchors", "clean_days_diaries",
    "clean_days_moments", "clean_days_dispatches", "group_msg_min", "group_msg_max", "group_speech_min",
    "group_speech_max", "group_nar_seg_min", "group_nar_seg_max", "group_nar_min", "group_nar_max",
    "comment_min_chars", "comment_max_chars",
]


def full_settings(operator_ids, group_session_id=None, user_avatar_uri="", support_conversation=""):
    """PortableSettings.snapshot 同形（s:/b:/i:/l: 前缀；密钥类 key 永不出现）。"""
    out = {}
    for i, key in enumerate(STRING_KEYS):
        value = "s:default"
        if key == "user_name":
            value = "s:林深"
        elif key == "user_gender":
            value = "s:female"
        elif key == "user_signature":
            value = "s:金样签名"
        elif key == "user_avatar_uri":
            value = "s:" + user_avatar_uri
        elif key == "support_conversation":
            value = "s:" + support_conversation
        out[key] = value
    for i, key in enumerate(BOOLEAN_KEYS):
        out[key] = "b:true" if i % 2 == 0 else "b:false"
    for i, key in enumerate(INT_KEYS):
        out[key] = "i:" + str((i * 7) % 97 + 1)
    out["hidden_ids"] = "s:shu"
    for oid in operator_ids:
        out["operator_prompt_slot_{}_private".format(oid)] = "i:1"
        out["operator_prompt_slot_{}_group".format(oid)] = "i:2"
        for slot in (1, 2, 3):
            for kind in ("private", "group"):
                out["operator_prompt_slot_{}_{}_{}".format(oid, kind, slot)] = \
                    "s:prompt-{}-{}-{}".format(oid, kind, slot)
        out["msg_{}".format(oid)] = "b:true"
        out["dyn_{}".format(oid)] = "b:false"
        out["voice_volume_{}".format(oid)] = "s:0.8"
        out["chat_tts_{}".format(oid)] = "b:true"
        out["bg_{}".format(oid)] = "s:bg-default"
        out["diary_read_at_{}".format(oid)] = "l:1749990000000"
    out["last_mode_golden"] = "s:online"
    if group_session_id:
        out["gbg_{}".format(group_session_id)] = "s:file:///data/user/0/{}/files/images/group_bg.png".format(PACKAGE)
    return out


# ------------------------------------------------------------------ payload/page 组装


def content(version=1, operators=None, relationships=None, sessions=None, session_obj=None,
            messages=None, memories=None, anchors=None, moments=None, moment_likes=None,
            moment_comments=None, diaries=None, memory_items=None, knowledge_bases=None,
            knowledge_base_chunks=None, assignments=None, settings=None):
    """ExportPayload.kt:22-38 字段序（encodeDefaults=true 全量写出，含 null）。"""
    return {
        "version": version, "type": "full_backup", "timestamp": CREATED_AT,
        "operators": operators, "relationships": relationships, "sessions": sessions,
        "session": session_obj, "messages": messages, "memories": memories,
        "anchors": anchors, "moments": moments, "momentLikes": moment_likes,
        "momentComments": moment_comments, "diaries": diaries, "memoryItems": memory_items,
        "knowledgeBases": knowledge_bases, "knowledgeBaseChunks": knowledge_base_chunks,
        "operatorKnowledgeBaseAssignments": assignments, "settings": settings,
    }


def backup_payload(content_obj, display_events=None, chat_archives=None, chat_history_segments=None,
                   gift_records=None, dispatch_records=None, mahjong_save=None,
                   shared_experiences=None, shared_participants=None, media=None):
    """BackupModels.kt:87-98 字段序。"""
    return {
        "content": content_obj,
        "displayEvents": display_events if display_events is not None else [],
        "chatArchives": chat_archives if chat_archives is not None else [],
        "chatHistorySegments": chat_history_segments if chat_history_segments is not None else [],
        "giftRecords": gift_records if gift_records is not None else [],
        "dispatchRecords": dispatch_records if dispatch_records is not None else [],
        "mahjongSave": mahjong_save,
        "sharedExperiences": shared_experiences if shared_experiences is not None else [],
        "sharedExperienceParticipants": shared_participants if shared_participants is not None else [],
        "media": media if media is not None else [],
    }


def page(category, page_index, payload):
    """BackupPayloadPage（BackupModels.kt:64-69）。"""
    return {"category": category, "pageIndex": page_index, "payload": payload}


def record_count(payload):
    """BackupFileWriter.kt:101-108 的 pageRecordCount 公式（媒体不计入）。"""
    c = payload["content"]
    return (len(c["operators"] or []) + len(c["relationships"] or []) + len(c["sessions"] or [])
            + len(c["messages"] or []) + len(c["memories"] or []) + len(c["anchors"] or [])
            + len(c["memoryItems"] or []) + len(c["moments"] or []) + len(c["momentLikes"] or [])
            + len(c["momentComments"] or []) + len(c["diaries"] or [])
            + len(c["knowledgeBases"] or []) + len(c["knowledgeBaseChunks"] or [])
            + len(c["operatorKnowledgeBaseAssignments"] or [])
            + len(payload["displayEvents"]) + len(payload["chatArchives"])
            + len(payload["chatHistorySegments"]) + len(payload["giftRecords"])
            + len(payload["dispatchRecords"]) + len(payload["sharedExperiences"])
            + len(payload["sharedExperienceParticipants"])
            + (1 if payload["mahjongSave"] is not None else 0)
            + (1 if c["settings"] is not None else 0))


def pages_for(data, version=1):
    """镜像 BackupPagePlanner.kt:14-83：每类目独立页 + pageIndex 基址；media 随首个 ROLES 页。"""
    pages = []

    def add(cat, idx, **content_kw):
        pages.append(page(cat, idx, backup_payload(content(version=version, **content_kw))))

    ops = data.get("operators") or []
    media = data.get("media") or []
    if ops:
        pages.append(page("ROLES", 0, backup_payload(content(version=version, operators=ops),
                                                     media=media)))
    elif media:
        pages.append(page("ROLES", 90_000, backup_payload(content(version=version), media=media)))
    if data.get("relationships"):
        add("ROLES", 10_000, relationships=data["relationships"])
    if data.get("sessions"):
        add("CHATS", 0, sessions=data["sessions"])
    if data.get("messages"):
        add("CHATS", 10_000, messages=data["messages"])
    if data.get("displayEvents"):
        pages.append(page("CHATS", 20_000, backup_payload(content(version=version),
                                                          display_events=data["displayEvents"])))
    if data.get("archives"):
        pages.append(page("CHATS", 30_000, backup_payload(content(version=version),
                                                          chat_archives=data["archives"])))
    if data.get("history"):
        pages.append(page("CHATS", 40_000, backup_payload(content(version=version),
                                                          chat_history_segments=data["history"])))
    if data.get("memories"):
        add("MEMORIES", 0, memories=data["memories"])
    if data.get("anchors"):
        add("MEMORIES", 10_000, anchors=data["anchors"])
    if data.get("memoryItems"):
        add("MEMORIES", 20_000, memory_items=data["memoryItems"])
    if data.get("moments"):
        add("SOCIAL", 0, moments=data["moments"])
    if data.get("comments"):
        add("SOCIAL", 10_000, moment_comments=data["comments"])
    if data.get("likes"):
        add("SOCIAL", 20_000, moment_likes=data["likes"])
    if data.get("diaries"):
        add("SOCIAL", 30_000, diaries=data["diaries"])
    if data.get("experiences"):
        pages.append(page("SOCIAL", 40_000, backup_payload(content(version=version),
                                                           shared_experiences=data["experiences"])))
    if data.get("participants"):
        pages.append(page("SOCIAL", 50_000, backup_payload(content(version=version),
                                                           shared_participants=data["participants"])))
    if data.get("knowledgeBases"):
        add("KNOWLEDGE_BASES", 0, knowledge_bases=data["knowledgeBases"])
    if data.get("chunks"):
        add("KNOWLEDGE_BASES", 10_000, knowledge_base_chunks=data["chunks"])
    if data.get("assignments"):
        add("KNOWLEDGE_BASES", 20_000, assignments=data["assignments"])
    if data.get("gifts"):
        pages.append(page("EXTRAS", 0, backup_payload(content(version=version),
                                                      gift_records=data["gifts"])))
    if data.get("dispatches"):
        pages.append(page("EXTRAS", 10_000, backup_payload(content(version=version),
                                                           dispatch_records=data["dispatches"])))
    if data.get("mahjong") is not None:
        pages.append(page("EXTRAS", 20_000, backup_payload(content(version=version),
                                                           mahjong_save=data["mahjong"])))
    if data.get("settings") is not None:
        add("SETTINGS", 0, settings=data["settings"])
    return pages


def _paginated_payload_from_data(data):
    """v2 分页包的完整 payload（用于 recordCounts 汇总 + 自校验期望）。"""
    return {
        "content": content(
            version=5,
            operators=data.get("operators"), relationships=data.get("relationships"),
            sessions=data.get("sessions"), messages=data.get("messages"),
            memories=data.get("memories"), anchors=data.get("anchors"),
            moments=data.get("moments"), moment_likes=data.get("likes"),
            moment_comments=data.get("comments"), diaries=data.get("diaries"),
            memory_items=data.get("memoryItems"), knowledge_bases=data.get("knowledgeBases"),
            knowledge_base_chunks=data.get("chunks"), assignments=data.get("assignments"),
            settings=data.get("settings"),
        ),
        "displayEvents": data.get("displayEvents") or [],
        "chatArchives": data.get("archives") or [],
        "chatHistorySegments": data.get("history") or [],
        "giftRecords": data.get("gifts") or [],
        "dispatchRecords": data.get("dispatches") or [],
        "mahjongSave": data.get("mahjong"),
        "sharedExperiences": data.get("experiences") or [],
        "sharedExperienceParticipants": data.get("participants") or [],
        "media": data.get("media") or [],
    }


def record_counts(payload):
    """BackupFileWriter.kt:111-131（17 键；注意无 memories/anchors/memoryItems/likes/participants）。"""
    c = payload["content"]
    return {
        "operators": len(c["operators"] or []),
        "relationships": len(c["relationships"] or []),
        "sessions": len(c["sessions"] or []),
        "messages": len(c["messages"] or []),
        "diaries": len(c["diaries"] or []),
        "moments": len(c["moments"] or []),
        "comments": len(c["momentComments"] or []),
        "displayEvents": len(payload["displayEvents"]),
        "archives": len(payload["chatArchives"]),
        "historySegments": len(payload["chatHistorySegments"]),
        "gifts": len(payload["giftRecords"]),
        "dispatches": len(payload["dispatchRecords"]),
        "sharedExperiences": len(payload["sharedExperiences"]),
        "knowledgeBases": len(c["knowledgeBases"] or []),
        "knowledgeBaseChunks": len(c["knowledgeBaseChunks"] or []),
        "knowledgeBaseAssignments": len(c["operatorKnowledgeBaseAssignments"] or []),
        "mediaFiles": len(payload["media"]),
    }


BACKUP_IDS = {
    "full-all-v2.zip": "1f3d9b7c-2a4e-4c8b-9d01-0000000000b1",
    "full-memories-v2.zip": "1f3d9b7c-2a4e-4c8b-9d01-0000000000b2",
    "full-social-v2.zip": "1f3d9b7c-2a4e-4c8b-9d01-0000000000b3",
    "full-knowledge-v2.zip": "1f3d9b7c-2a4e-4c8b-9d01-0000000000b4",
    "full-extras-v2.zip": "1f3d9b7c-2a4e-4c8b-9d01-0000000000b5",
    "full-media-v2.zip": "1f3d9b7c-2a4e-4c8b-9d01-0000000000b6",
    "full-empty-v2.zip": "1f3d9b7c-2a4e-4c8b-9d01-0000000000b7",
    "full-v1-single.zip": "1f3d9b7c-2a4e-4c8b-9d01-0000000000b8",
}


def build_zip_bytes(name, data, format_version=FORMAT_VERSION):
    """构造确定性 ZIP 字节：分页/单 payload → media → manifest（manifest 最后写，BackupFileWriter.kt:96）。"""
    media = data.get("media") or []
    pages = pages_for(data) if format_version == FORMAT_VERSION else []
    payload = _paginated_payload_from_data(data)
    files = []
    page_entries = []
    entries = []  # (path, bytes)
    if format_version == FORMAT_VERSION:
        for p in pages:
            path = "{root}/{cat}/{idx:06d}.json".format(
                root=PAGES_ROOT, cat=p["category"].lower(), idx=p["pageIndex"])  # BackupFileWriter.kt:52
            b = enc(p)
            if len(b) > MAX_PAGE_BYTES:
                raise AssertionError("page too large: " + path)
            entries.append((path, b))
            files.append({"path": path, "size": len(b), "sha256": sha256_hex(b)})
            page_entries.append({"category": p["category"], "pageIndex": p["pageIndex"],
                                 "path": path, "recordCount": record_count(p["payload"])})
        if not pages:
            raise AssertionError("v2 备份必须至少有一个分页")
    else:
        entries.append((LEGACY_PAYLOAD_PATH, enc(payload)))
        b = entries[-1][1]
        files.append({"path": LEGACY_PAYLOAD_PATH, "size": len(b), "sha256": sha256_hex(b)})
    for item in media:
        archive_path = item["archivePath"]
        raw = PNG[item["originalUri"].rsplit("/", 1)[-1]]
        entries.append((archive_path, raw))
        files.append({"path": archive_path, "size": len(raw), "sha256": sha256_hex(raw)})
    manifest = {
        "format": FULL_FORMAT,
        "formatVersion": format_version,
        "scope": SCOPE,
        "appVersion": APP_VERSION,
        "schemaVersion": SCHEMA_VERSION,
        "createdAt": CREATED_AT,
        "backupId": BACKUP_IDS[name],
        "mediaIncluded": len(media) > 0,
        "recordCounts": record_counts(payload),
        "files": files,
        "excludedCategories": list(EXCLUDED),
        "layout": "paged_payload" if format_version == FORMAT_VERSION else "single_payload",
        "pages": page_entries,
    }
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
        for path, raw in entries:
            zi = zipfile.ZipInfo(path, FIXED_ZIP_DATE)
            zi.compress_type = zipfile.ZIP_DEFLATED
            z.writestr(zi, raw)
        zi = zipfile.ZipInfo(MANIFEST_PATH, FIXED_ZIP_DATE)
        zi.compress_type = zipfile.ZIP_DEFLATED
        z.writestr(zi, enc(manifest))
    return buf.getvalue()


# ------------------------------------------------------------------ 各包数据定义（确定性常量）


def pkg_full_all():
    avatar = media_item("golden_avatar.png")
    msg_img = media_item("message_image.png")
    gift_img = media_item("gift_image.png")
    golden = operator("golden", "金样演示员", title="档案管理员", description="负责整理档案。",
                      gender="female", avatarUri=avatar["originalUri"], location="档案室",
                      activity="整理档案", emotion="平静", intimacy=42,
                      privatePrompt="你是金样演示员。", groupPrompt="群聊中保持简洁。",
                      userRelation="朋友", lmb=12000, attack=0.6, defense=0.4,
                      meldPref="high", activityLevel=0.7, voiceName="voice_a",
                      voiceSpeed="1.0", voicePitch="1.0")
    shu = operator("shu", "舒", title="夜班专员", description="夜班值守。", gender="female",
                   location="值班室", activity="值夜班", emotion="困倦", intimacy=18,
                   privatePrompt="你是舒。")
    sessions = [
        session("session_golden", "golden", "金样演示员", lastMessage="晚安。",
                lastTime=T0 + 60000, isPinned=True, unreadCount=2,
                avatarUri=avatar["originalUri"]),
        session("session_shu", "shu", "舒", lastMessage="早。", lastTime=T0 + 50000),
        session("group_galaxy", "group_galaxy", "银河小组", lastMessage="[舒] 收到。",
                lastTime=T0 + 40000, members="golden,shu", rules="保持简洁",
                mutedMembers="shu"),
    ]
    image_content = json.dumps({"imageUri": msg_img["originalUri"], "caption": "档案照片",
                                "visionSummary": ""}, ensure_ascii=False, separators=(",", ":"))
    ai_content = json.dumps({"segments": [
        {"type": "text", "content": "这是档案照片。"},
        {"type": "image", "content": "", "imageUri": msg_img["originalUri"]},
    ]}, ensure_ascii=False, separators=(",", ":"))
    messages = [
        message(1, "session_golden", "user", "我", "今天档案整理得怎么样？", isMe=True, timestamp=T0),
        message(2, "session_golden", "golden", "金样演示员", "整理完了，很顺利。", timestamp=T0 + 1000),
        message(3, "session_golden", "user", "我", image_content, type="image", isMe=True,
                timestamp=T0 + 2000),
        message(4, "session_golden", "golden", "金样演示员", ai_content, type="ai_json",
                timestamp=T0 + 3000),
        message(5, "session_golden", "golden", "金样演示员", "晚安。", timestamp=T0 + 60000),
        message(6, "session_shu", "user", "我", "夜班辛苦。", isMe=True, timestamp=T0 + 10000),
        message(7, "session_shu", "shu", "舒", "还好。", timestamp=T0 + 11000),
        message(8, "session_shu", "user", "我", "早上好。", isMe=True, timestamp=T0 + 50000),
        message(9, "group_galaxy", "golden", "金样演示员", "[金样演示员] 大家早。",
                mode="group", timestamp=T0 + 20000),
        message(10, "group_galaxy", "shu", "舒", "[舒] 收到。", mode="group", timestamp=T0 + 40000),
        message(11, "session_golden", "user", "我", "明天见。", isMe=True, timestamp=T0 + 70000),
        message(12, "session_golden", "golden", "金样演示员", "明天见。", timestamp=T0 + 71000),
    ]
    display = [
        display_event(2, 0, "session_golden", 1),
        display_event(4, 0, "session_golden", 2),
        display_event(4, 1, "session_golden", 3),
        display_event(10, 0, "group_galaxy", 4),
    ]
    archives = [
        archive("archive_golden_1", "session_golden", "golden", "旧日档案",
                json.dumps(messages[0:2], ensure_ascii=False, separators=(",", ":")),
                summary="初次对话"),
        archive("archive_group_1", "group_galaxy", "golden", "群档案",
                json.dumps(messages[8:10], ensure_ascii=False, separators=(",", ":"))),
    ]
    history = [history_segment("hist_golden_1", "session_golden", "上下文裁剪",
                               json.dumps(messages[2:4], ensure_ascii=False, separators=(",", ":")))]
    memories = [
        memory(1, "session_golden", "golden", "SHORT_TERM", "用户今天问了档案进度。",
               keywords="档案", createdAt=T0 + 2500, expiresAt=T0 + 86400000),
        memory(2, "daily_2026-11-22", "daily", "DAILY", "今日总结：整理档案。",
               createdAt=T0 + 80000),
        memory(3, "session_golden", "golden", "LONG_TERM", "用户喜欢被称呼为林深。",
               preferences="称呼林深", createdAt=T0 + 90000),
    ]
    anchors = [
        anchor(1, "session_golden", "golden", "PLAN", "明天继续整理档案。", importance="high"),
        anchor(2, "session_golden", "golden", "PREFERENCE", "用户喜欢简洁回复。",
               source="用户消息", sourceName="我", sourceActor="user", sourceTarget="golden"),
        anchor(3, "session_shu", "shu", "EMOTION", "夜班有点疲惫。", isPrivate=True),
    ]
    memory_items = [
        memory_item(1, "operator", "golden", "L1", "private_chat", "PRIVATE_CHAT",
                    "用户问了档案进度。", sourceRefId="2", sessionId="session_golden"),
        memory_item(2, "operator", "golden", "L2", "impression", "MANUAL_MEMORY",
                    "用户重视档案整理。", status="active"),
        memory_item(3, "operator", "golden", "L3", "impression", "MOMENT",
                    "用户发过档案照片。", sourceRefId="1"),
    ]
    kbs = [knowledge_base("kb_1", "档案手册", "# 档案\n第一章：分类。\n第二章：归档。")]
    chunks = [
        chunk("chunk_1", "kb_1", 1, "第一章：分类。", sourceHeading="第一章"),
        chunk("chunk_2", "kb_1", 2, "第二章：归档。", sourceHeading="第二章"),
    ]
    assignments = [assignment("golden", "kb_1")]
    moments = [
        moment(1, "user", "我", "今天整理完档案了。", isUserPost=True, likeCount=1, commentCount=2),
        moment(2, "golden", "金样演示员", "档案室的灯又亮了一夜。", likeCount=1, commentCount=1),
    ]
    comments = [
        comment(1, 1, "golden", "金样演示员", "辛苦了。"),
        comment(2, 1, "user", "我", "谢谢。", parentCommentId=1, replyToName="金样演示员"),
        comment(3, 2, "shu", "舒", "注意休息。"),
    ]
    likes = [like(1, 1, "golden", "金样演示员"), like(2, 2, "user", "我")]
    diaries = [
        diary(1, "golden", "金样演示员", "今天整理了很多档案。", "2026-11-21"),
        diary(2, "shu", "舒", "夜班安静。", "2026-11-21"),
    ]
    experiences = [
        shared_experience(1, "PRIVATE_CHAT", "session_golden", "", "一起整理档案。"),
        shared_experience(2, "GROUP_CHAT", "group_galaxy", "group_galaxy", "小组夜谈。"),
    ]
    participants = [participant(1, "golden"), participant(1, "shu"), participant(2, "golden")]
    gifts = [
        gift(1, "golden", gift_img["originalUri"], "档案夹", "我"),
        gift(2, "shu", "", "夜灯", "我"),
    ]
    dispatches = [
        dispatch("dispatch_1", "archive", status="active", operatorIds="golden"),
        dispatch("dispatch_2", "night_watch", status="finished", operatorIds="shu",
                 netProfit=200, endTime=T0 + 7200000),
    ]
    settings = full_settings(["golden", "shu"], group_session_id="group_galaxy",
                             user_avatar_uri=avatar["originalUri"],
                             support_conversation=json.dumps(
                                 [{"role": "assistant", "content": "需要我整理档案吗？",
                                   "imageUri": msg_img["originalUri"]}],
                                 ensure_ascii=False, separators=(",", ":")))
    return {
        "operators": [golden, shu], "relationships": [
            relationship("golden", "shu", "舒", "BIG_SISTER", intimacy=60),
            relationship("shu", "golden", "金样演示员", "LITTLE_SISTER", intimacy=55, isPreset=True),
        ],
        "sessions": sessions, "messages": messages, "displayEvents": display,
        "archives": archives, "history": history, "memories": memories, "anchors": anchors,
        "memoryItems": memory_items, "knowledgeBases": kbs, "chunks": chunks,
        "assignments": assignments, "moments": moments, "comments": comments, "likes": likes,
        "diaries": diaries, "experiences": experiences, "participants": participants,
        "gifts": gifts, "dispatches": dispatches, "mahjong": mahjong(),
        "settings": settings, "media": [avatar, msg_img, gift_img],
    }


def pkg_full_memories():
    golden = operator("golden", "金样演示员")
    shu = operator("shu", "舒")
    sessions = [session("session_golden", "golden", "金样演示员"),
                session("session_shu", "shu", "舒")]
    messages = [
        message(1, "session_golden", "user", "我", "记住我喜欢茶。", isMe=True, timestamp=T0),
        message(2, "session_golden", "golden", "金样演示员", "记住了。", timestamp=T0 + 1),
        message(3, "session_shu", "user", "我", "舒，晚安。", isMe=True, timestamp=T0 + 2),
        message(4, "session_shu", "shu", "舒", "晚安。", timestamp=T0 + 3),
    ]
    memories = [
        memory(1, "session_golden", "golden", "SHORT_TERM", "用户喜欢茶。", expiresAt=T0 + 3600000),
        memory(2, "daily_2026-11-22", "daily", "DAILY", "今日摘要。"),
        memory(3, "session_golden", "golden", "LONG_TERM", "用户叫林深。"),
        memory(4, "session_ghost", "ghost", "LONG_TERM", "孤儿记忆（应降级 issue）。"),
    ]
    anchors = [
        anchor(1, "session_golden", "golden", "PLAN", "计划：周末喝茶。"),
        anchor(2, "session_golden", "golden", "PREFERENCE", "偏好：清淡。"),
        anchor(3, "session_golden", "golden", "TABOO", "禁忌：熬夜。"),
        anchor(4, "session_golden", "golden", "EVENT", "事件：一起喝茶。"),
        anchor(5, "session_golden", "golden", "EMOTION", "情绪：开心。"),
        anchor(6, "session_shu", "shu", "RELATION", "关系：同事。"),
        anchor(7, "session_ghost", "ghost", "PLAN", "孤儿锚点（应降级 issue）。"),
    ]
    memory_items = [
        memory_item(1, "operator", "golden", "L1", "private_chat", "PRIVATE_CHAT",
                    "用户喜欢茶。", sourceRefId="1", sessionId="session_golden"),
        memory_item(2, "operator", "golden", "L1", "moment", "MOMENT",
                    "用户发过动态。", sourceRefId="1"),
        memory_item(3, "operator", "golden", "L2", "impression", "MOMENT_COMMENT",
                    "用户评论过。", sourceRefId="1"),
        memory_item(4, "operator", "golden", "L3", "impression", "MANUAL_MEMORY",
                    "长期印象：细致。"),
    ]
    return {"operators": [golden, shu], "sessions": sessions, "messages": messages,
            "memories": memories, "anchors": anchors, "memoryItems": memory_items,
            "settings": None, "media": []}


def pkg_full_social():
    golden = operator("golden", "金样演示员")
    moments = [
        moment(1, "user", "我", "今天天气不错。", isUserPost=True, likeCount=1, commentCount=1),
        moment(2, "golden", "金样演示员", "档案室的窗外有云。", likeCount=1, commentCount=1),
    ]
    comments = [
        comment(1, 1, "golden", "金样演示员", "是呀。"),
        comment(2, 1, "user", "我", "出去走走？", parentCommentId=1, replyToName="金样演示员"),
        comment(3, 2, "golden", "金样演示员", "云很好看。"),
        comment(9, 99, "golden", "金样演示员", "孤儿评论（应降级 issue）。"),
    ]
    likes = [like(1, 1, "golden", "金样演示员"), like(2, 2, "user", "我"),
             like(9, 99, "user", "我")]
    diaries = [
        diary(1, "golden", "金样演示员", "今天天气不错。", "2026-11-21"),
        diary(2, "golden", "金样演示员", "档案室很安静。", "2026-11-22"),
    ]
    experiences = [
        shared_experience(1, "MOMENT", "1", "", "一起看天气。"),
        shared_experience(2, "DIARY", "2", "", "一起写日记。"),
    ]
    participants = [participant(1, "golden"), participant(2, "golden"),
                    participant(2, "ghost", role="observer")]
    return {"operators": [golden], "moments": moments, "comments": comments, "likes": likes,
            "diaries": diaries, "experiences": experiences, "participants": participants,
            "settings": None, "media": []}


def pkg_full_knowledge():
    golden = operator("golden", "金样演示员")
    shu = operator("shu", "舒")
    kbs = [knowledge_base("kb_1", "档案手册", "# 档案手册\n第一章：分类。\n第二章：归档。",
                          sourceFileName="archive.md", sourceType="user")]
    chunks = [
        chunk("chunk_1", "kb_1", 1, "第一章：分类。", sourceHeading="第一章"),
        chunk("chunk_2", "kb_1", 2, "第二章：归档。", sourceHeading="第二章",
              userKeywords="归档"),
    ]
    assignments = [assignment("golden", "kb_1", sortOrder=1), assignment("shu", "kb_1", sortOrder=2)]
    return {"operators": [golden, shu], "knowledgeBases": kbs, "chunks": chunks,
            "assignments": assignments, "settings": None, "media": []}


def pkg_full_extras():
    golden = operator("golden", "金样演示员")
    sessions = [session("session_golden", "golden", "金样演示员")]
    messages = [
        message(1, "session_golden", "user", "我", "明天见。", isMe=True, timestamp=T0),
        message(2, "session_golden", "golden", "金样演示员", "明天见。", timestamp=T0 + 1),
    ]
    display = [display_event(1, 0, "session_golden", 1), display_event(2, 0, "session_golden", 2)]
    archives = [archive("archive_1", "session_golden", "golden", "旧档",
                        json.dumps(messages, ensure_ascii=False, separators=(",", ":")))]
    history = [history_segment("hist_1", "session_golden", "裁剪",
                               json.dumps(messages, ensure_ascii=False, separators=(",", ":")))]
    gifts = [gift(1, "golden", "", "档案夹", "我"), gift(2, "golden", "", "夜灯", "我")]
    dispatches = [
        dispatch("dispatch_1", "archive", status="active"),
        dispatch("dispatch_2", "night_watch", status="finished", netProfit=200),
    ]
    return {"operators": [golden], "sessions": sessions, "messages": messages,
            "displayEvents": display, "archives": archives, "history": history,
            "gifts": gifts, "dispatches": dispatches, "mahjong": mahjong(),
            "settings": None, "media": []}


def pkg_full_media():
    avatar = media_item("golden_avatar.png")
    session_avatar = media_item("session_avatar.png")
    msg_img = media_item("message_image.png")
    gift_img = media_item("gift_image.png")
    golden = operator("golden", "金样演示员", avatarUri=avatar["originalUri"],
                      description="头像来自备份媒体。")
    sessions = [session("session_golden", "golden", "金样演示员",
                        avatarUri=session_avatar["originalUri"])]
    image_content = json.dumps({"imageUri": msg_img["originalUri"], "caption": "档案照片",
                                "visionSummary": ""}, ensure_ascii=False, separators=(",", ":"))
    ai_content = json.dumps({"segments": [
        {"type": "text", "content": "照片如下。"},
        {"type": "image", "content": "", "imageUri": msg_img["originalUri"]},
    ]}, ensure_ascii=False, separators=(",", ":"))
    messages = [
        message(1, "session_golden", "user", "我", image_content, type="image",
                isMe=True, timestamp=T0),
        message(2, "session_golden", "golden", "金样演示员", ai_content, type="ai_json",
                timestamp=T0 + 1),
    ]
    archives = [archive("archive_media_1", "session_golden", "golden", "媒体存档",
                        json.dumps([messages[0]], ensure_ascii=False, separators=(",", ":")),
                        stateJson=json.dumps({"sourceMessagesJson":
                                             json.dumps([messages[1]], ensure_ascii=False,
                                                        separators=(",", ":"))},
                                             ensure_ascii=False, separators=(",", ":")))]
    history = [history_segment("hist_media_1", "session_golden", "媒体历史",
                               json.dumps([messages[1]], ensure_ascii=False, separators=(",", ":")))]
    gifts = [gift(1, "golden", gift_img["originalUri"], "档案夹", "我")]
    settings = full_settings(["golden"], user_avatar_uri=avatar["originalUri"],
                             support_conversation=json.dumps(
                                 [{"role": "assistant", "content": "需要看图吗？",
                                   "imageUri": msg_img["originalUri"]}],
                                 ensure_ascii=False, separators=(",", ":")))
    return {"operators": [golden], "sessions": sessions, "messages": messages,
            "archives": archives, "history": history, "gifts": gifts,
            "settings": settings, "media": [avatar, session_avatar, msg_img, gift_img]}


def pkg_full_empty():
    return {"operators": [], "settings": {}}


def pkg_full_v1():
    avatar_media = None
    golden = operator("golden", "金样演示员")
    shu = operator("shu", "舒")
    sessions = [session("session_golden", "golden", "金样演示员")]
    messages = [
        message(1, "session_golden", "user", "我", "v1 兼容包。", isMe=True, timestamp=T0),
        message(2, "session_golden", "golden", "金样演示员", "收到。", timestamp=T0 + 1),
    ]
    return {
        "operators": [golden, shu],
        "relationships": [relationship("golden", "shu", "舒", "FRIEND")],
        "sessions": sessions, "messages": messages,
        "displayEvents": [display_event(1, 0, "session_golden", 1)],
        "archives": [archive("archive_v1", "session_golden", "golden", "旧档",
                             json.dumps(messages, ensure_ascii=False, separators=(",", ":")))],
        "history": [history_segment("hist_v1", "session_golden", "裁剪",
                                    json.dumps(messages, ensure_ascii=False, separators=(",", ":")))],
        "memories": [memory(1, "session_golden", "golden", "LONG_TERM", "用户喜欢茶。")],
        "anchors": [anchor(1, "session_golden", "golden", "PREFERENCE", "偏好：茶。")],
        "memoryItems": [memory_item(1, "operator", "golden", "L1", "private_chat",
                                    "PRIVATE_CHAT", "用户喜欢茶。",
                                    sourceRefId="1", sessionId="session_golden")],
        "knowledgeBases": [knowledge_base("kb_v1", "v1 手册", "# v1")],
        "chunks": [chunk("chunk_v1", "kb_v1", 1, "v1 内容。")],
        "assignments": [assignment("golden", "kb_v1")],
        "moments": [moment(1, "user", "我", "v1 动态。", isUserPost=True, likeCount=1, commentCount=1)],
        "comments": [comment(1, 1, "golden", "金样演示员", "v1 评论。")],
        "likes": [like(1, 1, "golden", "金样演示员")],
        "diaries": [diary(1, "golden", "金样演示员", "v1 日记。", "2026-11-21")],
        "experiences": [shared_experience(1, "MOMENT", "1", "", "v1 共同经历。")],
        "participants": [participant(1, "golden")],
        "gifts": [gift(1, "golden", "", "v1 礼物", "我")],
        "dispatches": [dispatch("dispatch_v1", "archive", status="active", operatorIds="golden")],
        "mahjong": mahjong("{\"round\":2}"),
        "settings": full_settings(["golden"]),
        "media": [],
    }


PACKAGES = {
    "full-all-v2.zip": (pkg_full_all, FORMAT_VERSION),
    "full-memories-v2.zip": (pkg_full_memories, FORMAT_VERSION),
    "full-social-v2.zip": (pkg_full_social, FORMAT_VERSION),
    "full-knowledge-v2.zip": (pkg_full_knowledge, FORMAT_VERSION),
    "full-extras-v2.zip": (pkg_full_extras, FORMAT_VERSION),
    "full-media-v2.zip": (pkg_full_media, FORMAT_VERSION),
    "full-empty-v2.zip": (pkg_full_empty, FORMAT_VERSION),
    "full-v1-single.zip": (pkg_full_v1, LEGACY_FORMAT_VERSION),
}

# ------------------------------------------------------------------ 自校验（镜像 BackupFileReader 关键检查）


def _refs(payload, strict_display_events=True):
    """返回 (expected_issue_counts, hard_error)；镜像 BackupFileReader.kt:132-207。"""
    c = payload["content"]
    opts = c["operators"] or []
    sessions = c["sessions"] or []
    messages = c["messages"] or []
    moments = c["moments"] or []
    comments = c["momentComments"] or []
    kbs = c["knowledgeBases"] or []
    chunks = c["knowledgeBaseChunks"] or []
    assignments = c["operatorKnowledgeBaseAssignments"] or []
    op_ids = [o["id"] for o in opts]
    op_set = set(op_ids)
    sess_ids = [s["id"] for s in sessions]
    sess_set = set(sess_ids)
    msg_ids = [m["id"] for m in messages]
    moment_ids = {m["id"] for m in moments}
    comment_ids = {c2["id"] for c2 in comments}
    kb_ids = {k["id"] for k in kbs}
    if len(set(op_ids)) != len(op_ids):
        return None, "备份包含重复角色"
    if len(sess_set) != len(sess_ids):
        return None, "备份包含重复会话"
    if len(set(msg_ids)) != len(msg_ids):
        return None, "备份包含重复聊天消息"
    if len(kb_ids) != len(kbs):
        return None, "备份包含重复知识库"
    if len({ch["id"] for ch in chunks}) != len(chunks):
        return None, "备份包含重复知识库分段"
    if len({(ch["knowledgeBaseId"], ch["ordinal"]) for ch in chunks}) != len(chunks):
        return None, "备份包含重复知识库分段序号"
    if len({(a["operatorId"], a["knowledgeBaseId"]) for a in assignments}) != len(assignments):
        return None, "备份包含重复角色知识库关联"
    for ch in chunks:
        if ch["knowledgeBaseId"] not in kb_ids:
            return None, "知识库分段引用不存在的知识库"
    for a in assignments:
        if a["operatorId"] not in op_set or a["knowledgeBaseId"] not in kb_ids:
            return None, "角色知识库关联引用不完整"
    for r in c["relationships"] or []:
        if r["operatorId"] not in op_set or r["relatedOperatorId"] not in op_set:
            return None, "角色关系引用了不存在的角色"
    for s in sessions:
        if s["operatorId"].startswith("group_"):
            for member in [x.strip() for x in s["members"].split(",") if x.strip()]:
                if member not in op_set:
                    return None, "群聊成员引用了不存在的角色：" + member
        elif s["operatorId"] not in op_set:
            return None, "私聊会话引用了不存在的角色：" + s["operatorId"]
    for m in messages:
        if m["sessionId"] not in sess_set:
            return None, "聊天消息引用了不存在的会话"
    msg_session = {m["id"]: m["sessionId"] for m in messages}
    events = payload["displayEvents"]
    if strict_display_events:
        if len({(e["messageId"], e["segmentIndex"]) for e in events}) != len(events):
            return None, "备份包含重复消息展示事件"
        for e in events:
            if e["sessionId"] not in sess_set or msg_session.get(e["messageId"]) != e["sessionId"]:
                return None, "消息展示事件引用不完整"
    for a in payload["chatArchives"]:
        if a["sessionId"] not in sess_set or a["operatorId"] not in op_set:
            return None, "聊天存档引用不完整"
    for h in payload["chatHistorySegments"]:
        if h["sessionId"] not in sess_set:
            return None, "聊天历史片段引用了不存在的会话"
    for g in payload["giftRecords"]:
        if g["operatorId"] not in op_set:
            return None, "礼物记录引用了不存在的角色"
    issues = {}
    orphan_mem = sum(1 for m in (c["memories"] or [])
                     if not (m["type"] == "DAILY" and m["operatorId"] == "daily"
                             and m["sessionId"].startswith("daily_"))
                     and (m["operatorId"] not in op_set or m["sessionId"] not in sess_set))
    if orphan_mem:
        issues["ORPHAN_MEMORY"] = orphan_mem
    orphan_anchor = sum(1 for a in (c["anchors"] or [])
                        if a["operatorId"] not in op_set or a["sessionId"] not in sess_set)
    if orphan_anchor:
        issues["ORPHAN_ANCHOR"] = orphan_anchor
    orphan_comment = sum(1 for cm in comments
                         if cm["momentId"] not in moment_ids
                         or (cm["parentCommentId"] != 0 and cm["parentCommentId"] not in comment_ids))
    if orphan_comment:
        issues["ORPHAN_MOMENT_COMMENT"] = orphan_comment
    orphan_like = sum(1 for lk in (c["momentLikes"] or []) if lk["momentId"] not in moment_ids)
    if orphan_like:
        issues["ORPHAN_MOMENT_LIKE"] = orphan_like
    exp_ids = {e["id"] for e in payload["sharedExperiences"]}
    orphan_part = sum(1 for p in payload["sharedExperienceParticipants"]
                      if p["experienceId"] not in exp_ids or p["operatorId"] not in op_set)
    if orphan_part:
        issues["ORPHAN_SHARED_EXPERIENCE_PARTICIPANT"] = orphan_part
    return issues, None


def merge_pages(pages):
    pages = sorted(pages, key=lambda p: (p["category"], p["pageIndex"]))
    c = content(
        version=5,
        operators=_flat(pages, lambda p: p["payload"]["content"]["operators"]),
        relationships=_flat(pages, lambda p: p["payload"]["content"]["relationships"]),
        sessions=_flat(pages, lambda p: p["payload"]["content"]["sessions"]),
        messages=_flat(pages, lambda p: p["payload"]["content"]["messages"]),
        memories=_flat(pages, lambda p: p["payload"]["content"]["memories"]),
        anchors=_flat(pages, lambda p: p["payload"]["content"]["anchors"]),
        moments=_flat(pages, lambda p: p["payload"]["content"]["moments"]),
        moment_likes=_flat(pages, lambda p: p["payload"]["content"]["momentLikes"]),
        moment_comments=_flat(pages, lambda p: p["payload"]["content"]["momentComments"]),
        diaries=_flat(pages, lambda p: p["payload"]["content"]["diaries"]),
        memory_items=_flat(pages, lambda p: p["payload"]["content"]["memoryItems"]),
        knowledge_bases=_flat(pages, lambda p: p["payload"]["content"]["knowledgeBases"]),
        knowledge_base_chunks=_flat(pages, lambda p: p["payload"]["content"]["knowledgeBaseChunks"]),
        assignments=_flat(pages, lambda p: p["payload"]["content"]["operatorKnowledgeBaseAssignments"]),
        settings=next((p["payload"]["content"]["settings"] for p in reversed(pages)
                       if p["payload"]["content"]["settings"] is not None), None),
    )
    return {
        "content": c,
        "displayEvents": [e for p in pages for e in p["payload"]["displayEvents"]],
        "chatArchives": [e for p in pages for e in p["payload"]["chatArchives"]],
        "chatHistorySegments": [e for p in pages for e in p["payload"]["chatHistorySegments"]],
        "giftRecords": [e for p in pages for e in p["payload"]["giftRecords"]],
        "dispatchRecords": [e for p in pages for e in p["payload"]["dispatchRecords"]],
        "mahjongSave": next((p["payload"]["mahjongSave"] for p in reversed(pages)
                             if p["payload"]["mahjongSave"] is not None), None),
        "sharedExperiences": [e for p in pages for e in p["payload"]["sharedExperiences"]],
        "sharedExperienceParticipants": [e for p in pages for e in p["payload"]["sharedExperienceParticipants"]],
        "media": [e for p in pages for e in p["payload"]["media"]],
    }


def _flat(pages, getter):
    out = [x for p in pages for x in (getter(p) or [])]
    return out if out else None


def verify_zip(path):
    name = os.path.basename(path)
    raw = open(path, "rb").read()
    rebuild_name = name if name in PACKAGES else None
    checks = []
    z = zipfile.ZipFile(io.BytesIO(raw))
    names = z.namelist()
    if len(names) != len(set(names)):
        raise AssertionError("ZIP 重复路径")
    entries = {n: z.read(n) for n in names if not n.endswith("/")}
    checks.append(("ZIP 路径唯一", len(entries)))
    if MANIFEST_PATH not in entries:
        raise AssertionError("缺少 manifest.json")
    manifest = json.loads(entries[MANIFEST_PATH].decode("utf-8"))
    fmt = manifest["formatVersion"]
    assert manifest["format"] == FULL_FORMAT, "format"
    assert fmt in (1, 2), "formatVersion"
    assert manifest["scope"] == SCOPE, "scope"
    checks.append(("manifest 三元组", 1))
    files = manifest["files"]
    assert len({f["path"] for f in files}) == len(files), "files 重复"
    assert {f["path"] for f in files} == set(entries) - {MANIFEST_PATH}, "files 集合不一致"
    for f in files:
        data = entries[f["path"]]
        assert len(data) == f["size"], "size " + f["path"]
        assert sha256_hex(data) == f["sha256"], "sha256 " + f["path"]
    checks.append(("逐条目 size+sha256", len(files)))
    for p in files:
        assert p["path"] == p["path"].replace("\\", "/"), "路径归一"
        assert not p["path"].startswith("/") and ":/" not in p["path"], "不安全路径"
        parts = p["path"].split("/")
        assert all(x and x not in (".", "..") for x in parts), "不安全路径段"
    if fmt == 2:
        assert manifest["layout"] == "paged_payload", "layout"
        assert LEGACY_PAYLOAD_PATH not in entries, "v2 不能含单 payload"
        pages = manifest["pages"]
        assert pages, "v2 缺少分页清单"
        assert len({p["path"] for p in pages}) == len(pages), "分页重复路径"
        assert len({(p["category"], p["pageIndex"]) for p in pages}) == len(pages), "分页重复索引"
        page_names = {n for n in entries if n.startswith(PAGES_ROOT + "/")}
        assert {p["path"] for p in pages} == page_names, "分页清单与 ZIP 不一致"
        decoded = []
        for p in pages:
            assert p["path"].startswith(PAGES_ROOT + "/"), "分页路径"
            page_obj = json.loads(entries[p["path"]].decode("utf-8"))
            assert page_obj["category"] == p["category"], "分页 category"
            assert page_obj["pageIndex"] == p["pageIndex"], "分页 pageIndex"
            assert record_count(page_obj["payload"]) == p["recordCount"], "分页 recordCount"
            assert len(entries[p["path"]]) <= MAX_PAGE_BYTES, "分页超限"
            decoded.append(page_obj)
        payload = merge_pages(decoded)
        cats = {p["category"] for p in pages}
        expected_cats = EXPECTED_CATEGORIES.get(name)
        if expected_cats is not None:
            assert cats == expected_cats, "类目覆盖不符: {} != {}".format(cats, expected_cats)
        checks.append(("分页清单/标识/recordCount", len(pages)))
        checks.append(("类目覆盖", len(cats)))
    else:
        assert LEGACY_PAYLOAD_PATH in entries, "v1 缺少 data/backup.json"
        payload = json.loads(entries[LEGACY_PAYLOAD_PATH].decode("utf-8"))
    assert payload["content"]["type"] == "full_backup", "content.type"
    assert manifest["mediaIncluded"] == (len(payload["media"]) > 0), "mediaIncluded 不一致"
    checks.append(("载荷标记", 1))
    issues, err = _refs(payload, strict_display_events=(fmt == 2))
    assert err is None, "引用完整性: " + str(err)
    expected_issues = EXPECTED_ISSUES.get(name, {})
    assert issues == expected_issues, "孤儿 issue 不符: {} != {}".format(issues, expected_issues)
    checks.append(("引用完整性", 1 + sum(expected_issues.values())))
    media = payload["media"]
    assert len({m["archivePath"] for m in media}) == len(media), "媒体重复路径"
    assert len({m["mediaId"] for m in media}) == len(media), "媒体重复 mediaId"
    zip_media = {n for n in entries if n.startswith("media/")}
    assert {m["archivePath"] for m in media} == zip_media, "媒体清单与 ZIP 不一致"
    for m in media:
        stable = sha256_hex(canonical_path(m["originalUri"].rsplit("/", 1)[-1]).encode("utf-8"))[:24]
        assert m["mediaId"] == "local-image/" + stable, "mediaId 路径派生不符: " + m["mediaId"]
        assert m["archivePath"] == "media/images/" + stable + ".png", "archivePath 不符"
    checks.append(("媒体清单/mediaId 派生", len(media)))
    expected_counts = record_counts(payload)
    assert manifest["recordCounts"] == expected_counts, "manifest.recordCounts 不符"
    checks.append(("recordCounts 17 键", len(expected_counts)))
    if rebuild_name is not None:
        rebuilt = build_zip_bytes(rebuild_name, PACKAGES[rebuild_name][0](), PACKAGES[rebuild_name][1])
        assert rebuilt == raw, "确定性重生成字节不一致（生成器漂移或文件被手改）"
        checks.append(("确定性逐字节", 1))
    for title, count in checks:
        print("  [OK] {}: {}".format(title, count))
    print("PASS " + name + " sha256=" + sha256_hex(raw))


def main(argv):
    if len(argv) == 3 and argv[1] == "--verify":
        verify_zip(argv[2])
        return 0
    if len(argv) == 2 and argv[1] == "--verify":
        for name in PACKAGES:
            verify_zip(os.path.join(HERE, name))
        return 0
    hashes = {}
    for name, (builder, fmt) in PACKAGES.items():
        path = os.path.join(HERE, name)
        raw = build_zip_bytes(name, builder(), fmt)
        with open(path, "wb") as fh:
            fh.write(raw)
        hashes[name] = sha256_hex(raw)
        print("wrote {} ({} bytes)".format(name, len(raw)))
    print("\n---- 冻结 SHA-256（写入 README.md §1 与 sync_golden.sh EXPECTED_HASHES）----")
    for name in PACKAGES:
        print("{}  {}".format(hashes[name], name))
    print("\n---- 自校验 ----")
    for name in PACKAGES:
        verify_zip(os.path.join(HERE, name))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
