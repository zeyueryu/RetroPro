#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把 APK 上传到 AtomGit（实际托管在 GitCode）的 Release —— **不经过 CI / 不依赖 GitHub**。

## 需要什么
只有一样：GitCode 访问令牌（在 https://gitcode.com/setting/token-classic 生成）
    - 代码同步用 SSH 密钥就够，但**发版是 API 调用，必须用令牌**，两者不能互相替代。

## 用法
    # 令牌走环境变量
    ATOMGIT_TOKEN=xxx python tools/upload_to_atomgit.py --apk deliver/RetroPro-1.2.0-m1-signed.apk

    # 或把令牌存成文件（推荐：不落进 shell 历史）
    python tools/upload_to_atomgit.py --apk <apk> --token-file ~/.atomgit_token

    # 先看它打算干什么，不真的上传
    python tools/upload_to_atomgit.py --apk <apk> --dry-run

tag 默认从 APK 文件名推导（`RetroPro-1.2.0-m1-signed.apk` → `v1.2.0-m1`），也可 --tag 指定。
tag 不存在时会由 GitCode 在默认分支 HEAD 上自动创建。

## 用到的接口（取自 GitCode 官方文档，非猜测）
    ① POST /api/v5/repos/{owner}/{repo}/releases          建版本
    ② GET  .../releases/{tag}/upload_url?file_name=x      取预签名上传地址
    ③ PUT  <上一步返回的 url>（带返回的 headers）          上传文件
    ④ GET  .../releases/tags/{tag}                        回读附件直链
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.request

API = "https://api.gitcode.com/api/v5"


def read_token(args) -> str:
    tok = (args.token or os.environ.get("ATOMGIT_TOKEN") or "").strip()
    if tok:
        return tok
    path = os.path.expanduser(args.token_file or "~/.atomgit_token")
    if os.path.isfile(path):
        return open(path, encoding="utf-8").read().strip()
    sys.exit(
        "✗ 没有令牌。三种给法任选：\n"
        "   --token xxx\n"
        "   ATOMGIT_TOKEN=xxx（环境变量）\n"
        f"   把令牌写进文件 {path}（推荐）\n"
        "  生成地址：https://gitcode.com/setting/token-classic"
    )


def api(method: str, path: str, token: str, body: dict | None = None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(
        API + path,
        method=method,
        data=data,
        headers={
            "Authorization": f"Bearer {token}",
            "Content-Type": "application/json",
            "User-Agent": "RetroPro-release-uploader",
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=120) as resp:
            raw = resp.read()
            return resp.status, (json.loads(raw) if raw else {})
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")[:400]


def guess_tag(apk: str) -> str:
    m = re.search(r"RetroPro-([0-9][^-]*(?:-[0-9A-Za-z.]+)?)-signed\.apk$", os.path.basename(apk))
    return f"v{m.group(1)}" if m else ""


def main() -> None:
    ap = argparse.ArgumentParser(description="上传 APK 到 AtomGit / GitCode Release")
    ap.add_argument("--apk", required=True, help="APK 文件路径")
    ap.add_argument("--repo", default="zeyueryu/RetroPro", help="owner/repo（默认 %(default)s）")
    ap.add_argument("--tag", default="", help="Release 标签，默认从 APK 文件名推导")
    ap.add_argument("--name", default="", help="Release 标题，默认 RetroPro <tag>")
    ap.add_argument("--notes-file", default="", help="发布说明 markdown 文件（可选）")
    ap.add_argument("--token", default="")
    ap.add_argument("--token-file", default="~/.atomgit_token")
    ap.add_argument("--dry-run", action="store_true", help="只打印计划，不真的上传")
    args = ap.parse_args()

    apk = args.apk
    if not os.path.isfile(apk):
        sys.exit(f"✗ 找不到 APK：{apk}")
    tag = args.tag or guess_tag(apk)
    if not tag:
        sys.exit("✗ 无法从文件名推导 tag，请用 --tag 指定（如 --tag v1.2.0-m1）")
    name = args.name or f"RetroPro {tag}"
    body = open(args.notes_file, encoding="utf-8").read() if args.notes_file and os.path.isfile(args.notes_file) else f"RetroPro {tag}"
    size_mb = os.path.getsize(apk) / 1024 / 1024

    print(f"仓库 : {args.repo}")
    print(f"标签 : {tag}")
    print(f"标题 : {name}")
    print(f"文件 : {apk}  ({size_mb:.1f} MiB)")
    if args.dry_run:
        print("\n（--dry-run：到此为止，未上传）")
        return

    token = read_token(args)
    print()

    # ① 建版本（已存在时返回 409/400 之类，不算失败）
    code, res = api("POST", f"/repos/{args.repo}/releases", token,
                    {"tag_name": tag, "name": name, "body": body})
    print(f"① 建版本      → HTTP {code}" + (f"（{res}）" if isinstance(res, str) else ""))

    # ② 取预签名上传地址
    code, spec = api("GET", f"/repos/{args.repo}/releases/{tag}/upload_url"
                            f"?file_name={os.path.basename(apk)}", token)
    if code != 200 or not isinstance(spec, dict) or "url" not in spec:
        sys.exit(f"✗ 取上传地址失败 → HTTP {code}：{spec}")
    print("② 取上传地址  → OK（预签名 URL 已拿到）")

    # ③ 上传
    data = open(apk, "rb").read()
    req = urllib.request.Request(spec["url"], data=data, method="PUT")
    for k, v in (spec.get("headers") or {}).items():
        req.add_header(k, v)
    try:
        with urllib.request.urlopen(req, timeout=900) as resp:
            print(f"③ 上传        → HTTP {resp.status}（{size_mb:.1f} MiB 已传完）")
    except urllib.error.HTTPError as e:
        sys.exit(f"✗ 上传失败 → HTTP {e.code}：{e.read().decode('utf-8', 'replace')[:300]}")

    # ④ 回读附件直链
    code, rel = api("GET", f"/repos/{args.repo}/releases/tags/{tag}", token)
    print("④ 回读附件    →", "OK" if code == 200 else f"HTTP {code}")
    if code == 200 and isinstance(rel, dict):
        for a in rel.get("assets") or []:
            print(f"   直链：{a.get('browser_download_url')}")
    print("\n完成。到 AtomGit 仓库的 Releases 页即可看到。")


if __name__ == "__main__":
    main()
