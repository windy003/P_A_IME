
import bisect
import ctypes
import ctypes.wintypes as wt
import os
import queue
import subprocess
import sys
import threading
import time

# ---------------------------------------------------------------- 配置
DICT_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                         "pinyin_simp.dict.yaml")
PAGE_SIZE = 6        # 每页候选数
MAX_CANDS = 60       # 最多取多少个候选
MAX_PINYIN = 30      # 拼音缓冲区上限

# 模糊音:每对双向等价;不想要的删掉/注释掉即可,清空列表则完全关闭
FUZZY_PAIRS = [
    ("z", "zh"), ("c", "ch"), ("s", "sh"),    # 平翘舌
    ("n", "l"),                               # 鼻边音
     ("en", "eng"), ("in", "ing"), 
    ("l", "r"),                 # 按需开启
]
MAX_FUZZY_KEYS = 24  # 一次查询最多展开的模糊拼音组合数

ENABLE_SHOUPIN = True  # 简拼:每个音节只打声母,如 zg->这个/中国、bj->北京、nh->你好
MAX_SHOUPIN_PER_KEY = 80  # 每个声母串最多保留多少候选(按词频)

CN_PUNCT = False  # 中文模式下的标点:True=中文全角(,。?),False=英文半角(,.?)

user32 = ctypes.windll.user32
kernel32 = ctypes.windll.kernel32

# ---------------------------------------------------------------- Win32 常量
WH_KEYBOARD_LL = 13
WM_KEYDOWN, WM_KEYUP = 0x0100, 0x0101
WM_SYSKEYDOWN, WM_SYSKEYUP = 0x0104, 0x0105
LLKHF_INJECTED = 0x10
KEYEVENTF_KEYUP, KEYEVENTF_UNICODE = 0x0002, 0x0004
INPUT_KEYBOARD = 1
MAGIC_EXTRA = 0x50594D45  # 'PYME':标记自己注入的按键,避免钩子自我循环

VK_BACK, VK_RETURN, VK_SHIFT, VK_CONTROL, VK_MENU = 0x08, 0x0D, 0x10, 0x11, 0x12
VK_ESCAPE, VK_SPACE, VK_PRIOR, VK_NEXT = 0x1B, 0x20, 0x21, 0x22
VK_LWIN, VK_RWIN = 0x5B, 0x5C
VK_LSHIFT, VK_RSHIFT = 0xA0, 0xA1
# 低级钩子上报的是区分左右的键码(0xA0/0xA1),不是通用的 VK_SHIFT
SHIFT_KEYS = (VK_SHIFT, VK_LSHIFT, VK_RSHIFT)
VK_OEM_1, VK_OEM_PLUS, VK_OEM_COMMA, VK_OEM_MINUS = 0xBA, 0xBB, 0xBC, 0xBD
VK_OEM_PERIOD, VK_OEM_2, VK_OEM_3 = 0xBE, 0xBF, 0xC0
VK_OEM_4, VK_OEM_5, VK_OEM_6, VK_OEM_7 = 0xDB, 0xDC, 0xDD, 0xDE

ULONG_PTR = ctypes.c_size_t
LRESULT = ctypes.c_ssize_t


class KBDLLHOOKSTRUCT(ctypes.Structure):
    _fields_ = [("vkCode", wt.DWORD), ("scanCode", wt.DWORD),
                ("flags", wt.DWORD), ("time", wt.DWORD),
                ("dwExtraInfo", ULONG_PTR)]


class KEYBDINPUT(ctypes.Structure):
    _fields_ = [("wVk", wt.WORD), ("wScan", wt.WORD), ("dwFlags", wt.DWORD),
                ("time", wt.DWORD), ("dwExtraInfo", ULONG_PTR)]


class MOUSEINPUT(ctypes.Structure):
    _fields_ = [("dx", wt.LONG), ("dy", wt.LONG), ("mouseData", wt.DWORD),
                ("dwFlags", wt.DWORD), ("time", wt.DWORD),
                ("dwExtraInfo", ULONG_PTR)]


class _INPUTunion(ctypes.Union):
    _fields_ = [("ki", KEYBDINPUT), ("mi", MOUSEINPUT)]


class INPUT(ctypes.Structure):
    _fields_ = [("type", wt.DWORD), ("u", _INPUTunion)]


class GUITHREADINFO(ctypes.Structure):
    _fields_ = [("cbSize", wt.DWORD), ("flags", wt.DWORD),
                ("hwndActive", wt.HWND), ("hwndFocus", wt.HWND),
                ("hwndCapture", wt.HWND), ("hwndMenuOwner", wt.HWND),
                ("hwndMoveSize", wt.HWND), ("hwndCaret", wt.HWND),
                ("rcCaret", wt.RECT)]


HOOKPROC = ctypes.WINFUNCTYPE(LRESULT, ctypes.c_int, wt.WPARAM, wt.LPARAM)
user32.SetWindowsHookExW.restype = wt.HHOOK
user32.SetWindowsHookExW.argtypes = (ctypes.c_int, HOOKPROC, wt.HINSTANCE, wt.DWORD)
user32.CallNextHookEx.restype = LRESULT
user32.CallNextHookEx.argtypes = (wt.HHOOK, ctypes.c_int, wt.WPARAM, wt.LPARAM)
user32.SendInput.argtypes = (wt.UINT, ctypes.POINTER(INPUT), ctypes.c_int)
kernel32.GetModuleHandleW.restype = wt.HMODULE
kernel32.GetModuleHandleW.argtypes = (wt.LPCWSTR,)
# 句柄相关函数:必须声明类型,否则 64 位句柄被默认的 c_int 截断 → 取插入符失败
user32.GetForegroundWindow.restype = wt.HWND
user32.GetWindowThreadProcessId.restype = wt.DWORD
user32.GetWindowThreadProcessId.argtypes = (wt.HWND, ctypes.POINTER(wt.DWORD))
user32.GetGUIThreadInfo.argtypes = (wt.DWORD, ctypes.POINTER(GUITHREADINFO))
user32.ClientToScreen.argtypes = (wt.HWND, ctypes.POINTER(wt.POINT))
user32.GetWindowRect.argtypes = (wt.HWND, ctypes.POINTER(wt.RECT))


def shift_down():
    return bool(user32.GetAsyncKeyState(VK_SHIFT) & 0x8000)


def ctrl_down():
    return bool(user32.GetAsyncKeyState(VK_CONTROL) & 0x8000)


def alt_down():
    return bool(user32.GetAsyncKeyState(VK_MENU) & 0x8000)


def win_down():
    return bool((user32.GetAsyncKeyState(VK_LWIN) | user32.GetAsyncKeyState(VK_RWIN)) & 0x8000)


def send_text(text):
    """用 KEYEVENTF_UNICODE 把字符串发送到当前焦点窗口。"""
    data = text.encode("utf-16-le")
    units = [int.from_bytes(data[i:i + 2], "little") for i in range(0, len(data), 2)]
    arr = (INPUT * (len(units) * 2))()
    i = 0
    for code in units:
        for flags in (KEYEVENTF_UNICODE, KEYEVENTF_UNICODE | KEYEVENTF_KEYUP):
            arr[i].type = INPUT_KEYBOARD
            arr[i].u.ki = KEYBDINPUT(0, code, flags, 0, MAGIC_EXTRA)
            i += 1
    return user32.SendInput(len(arr), arr, ctypes.sizeof(INPUT))


