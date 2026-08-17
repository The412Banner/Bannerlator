package com.winlator.star.ui.screens

/**
 * Plain-English reference for Wine's debug channels, used by the Log Manager's "Browse all" dialog.
 *
 * Wine ships 521 of these and they are, with few exceptions, named after the DLL or subsystem whose
 * output they switch on. Hand-writing 521 accurate descriptions is not something anyone can do
 * honestly, so this works in two tiers:
 *
 *  1. [DESCRIPTIONS] — channels worth knowing, described specifically. These are the ones a user is
 *     plausibly here to find: the tracing channels, the graphics stack, audio, input, networking.
 *  2. [categoryOf] — a family for every remaining channel, so the tail still gets a true statement
 *     ("Direct3D and graphics — output from Wine's `d3dxof` component") instead of an invented one.
 *
 * The rule for tier 1: if the specific behaviour isn't known, leave it out and let the category
 * answer. A vague-but-true line is more use than a confident-sounding guess about which component
 * logs what.
 */
object WineChannelInfo {

    const val CAT_TRACING = "Errors and tracing"
    const val CAT_GRAPHICS = "Direct3D and graphics"
    const val CAT_AUDIO = "Sound"
    const val CAT_VIDEO = "Video and media playback"
    const val CAT_UI = "Windows, menus and controls"
    const val CAT_INPUT = "Input and devices"
    const val CAT_NET = "Networking and the internet"
    const val CAT_FILES = "Files and storage"
    const val CAT_RUNTIME = "Processes, threads and memory"
    const val CAT_REGISTRY = "Registry and settings"
    const val CAT_SECURITY = "Security and encryption"
    const val CAT_COM = "COM, scripting and automation"
    const val CAT_TEXT = "Fonts and text"
    const val CAT_PRINT = "Printing"
    const val CAT_INSTALL = "Installers and packaging"
    const val CAT_HOST = "The Android side"
    const val CAT_OTHER = "Everything else"

    /** Order the browse dialog groups them in — most useful first, not alphabetical. */
    val CATEGORY_ORDER = listOf(
        CAT_TRACING, CAT_GRAPHICS, CAT_AUDIO, CAT_VIDEO, CAT_INPUT, CAT_UI, CAT_RUNTIME,
        CAT_FILES, CAT_NET, CAT_REGISTRY, CAT_SECURITY, CAT_COM, CAT_TEXT, CAT_PRINT,
        CAT_INSTALL, CAT_HOST, CAT_OTHER,
    )

    /** Short note on what each category is for, shown once above its group. */
    fun categoryBlurb(category: String): String = when (category) {
        CAT_TRACING -> "Start here. These decide how much detail everything else prints."
        CAT_GRAPHICS -> "The graphics stack — where most game problems actually live."
        CAT_AUDIO -> "Sound output, mixing and the audio APIs games use."
        CAT_VIDEO -> "Cutscenes, codecs and media playback."
        CAT_INPUT -> "Controllers, mouse, keyboard and other hardware."
        CAT_UI -> "Windows, dialogs and the standard Windows controls."
        CAT_RUNTIME -> "How the program runs: processes, threads, memory, exceptions."
        CAT_FILES -> "Reading and writing files, drives and the filesystem."
        CAT_NET -> "Anything that talks over a network."
        CAT_REGISTRY -> "The Windows registry and configuration."
        CAT_SECURITY -> "Certificates, encryption, credentials and permissions."
        CAT_COM -> "Windows' component/automation plumbing, used heavily by launchers."
        CAT_TEXT -> "Font loading and text rendering."
        CAT_PRINT -> "Printers and print jobs."
        CAT_INSTALL -> "Game and dependency installers."
        CAT_HOST -> "Where Wine meets Android — the display driver and sound backends."
        else -> "Components without an obvious home. Mostly niche Windows DLLs."
    }

