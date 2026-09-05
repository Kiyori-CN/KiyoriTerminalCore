"""Real Bash/Readline regression harness, invoked by CommandEnvelopePtyTest."""
import base64
import os
from pathlib import Path
import pty
import select
import signal
import sys
import tempfile
import time

source, special, *envelopes = [base64.b64decode(line) for line in sys.stdin.buffer]


def read_until(fd, token, timeout=15):
    result = b""
    deadline = time.monotonic() + timeout
    while token not in result:
        assert time.monotonic() < deadline, ("PTY timeout", token, result[-2000:])
        if select.select([fd], [], [], 0.1)[0]:
            result += os.read(fd, 65536)
    return result


with tempfile.TemporaryDirectory(prefix="kiyori-envelope-") as directory:
    root = Path(directory)
    # With dotfiles, the old raw TAB completes to '.'; without them it disappears.
    (root / ".bashrc").touch()
    (root / ".profile").touch()
    pid, fd = pty.fork()
    if pid == 0:
        os.chdir(root)
        os.environ.update(HOME=directory, KIYORI_PROBE_ROOT=directory, PS1="PROBE> ",
                          PS2="MORE> ", TERM="dumb", INPUTRC="/dev/null", LC_ALL="C.UTF-8")
        os.execv("/bin/bash", ["bash", "--noprofile", "--norc", "-i"])
    try:
        read_until(fd, b"PROBE> ")
        for index, envelope in enumerate(envelopes):
            assert envelope.count(b"\n") == 1 and envelope.endswith(b"\n")
            assert all(32 <= byte < 127 for byte in envelope[:-1])
            # Match TerminalManager's separate ENTER after the protocol newline.
            data = envelope + b"\r"
            # Drain echo while writing long input, avoiding PTY buffer deadlock.
            os.set_blocking(fd, False)
            offset = 0
            while offset < len(data):
                readable, writable, _ = select.select([fd], [fd], [], 5)
                if readable:
                    os.read(fd, 65536)
                if writable:
                    offset += os.write(fd, data[offset:offset + 1024])
            os.set_blocking(fd, True)
            if index == 12:
                # Actual Ctrl+C through the PTY, as used by the timeout/cancel owner.
                time.sleep(0.2)
                os.write(fd, b"\x03")
                read_until(fd, b"PROBE> ")
                continue
            marker = f"\x1b]1337;__KIYORI_COMMAND_EXIT__:00000000-0000-0000-0000-{index:012d}:".encode()
            output = read_until(fd, marker)
            if b"\x07" not in output[output.index(marker):]:
                output += read_until(fd, b"\x07")
            status = int(output.split(marker)[1].split(b"\x07")[0])
            assert status == (1 if index in (10, 15) else 0), (index, status, output[-2000:])
            if index == 1:
                assert "中文 hi".encode() in output, output
                assert b"space hi" in output, output
            if index == 7:
                assert b"done" in output, output
        assert (root / "main.go").read_bytes() == source
        assert (root / "Makefile").read_bytes() == b"all:\n\t@echo hi\n"
        assert (root / "special.bin").read_bytes() == special
        assert (root / "kept/export.txt").read_text() == "中文 value"
        assert (root / "kept/background.txt").read_text() == "done"
        assert (root / "recovered.txt").read_text() == "recovered"
        assert (root / "large.txt").read_text() == "x\t中文'\\!" * 1800
        assert (root / "after-interrupt.txt").read_text() == "alive"
        assert not (root / "unexpected.txt").exists()
        print("PTY regression PASS: Go source bytes, Python TAB, Makefile, controls/Unicode/quotes, "
              "cwd/export, jobs, deleted cwd/HOME failure, status, long input, Ctrl+C/session reuse")
        print("GO_SOURCE_BASE64=" + base64.b64encode((root / "main.go").read_bytes()).decode())
        print("NODE_SOURCE_BASE64=" + base64.b64encode((root / "node.js").read_bytes()).decode())
    finally:
        os.kill(pid, signal.SIGKILL)
        os.waitpid(pid, 0)
        os.close(fd)