# ---------------------------------------------------------------- UI Automation 取插入符
# Chrome/Edge/Electron/VS Code/Cursor 等 Chromium 内核程序没有经典 Win32 插入符,
# 只能通过 UI Automation 问"当前焦点元素"的屏幕矩形,把候选框贴到它下方。
ole32 = ctypes.windll.ole32
oleaut32 = ctypes.windll.oleaut32
# 必须声明:否则 64 位 SAFEARRAY 指针被默认 c_int 截断 → OverflowError → 取位置失败
oleaut32.SafeArrayAccessData.argtypes = (ctypes.c_void_p, ctypes.POINTER(ctypes.c_void_p))
oleaut32.SafeArrayAccessData.restype = ctypes.c_long
oleaut32.SafeArrayUnaccessData.argtypes = (ctypes.c_void_p,)
oleaut32.SafeArrayUnaccessData.restype = ctypes.c_long
oleaut32.SafeArrayGetUBound.argtypes = (ctypes.c_void_p, ctypes.c_uint,
                                        ctypes.POINTER(ctypes.c_long))
oleaut32.SafeArrayGetUBound.restype = ctypes.c_long
oleaut32.SafeArrayDestroy.argtypes = (ctypes.c_void_p,)
oleaut32.SafeArrayDestroy.restype = ctypes.c_long
oleaut32.VariantClear.argtypes = (ctypes.c_void_p,)
oleaut32.VariantClear.restype = ctypes.c_long
_COINIT_APARTMENTTHREADED = 0x2
_CLSCTX_INPROC_SERVER = 1
_VT_ARRAY_R8 = 0x2005  # VT_ARRAY | VT_R8
_UIA_BoundingRectanglePropertyId = 30001
_UIA_TextPatternId = 10014
_UIA_TextPattern2Id = 10024
_TextUnit_Character = 0
_CLSID_CUIAutomation = "{ff48dba4-60ef-4201-aa87-54103eef594e}"
_IID_IUIAutomation = "{30cbe57d-d9d0-452a-ab13-7ac5ac4825ee}"
_IID_IUIAutomation2 = "{34723aff-0c9d-49d0-9896-7ab52df8cd8a}"
_IID_IUIAutomationTextPattern = "{32eba289-3583-42c9-9c59-3b6d9a1e9b6a}"
_IID_IUIAutomationTextPattern2 = "{506a921a-fcc9-409f-b23b-37eb74106872}"


class _GUID(ctypes.Structure):
    _fields_ = [("Data1", wt.DWORD), ("Data2", wt.WORD), ("Data3", wt.WORD),
                ("Data4", ctypes.c_ubyte * 8)]


class _VARIANT(ctypes.Structure):  # 只取到 union 第一个指针成员就够用
    _fields_ = [("vt", wt.WORD), ("r1", wt.WORD), ("r2", wt.WORD),
                ("r3", wt.WORD), ("parray", ctypes.c_void_p)]


def _guid(s):
    g = _GUID()
    ctypes.oledll.ole32.IIDFromString(s, ctypes.byref(g))
    return g


def _com_call(ptr, index, restype, *args):
    """调用 COM 对象 vtable 第 index 个方法。args 为 (argtype, value) 元组列表。"""
    vtbl = ctypes.cast(ptr, ctypes.POINTER(ctypes.c_void_p))[0]
    fn = ctypes.cast(vtbl, ctypes.POINTER(ctypes.c_void_p))[index]
    proto = ctypes.WINFUNCTYPE(restype, ctypes.c_void_p, *[a[0] for a in args])
    return proto(fn)(ptr, *[a[1] for a in args])


_uia = None         # IUIAutomation*(c_void_p)
_uia_tried = False  # 是否已尝试初始化(失败也只试一次)


def _uia_init():
    global _uia, _uia_tried
    _uia_tried = True
    try:
        ole32.CoInitializeEx(None, _COINIT_APARTMENTTHREADED)
        p = ctypes.c_void_p()
        clsid, iid = _guid(_CLSID_CUIAutomation), _guid(_IID_IUIAutomation)
        ctypes.oledll.ole32.CoCreateInstance(
            ctypes.byref(clsid), None, _CLSCTX_INPROC_SERVER,
            ctypes.byref(iid), ctypes.byref(p))
        _uia = p
        try:
            # IUIAutomation2(Win8+):缩短跨进程调用超时。个别程序(如有道词典
            # 的 Chromium 界面)UIA 响应极慢,不限时会把调用线程卡住数秒
            iid2 = _guid(_IID_IUIAutomation2)
            p2 = ctypes.c_void_p()
            hr = _com_call(p, 0, ctypes.c_long,  # IUnknown::QueryInterface
                           (ctypes.POINTER(_GUID), ctypes.byref(iid2)),
                           (ctypes.POINTER(ctypes.c_void_p), ctypes.byref(p2)))
            if hr == 0 and p2.value:
                # IUIAutomation2::put_ConnectionTimeout —— vtable[61](毫秒)
                _com_call(p2, 61, ctypes.c_long, (wt.DWORD, 1000))
                # IUIAutomation2::put_TransactionTimeout —— vtable[63](毫秒)
                _com_call(p2, 63, ctypes.c_long, (wt.DWORD, 1000))
                _com_call(p2, 2, ctypes.c_ulong)  # Release
        except Exception:
            pass
        print("[PyIME] UI Automation 已就绪(支持 Chrome/Electron 等程序光标跟随)")
    except Exception as e:
        _uia = None
        print("[PyIME] UI Automation 不可用,退回鼠标定位:", e)


def _range_rect(rng):
    """IUIAutomationTextRange::GetBoundingRectangles 的第一个矩形
    (left, top, width, height),没有矩形返回 None。"""
    psa = ctypes.c_void_p()
    # IUIAutomationTextRange::GetBoundingRectangles —— vtable[10]
    hr = _com_call(rng, 10, ctypes.c_long,
                   (ctypes.POINTER(ctypes.c_void_p), ctypes.byref(psa)))
    if hr != 0 or not psa.value:
        return None
    try:
        ub = ctypes.c_long(-1)
        if oleaut32.SafeArrayGetUBound(psa, 1, ctypes.byref(ub)) != 0 or ub.value < 3:
            return None  # 退化(空)区间没有矩形
        data = ctypes.c_void_p()
        if oleaut32.SafeArrayAccessData(psa, ctypes.byref(data)) != 0:
            return None
        d = ctypes.cast(data, ctypes.POINTER(ctypes.c_double))
        rect = (d[0], d[1], d[2], d[3])
        oleaut32.SafeArrayUnaccessData(psa)
        return rect if (rect[2] > 0 or rect[3] > 0) else None
    finally:
        oleaut32.SafeArrayDestroy(psa)


def _caret_from_range(rng):
    """文本区间 → 候选框落点;空区间先扩展到一个字符再取矩形。"""
    rect = _range_rect(rng)
    if rect is None:
        # IUIAutomationTextRange::ExpandToEnclosingUnit —— vtable[6]
        _com_call(rng, 6, ctypes.c_long, (ctypes.c_long, _TextUnit_Character))
        rect = _range_rect(rng)
    if rect:
        left, top, _w, h = rect
        return int(left), int(top + h) + 2
    return None