    /**
     * The channels worth describing specifically. Everything absent from this map falls through to
     * the category sentence, which is the point — see the class note.
     */
    private val DESCRIPTIONS: Map<String, String> = mapOf(
        // ── Tracing. The ones that change the character of the whole log. ──────────────────
        "err" to "Real errors. On by default, and the first thing to read.",
        "warn" to "Warnings — something unexpected but not fatal. On by default.",
        "fixme" to "Wine admitting a feature is missing or faked. Very often the cause of a bug.",
        "seh" to "Windows exceptions as they are thrown. Invaluable for a crash, but games throw " +
            "these constantly, so it makes logs huge and slows the game down noticeably.",
        "relay" to "Logs every call in and out of a DLL. Extremely detailed and extremely slow — " +
            "useful for a specific hang, unusable as a general setting.",
        "snoop" to "Like relay, but for calls Wine did not build itself. Same cost.",
        "exception" to "Exception handling as it unwinds. Pairs with seh.",
        "unwind" to "Stack unwinding during exception handling.",
        "debugstr" to "Text the program itself prints with OutputDebugString — often the " +
            "developer's own messages.",
        "debug_buffer" to "Contents of buffers passed around, dumped as hex.",

        // ── Graphics ──────────────────────────────────────────────────────────────────────
        "d3d" to "Direct3D core. The general starting point for a graphics problem.",
        "d3d8" to "Direct3D 8 — older games.",
        "d3d9" to "Direct3D 9 — the most common API for games of that era.",
        "d3d10" to "Direct3D 10.",
        "d3d10core" to "Direct3D 10 core layer.",
        "d3d11" to "Direct3D 11 — what most modern games in Bannerlator use.",
        "d3d12" to "Direct3D 12, translated by VKD3D.",
        "d3d_shader" to "Shader compilation. Try this for missing or corrupted visuals.",
        "d3d_decl" to "Vertex declarations — how geometry is described to the GPU.",
        "d3dcompiler" to "The shader compiler itself.",
        "d3dx" to "The D3DX helper library games use for textures, maths and effects.",
        "dxgi" to "DXGI — swapchains, display modes and presentation. Good for black-screen, " +
            "fullscreen and refresh-rate problems.",
        "ddraw" to "DirectDraw — 2D and pre-Direct3D-8 titles.",
        "vulkan" to "Vulkan calls. This is what DXVK and VKD3D ultimately produce.",
        "opengl" to "OpenGL calls.",
        "wgl" to "The glue between OpenGL and Windows windows.",
        "gl_compat" to "OpenGL compatibility workarounds.",
        "gdi" to "GDI — classic 2D Windows drawing. Menus and launchers use it heavily.",
        "gdiplus" to "GDI+ — the newer 2D drawing library.",
        "d2d" to "Direct2D.",
        "dcomp" to "DirectComposition — desktop composition of windows.",
        "dwmapi" to "Desktop Window Manager — compositing and window effects.",
        "bitblt" to "Bitmap block transfers, the workhorse of 2D drawing.",
        "bitmap" to "Bitmap handling.",
        "palette" to "Colour palettes, used by older games.",
        "dxcore" to "DXCore — how the app enumerates graphics adapters.",
        "dxva2" to "Hardware video acceleration.",
        "asmshader" to "Assembly shader parsing, for older shader models.",

        // ── Audio ─────────────────────────────────────────────────────────────────────────
        "dsound" to "DirectSound — the usual audio API for older games.",
        "dsound3d" to "DirectSound 3D positional audio.",
        "xaudio2" to "XAudio2 — the usual audio API for modern games.",
        "mmdevapi" to "The modern Windows audio device API (WASAPI).",
        "coreaudio" to "Core audio routing.",
        "winmm" to "Multimedia — legacy sound, MIDI and timers.",
        "midi" to "MIDI playback.",
        "wavemap" to "Wave audio mapping between formats.",
        "sound" to "General sound output.",
        "alsa" to "The ALSA backend — one way sound leaves Wine on the Android side.",
        "pulse" to "The PulseAudio backend.",
        "oss" to "The OSS audio backend.",
        "dmusic" to "DirectMusic.",
        "dmsynth" to "The DirectMusic software synthesiser.",
        "msacm" to "Audio compression and decompression.",

        // ── Video / media ─────────────────────────────────────────────────────────────────
        "quartz" to "DirectShow — cutscene and video playback. Try this when intro videos hang.",
        "mfplat" to "Media Foundation, the modern video pipeline. Also common for cutscenes.",
        "evr" to "The Enhanced Video Renderer.",
        "devenum" to "Enumeration of installed codecs and capture devices.",
        "avifile" to "AVI file handling.",
        "mciavi" to "AVI playback through the MCI interface.",
        "wmvcore" to "Windows Media Video.",
        "msvideo" to "Video compression and decompression.",
        "mmio" to "Multimedia file I/O.",

        // ── Input ─────────────────────────────────────────────────────────────────────────
        "dinput" to "DirectInput — controllers and joysticks in older games.",
        "xinput" to "XInput — the modern gamepad API. Try this when a controller isn't detected.",
        "rawinput" to "Raw input — mouse and keyboard straight from the device. Common in shooters.",
        "hid" to "USB HID devices, which is what most controllers are.",
        "joycpl" to "The joystick control panel.",
        "keyboard" to "Keyboard handling.",
        "cursor" to "Mouse cursor. Worth trying for an invisible or stuck cursor.",
        "input" to "General input dispatch.",
        "usb" to "USB devices.",
        "wintab32" to "Graphics-tablet input.",

        // ── Windows and controls ──────────────────────────────────────────────────────────
        "win" to "Window creation and management. Broad, and useful for windowing problems.",
        "message" to "The Windows message loop — every event the program receives. Very verbose.",
        "msg" to "Individual window messages.",
        "nonclient" to "Title bars, borders and window frames.",
        "dialog" to "Dialog boxes.",
        "menu" to "Menus.",
        "user" to "USER32 — the core windowing library.",
        "clipboard" to "Copy and paste.",
        "dragdrop" to "Drag and drop.",
        "hook" to "Windows hooks, which intercept events. Overlays and anti-cheat use these.",
        "theme_scroll" to "Themed scrollbar drawing.",
        "uxtheme" to "Visual themes and control styling.",
        "commctrl" to "The standard Windows controls, as a group.",
        "commdlg" to "The standard file/print/colour dialogs.",
        "edit" to "Text edit boxes.",
        "listbox" to "List boxes.",
        "listview" to "List views.",
        "combo" to "Combo boxes (drop-downs).",
        "button" to "Buttons.",
        "toolbar" to "Toolbars.",
        "tooltips" to "Tooltips.",
        "treeview" to "Tree views.",
        "statusbar" to "Status bars.",
        "systray" to "The system tray / notification area.",
        "shell" to "The Windows shell — folders, icons and file associations.",
        "explorerframe" to "Explorer window framing.",

        // ── Runtime ───────────────────────────────────────────────────────────────────────
        "process" to "Process creation and exit. Good for a game that closes immediately.",
        "thread" to "Thread creation and scheduling.",
        "threadpool" to "The thread pool.",
        "module" to "DLL loading — which libraries the game pulls in. Very useful early on.",
        "loaddll" to "Each DLL as it loads, one line apiece. Cheap and often enough on its own.",
        "unloaddll" to "DLLs being unloaded.",
        "heap" to "Memory allocation. Verbose, but the place to look for memory corruption.",
        "globalmem" to "Global memory allocation.",
        "sync" to "Locks, mutexes and synchronisation. The channel for a hang or deadlock.",
        "ntdll" to "The lowest-level Windows library. Broad and noisy.",
        "kernelbase" to "Core kernel API calls.",
        "ntoskrnl" to "Kernel-side calls, used by drivers and anti-cheat.",
        "syslevel" to "Legacy 16-bit locking.",
        "environ" to "Environment variables as the process sees them.",
        "exec" to "Program execution.",
        "toolhelp" to "Process and module enumeration, used by tools and anti-cheat.",
        "vcruntime" to "The Visual C++ runtime.",
        "msvcrt" to "The Microsoft C runtime — the standard library most games link against.",
        "msvcp" to "The Microsoft C++ standard library.",
        "concrt" to "Microsoft's concurrency runtime.",
        "vcomp" to "OpenMP parallel processing.",
        "wow" to "32-bit-on-64-bit translation.",
        "int" to "DOS-era interrupt handling.",
        "int21" to "DOS interrupt 21h, for very old software.",
        "vxd" to "Windows 9x virtual device drivers.",

        // ── Files, registry ───────────────────────────────────────────────────────────────
        "file" to "File opens, reads and writes. Broad, and the channel for a missing-file problem.",
        "reg" to "Registry reads and writes. Good for a game that won't see its own settings.",
        "storage" to "Structured storage files.",
        "volume" to "Drives and volumes.",
        "mountmgr" to "Drive letter assignment — relevant to how Bannerlator maps your storage.",
        "cabinet" to "CAB archive extraction, used by installers.",
        "profile" to "INI file reads and writes. Older games keep settings this way.",
        "path" to "Path handling and conversion between Windows and Linux paths.",
        "dosmem" to "DOS memory emulation.",

        // ── Networking ────────────────────────────────────────────────────────────────────
        "winsock" to "Sockets — the base of nearly all networking. Start here for online problems.",
        "wininet" to "The classic HTTP/FTP library, used by launchers and updaters.",
        "winhttp" to "The newer HTTP library.",
        "http" to "HTTP handling.",
        "urlmon" to "URL handling and downloads.",
        "dnsapi" to "DNS lookups.",
        "iphlpapi" to "Network adapter and IP configuration queries.",
        "netapi32" to "Windows networking and domain queries.",
        "dplay" to "DirectPlay — multiplayer in older games.",
        "dpnet" to "DirectPlay 8 networking.",
        "rpc" to "Remote procedure calls, used between Windows components.",
        "mswsock" to "Winsock extensions.",

        // ── Security ──────────────────────────────────────────────────────────────────────
        "crypt" to "Cryptography.",
        "crypto" to "Low-level cryptographic operations.",
        "bcrypt" to "The modern cryptography API.",
        "ncrypt" to "Key storage and management.",
        "schannel" to "TLS/SSL — secure connections. Try this when a launcher can't log in.",
        "secur32" to "Authentication.",
        "wintrust" to "Signature verification, including anti-tamper checks.",
        "cred" to "Stored credentials.",
        "security" to "Permissions and access checks.",
        "advapi" to "Advanced Windows APIs: services, registry and security.",

        // ── COM / scripting ───────────────────────────────────────────────────────────────
        "ole" to "COM/OLE — Windows' component system. Launchers lean on it heavily.",
        "combase" to "The COM base library.",
        "olemalloc" to "COM memory allocation.",
        "variant" to "COM variant type conversion.",
        "msi" to "Windows Installer. The channel for a failing .msi install.",
        "msxml" to "XML parsing.",
        "xmllite" to "Lightweight XML parsing.",
        "vbscript" to "VBScript.",
        "jscript" to "JScript/JavaScript.",
        "mshtml" to "The embedded web browser control, used by some launchers.",
        "actctx" to "Activation contexts and side-by-side assemblies — a common source of " +
            "\"the application failed to initialize\" errors.",
        "sxs" to "Side-by-side assembly resolution, the same family as actctx.",
        "fusion" to ".NET assembly loading.",

        // ── Text and printing ─────────────────────────────────────────────────────────────
        "font" to "Font loading and matching. The channel for missing or wrong text.",
        "dwrite" to "DirectWrite — modern text rendering.",
        "fontcache" to "The font cache service.",
        "text" to "Text drawing.",
        "string" to "String handling.",
        "nls" to "Locale and codepage handling — relevant to non-English text.",
        "locale" to "Regional settings.",
        "imm" to "Input method editors, for CJK text entry.",
        "print" to "Printing.",
        "winspool" to "The print spooler.",

        // ── Host side ─────────────────────────────────────────────────────────────────────
        "x11drv" to "The X11 display driver — how Wine's windows reach Bannerlator's screen. " +
            "The channel for windowing, fullscreen and mouse-capture problems.",
        "waylanddrv" to "The Wayland display driver, used by the experimental Wayland path.",
        "xrandr" to "Display modes and refresh rates under X11.",
        "xrender" to "X11 accelerated drawing.",
        "xim" to "X11 input methods.",
        "xdnd" to "X11 drag and drop.",
        "macdrv" to "The macOS display driver. Not used on Android.",
        "winebrowser" to "Opening links in a browser.",
    )

