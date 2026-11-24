"""일반/백그라운드 실행 모두 로컬 파일 큐로 관리자 명령을 받습니다."""
import argparse
from pathlib import Path
import queue
import subprocess
import sys
import threading
import time

def send_command(process, command):
    command = command.strip()
    if not command:
        return
    process.stdin.write((command + "\n").encode("utf-8"))
    process.stdin.flush()

def read_console(stream, commands):
    # readline은 입력을 기다릴 수 있으므로 파일 큐를 처리하는 메인 루프와 분리합니다.
    try:
        for line in stream:
            commands.put(line)
    except (OSError, ValueError):
        pass

def copy_console_output(stream, log, console):
    try:
        for line in iter(stream.readline, b""):
            log.write(line)
            if console is not None:
                try:
                    console.write(line.decode("utf-8", errors="replace"))
                    console.flush()
                except (OSError, ValueError):
                    # 콘솔이 닫히거나 특정 글자를 표시하지 못해도 Java 출력은 계속 비웁니다.
                    console = None
    except (OSError, ValueError):
        pass

def forward_requests(process, inbox, console_commands=None):
    # 표준 입력과 파일 입력을 한 곳에서 전달해 Java stdin 쓰기가 섞이지 않게 합니다.
    if console_commands is not None:
        while True:
            try:
                command = console_commands.get_nowait()
            except queue.Empty:
                break
            send_command(process, command)
    for path in sorted(inbox.glob("*.cmd")):
        command = path.read_text(encoding="utf-8-sig")
        send_command(process, command)
        path.unlink()

def stop_server(process):
    if process.poll() is not None:
        return
    try:
        send_command(process, "stop")
    except (BrokenPipeError, OSError):
        pass
    # 종료 중 Ctrl+C를 다시 눌러도 Java를 강제 종료하지 않습니다.
    while process.poll() is None:
        try:
            process.wait(timeout=1)
        except (subprocess.TimeoutExpired, KeyboardInterrupt):
            continue

def supervise(process, inbox, console_commands=None):
    try:
        while process.poll() is None:
            forward_requests(process, inbox, console_commands)
            time.sleep(.2)
    except KeyboardInterrupt:
        # Java는 별도 process group에 두어 Python만 Ctrl+C를 받고 정상 stop을 보냅니다.
        pass
    finally:
        stop_server(process)
    return process.returncode

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--java", required=True)
    parser.add_argument("--memory", default="2G")
    parser.add_argument("--console", action="store_true", help="일반 콘솔 출력과 직접 명령 입력을 연결합니다.")
    args = parser.parse_args()
    server = Path(__file__).resolve().parents[1] / "server"
    inbox = server / "control" / "inbox"
    inbox.mkdir(parents=True, exist_ok=True)
    for stale in inbox.glob("*.cmd"):
        stale.unlink()
    pid_file = server / "server.pid"
    with (server / "console.log").open("ab", buffering=0) as log:
        creationflags = getattr(subprocess, "CREATE_NEW_PROCESS_GROUP", 0) if args.console else getattr(subprocess, "CREATE_NO_WINDOW", 0)
        process = subprocess.Popen([args.java, "-Xms512M", f"-Xmx{args.memory}", "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-jar", "paper.jar", "--nogui"], cwd=server, stdin=subprocess.PIPE, stdout=subprocess.PIPE if args.console else log, stderr=subprocess.STDOUT, creationflags=creationflags)
        output_thread = None
        try:
            pid_file.write_text(str(process.pid), encoding="ascii")
            commands = queue.Queue() if args.console else None
            if args.console:
                threading.Thread(target=read_console, args=(sys.stdin, commands), daemon=True).start()
                output_thread = threading.Thread(target=copy_console_output, args=(process.stdout, log, sys.stdout), daemon=True)
                output_thread.start()
            return supervise(process, inbox, commands)
        finally:
            # PID 파일/스레드 준비 실패 때도 시작한 서버를 그대로 남기지 않습니다.
            stop_server(process)
            if output_thread is not None:
                output_thread.join(timeout=5)
            process.stdin.close()
            if process.stdout is not None:
                process.stdout.close()
            if pid_file.exists() and pid_file.read_text().strip() == str(process.pid):
                pid_file.unlink()

if __name__ == "__main__":
    raise SystemExit(main())