def _uia_text_caret(elem):
    """焦点元素的文本光标矩形 → (x, y),取不到返回 None。
    PowerShell / cmd(Windows Terminal、conhost)等终端没有经典插入符,
    焦点元素矩形又是整个窗口,只能用 TextPattern 拿真正的光标位置。"""
    iid2 = _guid(_IID_IUIAutomationTextPattern2)
    pat = ctypes.c_void_p()
    # IUIAutomationElement::GetCurrentPatternAs —— vtable[14]
    hr = _com_call(elem, 14, ctypes.c_long,
                   (ctypes.c_long, _UIA_TextPattern2Id),
                   (ctypes.POINTER(_GUID), ctypes.byref(iid2)),
                   (ctypes.POINTER(ctypes.c_void_p), ctypes.byref(pat)))
    if hr == 0 and pat.value:
        try:
            active = wt.BOOL()
            rng = ctypes.c_void_p()
            # IUIAutomationTextPattern2::GetCaretRange —— vtable[10]
            hr = _com_call(pat, 10, ctypes.c_long,
                           (ctypes.POINTER(wt.BOOL), ctypes.byref(active)),
                           (ctypes.POINTER(ctypes.c_void_p), ctypes.byref(rng)))
            if hr == 0 and rng.value:
                try:
                    pos = _caret_from_range(rng)
                    if pos:
                        return pos
                finally:
                    _com_call(rng, 2, ctypes.c_ulong)  # Release
        finally:
            _com_call(pat, 2, ctypes.c_ulong)  # Release
    # 不支持 TextPattern2 时退而求其次:无选区时 GetSelection 返回光标处的空区间
    iid1 = _guid(_IID_IUIAutomationTextPattern)
    pat = ctypes.c_void_p()
    hr = _com_call(elem, 14, ctypes.c_long,
                   (ctypes.c_long, _UIA_TextPatternId),
                   (ctypes.POINTER(_GUID), ctypes.byref(iid1)),
                   (ctypes.POINTER(ctypes.c_void_p), ctypes.byref(pat)))
    if hr != 0 or not pat.value:
        return None
    try:
        arr = ctypes.c_void_p()
        # IUIAutomationTextPattern::GetSelection —— vtable[5]
        hr = _com_call(pat, 5, ctypes.c_long,
                       (ctypes.POINTER(ctypes.c_void_p), ctypes.byref(arr)))
        if hr != 0 or not arr.value:
            return None
        try:
            n = ctypes.c_int(0)
            # IUIAutomationTextRangeArray::get_Length —— vtable[3]
            _com_call(arr, 3, ctypes.c_long,
                      (ctypes.POINTER(ctypes.c_int), ctypes.byref(n)))
            if n.value < 1:
                return None
            rng = ctypes.c_void_p()
            # IUIAutomationTextRangeArray::GetElement —— vtable[4]
            hr = _com_call(arr, 4, ctypes.c_long,
                           (ctypes.c_int, 0),
                           (ctypes.POINTER(ctypes.c_void_p), ctypes.byref(rng)))
            if hr != 0 or not rng.value:
                return None
            try:
                return _caret_from_range(rng)
            finally:
                _com_call(rng, 2, ctypes.c_ulong)  # Release
        finally:
            _com_call(arr, 2, ctypes.c_ulong)  # Release
    finally:
        _com_call(pat, 2, ctypes.c_ulong)  # Release


def uia_caret():
    """用 UIA 取候选框落点 (x, y):先 TextPattern 取真实文本光标
    (终端/编辑器),再退回焦点元素矩形(普通输入框);取不到返回 None。"""
    if not _uia_tried:
        _uia_init()
    if not _uia:
        return None
    elem = ctypes.c_void_p()
    try:
        # IUIAutomation::GetFocusedElement —— vtable[8]
        hr = _com_call(_uia, 8, ctypes.c_long,
                       (ctypes.POINTER(ctypes.c_void_p), ctypes.byref(elem)))
        if hr != 0 or not elem.value:
            return None
        pos = _uia_text_caret(elem)  # 终端/编辑器:真实文本光标
        if pos:
            return pos
        # IUIAutomationElement::GetCurrentPropertyValue —— vtable[10]
        var = _VARIANT()
        hr = _com_call(elem, 10, ctypes.c_long,
                       (ctypes.c_long, _UIA_BoundingRectanglePropertyId),
                       (ctypes.POINTER(_VARIANT), ctypes.byref(var)))
        rect = None
        if hr == 0 and var.vt == _VT_ARRAY_R8 and var.parray:
            data = ctypes.c_void_p()
            if oleaut32.SafeArrayAccessData(var.parray, ctypes.byref(data)) == 0:
                d = ctypes.cast(data, ctypes.POINTER(ctypes.c_double))
                rect = (d[0], d[1], d[2], d[3])  # left, top, width, height
                oleaut32.SafeArrayUnaccessData(var.parray)
        oleaut32.VariantClear(ctypes.byref(var))
        if rect and (rect[2] > 0 or rect[3] > 0):
            left, top, _w, h = rect
            # 拿不到真实光标时(如有道词典的 Chromium 页面),焦点元素往往是
            # 整个文档/窗口,矩形底边就是窗口底部,毫无定位意义;
            # 只有看起来像单行输入框的小矩形才可信,否则退回鼠标位置
            if h > 100:
                return None
            return int(left), int(top + h) + 2
        return None
    except Exception:
        return None
    finally:
        if elem.value:
            _com_call(elem, 2, ctypes.c_ulong)  # IUnknown::Release


def caret_pos():
    """取当前焦点窗口插入符(文本光标)屏幕坐标。
    经典插入符 → UI Automation 焦点元素 → 实在取不到才退回鼠标位置。"""
    try:
        hwnd = user32.GetForegroundWindow()
        tid = user32.GetWindowThreadProcessId(hwnd, None)
        gui = GUITHREADINFO()
        gui.cbSize = ctypes.sizeof(GUITHREADINFO)
        if user32.GetGUIThreadInfo(tid, ctypes.byref(gui)) and gui.hwndCaret:
            # 经典插入符:记事本(经典版)、Win32 编辑框、多数原生 IDE
            pt = wt.POINT(gui.rcCaret.left, gui.rcCaret.bottom)
            user32.ClientToScreen(gui.hwndCaret, ctypes.byref(pt))
            if pt.x or pt.y:
                return pt.x, pt.y + 4
    except Exception:
        pass
    try:
        pos = uia_caret()  # Chromium/Electron/UWP 等:UIA 焦点元素
        if pos:
            return pos
    except Exception:
        pass
    pt = wt.POINT()
    user32.GetCursorPos(ctypes.byref(pt))
    return pt.x + 10, pt.y + 16


# ---------------------------------------------------------------- 词库
_INITIALS_2 = ("zh", "ch", "sh")
_INITIALS_1 = frozenset("bpmfdtnlgkhjqxrzcsyw")
_ABBR_LETTERS = _INITIALS_1  # 简拼里允许出现的字母(每音节取首字母,zh/ch/sh 归为 z/c/s)


def _initial(s):
    """取音节(或不完整音节)的声母,无声母返回空串。"""
    if s[:2] in _INITIALS_2:
        return s[:2]
    return s[0] if s[:1] in _INITIALS_1 else ""


