#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
KECE OJ 测试用例批量同步与解包部署脚本
Batch Test Cases Packaging, Synchronization & Unpacking Tool for KECE OJ
-------------------------------------------------------------------------
功能说明：
1. 扫描本地测试用例目录（默认为 ./testcases 或指定路径）；
2. 校验题目目录格式及 .in / .out 评测点成对完整性；
3. 将所有测试用例按标准化相对路径打包为 tar.gz 压缩包；
4. 通过 SFTP/SSH 安全上传至远端评测服务器；
5. 在远端执行流式解压、权限重设（755/644）、归属调整（zco:zco）；
6. 校验远端目录与文件完整性；
7. 可选执行 Redis 缓存原子清空（题目列表、题集、标签缓存）。
"""

import os
import sys
import argparse
import tarfile
import tempfile
import time
import hashlib
from typing import List, Tuple, Dict

if hasattr(sys.stdout, 'reconfigure'):
    try:
        sys.stdout.reconfigure(encoding='utf-8', errors='replace')
    except Exception:
        pass

try:
    import paramiko
except ImportError:
    print("[ERROR] 未安装 paramiko 库，请使用 pip install paramiko 安装。")
    sys.exit(1)

# 默认远端配置
DEFAULT_REMOTE_HOST = os.environ.get("KECE_REMOTE_HOST", "10.56.40.238")
DEFAULT_REMOTE_PORT = int(os.environ.get("KECE_REMOTE_PORT", "22"))
DEFAULT_REMOTE_USER = os.environ.get("KECE_REMOTE_USER", "zco")
DEFAULT_REMOTE_PASS = os.environ.get("KECE_REMOTE_PASS", "zco070612")
DEFAULT_REMOTE_TESTCASE_DIR = "/home/zco/kece/testcases"

def md5_file(filepath: str) -> str:
    hasher = hashlib.md5()
    with open(filepath, 'rb') as f:
        while chunk := f.read(65536):
            hasher.update(chunk)
    return hasher.hexdigest()

def scan_and_validate_local_testcases(src_dir: str) -> Tuple[List[str], Dict[str, List[str]]]:
    """
    扫描本地目录，校验用例格式与完整性
    返回: (所有文件相对路径列表, 每个题目的文件统计映射)
    """
    if not os.path.isdir(src_dir):
        raise FileNotFoundError(f"本地用例目录不存在: {src_dir}")

    rel_files = []
    question_stats = {}
    unpaired_warnings = []

    # 遍历每个题目子目录
    for item in sorted(os.listdir(src_dir)):
        item_path = os.path.join(src_dir, item)
        if not os.path.isdir(item_path):
            continue

        qid = item
        # 记录题目目录下的测试用例
        files = os.listdir(item_path)
        in_files = {f for f in files if f.endswith(".in")}
        out_files = {f for f in files if f.endswith(".out")}

        # 检查配对
        in_bases = {f[:-3] for f in in_files}
        out_bases = {f[:-4] for f in out_files}

        common_bases = in_bases & out_bases
        missing_out = in_bases - out_bases
        missing_in = out_bases - in_bases

        if missing_out:
            unpaired_warnings.append(f"题目 {qid} 缺失 .out 文件: {sorted(missing_out)}")
        if missing_in:
            unpaired_warnings.append(f"题目 {qid} 缺失 .in 文件: {sorted(missing_in)}")

        matched_files = []
        for base in sorted(common_bases, key=lambda x: int(x) if x.isdigit() else x):
            rel_in = f"{qid}/{base}.in"
            rel_out = f"{qid}/{base}.out"
            rel_files.append(rel_in)
            rel_files.append(rel_out)
            matched_files.extend([f"{base}.in", f"{base}.out"])

        if matched_files:
            question_stats[qid] = matched_files

    if unpaired_warnings:
        print("[WARN] 发现未严格配对的用例文件：")
        for w in unpaired_warnings:
            print(f"  - {w}")

    return rel_files, question_stats

def create_tarball(src_dir: str, rel_files: List[str], output_tar: str):
    """
    将待同步文件打包为 tar.gz，强制使用正斜杠 Linux 路径
    """
    print(f"[*] 正在打包 {len(rel_files)} 个用例文件至 {output_tar}...")
    with tarfile.open(output_tar, "w:gz") as tar:
        for rel_path in rel_files:
            abs_path = os.path.join(src_dir, rel_path)
            # 规范化为 POSIX 相对路径
            arcname = rel_path.replace("\\", "/")
            tar.add(abs_path, arcname=arcname)
    print(f"[OK] 打包完成！压缩包大小: {os.path.getsize(output_tar) / 1024:.2f} KB")

def flush_redis_caches(ssh: paramiko.SSHClient) -> int:
    """
    在远端执行 Redis 题目列表、题集、标签缓存原子清理
    """
    lua_script = (
        "local count = 0; "
        "for _, pattern in ipairs({'question:cache:*', 'pset:*', 'db:question:cache:*'}) do "
        "  local keys = redis.call('keys', pattern); "
        "  for _, k in ipairs(keys) do "
        "    redis.call('del', k); "
        "    count = count + 1; "
        "  end "
        "end; "
        "return count;"
    )
    cmd = f'docker exec kece-redis redis-cli eval "{lua_script}" 0'
    stdin, stdout, stderr = ssh.exec_command(cmd)
    out = stdout.read().decode('utf-8', errors='ignore').strip()
    err = stderr.read().decode('utf-8', errors='ignore').strip()
    try:
        evicted = int(out)
        print(f"[OK] Redis 缓存清理成功！已清理失效缓存键共 {evicted} 个。")
        return evicted
    except ValueError:
        print(f"[WARN] Redis 缓存清理返回非数字结果: stdout='{out}', stderr='{err}'")
        return -1

def sync_testcases(
    src_dir: str,
    remote_host: str = DEFAULT_REMOTE_HOST,
    remote_port: int = DEFAULT_REMOTE_PORT,
    remote_user: str = DEFAULT_REMOTE_USER,
    remote_pass: str = DEFAULT_REMOTE_PASS,
    remote_target_dir: str = DEFAULT_REMOTE_TESTCASE_DIR,
    dry_run: bool = False,
    flush_redis: bool = False
):
    print("=" * 80)
    print(" KECE OJ 测试用例批量同步与解包任务启动")
    print("=" * 80)
    print(f" 本地源目录: {os.path.abspath(src_dir)}")
    print(f" 目标服务器: {remote_user}@{remote_host}:{remote_port}")
    print(f" 远端目标目录: {remote_target_dir}")
    print(f" Dry-Run 模式: {'开启 (仅校验与打包，不上载)' if dry_run else '关闭 (正式同步部署)'}")
    print(f" Redis 刷新: {'是 (解包后立即清空题目与题集缓存)' if flush_redis else '否'}")
    print("-" * 80)

    # 1. 本地扫描与完整性校验
    rel_files, q_stats = scan_and_validate_local_testcases(src_dir)
    total_q = len(q_stats)
    total_files = len(rel_files)
    total_cases = total_files // 2

    print(f"[*] 扫描统计: 共发现 {total_q} 道题目, {total_cases} 组完整输入/输出测试点 ({total_files} 个文件)")

    if total_files == 0:
        print("[!] 没有扫描到有效用例文件，任务结束。")
        return

    # 2. 本地临时打包
    timestamp = time.strftime("%Y%m%d_%H%M%S")
    temp_dir = tempfile.gettempdir()
    tar_filename = f"testcases_sync_{timestamp}.tar.gz"
    local_tar_path = os.path.join(temp_dir, tar_filename)

    try:
        create_tarball(src_dir, rel_files, local_tar_path)
        tar_md5 = md5_file(local_tar_path)
        tar_size = os.path.getsize(local_tar_path)
        print(f"[*] 压缩包 MD5: {tar_md5}")

        if dry_run:
            print("\n[OK] Dry-Run 模式校验成功！压缩包生成于: " + local_tar_path)
            return

        # 3. 连接远端 SSH / SFTP
        print(f"\n[*] 正在建立 SSH 连接至 {remote_host}:{remote_port} ...")
        ssh = paramiko.SSHClient()
        ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
        ssh.connect(remote_host, port=remote_port, username=remote_user, password=remote_pass, timeout=15)
        print("[OK] SSH 连接成功！")

        remote_tar_path = f"/home/{remote_user}/kece/{tar_filename}"
        print(f"[*] 正在通过 SFTP 上传至远端 {remote_tar_path} ...")
        sftp = ssh.open_sftp()

        def sftp_progress(transferred, total):
            percent = (transferred / total) * 100
            print(f"\r    上传进度: {transferred}/{total} 字节 ({percent:.1f}%)", end="", flush=True)

        sftp.put(local_tar_path, remote_tar_path, callback=sftp_progress)
        sftp.close()
        print("\n[OK] SFTP 文件上传完成！")

        # 4. 远端 MD5 校验
        print("[*] 正在校验远端文件完整性...")
        stdin, stdout, stderr = ssh.exec_command(f"md5sum {remote_tar_path}")
        remote_md5 = stdout.read().decode('utf-8').split()[0]
        if remote_md5 != tar_md5:
            raise ValueError(f"MD5 校验失败！本地: {tar_md5}, 远端: {remote_md5}")
        print(f"[OK] 远端 MD5 校验通过: {remote_md5}")

        # 5. 执行远端解包与权限修正
        print(f"[*] 正在解包到远端目录: {remote_target_dir} ...")
        remote_script = f"""
