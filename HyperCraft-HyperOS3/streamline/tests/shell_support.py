"""为 macOS 主机提供 Android flock 的同等内核锁语义，不 mock 锁结果。"""
from pathlib import Path
import sys


def install_flock(bin_dir: Path):
    helper = bin_dir / "flock_impl.py"
    helper.write_text('''import fcntl
import sys
opts = "".join(arg[1:] for arg in sys.argv[1:] if arg.startswith("-"))
mode = fcntl.LOCK_UN if "u" in opts else fcntl.LOCK_EX
if "n" in opts:
    mode |= fcntl.LOCK_NB
try:
    fcntl.flock(int(sys.argv[-1]), mode)
except BlockingIOError:
    sys.exit(1)
''')
    command = bin_dir / "flock"
    command.write_text(f'#!/bin/sh\nexec "{sys.executable}" "{helper}" "$@"\n')
    command.chmod(0o700)


def install_stat(bin_dir: Path):
    """直接读取真实临时文件属性，提供 Android stat -c 格式而非 BSD stat 参数。"""
    helper = bin_dir / "stat_impl.py"
    helper.write_text('''import os
import stat
import sys
if len(sys.argv) != 4 or sys.argv[1] != "-c":
    sys.exit(1)
try:
    value = os.stat(sys.argv[3])
except OSError:
    sys.exit(1)
formats = {"%s": str(value.st_size), "%a": oct(stat.S_IMODE(value.st_mode))[2:],
           "%d:%i": f"{value.st_dev}:{value.st_ino}"}
if sys.argv[2] not in formats:
    sys.exit(1)
print(formats[sys.argv[2]])
''')
    command = bin_dir / "stat"
    command.write_text(f'#!/bin/sh\nexec "{sys.executable}" "{helper}" "$@"\n')
    command.chmod(0o700)


def wait_file(file: Path, process=None, timeout=5):
    import time
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if file.exists():
            return
        if process is not None and process.poll() is not None:
            raise AssertionError(f"进程提前退出: {process.communicate()}")
        time.sleep(0.01)
    raise AssertionError(f"等待测试同步点超时: {file}")