class Dict:
    def __init__(self, path):
        self.table = {}        # "ni hao" -> [(词, 权重), ...] 按权重降序
        self.syllables = set() # 所有合法音节
        raw = open(path, encoding="utf-8").read()
        body = raw.split("\n...", 1)[1]
        for line in body.splitlines():
            parts = line.rstrip("\n").split("\t")
            if len(parts) < 2 or not parts[0] or parts[0].startswith("#"):
                continue
            word, py = parts[0], parts[1].strip()
            try:
                weight = int(parts[2]) if len(parts) > 2 else 0
            except ValueError:
                weight = 0
            self.table.setdefault(py, []).append((word, weight))
            for s in py.split(" "):
                self.syllables.add(s)
        for v in self.table.values():
            v.sort(key=lambda x: -x[1])
        self.sorted_keys = sorted(self.table.keys())
        self.maxsyl = max(len(s) for s in self.syllables)
        self.prefixes = set()  # 所有音节的真前缀,如 zh / zho(用于识别打了一半的音节)
        for s in self.syllables:
            for L in range(1, len(s)):
                self.prefixes.add(s[:L])
        # 模糊音:声母替换对 + 每个音节的等价音节集合(闭包,只保留词库里存在的)
        self.init_subs, final_subs = [], []
        for a, b in FUZZY_PAIRS:
            for x, y in ((a, b), (b, a)):
                if x in _INITIALS_1 or x in _INITIALS_2:
                    self.init_subs.append((x, y))
                else:
                    final_subs.append((x, y))
        self.fuzzy = {}  # 音节 -> 等价音节列表(不含自身)
        for s in self.syllables:
            group, todo = {s}, [s]
            while todo:
                cur = todo.pop()
                vs = [y + cur[len(x):] for x, y in self.init_subs if _initial(cur) == x]
                vs += [cur[:-len(x)] + y for x, y in final_subs if cur.endswith(x)]
                for v in vs:
                    if v in self.syllables and v not in group:
                        group.add(v)
                        todo.append(v)
            if len(group) > 1:
                self.fuzzy[s] = sorted(group - {s})
        # 简拼索引:声母串(每音节取首字母)-> [(词, 权重)],按词频降序、截断
        self.abbr = {}
        if ENABLE_SHOUPIN:
            for py, words in self.table.items():
                sylls = py.split(" ")
                if len(sylls) < 2:
                    continue  # 单字简拼只有一个字母,太歧义,不收
                key = "".join(s[0] for s in sylls)
                self.abbr.setdefault(key, []).extend(words)
            for v in self.abbr.values():
                v.sort(key=lambda x: -x[1])
                del v[MAX_SHOUPIN_PER_KEY:]

    def segment(self, buf):
        """把字母串贪心切分成音节列表;切不动时退化为单字母段。"""
        segs, i, n = [], 0, len(buf)
        while i < n:
            if buf[i] == "'":
                i += 1
                continue
            for L in range(min(self.maxsyl, n - i), 0, -1):
                if buf[i:i + L] in self.syllables:
                    segs.append(buf[i:i + L])
                    i += L
                    break
            else:
                for L in range(min(self.maxsyl, n - i), 0, -1):
                    if buf[i:i + L] in self.prefixes:  # 不完整音节,如 zhon
                        segs.append(buf[i:i + L])
                        i += L
                        break
                else:
                    segs.append(buf[i])
                    i += 1
        return segs

    def fuzzy_keys(self, sub):
        """完整音节列表 sub 的所有模糊音组合键,第一个固定是原拼音。"""
        combos = [""]
        for s in sub:
            vs = [s] + self.fuzzy.get(s, [])
            combos = [(c + " " + v if c else v) for c in combos for v in vs]
            if len(combos) > MAX_FUZZY_KEYS:
                combos = combos[:MAX_FUZZY_KEYS]
        return combos

    def candidates(self, buf):
        """返回 [(候选词, 消耗的音节数)];优先整串精确匹配,再模糊音,
        再末音节前缀补全,再逐级缩短。"""
        segs = self.segment(buf)
        if not segs:
            return [], []
        out, seen = [], set()

        def add(word, nseg):
            if word not in seen and len(out) < MAX_CANDS:
                seen.add(word)
                out.append((word, nseg))

        for n in range(len(segs), 0, -1):
            sub = segs[:n]
            complete = all(s in self.syllables for s in sub)
            if complete:
                keys = self.fuzzy_keys(sub)
                for w, _wt in self.table.get(keys[0], []):  # 精确匹配在前
                    add(w, n)
                fz = []
                for k in keys[1:]:
                    fz.extend(self.table.get(k, []))
                for w, _wt in sorted(fz, key=lambda x: -x[1]):
                    add(w, n)
            if n == len(segs) and all(s in self.syllables for s in sub[:-1]):
                # 末音节按前缀补全(例如 zhon -> zhong;nihaom -> ni hao ma),
                # 前面的音节及末音节声母同样参与模糊
                lasts = [sub[-1]]
                for x, y in self.init_subs:
                    if _initial(sub[-1]) == x:
                        lasts.append(y + sub[-1][len(x):])
                leads = self.fuzzy_keys(sub[:-1])[:8] if n > 1 else [""]
                hits = []
                for li, lead in enumerate(leads):
                    for fi, last in enumerate(lasts):
                        fz = li > 0 or fi > 0
                        prefix = (lead + " " + last) if lead else last
                        lo = bisect.bisect_left(self.sorted_keys, prefix)
                        for k in self.sorted_keys[lo:lo + 800]:
                            if not k.startswith(prefix):
                                break
                            if complete and k == prefix:
                                continue  # 整串匹配分支已经加过
                            for w, wt_ in self.table[k][:3]:
                                hits.append((w, n, wt_, k.count(" ") + 1, fz))
                hits.sort(key=lambda x: (x[3] != n, x[4], -x[2]))
                for w, nseg, _w, _c, _f in hits[:20]:
                    add(w, nseg)

        # 简拼:整串全是声母字母时,按声母串补充候选(消耗整个缓冲区)
        letters = buf.replace("'", "")
        if (self.abbr and len(letters) >= 2
                and all(c in _ABBR_LETTERS for c in letters)):
            for w, _wt in self.abbr.get(letters, []):
                add(w, len(segs))
        return out, segs


