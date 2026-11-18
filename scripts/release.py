#!/usr/bin/env python3
"""知乎水墨同仓库测试版发布；先校验完整产物，再从草稿公开。"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

REPOSITORY = "xiayuhu0414/zhihu-eink"
APPLICATION_ID = "com.github.xiayuhu0414.zhihu.eink"
CERTIFICATE_SHA256 = "955081fa13f79f08a20f7188a3a0602e4cd4545757b79a46f265595b092e5b4f"
MAX_APK_BYTES = 128 * 1024 * 1024


def run(*args):
    return subprocess.run(args, check=True, capture_output=True, text=True).stdout


def sha256(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def build_tools():
    sdk = Path(os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT", ""))
    candidates = list((sdk / "build-tools").glob("*/apksigner"))
    if not candidates:
        raise ValueError("找不到 Android build-tools；请设置 ANDROID_HOME")
    return max(candidates, key=lambda p: tuple(int(n) for n in re.findall(r"\d+", p.parent.name))).parent


def check_apk(apk, code, tools=None):
    tools = tools or build_tools()
    badging = run(str(tools / "aapt2"), "dump", "badging", str(apk))
    package = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging)
    if not package or package.groups() != (APPLICATION_ID, str(code), f"0.1.{code}"):
        raise ValueError("APK 包名或版本不匹配")
    if not re.search(r"(?m)^(?:minSdkVersion|sdkVersion):'27'$", badging):
        raise ValueError("APK 最低 Android 版本不匹配")
    certs = run(str(tools / "apksigner"), "verify", "--verbose", "--print-certs", str(apk))
    digests = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})", certs)
    if digests != [CERTIFICATE_SHA256]:
        raise ValueError("APK 固定签名不匹配")


def names(code):
    return [f"zhihu-eink-lite-0.1.{code}.apk", "SHA256SUMS.txt", "build-info.json", "LICENSE", f"zhihu-eink-source-0.1.{code}.tar.gz"]


def prepare(output, apk, code, commit, run_id):
    output.mkdir(parents=True, exist_ok=True)
    if not 1 <= code <= 2100000000 or not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise ValueError("构建编号或源码提交无效")
    check_apk(apk, code)
    apk_dest = output / names(code)[0]
    shutil.copy2(apk, apk_dest)
    source = output / names(code)[-1]
    run("git", "archive", "--format=tar.gz", f"--prefix=zhihu-eink-0.1.{code}/", f"--output={source.resolve()}", commit)
    shutil.copy2("LICENSE", output / "LICENSE")
    hashes = {p.name: sha256(p) for p in [apk_dest, source]}
    (output / "SHA256SUMS.txt").write_text("".join(f"{digest}  {name}\n" for name, digest in hashes.items()))
    info = dict(applicationId=APPLICATION_ID, versionName=f"0.1.{code}", versionCode=code,
                certificateSha256=CERTIFICATE_SHA256, sourceCommit=commit, workflowRunId=str(run_id),
                workflowRunNumber=code, sha256=hashes)
    (output / "build-info.json").write_text(json.dumps(info, indent=2) + "\n")
    verify(output, code, commit, run_id)


def verify(output, code, commit, run_id):
    expected = names(code)
    if any(not (output / name).is_file() or (output / name).is_symlink() for name in expected):
        raise ValueError("发布产物缺失或不是普通文件")
    info = json.loads((output / "build-info.json").read_text())
    required = dict(applicationId=APPLICATION_ID, versionName=f"0.1.{code}", versionCode=code,
                    certificateSha256=CERTIFICATE_SHA256, sourceCommit=commit, workflowRunId=str(run_id),
                    workflowRunNumber=code)
    if any(info.get(key) != value for key, value in required.items()):
        raise ValueError("发布产物来源、版本或签名信息不匹配")
    if not 0 < (output / expected[0]).stat().st_size <= MAX_APK_BYTES:
        raise ValueError("APK 大小无效")
    hashes = {name: sha256(output / name) for name in [expected[0], expected[-1]]}
    checksum_text = "".join(f"{digest}  {name}\n" for name, digest in hashes.items())
    if info.get("sha256") != hashes or (output / "SHA256SUMS.txt").read_text() != checksum_text:
        raise ValueError("发布文件摘要不匹配")
    check_apk(output / expected[0], code)
    return [output / name for name in expected]


def gh(*args):
    return run("gh", *args)


def release_code(release):
    match = re.fullmatch(r"test-v0\.1\.([1-9][0-9]*)", release["tag_name"])
    return int(match[1]) if match else 0


def publish(output, code, commit, run_id):
    files = verify(output, code, commit, run_id)
    releases = json.loads(gh("api", f"repos/{REPOSITORY}/releases?per_page=100"))
    if any(not r["draft"] and release_code(r) > code for r in releases):
        raise ValueError("已有更新构建公开，拒绝发布旧版本")
    tag = f"test-v0.1.{code}"
    existing = next((r for r in releases if r["tag_name"] == tag), None)
    if existing and not existing["draft"]:
        # 重跑只核对，不覆盖已经被设备下载的公开版本。
        with tempfile.TemporaryDirectory() as destination:
            gh("release", "download", tag, "--repo", REPOSITORY, "--dir", destination)
            if any(not (Path(destination) / p.name).is_file() or sha256(Path(destination) / p.name) != sha256(p) for p in files):
                raise ValueError("同名公开版本与本次产物不同，禁止覆盖")
        print(f"已核对现有公开版本 {tag}，无需重复发布")
        return
    if not existing:
        subject = run("git", "show", "-s", "--format=%s", commit).strip()
        notes = (f"知乎水墨测试版 0.1.{code}（构建 {code}）\n\n{subject}\n\n"
                 "在应用的“系统与更新”中检查并下载安装。首次安装由 Android 确认；后续沿用同包名、固定签名覆盖升级。\n\n"
                 f"源码提交：{commit}\n构建运行：https://github.com/{REPOSITORY}/actions/runs/{run_id}\n\n"
                 "APK、SHA-256 校验文件、构建信息与对应 AGPL-3.0 源码归档见附件。")
        with tempfile.TemporaryDirectory() as temporary:
            notes_path = Path(temporary) / "notes.md"
            notes_path.write_text(notes)
            gh("release", "create", tag, "--repo", REPOSITORY, "--target", commit, "--draft", "--prerelease",
               "--title", f"知乎水墨 0.1.{code}（测试构建 {code}）", "--notes-file", str(notes_path))
    gh("release", "upload", tag, "--repo", REPOSITORY, "--clobber", *[str(p) for p in files])
    current_releases = json.loads(gh("api", f"repos/{REPOSITORY}/releases?per_page=100"))
    if any(not r["draft"] and release_code(r) > code for r in current_releases):
        raise ValueError("上传期间已有更新构建公开，保持当前草稿")
    uploaded = next((r for r in current_releases if r["tag_name"] == tag), None)
    assets = {a["name"]: a for a in uploaded["assets"]} if uploaded else {}
    if not uploaded or not uploaded["draft"] or any(p.name not in assets or assets[p.name]["size"] != p.stat().st_size for p in files):
        raise ValueError("草稿附件不完整，保持草稿不公开")
    # 附件全部到齐才公开；预发布版本不占用稳定版 latest。
    gh("release", "edit", tag, "--repo", REPOSITORY, "--draft=false", "--prerelease", "--latest=false")
    print(f"已发布 https://github.com/{REPOSITORY}/releases/tag/{tag}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["prepare", "verify", "publish"])
    parser.add_argument("--output", type=Path, default=Path("dist"))
    parser.add_argument("--apk", type=Path)
    parser.add_argument("--code", type=int, required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--run-id", required=True)
    args = parser.parse_args()
    if args.command == "prepare":
        if not args.apk:
            parser.error("prepare 需要 --apk")
        prepare(args.output, args.apk, args.code, args.commit, args.run_id)
    elif args.command == "verify":
        verify(args.output, args.code, args.commit, args.run_id)
        print("发布产物校验通过")
    else:
        publish(args.output, args.code, args.commit, args.run_id)


if __name__ == "__main__":
    main()