    /** The specific description when there is one, otherwise an honest category-based line. */
    fun describe(channel: String): String {
        DESCRIPTIONS[channel]?.let { return it }
        val cat = categoryOf(channel)
        val tail = when (cat) {
            CAT_OTHER -> "Debug output from Wine's `$channel` component."
            else -> "${cat.replaceFirstChar { it.uppercase() }} — debug output from Wine's " +
                "`$channel` component."
        }
        return tail
    }

    /** True when [describe] has something specific to say, rather than a category fallback. */
    fun hasDetail(channel: String): Boolean = DESCRIPTIONS.containsKey(channel)

    /**
     * Family for any channel. Prefix and suffix matching, because Wine's naming is consistent
     * enough for it: everything starting "d3d" is graphics, everything starting "crypt" is
     * security, and so on. Order matters — the first match wins.
     */
    fun categoryOf(channel: String): String {
        val c = channel.lowercase()

        if (c in setOf("err", "warn", "fixme", "seh", "relay", "snoop", "trace", "exception",
                "unwind", "debugstr", "debug_buffer", "fixup", "stress", "message", "msg")) return CAT_TRACING

        if (c in setOf("x11drv", "waylanddrv", "macdrv", "xrandr", "xrender", "xim", "xdnd",
                "xvidmode", "alsa", "pulse", "oss", "winebrowser", "winemapi", "wineusb")) return CAT_HOST

        if (c.startsWith("d3d") || c.startsWith("dxg") || c.startsWith("ddraw") ||
            c.startsWith("gdi") || c.startsWith("opengl") || c.startsWith("wgl") ||
            c in setOf("vulkan", "d2d", "dcomp", "dwmapi", "bitblt", "bitmap", "palette", "dxcore",
                "dxva2", "asmshader", "gl_compat", "glu", "graphics", "icm", "image", "region",
                "clipping", "dc", "dciman", "enhmetafile", "metafile", "dxtrans", "dxdiag",
                "wincodecs", "wing", "icon", "imagelist", "cursor", "display", "psdrv", "olepicture",
                "d3drm", "dx8vb", "uianimation", "manipulation")) return CAT_GRAPHICS

        if (c.startsWith("dsound") || c.startsWith("dm") || c.startsWith("xaudio") ||
            c.startsWith("midi") || c.startsWith("mci") && c.contains("wave") ||
            c in setOf("winmm", "mmdevapi", "coreaudio", "wavemap", "sound", "msacm", "mmaux",
                "mmsys", "mmtime", "adpcm", "g711", "gsm", "speech", "sapi", "msttsengine",
                "mp3dmod", "wmadec", "avrt", "mciwave", "mcicda", "mcimidi", "audio")) return CAT_AUDIO

        if (c.startsWith("wmv") || c.startsWith("mpeg") || c.startsWith("msvid") ||
            c in setOf("quartz", "mfplat", "evr", "devenum", "avifile", "avicap", "mciavi",
                "mciqtz", "msvideo", "mmio", "media", "mediacontrol", "capture", "iccvid",
                "ir50_32", "msrle32", "msvidc32", "msmpeg2vdec", "msauddecmft", "dmo", "msdmo",
                "dsdmo", "twain", "sti", "wia", "bytecodewriter", "packager")) return CAT_VIDEO

        if (c.startsWith("dinput") || c.startsWith("xinput") || c.startsWith("wintab") ||
            c in setOf("rawinput", "hid", "joycpl", "keyboard", "input", "usb", "usbd", "winusb",
                "bluetooth", "bluetoothapis", "hotkey", "ndis", "plugplay", "setupapi", "ir",
                "scsiport", "tape", "aspi", "capi", "ctapi32", "smbios", "perception",
                "geolocator", "sensapi", "winscard", "hostname")) return CAT_INPUT

        if (c.startsWith("list") || c.startsWith("combo") || c.startsWith("tool") ||
            c.startsWith("prop") || c.startsWith("tree") ||
            c in setOf("win", "nonclient", "dialog", "menu", "menubuilder", "user", "clipboard",
                "dragdrop", "hook", "theme_scroll", "uxtheme", "commctrl", "commdlg", "edit",
                "button", "statusbar", "systray", "shell", "explorerframe", "shdocvw", "shlctrl",
                "shcore", "browseui", "appbar", "animate", "comboex", "header", "monthcal",
                "pager", "progress", "rebar", "scroll", "static", "syslink", "tab", "trackbar",
                "updown", "ipaddress", "datetime", "taskdialog", "uiribbon", "uiautomation",
                "oleacc", "nstc", "cards", "mdi", "richedit", "richedit_lists", "class", "ui",
                "ninput", "gamebar", "gameux", "gamingtcui", "mmc", "recyclebin", "pidl",
                "selector", "enumeration", "twinapi", "wpc", "htmlhelp", "hlink")) return CAT_UI

        if (c.startsWith("msvc") || c.startsWith("dbghelp") ||
            c in setOf("process", "thread", "threadpool", "module", "loaddll", "unloaddll", "heap",
                "globalmem", "virtual", "sync", "ntdll", "kernelbase", "ntoskrnl", "server",
                "syslevel", "environ", "exec", "toolhelp", "vcruntime", "concrt", "vcomp", "wow",
                "int", "int21", "int31", "vxd", "dosmem", "atom", "handle", "global", "local",
                "context", "thunk", "atlthunk", "atl", "dll", "resource", "ver", "system",
                "dbgeng", "diasymreader", "faultrep", "wer", "rstrtmgr", "apphelp", "fltlib",
                "fltmgr", "driver", "vdmdbg", "event", "eventlog", "wevtapi", "tdh", "pdh",
                "loadperf", "powermgnt", "powrprof", "clusapi", "schedsvc", "mstask", "taskschd",
                "task", "service", "wmi", "wmiutils", "wbemprox", "wbemdisp", "mgmtapi",
                "query", "data", "model", "wldp", "hvsi", "tbs", "amsi")) return CAT_RUNTIME

        if (c in setOf("file", "storage", "volume", "mountmgr", "cabinet", "profile", "path",
                "davclnt", "wofutil", "virtdisk", "sfc", "msisip", "mspatcha", "msopc",
                "wimgapi", "wnet", "mpr", "lanman", "itss", "infosoft")) return CAT_FILES

        if (c.startsWith("dp") || c.startsWith("dhcp") || c.startsWith("snmp") ||
            c.startsWith("wsnmp") || c.startsWith("ras") || c.startsWith("wlan") ||
            c in setOf("winsock", "wininet", "winhttp", "http", "urlmon", "dnsapi", "iphlpapi",
                "netapi32", "rpc", "mswsock", "netbios", "netcfgx", "netio", "netprofm", "nsi",
                "url", "jsproxy", "inetcomm", "inetcpl", "inetmib1", "wldap32", "ldap", "webservices",
                "wsdapi", "qwave", "traffic", "qmgr", "hnetcfg", "fwpuclnt", "mprapi", "rtutils",
                "tdi", "tapi", "wpcap", "wtsapi", "winsta", "winstation", "mapi", "cdosys",
                "wuapi", "connect", "sensapi2", "wsock")) return CAT_NET

        if (c in setOf("reg", "advapi", "policy")) return CAT_REGISTRY

        if (c.startsWith("crypt") || c.startsWith("acl") || c.startsWith("cred") ||
            c in setOf("bcrypt", "ncrypt", "schannel", "secur32", "wintrust", "security", "authz",
                "kerberos", "ntlm", "ksecdd", "sspicli", "msasn1", "dssenh", "pstores", "msdrm",
                "slc", "pidgen", "mssign", "ntdsapi", "activeds", "adsldp", "dsquery", "dsdmo2",
                "dsuiext", "objsel", "msident", "scrobj")) return CAT_SECURITY

        if (c.startsWith("ole") || c.startsWith("msxml") || c.startsWith("msdas") ||
            c in setOf("ole", "combase", "olemalloc", "variant", "xmllite", "vbscript", "jscript",
                "mshtml", "actctx", "sxs", "fusion", "actxprxy", "comsvcs", "msctf", "msctfmonitor",
                "msimtf", "msscript", "scrrun", "odbc", "oledb", "msado15", "xolehlp", "dhtmled",
                "ieframe", "inkobj", "inseng", "propsys", "wintypes", "winstring", "dcom",
                "typelib", "msdasql")) return CAT_COM

        if (c.startsWith("font") || c.startsWith("atm") ||
            c in setOf("dwrite", "text", "string", "nls", "locale", "imm", "t2embed", "fontsub",
                "nativefont", "mlang", "bidi", "msftedit", "atmlib")) return CAT_TEXT

        if (c.startsWith("print") || c.startsWith("spool") || c.startsWith("localsp") ||
            c in setOf("winspool", "compstui", "prntvpt", "ntprint", "winprint", "localui",
                "printui", "spoolss")) return CAT_PRINT

        if (c.startsWith("msi") || c.startsWith("appwiz") || c.startsWith("adv") ||
            c in setOf("appx", "advpack", "difxapi", "updspapi", "setupapi2", "cabinet2",
                "msidb", "msisys")) return CAT_INSTALL

        return CAT_OTHER
    }
}
