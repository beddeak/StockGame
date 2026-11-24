"""실제 Java/서버 없이 콘솔과 관리자 파일 큐의 전달·종료를 검증합니다."""
import io
from pathlib import Path
import queue
import tempfile
import unittest
from unittest.mock import Mock, patch

from server_supervisor import copy_console_output, forward_requests, read_console, supervise


class FakeProcess:
    def __init__(self, returncode=None):
        self.stdin = io.BytesIO()
        self.returncode = returncode
        self.wait_calls = 0

    def poll(self):
        return self.returncode

    def wait(self, timeout):
        self.wait_calls += 1
        self.returncode = 0
        return self.returncode


class ServerSupervisorTest(unittest.TestCase):
    def test_console_and_admin_queue_both_forward_without_losing_commands(self):
        process = FakeProcess()
        commands = queue.Queue()
        commands.put("stock status\n")
        commands.put("\n")
        with tempfile.TemporaryDirectory() as directory:
            inbox = Path(directory)
            (inbox / "002.cmd").write_text("stock stop", encoding="utf-8")
            (inbox / "001.cmd").write_text("say 관리자 입력", encoding="utf-8-sig")
            forward_requests(process, inbox, commands)
            self.assertEqual([], list(inbox.glob("*.cmd")))
        self.assertEqual("stock status\nsay 관리자 입력\nstock stop\n".encode("utf-8"), process.stdin.getvalue())

    def test_console_reader_finishes_on_eof_and_preserves_each_line(self):
        commands = queue.Queue()
        read_console(io.StringIO("stock status\nstock interval 600\n"), commands)
        self.assertEqual("stock status\n", commands.get_nowait())
        self.assertEqual("stock interval 600\n", commands.get_nowait())
        self.assertTrue(commands.empty())

    def test_ctrl_c_sends_normal_stop_and_waits_for_exit(self):
        process = FakeProcess()
        with tempfile.TemporaryDirectory() as directory, patch("server_supervisor.time.sleep", side_effect=KeyboardInterrupt):
            self.assertEqual(0, supervise(process, Path(directory)))
        self.assertEqual(b"stop\n", process.stdin.getvalue())
        self.assertEqual(1, process.wait_calls)

    def test_already_exited_process_keeps_exit_code_without_extra_command(self):
        process = FakeProcess(returncode=7)
        with tempfile.TemporaryDirectory() as directory:
            self.assertEqual(7, supervise(process, Path(directory)))
        self.assertEqual(b"", process.stdin.getvalue())
        self.assertEqual(0, process.wait_calls)

    def test_java_output_reaches_console_and_log_as_utf8(self):
        output = "거래소 준비 완료\nDone\n".encode("utf-8")
        log = io.BytesIO()
        console = io.StringIO()
        copy_console_output(io.BytesIO(output), log, console)
        self.assertEqual(output, log.getvalue())
        self.assertEqual(output.decode("utf-8"), console.getvalue())

    def test_closed_console_still_drains_java_output_to_log(self):
        output = b"first line\nsecond line\n"
        log = io.BytesIO()
        console = Mock()
        console.write.side_effect = OSError("console closed")
        copy_console_output(io.BytesIO(output), log, console)
        self.assertEqual(output, log.getvalue())
        console.write.assert_called_once()


if __name__ == "__main__":
    unittest.main()
