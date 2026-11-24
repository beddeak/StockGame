"""사용자 PC에서 열리는 관리자 창. 외부 관리 포트 없이 기존 로컬 서버 콘솔을 이용합니다."""
import json
import os
from pathlib import Path
import sys
import time
import tkinter as tk
from tkinter import messagebox, ttk
from decimal import Decimal, InvalidOperation, ROUND_HALF_UP
import re
import uuid

ROOT = Path(__file__).resolve().parents[1]
SERVER = ROOT / "server"
STATUS = SERVER / "plugins" / "StockGame" / "admin-status.json"

def load_settings():
    return json.loads((ROOT / "admin-panel.json").read_text(encoding="utf-8"))

def display_number(value, decimals=0, signed=False):
    """표시만 반올림하고 서버에서 받은 실제 금액은 그대로 유지합니다."""
    digits = max(0, min(2, int(decimals)))
    rounded = Decimal(str(value)).quantize(Decimal(1).scaleb(-digits), rounding=ROUND_HALF_UP)
    return format(rounded, ("+" if signed else "") + f",.{digits}f")

def interval_value(raw, maximum, words):
    try:
        value = Decimal(raw.strip())
    except (InvalidOperation, ValueError):
        raise ValueError(words["invalid"]) from None
    if not value.is_finite() or value < 0:
        raise ValueError(words["invalid"])
    if value > Decimal(str(maximum)):
        raise ValueError(words["maximum"].format(maximum=maximum))
    # 줄바꿈/명령문을 입력에서 거부하고 서버에는 검증된 숫자만 전달합니다.
    return format(value.normalize(), "f")

def initial_cash_value(raw, maximum, words):
    """원 단위 입력을 소수 두 자리 이내의 안전한 콘솔 숫자로 바꿉니다."""
    value_text = raw.strip()
    if not re.fullmatch(r"(?:[0-9]+(?:\.[0-9]{1,2})?|\.[0-9]{1,2})", value_text):
        raise ValueError(words["cash_invalid"])
    try:
        value = Decimal(value_text)
    except (InvalidOperation, ValueError):
        raise ValueError(words["cash_invalid"]) from None
    if not value.is_finite() or value < 0:
        raise ValueError(words["cash_invalid"])
    if value > Decimal(str(maximum)):
        raise ValueError(words["cash_maximum"].format(maximum=f"{maximum:,.0f}"))
    return format(value, ".2f")

def confirmed_reset(confirm, enqueue_command, words):
    """테스트에서도 실제 Tk 창 없이 초기화 확인/전달 경계를 검증합니다."""
    if not confirm(words["reset_title"], words["reset_confirm"]):
        return False
    enqueue_command("stock reset confirm")
    return True

def read_status():
    data = json.loads(STATUS.read_text(encoding="utf-8"))
    if time.time() * 1000 - data["updatedAt"] > 5000:
        raise ValueError("stale status")
    return data

def enqueue(command):
    read_status()
    inbox = SERVER / "control" / "inbox"
    if not (SERVER / "server.pid").exists() or not inbox.is_dir():
        raise ValueError("server not running")
    # PowerShell 콘솔 도구와 같은 .NET UTC tick 기준으로 명령 순서를 보존합니다.
    target = inbox / f"{time.time_ns() // 100 + 621355968000000000}-{uuid.uuid4().hex}.cmd"
    temporary = target.with_suffix(".tmp")
    temporary.write_text(command + "\n", encoding="utf-8")
    temporary.replace(target)

