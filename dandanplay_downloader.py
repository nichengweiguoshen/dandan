import base64
import hashlib
import json
import os
import queue
import re
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path
import tkinter as tk
from tkinter import filedialog, messagebox, ttk


APP_NAME = "弹弹play字幕下载"
API_BASE = "https://api.dandanplay.net"
CONFIG_FILE = "config.json"
DEFAULT_INTERVAL_SECONDS = 1.5


class ApiError(Exception):
    pass


def safe_filename(value):
    value = (value or "").strip()
    value = re.sub(r'[<>:"/\\|?*\x00-\x1f]', "_", value)
    value = re.sub(r"\s+", " ", value)
    return value[:120] or "untitled"


def atomic_write_text(path, text):
    path.parent.mkdir(parents=True, exist_ok=True)
    temp_path = path.with_suffix(path.suffix + ".tmp")
    temp_path.write_text(text, encoding="utf-8")
    temp_path.replace(path)


def atomic_write_json(path, data):
    atomic_write_text(path, json.dumps(data, ensure_ascii=False, indent=2))


def make_signature(app_id, timestamp, path, app_secret):
    raw = f"{app_id}{timestamp}{path.lower()}{app_secret}".encode("utf-8")
    return base64.b64encode(hashlib.sha256(raw).digest()).decode("ascii")


class DandanplayClient:
    def __init__(self, app_id, app_secret, auth_mode="signature", timeout=25):
        self.app_id = app_id.strip()
        self.app_secret = app_secret.strip()
        self.auth_mode = auth_mode
        self.timeout = timeout

    def _headers(self, path):
        headers = {
            "Accept": "application/json",
            "User-Agent": "DanDanPlaySubtitleDownloader/0.1",
        }
        if not self.app_id or not self.app_secret:
            raise ApiError("请先填写 AppId 和 AppSecret。")

        if self.auth_mode == "credential":
            headers["X-AppId"] = self.app_id
            headers["X-AppSecret"] = self.app_secret
            return headers

        timestamp = str(int(time.time()))
        headers["X-AppId"] = self.app_id
        headers["X-Timestamp"] = timestamp
        headers["X-Signature"] = make_signature(self.app_id, timestamp, path, self.app_secret)
        return headers

    def get_json(self, path, params=None):
        params = params or {}
        query = urllib.parse.urlencode(
            {k: v for k, v in params.items() if v is not None},
            doseq=True,
        )
        url = API_BASE + path + (("?" + query) if query else "")
        request = urllib.request.Request(url, headers=self._headers(path), method="GET")
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                charset = response.headers.get_content_charset() or "utf-8"
                body = response.read().decode(charset, errors="replace")
        except urllib.error.HTTPError as exc:
            error_message = exc.headers.get("X-Error-Message") or exc.reason
            raise ApiError(f"HTTP {exc.code}: {error_message}") from exc
        except urllib.error.URLError as exc:
            raise ApiError(f"网络请求失败: {exc.reason}") from exc

        try:
            data = json.loads(body)
        except json.JSONDecodeError as exc:
            raise ApiError("接口返回内容不是有效 JSON。") from exc

        if isinstance(data, dict) and data.get("success") is False:
            raise ApiError(data.get("errorMessage") or "接口返回失败。")
        return data

    def search_anime(self, keyword):
        return self.get_json("/api/v2/search/anime", {"keyword": keyword}).get("animes") or []

    def bangumi_details(self, bangumi_id):
        return self.get_json(f"/api/v2/bangumi/{urllib.parse.quote(str(bangumi_id), safe='')}").get("bangumi")

    def comments(self, episode_id, with_related=True, ch_convert=1):
        return self.get_json(
            f"/api/v2/comment/{episode_id}",
            {
                "withRelated": "true" if with_related else "false",
                "chConvert": ch_convert,
            },
        )