set -e
mkdir -p {remote_target_dir}
tar -xzf {remote_tar_path} -C {remote_target_dir}/
chmod -R 755 {remote_target_dir}
find {remote_target_dir} -type f -name "*.in" -o -name "*.out" | xargs -r chmod 644
chown -R {remote_user}:{remote_user} {remote_target_dir}
rm -f {remote_tar_path}
"""
        stdin, stdout, stderr = ssh.exec_command(remote_script)
        exit_status = stdout.channel.recv_exit_status()
        if exit_status != 0:
            err_msg = stderr.read().decode('utf-8', errors='ignore')
            raise RuntimeError(f"远端解包脚本执行失败 (exit code {exit_status}): {err_msg}")
        print("[OK] 远端解压与权限初始化完成！")

        # 6. 校验远端目录文件数量
        print("[*] 正在核验远端用例目录...")
        chk_cmd = f"find {remote_target_dir} -type f \\( -name '*.in' -o -name '*.out' \\) | wc -l"
        stdin, stdout, stderr = ssh.exec_command(chk_cmd)
        remote_file_count = int(stdout.read().decode().strip())

        chk_dir_cmd = f"find {remote_target_dir} -mindepth 1 -maxdepth 1 -type d | wc -l"
        stdin, stdout, stderr = ssh.exec_command(chk_dir_cmd)
        remote_dir_count = int(stdout.read().decode().strip())

        print(f"    远端当前总题目目录数: {remote_dir_count}")
        print(f"    远端当前总用例文件数: {remote_file_count}")

        # 7. 可选刷新 Redis 缓存
        if flush_redis:
            print("\n[*] 正在触发 Redis 缓存清空...")
            flush_redis_caches(ssh)

        ssh.close()
        print("\n" + "=" * 80)
        print(" [SUCCESS] 测试用例同步与解包部署全部顺利完成！")
        print("=" * 80)

    finally:
        # 清理本地临时压缩包
        if os.path.exists(local_tar_path):
            try:
                os.remove(local_tar_path)
            except Exception:
                pass

def main():
    parser = argparse.ArgumentParser(description="KECE OJ 评测用例批量打包、上传与解包同步工具")
    parser.add_argument("--src-dir", default="./testcases", help="本地用例存放目录 (默认: ./testcases)")
    parser.add_argument("--remote-host", default=DEFAULT_REMOTE_HOST, help=f"远端主机 IP (默认: {DEFAULT_REMOTE_HOST})")
    parser.add_argument("--remote-port", type=int, default=DEFAULT_REMOTE_PORT, help=f"远端 SSH 端口 (默认: {DEFAULT_REMOTE_PORT})")
    parser.add_argument("--remote-user", default=DEFAULT_REMOTE_USER, help=f"远端 SSH 用户名 (默认: {DEFAULT_REMOTE_USER})")
    parser.add_argument("--remote-pass", default=DEFAULT_REMOTE_PASS, help="远端 SSH 密码")
    parser.add_argument("--remote-target-dir", default=DEFAULT_REMOTE_TESTCASE_DIR, help=f"远端评测点目标目录 (默认: {DEFAULT_REMOTE_TESTCASE_DIR})")
    parser.add_argument("--dry-run", action="store_true", help="仅在本地执行完整性检测与打包，不执行网络上传")
    parser.add_argument("--flush-redis", action="store_true", help="同步完成后自动执行 Redis 题目与题集缓存刷新")

    args = parser.parse_args()
    sync_testcases(
        src_dir=args.src_dir,
        remote_host=args.remote_host,
        remote_port=args.remote_port,
        remote_user=args.remote_user,
        remote_pass=args.remote_pass,
        remote_target_dir=args.remote_target_dir,
        dry_run=args.dry_run,
        flush_redis=args.flush_redis
    )

if __name__ == "__main__":
    main()