# ---------------------------------------------------------------- 输入法状态机(运行在钩子线程)
class Engine:
    PUNCT = {  # vk: (普通, Shift)
        VK_OEM_COMMA: (",", "《"), VK_OEM_PERIOD: ("。", "》"),
        VK_OEM_1: (";", ":"), VK_OEM_2: ("、", "?"),
        VK_OEM_5: ("、", "·"), VK_OEM_3: ("·", "~"),
        VK_OEM_4: ("【", "「"), VK_OEM_6: ("】", "」"),
        VK_OEM_MINUS: (None, "——"), VK_OEM_PLUS: (None, None),
        0x31: (None, "!"), 0x34: (None, "¥"), 0x36: (None, "……"),
        0x39: (None, "("), 0x30: (None, ")"),
    }
    PUNCT_EN = {  # CN_PUNCT=False 时组词中按标点用:vk -> (普通, Shift)
        VK_OEM_COMMA: (",", "<"), VK_OEM_PERIOD: (".", ">"),
        VK_OEM_1: (";", ":"), VK_OEM_2: ("/", "?"),
        VK_OEM_5: ("\\", "|"), VK_OEM_3: ("`", "~"),
        VK_OEM_4: ("[", "{"), VK_OEM_6: ("]", "}"),
        VK_OEM_MINUS: ("-", "_"), VK_OEM_PLUS: ("=", "+"),
        VK_OEM_7: ("'", '"'),
        0x31: (None, "!"), 0x34: (None, "$"), 0x36: (None, "^"),
        0x39: (None, "("), 0x30: (None, ")"),
    }

    def __init__(self, dic, ui_q):
        self.dic = dic
        self.q = ui_q
        self.cn_mode = True
        self.buf = ""
        self.cands = []      # [(词, 消耗音节数)]
        self.segs = []
        self.page = 0
        self.quote_s = True  # 下一个单引号是否为左引号
        self.quote_d = True
        self.shift_tap = False  # Shift 按下且尚无其他键 → 抬起时切换模式
        self.posgen = 0      # 定位代数:上屏/清空后 +1,UI 线程据此重新取候选框落点
        self.tray = None     # 系统托盘图标(托盘创建后回填),用于切换时刷新 中/英

    # ---------- UI 通知 ----------
    def refresh(self):
        if not self.buf:
            self.q.put(("hide",))
            return
        self.cands, self.segs = self.dic.candidates(self.buf)
        self.page = 0
        self.push_ui()

    def push_ui(self):
        total = max(1, (len(self.cands) + PAGE_SIZE - 1) // PAGE_SIZE)
        self.page = max(0, min(self.page, total - 1))
        items = self.cands[self.page * PAGE_SIZE:(self.page + 1) * PAGE_SIZE]
        disp = ["%d.%s" % (i + 1, w) for i, (w, _n) in enumerate(items)]
        # 定位放到 UI 线程做:UIA 跨进程调用可能阻塞几百毫秒(如有道词典的
        # Chromium 界面),在钩子线程里做会超时,按键被系统直接放行
        self.q.put(("show", "'".join(self.segs), disp, self.page + 1, total, self.posgen))

    def commit(self, text):
        n = send_text(text)
        self.posgen += 1  # 上屏后目标光标移动了,下次重新定位
        print("[PyIME] 上屏 %r,SendInput=%d" % (text, n))

    def clear(self):
        self.buf = ""
        self.cands = []
        self.posgen += 1
        self.q.put(("hide",))

    def toggle(self):
        self.cn_mode = not self.cn_mode
        self.clear()
        if self.tray:  # 状态显示在系统托盘图标上(中/英),不再屏幕弹窗
            self.tray.set_mode(self.cn_mode)
        print("[PyIME] %s" % ("中文模式" if self.cn_mode else "英文模式"))

    # ---------- 候选选择 ----------
    def choose(self, idx):
        flat = self.page * PAGE_SIZE + idx
        if flat >= len(self.cands):
            return
        word, nseg = self.cands[flat]
        self.commit(word)
        # 去掉已消耗音节对应的字母(buf 里可能夹隔音符),余下继续组词
        b = self.buf
        for s in self.segs[:nseg]:
            b = b.lstrip("'")
            if b.startswith(s):
                b = b[len(s):]
        self.buf = b.lstrip("'")
        if self.buf:
            self.refresh()
        else:
            self.clear()

    # ---------- 按键处理:返回 True 表示吞掉该键 ----------
    def on_key_down(self, vk):
        if vk not in SHIFT_KEYS:
            self.shift_tap = False

        # 全局热键
        if vk == VK_SPACE and ctrl_down():
            self.toggle()
            return True
        if vk in SHIFT_KEYS:
            if not (ctrl_down() or alt_down() or win_down()):
                self.shift_tap = True
            return False

        if not self.cn_mode:
            return False
        if ctrl_down() or alt_down() or win_down():
            if self.buf:
                self.clear()
            return False

        composing = bool(self.buf)

        # 字母
        if 0x41 <= vk <= 0x5A:
            if shift_down() and not composing:
                return False  # Shift+字母 → 直接当英文大写
            if len(self.buf) < MAX_PINYIN:
                self.buf += chr(vk + 32)
                self.refresh()
            return True

        if composing:
            if 0x31 <= vk <= 0x39 or 0x61 <= vk <= 0x69:  # 1-9 / 小键盘1-9
                if shift_down() and 0x31 <= vk <= 0x39:
                    pass  # 落到标点逻辑
                else:
                    self.choose((vk & 0x0F) - 1)
                    return True
            if vk == VK_SPACE:
                self.choose(0)
                return True
            if vk == VK_BACK:
                self.buf = self.buf[:-1]
                self.refresh()
                return True
            if vk == VK_ESCAPE:
                self.clear()
                return True
            if vk == VK_RETURN:
                self.commit(self.buf.replace("'", ""))
                self.clear()
                return True
            if vk in (VK_OEM_MINUS, VK_PRIOR) and not shift_down():
                self.page -= 1
                self.push_ui()
                return True
            if vk in (VK_OEM_PLUS, VK_NEXT) and not shift_down():
                self.page += 1
                self.push_ui()
                return True
            if vk == VK_OEM_7 and not shift_down():  # ' 隔音符
                if self.buf and not self.buf.endswith("'"):
                    self.buf += "'"
                    self.refresh()
                return True

        # 标点(组词中按标点 = 先上屏首选词再发标点)
        ch = self.punct_char(vk)
        if ch is None:
            return False
        if composing:
            self.choose(0)
        elif not CN_PUNCT:
            return False  # 英文标点 + 不在组词:直接放行原始按键
        self.commit(ch)
        return True

    def punct_char(self, vk):
        if not CN_PUNCT:  # 英文标点模式
            pair = self.PUNCT_EN.get(vk)
            if not pair:
                return None
            return pair[1] if shift_down() else pair[0]
        if vk == VK_OEM_7:  # 中文引号需要配对
            if shift_down():
                ch = "“" if self.quote_d else "”"
                self.quote_d = not self.quote_d
            else:
                ch = "‘" if self.quote_s else "’"
                self.quote_s = not self.quote_s
            return ch
        pair = self.PUNCT.get(vk)
        if not pair:
            return None
        return pair[1] if shift_down() else pair[0]

    def on_key_up(self, vk):
        if vk in SHIFT_KEYS and self.shift_tap:
            self.shift_tap = False
            self.toggle()
        return False


# ---------------------------------------------------------------- 系统托盘图标
shell32 = ctypes.windll.shell32
gdi32 = ctypes.windll.gdi32

WM_USER = 0x0400
WM_TRAYICON = WM_USER + 1
NIM_ADD, NIM_MODIFY, NIM_DELETE = 0, 1, 2
NIF_MESSAGE, NIF_ICON, NIF_TIP = 0x01, 0x02, 0x04
WM_LBUTTONUP, WM_LBUTTONDBLCLK, WM_RBUTTONUP = 0x0202, 0x0203, 0x0205
MF_STRING, MF_SEPARATOR = 0x0000, 0x0800
TPM_RIGHTBUTTON, TPM_RETURNCMD = 0x0002, 0x0100
SM_CXSMICON, SM_CYSMICON = 49, 50

WNDPROC = ctypes.WINFUNCTYPE(LRESULT, wt.HWND, wt.UINT, wt.WPARAM, wt.LPARAM)


class WNDCLASSW(ctypes.Structure):
    _fields_ = [("style", wt.UINT), ("lpfnWndProc", WNDPROC),
                ("cbClsExtra", ctypes.c_int), ("cbWndExtra", ctypes.c_int),
                ("hInstance", wt.HINSTANCE), ("hIcon", wt.HICON),
                ("hCursor", wt.HANDLE), ("hbrBackground", wt.HANDLE),
                ("lpszMenuName", wt.LPCWSTR), ("lpszClassName", wt.LPCWSTR)]


class NOTIFYICONDATAW(ctypes.Structure):
    _fields_ = [("cbSize", wt.DWORD), ("hWnd", wt.HWND), ("uID", wt.UINT),
                ("uFlags", wt.UINT), ("uCallbackMessage", wt.UINT),
                ("hIcon", wt.HICON), ("szTip", wt.WCHAR * 128),
                ("dwState", wt.DWORD), ("dwStateMask", wt.DWORD),
                ("szInfo", wt.WCHAR * 256), ("uVersion", wt.UINT),
                ("szInfoTitle", wt.WCHAR * 64), ("dwInfoFlags", wt.DWORD),
                ("guidItem", _GUID), ("hBalloonIcon", wt.HICON)]


class ICONINFO(ctypes.Structure):
    _fields_ = [("fIcon", wt.BOOL), ("xHotspot", wt.DWORD), ("yHotspot", wt.DWORD),
                ("hbmMask", wt.HBITMAP), ("hbmColor", wt.HBITMAP)]


class BITMAPINFOHEADER(ctypes.Structure):
    _fields_ = [("biSize", wt.DWORD), ("biWidth", wt.LONG), ("biHeight", wt.LONG),
                ("biPlanes", wt.WORD), ("biBitCount", wt.WORD),
                ("biCompression", wt.DWORD), ("biSizeImage", wt.DWORD),
                ("biXPelsPerMeter", wt.LONG), ("biYPelsPerMeter", wt.LONG),
                ("biClrUsed", wt.DWORD), ("biClrImportant", wt.DWORD)]


# 句柄相关:声明类型,避免 64 位句柄/指针被默认 c_int 截断
user32.CreateWindowExW.restype = wt.HWND
user32.CreateWindowExW.argtypes = (
    wt.DWORD, wt.LPCWSTR, wt.LPCWSTR, wt.DWORD,
    ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_int,
    ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p)
user32.DefWindowProcW.restype = LRESULT
user32.DefWindowProcW.argtypes = (ctypes.c_void_p, wt.UINT, wt.WPARAM, wt.LPARAM)
user32.RegisterClassW.argtypes = (ctypes.POINTER(WNDCLASSW),)
user32.RegisterClassW.restype = ctypes.c_ushort
user32.GetDC.restype = ctypes.c_void_p
user32.GetDC.argtypes = (ctypes.c_void_p,)
user32.ReleaseDC.argtypes = (ctypes.c_void_p, ctypes.c_void_p)
user32.FillRect.argtypes = (ctypes.c_void_p, ctypes.POINTER(wt.RECT), ctypes.c_void_p)
user32.DrawTextW.argtypes = (ctypes.c_void_p, wt.LPCWSTR, ctypes.c_int,
                             ctypes.POINTER(wt.RECT), wt.UINT)
user32.CreateIconIndirect.restype = ctypes.c_void_p
user32.CreateIconIndirect.argtypes = (ctypes.POINTER(ICONINFO),)
user32.DestroyIcon.argtypes = (ctypes.c_void_p,)
gdi32.CreateCompatibleDC.restype = ctypes.c_void_p
gdi32.CreateCompatibleDC.argtypes = (ctypes.c_void_p,)
gdi32.DeleteDC.argtypes = (ctypes.c_void_p,)
gdi32.CreateBitmap.restype = ctypes.c_void_p
gdi32.CreateBitmap.argtypes = (ctypes.c_int, ctypes.c_int, wt.UINT, wt.UINT, ctypes.c_void_p)
gdi32.CreateDIBSection.restype = ctypes.c_void_p
gdi32.CreateDIBSection.argtypes = (ctypes.c_void_p, ctypes.c_void_p, wt.UINT,
                                   ctypes.POINTER(ctypes.c_void_p), ctypes.c_void_p, wt.DWORD)
gdi32.SelectObject.restype = ctypes.c_void_p
gdi32.SelectObject.argtypes = (ctypes.c_void_p, ctypes.c_void_p)
gdi32.DeleteObject.argtypes = (ctypes.c_void_p,)
gdi32.SetBkMode.argtypes = (ctypes.c_void_p, ctypes.c_int)
gdi32.SetTextColor.restype = wt.COLORREF
gdi32.SetTextColor.argtypes = (ctypes.c_void_p, wt.COLORREF)
gdi32.CreateSolidBrush.restype = ctypes.c_void_p
gdi32.CreateSolidBrush.argtypes = (wt.COLORREF,)
gdi32.CreateFontW.restype = ctypes.c_void_p
gdi32.CreateFontW.argtypes = (ctypes.c_int,) * 5 + (wt.DWORD,) * 8 + (wt.LPCWSTR,)
user32.CreatePopupMenu.restype = wt.HMENU
user32.AppendMenuW.argtypes = (ctypes.c_void_p, wt.UINT, ctypes.c_size_t, wt.LPCWSTR)
user32.TrackPopupMenu.restype = ctypes.c_int
user32.TrackPopupMenu.argtypes = (ctypes.c_void_p, wt.UINT, ctypes.c_int,
    ctypes.c_int, ctypes.c_int, ctypes.c_void_p, ctypes.c_void_p)
user32.SetForegroundWindow.argtypes = (ctypes.c_void_p,)
user32.DestroyMenu.argtypes = (ctypes.c_void_p,)
user32.DestroyWindow.argtypes = (ctypes.c_void_p,)
user32.PostMessageW.argtypes = (ctypes.c_void_p, wt.UINT, wt.WPARAM, wt.LPARAM)
shell32.Shell_NotifyIconW.restype = wt.BOOL
shell32.Shell_NotifyIconW.argtypes = (wt.DWORD, ctypes.POINTER(NOTIFYICONDATAW))


def _rgb(r, g, b):
    return r | (g << 8) | (b << 16)


def make_text_icon(text, bg, fg):
    """用 GDI 现画一个写着 text(如 '中'/'英')的小图标,返回 HICON。"""
    w = user32.GetSystemMetrics(SM_CXSMICON) or 16
    h = user32.GetSystemMetrics(SM_CYSMICON) or 16
    screen = user32.GetDC(None)
    hdc = gdi32.CreateCompatibleDC(screen)
    bmi = BITMAPINFOHEADER()
    bmi.biSize = ctypes.sizeof(BITMAPINFOHEADER)
    bmi.biWidth, bmi.biHeight = w, -h   # 负高 = 自上而下,方便直接定位像素
    bmi.biPlanes, bmi.biBitCount = 1, 32
    bits = ctypes.c_void_p()
    color = gdi32.CreateDIBSection(hdc, ctypes.byref(bmi), 0, ctypes.byref(bits), None, 0)
    mask = gdi32.CreateBitmap(w, h, 1, 1, None)
    old = gdi32.SelectObject(hdc, color)
    rect = wt.RECT(0, 0, w, h)
    brush = gdi32.CreateSolidBrush(bg)
    user32.FillRect(hdc, ctypes.byref(rect), brush)
    gdi32.DeleteObject(brush)
    font = gdi32.CreateFontW(-(h * 4 // 5), 0, 0, 0, 700, 0, 0, 0,
                             1, 0, 0, 4, 0, "Microsoft YaHei")
    oldf = gdi32.SelectObject(hdc, font)
    gdi32.SetBkMode(hdc, 1)             # TRANSPARENT
    gdi32.SetTextColor(hdc, fg)
    user32.DrawTextW(hdc, text, -1, ctypes.byref(rect), 0x25)  # CENTER|VCENTER|SINGLELINE
    gdi32.SelectObject(hdc, oldf)
    gdi32.DeleteObject(font)
    gdi32.SelectObject(hdc, old)
    if bits.value:  # GDI 画完 alpha 通道全 0,强制设满,否则整个图标会透明
        buf = (ctypes.c_ubyte * (w * h * 4)).from_address(bits.value)
        for i in range(3, w * h * 4, 4):
            buf[i] = 255
    ii = ICONINFO(1, 0, 0, mask, color)
    hicon = user32.CreateIconIndirect(ctypes.byref(ii))
    gdi32.DeleteObject(color)
    gdi32.DeleteObject(mask)
    gdi32.DeleteDC(hdc)
    user32.ReleaseDC(None, screen)
    return hicon


class TrayIcon:
    """在钩子线程(已有 Win32 消息循环)里挂一个隐藏窗口承载托盘图标 + 右键菜单。"""
    CLASS_NAME = "PyIME_TrayWnd"
    ID_TOGGLE, ID_RESTART, ID_QUIT = 1, 2, 3

    def __init__(self, engine):
        self.engine = engine
        self.hwnd = None
        self.nid = None
        self.hicon = None
        self._proc = WNDPROC(self._wnd_proc)  # 必须保持引用防止被 GC

    def _make_icon(self):
        text = "中" if self.engine.cn_mode else "英"
        return make_text_icon(text, _rgb(0x1e, 0x6f, 0xd9), _rgb(255, 255, 255))

    def _tip(self):
        return "PyIME — %s(右键菜单 / 双击切换)" % ("中文" if self.engine.cn_mode else "英文")

    def create(self):
        hinst = kernel32.GetModuleHandleW(None)
        wc = WNDCLASSW()
        wc.lpfnWndProc = self._proc
        wc.hInstance = hinst
        wc.lpszClassName = self.CLASS_NAME
        user32.RegisterClassW(ctypes.byref(wc))
        self.hwnd = user32.CreateWindowExW(0, self.CLASS_NAME, "PyIME", 0,
                                           0, 0, 0, 0, None, None, hinst, None)
        self.hicon = self._make_icon()
        nid = NOTIFYICONDATAW()
        nid.cbSize = ctypes.sizeof(NOTIFYICONDATAW)
        nid.hWnd = self.hwnd
        nid.uID = 1
        nid.uFlags = NIF_MESSAGE | NIF_ICON | NIF_TIP
        nid.uCallbackMessage = WM_TRAYICON
        nid.hIcon = self.hicon
        nid.szTip = self._tip()
        shell32.Shell_NotifyIconW(NIM_ADD, ctypes.byref(nid))
        self.nid = nid
        print("[PyIME] 系统托盘图标已创建")

    def set_mode(self, cn_mode):
        """中/英状态变化时刷新托盘图标和提示文字。"""
        if self.nid is None:
            return
        old = self.hicon
        self.hicon = self._make_icon()
        self.nid.hIcon = self.hicon
        self.nid.szTip = self._tip()
        shell32.Shell_NotifyIconW(NIM_MODIFY, ctypes.byref(self.nid))
        if old:
            user32.DestroyIcon(old)

    def _wnd_proc(self, hwnd, msg, wparam, lparam):
        if msg == WM_TRAYICON:
            low = lparam & 0xFFFF
            if low in (WM_RBUTTONUP, WM_LBUTTONUP):
                self._menu()
            elif low == WM_LBUTTONDBLCLK:
                self.engine.toggle()
            return 0
        return user32.DefWindowProcW(hwnd, msg, wparam, lparam)

    def _menu(self):
        menu = user32.CreatePopupMenu()
        toggle_text = "切换到英文" if self.engine.cn_mode else "切换到中文"
        user32.AppendMenuW(menu, MF_STRING, self.ID_TOGGLE, toggle_text)
        user32.AppendMenuW(menu, MF_SEPARATOR, 0, None)
        user32.AppendMenuW(menu, MF_STRING, self.ID_RESTART, "重启(&R)")
        user32.AppendMenuW(menu, MF_STRING, self.ID_QUIT, "退出(&X)")
        pt = wt.POINT()
        user32.GetCursorPos(ctypes.byref(pt))
        user32.SetForegroundWindow(self.hwnd)  # 否则菜单点外面不消失
        cmd = user32.TrackPopupMenu(menu, TPM_RIGHTBUTTON | TPM_RETURNCMD,
                                    pt.x, pt.y, 0, self.hwnd, None)
        user32.PostMessageW(self.hwnd, 0, 0, 0)  # WM_NULL:修复菜单收起的老 bug
        user32.DestroyMenu(menu)
        if cmd == self.ID_QUIT:
            self.engine.q.put(("quit",))
        elif cmd == self.ID_RESTART:
            self._restart()
        elif cmd == self.ID_TOGGLE:
            self.engine.toggle()

    def _restart(self):
        """拉起一个新的 PyIME 进程,再让当前进程退出。"""
        try:
            args = [sys.executable, os.path.abspath(sys.argv[0])] + sys.argv[1:]
            subprocess.Popen(args, close_fds=True)
            print("[PyIME] 正在重启 ...")
        except Exception as e:
            print("[PyIME] 重启失败:", e)
            return
        self.engine.q.put(("quit",))

    def destroy(self):
        if self.nid is not None:
            shell32.Shell_NotifyIconW(NIM_DELETE, ctypes.byref(self.nid))
            self.nid = None
        if self.hicon:
            user32.DestroyIcon(self.hicon)
            self.hicon = None
        if self.hwnd:
            user32.DestroyWindow(self.hwnd)
            self.hwnd = None


# ---------------------------------------------------------------- 钩子线程
class HookThread(threading.Thread):
    def __init__(self, engine):
        super().__init__(daemon=True)
        self.engine = engine
        self.tid = None
        self._proc = HOOKPROC(self._callback)  # 必须保持引用防止被 GC

    def _callback(self, n_code, w_param, l_param):
        if n_code >= 0:
            kb = ctypes.cast(l_param, ctypes.POINTER(KBDLLHOOKSTRUCT)).contents
            if kb.dwExtraInfo != MAGIC_EXTRA:  # 忽略自己注入的按键
                try:
                    if os.environ.get("PYIME_DEBUG"):
                        print("[PyIME] key wp=%#x vk=%#x flags=%#x" %
                              (w_param, kb.vkCode, kb.flags))
                    if w_param in (WM_KEYDOWN, WM_SYSKEYDOWN):
                        if self.engine.on_key_down(kb.vkCode):
                            return 1
                    elif w_param in (WM_KEYUP, WM_SYSKEYUP):
                        if self.engine.on_key_up(kb.vkCode):
                            return 1
                except Exception as e:
                    print("[PyIME] 钩子异常:", e)
        return user32.CallNextHookEx(None, n_code, w_param, l_param)

    def run(self):
        self.tid = kernel32.GetCurrentThreadId()
        hook = user32.SetWindowsHookExW(WH_KEYBOARD_LL, self._proc,
                                        kernel32.GetModuleHandleW(None), 0)
        if not hook:
            print("[PyIME] 安装键盘钩子失败!GetLastError=%d" % kernel32.GetLastError())
            self.engine.q.put(("quit",))
            return
        tray = TrayIcon(self.engine)
        try:
            tray.create()
            self.engine.tray = tray
        except Exception as e:
            print("[PyIME] 创建系统托盘图标失败:", e)
        msg = wt.MSG()
        while user32.GetMessageW(ctypes.byref(msg), None, 0, 0) > 0:
            user32.TranslateMessage(ctypes.byref(msg))
            user32.DispatchMessageW(ctypes.byref(msg))
        try:
            tray.destroy()
        except Exception:
            pass
        user32.UnhookWindowsHookEx(hook)

    def stop(self):
        if self.tid:
            user32.PostThreadMessageW(self.tid, 0x0012, 0, 0)  # WM_QUIT


# ---------------------------------------------------------------- 界面(主线程)
def run_ui(ui_q, hook_thread):
    import tkinter as tk
    import tkinter.font as tkfont

    root = tk.Tk()
    root.withdraw()

    win = tk.Toplevel(root)
    win.withdraw()
    win.overrideredirect(True)
    win.attributes("-topmost", True)
    BG, FG, HL = "#ffffff", "#202020", "#1a73e8"
    frame = tk.Frame(win, bg=BG, padx=8, pady=5,
                     highlightthickness=1, highlightbackground="#c0c0c0")
    frame.pack()
    f_comp = tkfont.Font(family="Microsoft YaHei UI", size=10)
    f_cand = tkfont.Font(family="Microsoft YaHei UI", size=12)
    lbl_comp = tk.Label(frame, bg=BG, fg=HL, font=f_comp, anchor="w")
    lbl_comp.pack(fill="x")
    lbl_cand = tk.Label(frame, bg=BG, fg=FG, font=f_cand, anchor="w")
    lbl_cand.pack(fill="x")

    SW_HIDE, SW_SHOWNOACTIVATE = 0, 4
    GWL_EXSTYLE = -20
    WS_EX_NOACTIVATE, WS_EX_TOOLWINDOW, WS_EX_TOPMOST = 0x08000000, 0x80, 0x08

    def init_hwnd(w):
        """取窗口句柄并加 WS_EX_NOACTIVATE,显示/隐藏全部走 ShowWindow,
        避免 tk 的 deiconify 抢走目标程序的焦点。"""
        w.geometry("+-10000+-10000")   # 在屏幕外完成首次映射
        w.deiconify()
        w.update_idletasks()
        try:
            hwnd = int(w.wm_frame(), 16) or w.winfo_id()
        except Exception:
            hwnd = w.winfo_id()
        hwnd = wt.HWND(hwnd)
        style = user32.GetWindowLongPtrW(hwnd, GWL_EXSTYLE)
        user32.SetWindowLongPtrW(hwnd, GWL_EXSTYLE,
                                 style | WS_EX_NOACTIVATE | WS_EX_TOOLWINDOW | WS_EX_TOPMOST)
        user32.ShowWindow(hwnd, SW_HIDE)
        return hwnd

    hwnd_win = init_hwnd(win)

    def place_show(w, hwnd, x, y):
        w.update_idletasks()
        sw, sh = w.winfo_screenwidth(), w.winfo_screenheight()
        ww, wh = w.winfo_reqwidth(), w.winfo_reqheight()
        x = max(0, min(x, sw - ww))
        y = max(0, min(y, sh - wh))
        w.geometry("+%d+%d" % (x, y))
        w.update_idletasks()
        user32.ShowWindow(hwnd, SW_SHOWNOACTIVATE)

    pos_cache = (None, (0, 0))  # (定位代数, 落点):组词期间光标不动,每代只查一次

    def poll():
        nonlocal pos_cache
        try:
            while True:
                msg = ui_q.get_nowait()
                kind = msg[0]
                if kind == "show":
                    _, comp, cands, page, pages, gen = msg
                    lbl_comp.config(text=comp)
                    txt = "  ".join(cands) if cands else "(无候选,回车上屏字母)"
                    if pages > 1:
                        txt += "   %d/%d" % (page, pages)
                    lbl_cand.config(text=txt)
                    if pos_cache[0] != gen:
                        pos_cache = (gen, caret_pos())
                    place_show(win, hwnd_win, *pos_cache[1])
                elif kind == "hide":
                    user32.ShowWindow(hwnd_win, SW_HIDE)
                elif kind == "quit":
                    print("[PyIME] 退出。")
                    hook_thread.stop()
                    root.destroy()
                    return
        except queue.Empty:
            pass
        root.after(25, poll)

    root.after(25, poll)
    root.mainloop()


# ---------------------------------------------------------------- 自测
def selftest(dic):
    assert dic.segment("nihao") == ["ni", "hao"]
    assert dic.segment("zhongguo") == ["zhong", "guo"]
    assert dic.segment("xi'an") == ["xi", "an"]
    cands, _ = dic.candidates("nihao")
    assert cands and cands[0][0] == "你好", cands[:5]
    cands, _ = dic.candidates("zhongguo")
    assert any(w == "中国" for w, _n in cands[:3]), cands[:5]
    cands, _ = dic.candidates("zhon")       # 末音节前缀
    assert any(w == "中" for w, _n in cands[:8]), cands[:8]
    cands, _ = dic.candidates("shurufa")
    assert any(w == "输入法" for w, _n in cands[:5]), cands[:5]
    cands, _ = dic.candidates("women")
    assert cands[0][0] == "我们", cands[:5]
    cands, _ = dic.candidates("nihaoshijie")  # 部分匹配:你好 + 剩余
    assert cands[0] == ("你好", 2), cands[:5]
    # 模糊音
    cands, _ = dic.candidates("zongguo")      # z/zh
    assert any(w == "中国" for w, _n in cands[:5]), cands[:5]
    cands, _ = dic.candidates("lihao")        # n/l
    assert any(w == "你好" for w, _n in cands[:5]), cands[:5]
    cands, _ = dic.candidates("gaoxin")       # in/ing:高兴
    assert any(w == "高兴" for w, _n in cands[:5]), cands[:5]
    cands, _ = dic.candidates("zon")          # 不完整音节 + 声母模糊
    assert any(w == "中" for w, _n in cands[:8]), cands[:8]
    cands, _ = dic.candidates("zhongguo")     # 精确匹配仍排第一
    assert cands[0][0] == "中国", cands[:5]
    # 简拼
    cands, _ = dic.candidates("zg")           # 这个 / 中国
    assert any(w == "这个" for w, _n in cands), [w for w, _ in cands[:10]]
    assert any(w == "中国" for w, _n in cands), [w for w, _ in cands[:10]]
    cands, _ = dic.candidates("bj")           # 北京
    assert any(w == "北京" for w, _n in cands[:10]), [w for w, _ in cands[:10]]
    cands, _ = dic.candidates("nh")           # 你好
    assert any(w == "你好" for w, _n in cands[:10]), [w for w, _ in cands[:10]]
    cands, segs = dic.candidates("zg")        # 选 zg 应消耗整个缓冲区
    assert segs == ["z", "g"], segs
    print("selftest OK —— 词条数:%d,音节数:%d,模糊音节:%d,简拼串:%d"
          % (len(dic.table), len(dic.syllables), len(dic.fuzzy), len(dic.abbr)))


def main():
    try:  # Per-Monitor V2 DPI,坐标才能和 tkinter 对得上
        user32.SetProcessDpiAwarenessContext(ctypes.c_ssize_t(-4))
    except Exception:
        try:
            user32.SetProcessDPIAware()
        except Exception:
            pass

    print("[PyIME] 加载词库 ...")
    t0 = time.time()
    dic = Dict(DICT_FILE)
    print("[PyIME] 词库加载完成:%d 条,耗时 %.1fs" % (len(dic.table), time.time() - t0))

    if "--selftest" in sys.argv:
        selftest(dic)
        return

    ui_q = queue.Queue()
    engine = Engine(dic, ui_q)
    hook = HookThread(engine)
    hook.start()
    print("[PyIME] 已启动,当前为中文模式。")
    run_ui(ui_q, hook)


if __name__ == "__main__":
    main()