def comments_to_bilibili_xml(comments):
    root = ET.Element("i")
    ET.SubElement(root, "chatserver").text = "api.dandanplay.net"
    ET.SubElement(root, "chatid").text = "0"
    ET.SubElement(root, "mission").text = "0"
    ET.SubElement(root, "maxlimit").text = str(len(comments))
    ET.SubElement(root, "state").text = "0"
    ET.SubElement(root, "real_name").text = "0"
    ET.SubElement(root, "source").text = "k-v"

    for item in comments:
        p = str(item.get("p") or "0,1,16777215,0").split(",")
        appear_time = p[0] if len(p) > 0 and p[0] else "0"
        mode = p[1] if len(p) > 1 and p[1] else "1"
        color = p[2] if len(p) > 2 and p[2] else "16777215"
        user_id = p[3] if len(p) > 3 and p[3] else "0"
        cid = str(item.get("cid") or 0)

        # Bilibili XML p: time,mode,fontSize,color,timestamp,pool,user,rowId
        d = ET.SubElement(root, "d")
        d.set("p", f"{appear_time},{mode},25,{color},0,0,{user_id},{cid}")
        d.text = item.get("m") or ""

    ET.indent(root, space="  ")
    return '<?xml version="1.0" encoding="UTF-8"?>\n' + ET.tostring(root, encoding="unicode")