class AdminPanel:
    def __init__(self, root):
        self.root = root
        self.settings = load_settings()
        self.words = self.settings["text"]
        self.money_decimals = self.settings.get("display", {}).get("money_decimals", 0)
        self.percent_decimals = self.settings.get("display", {}).get("percent_decimals", 0)
        self.pending = None
        self.pending_initial_cash = None
        self.pending_reset = None
        self.initialized = False
        self.cash_initialized = False
        self.maximum = self.settings["defaults"]["maximum_interval_seconds"]
        self.cash_maximum = self.settings["defaults"]["maximum_initial_cash"]
        self.data = {}
        root.title(self.settings["title"])
        root.geometry(f'{self.settings["width"]}x{self.settings["height"]}')
        root.minsize(self.settings["minimum_width"], self.settings["minimum_height"])
        style = ttk.Style(root)
        style.theme_use("clam")
        style.configure(".", font=(self.settings["font"], self.settings["font_size"]))
        style.configure("Treeview", rowheight=34, background=self.settings["table_background"], fieldbackground=self.settings["table_background"])
        style.configure("Title.TLabel", font=(self.settings["font"], 17, "bold"))
        frame = ttk.Frame(root, padding=22)
        frame.pack(fill="both", expand=True)
        heading = ttk.Frame(frame)
        heading.pack(fill="x")
        ttk.Label(heading, text=self.words["heading"], style="Title.TLabel").pack(side="left")
        self.state = tk.StringVar(value=self.words["waiting"])
        ttk.Label(heading, textvariable=self.state).pack(side="right")
        timer = ttk.LabelFrame(frame, text=self.words["interval"], padding=16)
        timer.pack(fill="x", pady=(18, 14))
        row = ttk.Frame(timer)
        row.pack(fill="x")
        self.input = tk.StringVar(value=str(self.settings["defaults"]["interval_seconds"]))
        self.entry = ttk.Entry(row, textvariable=self.input, width=18, font=(self.settings["font"], 17))
        self.entry.pack(side="left")
        self.entry.bind("<Return>", lambda event: self.apply())
        ttk.Label(row, text=self.words["unit"]).pack(side="left", padx=8)
        ttk.Button(row, text=self.words["apply"], command=self.apply).pack(side="left", padx=10)
        self.current = tk.StringVar(value=self.words["waiting"])
        ttk.Label(row, textvariable=self.current).pack(side="right")
        ttk.Label(timer, text=self.words["help"]).pack(anchor="w", pady=(10, 3))
        ttk.Label(timer, text=self.words["zero"]).pack(anchor="w")
        money = ttk.LabelFrame(frame, text=self.words["initial_cash"], padding=16)
        money.pack(fill="x", pady=(0, 14))
        cash_row = ttk.Frame(money)
        cash_row.pack(fill="x")
        self.cash_input = tk.StringVar(value=str(self.settings["defaults"]["initial_cash"]))
        self.cash_entry = ttk.Entry(cash_row, textvariable=self.cash_input, width=18, font=(self.settings["font"], 17))
        self.cash_entry.pack(side="left")
        self.cash_entry.bind("<Return>", lambda event: self.apply_initial_cash())
        ttk.Label(cash_row, text=self.words["cash_unit"]).pack(side="left", padx=8)
        ttk.Button(cash_row, text=self.words["apply"], command=self.apply_initial_cash).pack(side="left", padx=10)
        self.current_cash = tk.StringVar(value=self.words["waiting"])
        ttk.Label(cash_row, textvariable=self.current_cash).pack(side="right")
        ttk.Label(money, text=self.words["cash_help"], wraplength=1000).pack(anchor="w", pady=(10, 3))
        actions = ttk.Frame(frame)
        actions.pack(fill="x", pady=(0, 14))
        for name, command in (("start", "stock start"), ("stop", "stock stop"), ("news", "stock news"), ("reload", "stock reload")):
            ttk.Button(actions, text=self.words[name], command=lambda cmd=command: self.command(cmd)).pack(side="left", padx=(0, 8))
        ttk.Button(actions, text=self.words["reset"], command=self.reset_game).pack(side="left", padx=(0, 8))
        ttk.Button(actions, text=self.words["settings"], command=lambda: os.startfile(STATUS.parent)).pack(side="right")
        self.table = ttk.Treeview(frame, columns=("name", "price", "change", "state"), show="headings", height=7)
        for name, width in (("name", 650), ("price", 110), ("change", 105), ("state", 95)):
            self.table.heading(name, text=self.words[name])
            self.table.column(name, width=width, minwidth=65, anchor="w" if name == "name" else "e")
        for name, color in self.settings["colors"].items():
            self.table.tag_configure(name, foreground=color)
        self.table.pack(fill="both", expand=True)
        self.notice = tk.StringVar(value="")
        ttk.Label(frame, textvariable=self.notice, wraplength=1000).pack(anchor="w", pady=(12, 4))
        ttk.Label(frame, text=self.words["footer"]).pack(anchor="w")
        self.poll()

    def command(self, command):
        try:
            enqueue(command)
            self.notice.set(self.words["pending"])
        except Exception:
            self.notice.set(self.words["not_running"])

    def apply(self):
        try:
            value = interval_value(self.input.get(), self.maximum, self.words)
            enqueue("stock interval " + value)
            self.pending = (float(value), time.monotonic())
            self.notice.set(self.words["pending"])
        except ValueError as error:
            self.notice.set(self.words["error"].format(reason=error))
        except Exception:
            self.notice.set(self.words["not_running"])

    def apply_initial_cash(self):
        try:
            value = initial_cash_value(self.cash_input.get(), self.cash_maximum, self.words)
            enqueue("stock initialcash " + value)
            self.pending_initial_cash = (Decimal(value), time.monotonic())
            self.notice.set(self.words["pending"])
        except ValueError as error:
            self.notice.set(self.words["error"].format(reason=error))
        except Exception:
            self.notice.set(self.words["not_running"])

    def reset_game(self):
        def queue_reset(command):
            # 확인창을 띄운 동안 다른 관리자가 초기화했을 수도 있어 전송 직전에 읽습니다.
            generation = int(read_status()["resetGeneration"])
            enqueue(command)
            self.pending_reset = (generation, time.monotonic())
            self.notice.set(self.words["pending"])

        try:
            if not confirmed_reset(lambda title, message: messagebox.askyesno(title, message, parent=self.root), queue_reset, self.words):
                self.notice.set(self.words["reset_cancelled"])
        except Exception:
            self.notice.set(self.words["not_running"])

    def poll(self):
        try:
            self.data = read_status()
            self.money_decimals = self.data.get("moneyDecimals", self.money_decimals)
            self.percent_decimals = self.data.get("percentDecimals", self.percent_decimals)
            self.maximum = self.data["maxIntervalSeconds"]
            value = self.data["intervalSeconds"]
            if not self.initialized:
                self.input.set(f"{value:g}")
                self.initialized = True
            if "initialCash" in self.data:
                cash = Decimal(str(self.data["initialCash"]))
                self.current_cash.set(self.words["current_initial_cash"].format(amount=display_number(cash, self.money_decimals)))
                if not self.cash_initialized:
                    self.cash_input.set(format(cash.normalize(), "f"))
                    self.cash_initialized = True
            market = self.words["open"] if self.data["running"] else self.words["closed"]
            self.state.set(self.words["market_state"].format(market=market, count=len(self.data["stocks"])))
            self.current.set(self.words["current_interval"].format(seconds=f"{value:g}", effective=f'{self.data["effectiveIntervalSeconds"]:g}'))
            present = set()
            for stock in self.data["stocks"]:
                key = stock["id"]
                present.add(key)
                direction = "rise" if stock["change"] > 0 else "fall" if stock["change"] < 0 else "flat"
                values = (stock["name"], display_number(stock["price"], self.money_decimals), display_number(stock["change"], self.percent_decimals, signed=True) + "%", self.words["delisted"] if stock["delisted"] else self.words["listed"])
                if self.table.exists(key):
                    self.table.item(key, values=values, tags=(direction,))
                else:
                    self.table.insert("", "end", iid=key, values=values, tags=(direction,))
            for key in self.table.get_children():
                if key not in present:
                    self.table.delete(key)
            if self.pending:
                expected, started = self.pending
                if value == expected:
                    self.notice.set(self.words["applied"].format(seconds=f"{value:g}"))
                    self.pending = None
                elif time.monotonic() - started > self.settings["defaults"]["ack_timeout_seconds"]:
                    self.notice.set(self.words["error"].format(reason=self.words["ack_timeout"]))
                    self.pending = None
            if self.pending_initial_cash:
                expected, started = self.pending_initial_cash
                if "initialCash" in self.data and Decimal(str(self.data["initialCash"])) == expected:
                    self.notice.set(self.words["cash_applied"].format(amount=display_number(expected, self.money_decimals)))
                    self.pending_initial_cash = None
                elif time.monotonic() - started > self.settings["defaults"]["ack_timeout_seconds"]:
                    self.notice.set(self.words["error"].format(reason=self.words["ack_timeout"]))
                    self.pending_initial_cash = None
            if self.pending_reset:
                generation, started = self.pending_reset
                if self.data.get("resetGeneration", generation) > generation:
                    self.notice.set(self.words["reset_applied"])
                    self.pending_reset = None
                elif time.monotonic() - started > self.settings["defaults"]["ack_timeout_seconds"]:
                    self.notice.set(self.words["error"].format(reason=self.words["ack_timeout"]))
                    self.pending_reset = None
        except (OSError, ValueError, KeyError):
            self.state.set(self.words["offline"])
        self.root.after(self.settings["refresh_ms"], self.poll)

def main():
    root = tk.Tk()
    panel = AdminPanel(root)
    if "--smoke-test" in sys.argv:
        root.update_idletasks()
        assert root.title() == panel.settings["title"]
        if panel.data:
            assert len(panel.table.get_children()) == len(panel.data["stocks"])
        print("Admin window widgets and live rows OK")
        root.destroy()
        return
    pid_file = SERVER / "admin-panel.pid"
    pid_file.write_text(str(os.getpid()), encoding="ascii")
    try:
        root.lift()
        root.mainloop()
    finally:
        if pid_file.exists() and pid_file.read_text().strip() == str(os.getpid()):
            pid_file.unlink()

if __name__ == "__main__":
    main()