class DownloaderApp(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title(APP_NAME)
        self.geometry("1080x720")
        self.minsize(980, 640)

        self.config_data = self.load_config()
        self.anime_results = []
        self.current_bangumi = None
        self.episode_vars = []
        self.log_queue = queue.Queue()
        self.worker = None

        self.create_widgets()
        self.after(100, self.drain_log_queue)

    def load_config(self):
        path = Path(CONFIG_FILE)
        if not path.exists():
            return {
                "app_id": "",
                "app_secret": "",
                "auth_mode": "signature",
                "download_dir": str(Path.cwd() / "downloads"),
                "interval_seconds": DEFAULT_INTERVAL_SECONDS,
                "with_related": True,
                "ch_convert": 1,
            }
        try:
            with path.open("r", encoding="utf-8") as f:
                data = json.load(f)
            data.setdefault("download_dir", str(Path.cwd() / "downloads"))
            data.setdefault("interval_seconds", DEFAULT_INTERVAL_SECONDS)
            data.setdefault("auth_mode", "signature")
            data.setdefault("with_related", True)
            data.setdefault("ch_convert", 1)
            return data
        except Exception:
            return {
                "app_id": "",
                "app_secret": "",
                "auth_mode": "signature",
                "download_dir": str(Path.cwd() / "downloads"),
                "interval_seconds": DEFAULT_INTERVAL_SECONDS,
                "with_related": True,
                "ch_convert": 1,
            }

    def save_config(self):
        self.config_data = {
            "app_id": self.app_id_var.get().strip(),
            "app_secret": self.app_secret_var.get().strip(),
            "auth_mode": self.auth_mode_var.get(),
            "download_dir": self.download_dir_var.get().strip(),
            "interval_seconds": self.interval_var.get(),
            "with_related": self.with_related_var.get(),
            "ch_convert": int(self.ch_convert_var.get()),
        }
        atomic_write_json(Path(CONFIG_FILE), self.config_data)
        self.log("配置已保存。")

    def create_widgets(self):
        self.columnconfigure(0, weight=1)
        self.rowconfigure(2, weight=1)

        settings = ttk.LabelFrame(self, text="开放平台认证")
        settings.grid(row=0, column=0, sticky="ew", padx=10, pady=(10, 6))
        settings.columnconfigure(1, weight=1)
        settings.columnconfigure(3, weight=1)

        self.app_id_var = tk.StringVar(value=self.config_data.get("app_id", ""))
        self.app_secret_var = tk.StringVar(value=self.config_data.get("app_secret", ""))
        self.auth_mode_var = tk.StringVar(value=self.config_data.get("auth_mode", "signature"))

        ttk.Label(settings, text="AppId").grid(row=0, column=0, padx=8, pady=8, sticky="w")
        ttk.Entry(settings, textvariable=self.app_id_var).grid(row=0, column=1, padx=8, pady=8, sticky="ew")
        ttk.Label(settings, text="AppSecret").grid(row=0, column=2, padx=8, pady=8, sticky="w")
        ttk.Entry(settings, textvariable=self.app_secret_var, show="*").grid(row=0, column=3, padx=8, pady=8, sticky="ew")
        ttk.Radiobutton(settings, text="签名模式", variable=self.auth_mode_var, value="signature").grid(row=0, column=4, padx=8)
        ttk.Radiobutton(settings, text="凭证模式", variable=self.auth_mode_var, value="credential").grid(row=0, column=5, padx=8)
        ttk.Button(settings, text="保存配置", command=self.save_config).grid(row=0, column=6, padx=8, pady=8)

        search_frame = ttk.Frame(self)
        search_frame.grid(row=1, column=0, sticky="ew", padx=10, pady=6)
        search_frame.columnconfigure(1, weight=1)
        self.keyword_var = tk.StringVar()
        ttk.Label(search_frame, text="番剧关键词").grid(row=0, column=0, padx=(0, 8), sticky="w")
        keyword_entry = ttk.Entry(search_frame, textvariable=self.keyword_var)
        keyword_entry.grid(row=0, column=1, sticky="ew")
        keyword_entry.bind("<Return>", lambda _event: self.search())
        ttk.Button(search_frame, text="搜索", command=self.search).grid(row=0, column=2, padx=(8, 0))

        main = ttk.PanedWindow(self, orient=tk.HORIZONTAL)
        main.grid(row=2, column=0, sticky="nsew", padx=10, pady=6)

        left = ttk.Frame(main)
        left.columnconfigure(0, weight=1)
        left.rowconfigure(0, weight=1)
        self.result_list = tk.Listbox(left, exportselection=False)
        self.result_list.grid(row=0, column=0, sticky="nsew")
        self.result_list.bind("<<ListboxSelect>>", lambda _event: self.load_selected_bangumi())
        left_scroll = ttk.Scrollbar(left, orient="vertical", command=self.result_list.yview)
        left_scroll.grid(row=0, column=1, sticky="ns")
        self.result_list.configure(yscrollcommand=left_scroll.set)
        main.add(left, weight=1)

        right = ttk.Frame(main)
        right.columnconfigure(0, weight=1)
        right.rowconfigure(2, weight=1)
        self.detail_var = tk.StringVar(value="搜索并选择一个番剧。")
        ttk.Label(right, textvariable=self.detail_var, wraplength=650, justify="left").grid(row=0, column=0, sticky="ew", pady=(0, 6))

        select_buttons = ttk.Frame(right)
        select_buttons.grid(row=1, column=0, sticky="ew", pady=(0, 6))
        ttk.Button(select_buttons, text="全选", command=lambda: self.set_all_episodes(True)).pack(side=tk.LEFT)
        ttk.Button(select_buttons, text="全不选", command=lambda: self.set_all_episodes(False)).pack(side=tk.LEFT, padx=8)

        episode_holder = ttk.Frame(right)
        episode_holder.grid(row=2, column=0, sticky="nsew")
        episode_holder.columnconfigure(0, weight=1)
        episode_holder.rowconfigure(0, weight=1)
        self.episode_canvas = tk.Canvas(episode_holder, highlightthickness=0)
        self.episode_frame = ttk.Frame(self.episode_canvas)
        self.episode_scroll = ttk.Scrollbar(episode_holder, orient="vertical", command=self.episode_canvas.yview)
        self.episode_canvas.configure(yscrollcommand=self.episode_scroll.set)
        self.episode_canvas.grid(row=0, column=0, sticky="nsew")
        self.episode_scroll.grid(row=0, column=1, sticky="ns")
        self.episode_window = self.episode_canvas.create_window((0, 0), window=self.episode_frame, anchor="nw")
        self.episode_frame.bind("<Configure>", self.update_episode_scroll)
        self.episode_canvas.bind("<Configure>", self.resize_episode_frame)
        main.add(right, weight=2)

        bottom = ttk.LabelFrame(self, text="下载设置")
        bottom.grid(row=3, column=0, sticky="ew", padx=10, pady=6)
        bottom.columnconfigure(1, weight=1)

        self.download_dir_var = tk.StringVar(value=self.config_data.get("download_dir", str(Path.cwd() / "downloads")))
        self.interval_var = tk.DoubleVar(value=float(self.config_data.get("interval_seconds", DEFAULT_INTERVAL_SECONDS)))
        self.with_related_var = tk.BooleanVar(value=bool(self.config_data.get("with_related", True)))
        self.ch_convert_var = tk.StringVar(value=str(self.config_data.get("ch_convert", 1)))

        ttk.Label(bottom, text="目录").grid(row=0, column=0, padx=8, pady=8, sticky="w")
        ttk.Entry(bottom, textvariable=self.download_dir_var).grid(row=0, column=1, padx=8, pady=8, sticky="ew")
        ttk.Button(bottom, text="选择", command=self.choose_download_dir).grid(row=0, column=2, padx=8, pady=8)
        ttk.Checkbutton(bottom, text="整合第三方弹幕", variable=self.with_related_var).grid(row=0, column=3, padx=8, pady=8)
        ttk.Label(bottom, text="简繁").grid(row=0, column=4, padx=(12, 4), pady=8)
        ttk.Combobox(
            bottom,
            width=8,
            state="readonly",
            textvariable=self.ch_convert_var,
            values=("0", "1", "2"),
        ).grid(row=0, column=5, padx=4, pady=8)
        ttk.Label(bottom, text="间隔秒").grid(row=0, column=6, padx=(12, 4), pady=8)
        ttk.Spinbox(bottom, from_=0.5, to=10, increment=0.5, width=6, textvariable=self.interval_var).grid(row=0, column=7, padx=4, pady=8)
        ttk.Button(bottom, text="下载 JSON + XML", command=self.start_download).grid(row=0, column=8, padx=8, pady=8)

        log_frame = ttk.LabelFrame(self, text="日志")
        log_frame.grid(row=4, column=0, sticky="nsew", padx=10, pady=(6, 10))
        log_frame.columnconfigure(0, weight=1)
        log_frame.rowconfigure(0, weight=1)
        self.log_text = tk.Text(log_frame, height=9, wrap="word")
        self.log_text.grid(row=0, column=0, sticky="nsew")
        log_scroll = ttk.Scrollbar(log_frame, orient="vertical", command=self.log_text.yview)
        log_scroll.grid(row=0, column=1, sticky="ns")
        self.log_text.configure(yscrollcommand=log_scroll.set)

    def update_episode_scroll(self, _event=None):
        self.episode_canvas.configure(scrollregion=self.episode_canvas.bbox("all"))

    def resize_episode_frame(self, event):
        self.episode_canvas.itemconfigure(self.episode_window, width=event.width)

    def client(self):
        return DandanplayClient(
            self.app_id_var.get(),
            self.app_secret_var.get(),
            self.auth_mode_var.get(),
        )

    def choose_download_dir(self):
        selected = filedialog.askdirectory(initialdir=self.download_dir_var.get() or str(Path.cwd()))
        if selected:
            self.download_dir_var.set(selected)

    def log(self, message):
        self.log_queue.put(message)

    def drain_log_queue(self):
        try:
            while True:
                message = self.log_queue.get_nowait()
                self.log_text.insert(tk.END, f"{time.strftime('%H:%M:%S')}  {message}\n")
                self.log_text.see(tk.END)
        except queue.Empty:
            pass
        self.after(100, self.drain_log_queue)

    def run_worker(self, target):
        if self.worker and self.worker.is_alive():
            messagebox.showinfo(APP_NAME, "当前任务还在运行，请稍等。")
            return
        self.worker = threading.Thread(target=target, daemon=True)
        self.worker.start()

    def search(self):
        keyword = self.keyword_var.get().strip()
        if len(keyword) < 2:
            messagebox.showwarning(APP_NAME, "关键词至少需要 2 个字符。")
            return

        def work():
            try:
                self.log(f"搜索：{keyword}")
                results = self.client().search_anime(keyword)
                self.after(0, lambda: self.show_search_results(results))
                self.log(f"搜索完成，共 {len(results)} 个结果。")
            except Exception as exc:
                message = str(exc)
                self.log(f"搜索失败：{message}")
                self.after(0, lambda: messagebox.showerror(APP_NAME, message))

        self.run_worker(work)

    def show_search_results(self, results):
        self.anime_results = results
        self.result_list.delete(0, tk.END)
        for item in results:
            title = item.get("animeTitle") or "(无标题)"
            count = item.get("episodeCount")
            rating = item.get("rating")
            type_desc = item.get("typeDescription") or ""
            suffix = []
            if count:
                suffix.append(f"{count}集")
            if rating:
                suffix.append(f"{rating}分")
            if type_desc:
                suffix.append(type_desc)
            self.result_list.insert(tk.END, f"{title}  {' / '.join(suffix)}")
        self.clear_episodes()

    def load_selected_bangumi(self):
        selection = self.result_list.curselection()
        if not selection:
            return
        item = self.anime_results[selection[0]]
        bangumi_id = item.get("bangumiId") or item.get("animeId")

        def work():
            try:
                self.log(f"获取番剧详情：{item.get('animeTitle')}")
                details = self.client().bangumi_details(bangumi_id)
                if not details:
                    raise ApiError("没有获取到番剧详情。")
                self.after(0, lambda: self.show_bangumi(details))
                self.log("番剧详情加载完成。")
            except Exception as exc:
                message = str(exc)
                self.log(f"详情加载失败：{message}")
                self.after(0, lambda: messagebox.showerror(APP_NAME, message))

        self.run_worker(work)

    def clear_episodes(self):
        self.current_bangumi = None
        self.episode_vars = []
        for child in self.episode_frame.winfo_children():
            child.destroy()
        self.detail_var.set("搜索并选择一个番剧。")

    def show_bangumi(self, bangumi):
        self.current_bangumi = bangumi
        for child in self.episode_frame.winfo_children():
            child.destroy()
        self.episode_vars = []

        title = bangumi.get("animeTitle") or "(无标题)"
        rating = bangumi.get("rating")
        intro = (bangumi.get("intro") or bangumi.get("summary") or "").replace("\n", " ")
        if len(intro) > 180:
            intro = intro[:180] + "..."
        self.detail_var.set(f"{title}\n评分：{rating or '-'}\n{intro}")

        episodes = bangumi.get("episodes") or []
        for index, episode in enumerate(episodes):
            var = tk.BooleanVar(value=True)
            number = episode.get("episodeNumber") or str(index + 1)
            ep_title = episode.get("episodeTitle") or f"第 {number} 集"
            label = f"{number}  {ep_title}  ID:{episode.get('episodeId')}"
            cb = ttk.Checkbutton(self.episode_frame, text=label, variable=var)
            cb.grid(row=index, column=0, sticky="ew", padx=4, pady=3)
            self.episode_vars.append((var, episode))

        self.update_episode_scroll()

    def set_all_episodes(self, value):
        for var, _episode in self.episode_vars:
            var.set(value)

    def selected_episodes(self):
        return [episode for var, episode in self.episode_vars if var.get()]

    def start_download(self):
        if not self.current_bangumi:
            messagebox.showwarning(APP_NAME, "请先选择番剧。")
            return
        episodes = self.selected_episodes()
        if not episodes:
            messagebox.showwarning(APP_NAME, "请至少选择一集。")
            return
        self.save_config()

        def work():
            client = self.client()
            bangumi_title = safe_filename(self.current_bangumi.get("animeTitle") or "番剧")
            target_dir = Path(self.download_dir_var.get()).expanduser() / bangumi_title
            target_dir.mkdir(parents=True, exist_ok=True)
            interval = max(0.5, float(self.interval_var.get()))
            with_related = self.with_related_var.get()
            ch_convert = int(self.ch_convert_var.get())

            total = len(episodes)
            ok = 0
            failed = 0
            self.log(f"开始下载：{bangumi_title}，共 {total} 集。")

            for index, episode in enumerate(episodes, start=1):
                episode_id = episode.get("episodeId")
                number = safe_filename(episode.get("episodeNumber") or f"{index:02d}")
                title = safe_filename(episode.get("episodeTitle") or f"第{number}集")
                base_name = f"{index:02d} - {number} - {title}"
                json_path = target_dir / f"{base_name}.json"
                xml_path = target_dir / f"{base_name}.xml"

                try:
                    if json_path.exists() and xml_path.exists():
                        self.log(f"[{index}/{total}] 已存在，跳过：{title}")
                        ok += 1
                        continue

                    self.log(f"[{index}/{total}] 下载弹幕：{title}")
                    data = None
                    last_error = None
                    for attempt in range(1, 4):
                        try:
                            data = client.comments(episode_id, with_related=with_related, ch_convert=ch_convert)
                            break
                        except Exception as exc:
                            last_error = exc
                            self.log(f"  第 {attempt} 次失败：{exc}")
                            time.sleep(min(2 * attempt, 5))
                    if data is None:
                        raise last_error or ApiError("下载失败")

                    comments = data.get("comments") or []
                    atomic_write_json(json_path, data)
                    atomic_write_text(xml_path, comments_to_bilibili_xml(comments))
                    self.log(f"  保存完成：{len(comments)} 条 -> {xml_path.name}")
                    ok += 1
                except Exception as exc:
                    failed += 1
                    self.log(f"  失败：{title}，原因：{exc}")

                if index < total:
                    time.sleep(interval)

            self.log(f"下载结束：成功 {ok}，失败 {failed}。目录：{target_dir}")
            self.after(0, lambda: messagebox.showinfo(APP_NAME, f"下载结束：成功 {ok}，失败 {failed}\n{target_dir}"))

        self.run_worker(work)


if __name__ == "__main__":
    app = DownloaderApp()
    app.mainloop()
