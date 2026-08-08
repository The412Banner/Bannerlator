package com.winlator.star.ui.screens

import android.content.Context
import androidx.annotation.StringRes
import com.winlator.star.R

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

    enum class Category(
        @StringRes val titleRes: Int,
        @StringRes val blurbRes: Int,
    ) {
        TRACING(R.string.wine_channel_category_tracing, R.string.wine_channel_category_tracing_blurb),
        GRAPHICS(R.string.wine_channel_category_graphics, R.string.wine_channel_category_graphics_blurb),
        AUDIO(R.string.wine_channel_category_audio, R.string.wine_channel_category_audio_blurb),
        VIDEO(R.string.wine_channel_category_video, R.string.wine_channel_category_video_blurb),
        UI(R.string.wine_channel_category_ui, R.string.wine_channel_category_ui_blurb),
        INPUT(R.string.wine_channel_category_input, R.string.wine_channel_category_input_blurb),
        NETWORK(R.string.wine_channel_category_network, R.string.wine_channel_category_network_blurb),
        FILES(R.string.wine_channel_category_files, R.string.wine_channel_category_files_blurb),
        RUNTIME(R.string.wine_channel_category_runtime, R.string.wine_channel_category_runtime_blurb),
        REGISTRY(R.string.wine_channel_category_registry, R.string.wine_channel_category_registry_blurb),
        SECURITY(R.string.wine_channel_category_security, R.string.wine_channel_category_security_blurb),
        COM(R.string.wine_channel_category_com, R.string.wine_channel_category_com_blurb),
        TEXT(R.string.wine_channel_category_text, R.string.wine_channel_category_text_blurb),
        PRINT(R.string.wine_channel_category_print, R.string.wine_channel_category_print_blurb),
        INSTALL(R.string.wine_channel_category_install, R.string.wine_channel_category_install_blurb),
        HOST(R.string.wine_channel_category_host, R.string.wine_channel_category_host_blurb),
        OTHER(R.string.wine_channel_category_other, R.string.wine_channel_category_other_blurb),
    }

    /** Order the browse dialog groups them in — most useful first, not alphabetical. */
    val CATEGORY_ORDER = listOf(
        Category.TRACING, Category.GRAPHICS, Category.AUDIO, Category.VIDEO, Category.INPUT,
        Category.UI, Category.RUNTIME, Category.FILES, Category.NETWORK, Category.REGISTRY,
        Category.SECURITY, Category.COM, Category.TEXT, Category.PRINT, Category.INSTALL,
        Category.HOST, Category.OTHER,
    )

    fun categoryName(context: Context, category: Category): String = context.getString(category.titleRes)

    /** Short note on what each category is for, shown once above its group. */
    fun categoryBlurb(context: Context, category: Category): String = context.getString(category.blurbRes)

    /**
     * The channels worth describing specifically. Everything absent from this map falls through to
     * the category sentence, which is the point — see the class note.
     */
    private val DESCRIPTIONS: Map<String, Int> = mapOf(
        "err" to R.string.wine_channel_description_err,
        "warn" to R.string.wine_channel_description_warn,
        "fixme" to R.string.wine_channel_description_fixme,
        "seh" to R.string.wine_channel_description_seh,
        "relay" to R.string.wine_channel_description_relay,
        "snoop" to R.string.wine_channel_description_snoop,
        "exception" to R.string.wine_channel_description_exception,
        "unwind" to R.string.wine_channel_description_unwind,
        "debugstr" to R.string.wine_channel_description_debugstr,
        "debug_buffer" to R.string.wine_channel_description_debug_buffer,
        "d3d" to R.string.wine_channel_description_d3d,
        "d3d8" to R.string.wine_channel_description_d3d8,
        "d3d9" to R.string.wine_channel_description_d3d9,
        "d3d10" to R.string.wine_channel_description_d3d10,
        "d3d10core" to R.string.wine_channel_description_d3d10core,
        "d3d11" to R.string.wine_channel_description_d3d11,
        "d3d12" to R.string.wine_channel_description_d3d12,
        "d3d_shader" to R.string.wine_channel_description_d3d_shader,
        "d3d_decl" to R.string.wine_channel_description_d3d_decl,
        "d3dcompiler" to R.string.wine_channel_description_d3dcompiler,
        "d3dx" to R.string.wine_channel_description_d3dx,
        "dxgi" to R.string.wine_channel_description_dxgi,
        "ddraw" to R.string.wine_channel_description_ddraw,
        "vulkan" to R.string.wine_channel_description_vulkan,
        "opengl" to R.string.wine_channel_description_opengl,
        "wgl" to R.string.wine_channel_description_wgl,
        "gl_compat" to R.string.wine_channel_description_gl_compat,
        "gdi" to R.string.wine_channel_description_gdi,
        "gdiplus" to R.string.wine_channel_description_gdiplus,
        "d2d" to R.string.wine_channel_description_d2d,
        "dcomp" to R.string.wine_channel_description_dcomp,
        "dwmapi" to R.string.wine_channel_description_dwmapi,
        "bitblt" to R.string.wine_channel_description_bitblt,
        "bitmap" to R.string.wine_channel_description_bitmap,
        "palette" to R.string.wine_channel_description_palette,
        "dxcore" to R.string.wine_channel_description_dxcore,
        "dxva2" to R.string.wine_channel_description_dxva2,
        "asmshader" to R.string.wine_channel_description_asmshader,
        "dsound" to R.string.wine_channel_description_dsound,
        "dsound3d" to R.string.wine_channel_description_dsound3d,
        "xaudio2" to R.string.wine_channel_description_xaudio2,
        "mmdevapi" to R.string.wine_channel_description_mmdevapi,
        "coreaudio" to R.string.wine_channel_description_coreaudio,
        "winmm" to R.string.wine_channel_description_winmm,
        "midi" to R.string.wine_channel_description_midi,
        "wavemap" to R.string.wine_channel_description_wavemap,
        "sound" to R.string.wine_channel_description_sound,
        "alsa" to R.string.wine_channel_description_alsa,
        "pulse" to R.string.wine_channel_description_pulse,
        "oss" to R.string.wine_channel_description_oss,
        "dmusic" to R.string.wine_channel_description_dmusic,
        "dmsynth" to R.string.wine_channel_description_dmsynth,
        "msacm" to R.string.wine_channel_description_msacm,
        "quartz" to R.string.wine_channel_description_quartz,
        "mfplat" to R.string.wine_channel_description_mfplat,
        "evr" to R.string.wine_channel_description_evr,
        "devenum" to R.string.wine_channel_description_devenum,
        "avifile" to R.string.wine_channel_description_avifile,
        "mciavi" to R.string.wine_channel_description_mciavi,
        "wmvcore" to R.string.wine_channel_description_wmvcore,
        "msvideo" to R.string.wine_channel_description_msvideo,
        "mmio" to R.string.wine_channel_description_mmio,
        "dinput" to R.string.wine_channel_description_dinput,
        "xinput" to R.string.wine_channel_description_xinput,
        "rawinput" to R.string.wine_channel_description_rawinput,
        "hid" to R.string.wine_channel_description_hid,
        "joycpl" to R.string.wine_channel_description_joycpl,
        "keyboard" to R.string.wine_channel_description_keyboard,
        "cursor" to R.string.wine_channel_description_cursor,
        "input" to R.string.wine_channel_description_input,
        "usb" to R.string.wine_channel_description_usb,
        "wintab32" to R.string.wine_channel_description_wintab32,
        "win" to R.string.wine_channel_description_win,
        "message" to R.string.wine_channel_description_message,
        "msg" to R.string.wine_channel_description_msg,
        "nonclient" to R.string.wine_channel_description_nonclient,
        "dialog" to R.string.wine_channel_description_dialog,
        "menu" to R.string.wine_channel_description_menu,
        "user" to R.string.wine_channel_description_user,
        "clipboard" to R.string.wine_channel_description_clipboard,
        "dragdrop" to R.string.wine_channel_description_dragdrop,
        "hook" to R.string.wine_channel_description_hook,
        "theme_scroll" to R.string.wine_channel_description_theme_scroll,
        "uxtheme" to R.string.wine_channel_description_uxtheme,
        "commctrl" to R.string.wine_channel_description_commctrl,
        "commdlg" to R.string.wine_channel_description_commdlg,
        "edit" to R.string.wine_channel_description_edit,
        "listbox" to R.string.wine_channel_description_listbox,
        "listview" to R.string.wine_channel_description_listview,
        "combo" to R.string.wine_channel_description_combo,
        "button" to R.string.wine_channel_description_button,
        "toolbar" to R.string.wine_channel_description_toolbar,
        "tooltips" to R.string.wine_channel_description_tooltips,
        "treeview" to R.string.wine_channel_description_treeview,
        "statusbar" to R.string.wine_channel_description_statusbar,
        "systray" to R.string.wine_channel_description_systray,
        "shell" to R.string.wine_channel_description_shell,
        "explorerframe" to R.string.wine_channel_description_explorerframe,
        "process" to R.string.wine_channel_description_process,
        "thread" to R.string.wine_channel_description_thread,
        "threadpool" to R.string.wine_channel_description_threadpool,
        "module" to R.string.wine_channel_description_module,
        "loaddll" to R.string.wine_channel_description_loaddll,
        "unloaddll" to R.string.wine_channel_description_unloaddll,
        "heap" to R.string.wine_channel_description_heap,
        "globalmem" to R.string.wine_channel_description_globalmem,
        "sync" to R.string.wine_channel_description_sync,
        "ntdll" to R.string.wine_channel_description_ntdll,
        "kernelbase" to R.string.wine_channel_description_kernelbase,
        "ntoskrnl" to R.string.wine_channel_description_ntoskrnl,
        "syslevel" to R.string.wine_channel_description_syslevel,
        "environ" to R.string.wine_channel_description_environ,
        "exec" to R.string.wine_channel_description_exec,
        "toolhelp" to R.string.wine_channel_description_toolhelp,
        "vcruntime" to R.string.wine_channel_description_vcruntime,
        "msvcrt" to R.string.wine_channel_description_msvcrt,
        "msvcp" to R.string.wine_channel_description_msvcp,
        "concrt" to R.string.wine_channel_description_concrt,
        "vcomp" to R.string.wine_channel_description_vcomp,
        "wow" to R.string.wine_channel_description_wow,
        "int" to R.string.wine_channel_description_int,
        "int21" to R.string.wine_channel_description_int21,
        "vxd" to R.string.wine_channel_description_vxd,
        "file" to R.string.wine_channel_description_file,
        "reg" to R.string.wine_channel_description_reg,
        "storage" to R.string.wine_channel_description_storage,
        "volume" to R.string.wine_channel_description_volume,
        "mountmgr" to R.string.wine_channel_description_mountmgr,
        "cabinet" to R.string.wine_channel_description_cabinet,
        "profile" to R.string.wine_channel_description_profile,
        "path" to R.string.wine_channel_description_path,
        "dosmem" to R.string.wine_channel_description_dosmem,
        "winsock" to R.string.wine_channel_description_winsock,
        "wininet" to R.string.wine_channel_description_wininet,
        "winhttp" to R.string.wine_channel_description_winhttp,
        "http" to R.string.wine_channel_description_http,
        "urlmon" to R.string.wine_channel_description_urlmon,
        "dnsapi" to R.string.wine_channel_description_dnsapi,
        "iphlpapi" to R.string.wine_channel_description_iphlpapi,
        "netapi32" to R.string.wine_channel_description_netapi32,
        "dplay" to R.string.wine_channel_description_dplay,
        "dpnet" to R.string.wine_channel_description_dpnet,
        "rpc" to R.string.wine_channel_description_rpc,
        "mswsock" to R.string.wine_channel_description_mswsock,
        "crypt" to R.string.wine_channel_description_crypt,
        "crypto" to R.string.wine_channel_description_crypto,
        "bcrypt" to R.string.wine_channel_description_bcrypt,
        "ncrypt" to R.string.wine_channel_description_ncrypt,
        "schannel" to R.string.wine_channel_description_schannel,
        "secur32" to R.string.wine_channel_description_secur32,
        "wintrust" to R.string.wine_channel_description_wintrust,
        "cred" to R.string.wine_channel_description_cred,
        "security" to R.string.wine_channel_description_security,
        "advapi" to R.string.wine_channel_description_advapi,
        "ole" to R.string.wine_channel_description_ole,
        "combase" to R.string.wine_channel_description_combase,
        "olemalloc" to R.string.wine_channel_description_olemalloc,
        "variant" to R.string.wine_channel_description_variant,
        "msi" to R.string.wine_channel_description_msi,
        "msxml" to R.string.wine_channel_description_msxml,
        "xmllite" to R.string.wine_channel_description_xmllite,
        "vbscript" to R.string.wine_channel_description_vbscript,
        "jscript" to R.string.wine_channel_description_jscript,
        "mshtml" to R.string.wine_channel_description_mshtml,
        "actctx" to R.string.wine_channel_description_actctx,
        "sxs" to R.string.wine_channel_description_sxs,
        "fusion" to R.string.wine_channel_description_fusion,
        "font" to R.string.wine_channel_description_font,
        "dwrite" to R.string.wine_channel_description_dwrite,
        "fontcache" to R.string.wine_channel_description_fontcache,
        "text" to R.string.wine_channel_description_text,
        "string" to R.string.wine_channel_description_string,
        "nls" to R.string.wine_channel_description_nls,
        "locale" to R.string.wine_channel_description_locale,
        "imm" to R.string.wine_channel_description_imm,
        "print" to R.string.wine_channel_description_print,
        "winspool" to R.string.wine_channel_description_winspool,
        "x11drv" to R.string.wine_channel_description_x11drv,
        "waylanddrv" to R.string.wine_channel_description_waylanddrv,
        "xrandr" to R.string.wine_channel_description_xrandr,
        "xrender" to R.string.wine_channel_description_xrender,
        "xim" to R.string.wine_channel_description_xim,
        "xdnd" to R.string.wine_channel_description_xdnd,
        "macdrv" to R.string.wine_channel_description_macdrv,
        "winebrowser" to R.string.wine_channel_description_winebrowser,
    )

    /** The specific description when there is one, otherwise an honest category-based line. */
    fun describe(context: Context, channel: String): String {
        DESCRIPTIONS[channel]?.let { return context.getString(it) }
        val cat = categoryOf(channel)
        return when (cat) {
            Category.OTHER -> context.getString(R.string.wine_channel_fallback_other, channel)
            else -> context.getString(
                R.string.wine_channel_fallback_category,
                categoryName(context, cat),
                channel,
            )
        }
    }

    /** True when [describe] has something specific to say, rather than a category fallback. */
    fun hasDetail(channel: String): Boolean = DESCRIPTIONS.containsKey(channel)

    /**
     * Family for any channel. Prefix and suffix matching, because Wine's naming is consistent
     * enough for it: everything starting "d3d" is graphics, everything starting "crypt" is
     * security, and so on. Order matters — the first match wins.
     */
    fun categoryOf(channel: String): Category {
        val c = channel.lowercase()

        if (c in setOf("err", "warn", "fixme", "seh", "relay", "snoop", "trace", "exception",
                "unwind", "debugstr", "debug_buffer", "fixup", "stress", "message", "msg")) return Category.TRACING

        if (c in setOf("x11drv", "waylanddrv", "macdrv", "xrandr", "xrender", "xim", "xdnd",
                "xvidmode", "alsa", "pulse", "oss", "winebrowser", "winemapi", "wineusb")) return Category.HOST

        if (c.startsWith("d3d") || c.startsWith("dxg") || c.startsWith("ddraw") ||
            c.startsWith("gdi") || c.startsWith("opengl") || c.startsWith("wgl") ||
            c in setOf("vulkan", "d2d", "dcomp", "dwmapi", "bitblt", "bitmap", "palette", "dxcore",
                "dxva2", "asmshader", "gl_compat", "glu", "graphics", "icm", "image", "region",
                "clipping", "dc", "dciman", "enhmetafile", "metafile", "dxtrans", "dxdiag",
                "wincodecs", "wing", "icon", "imagelist", "cursor", "display", "psdrv", "olepicture",
                "d3drm", "dx8vb", "uianimation", "manipulation")) return Category.GRAPHICS

        if (c.startsWith("dsound") || c.startsWith("dm") || c.startsWith("xaudio") ||
            c.startsWith("midi") || c.startsWith("mci") && c.contains("wave") ||
            c in setOf("winmm", "mmdevapi", "coreaudio", "wavemap", "sound", "msacm", "mmaux",
                "mmsys", "mmtime", "adpcm", "g711", "gsm", "speech", "sapi", "msttsengine",
                "mp3dmod", "wmadec", "avrt", "mciwave", "mcicda", "mcimidi", "audio")) return Category.AUDIO

        if (c.startsWith("wmv") || c.startsWith("mpeg") || c.startsWith("msvid") ||
            c in setOf("quartz", "mfplat", "evr", "devenum", "avifile", "avicap", "mciavi",
                "mciqtz", "msvideo", "mmio", "media", "mediacontrol", "capture", "iccvid",
                "ir50_32", "msrle32", "msvidc32", "msmpeg2vdec", "msauddecmft", "dmo", "msdmo",
                "dsdmo", "twain", "sti", "wia", "bytecodewriter", "packager")) return Category.VIDEO

        if (c.startsWith("dinput") || c.startsWith("xinput") || c.startsWith("wintab") ||
            c in setOf("rawinput", "hid", "joycpl", "keyboard", "input", "usb", "usbd", "winusb",
                "bluetooth", "bluetoothapis", "hotkey", "ndis", "plugplay", "setupapi", "ir",
                "scsiport", "tape", "aspi", "capi", "ctapi32", "smbios", "perception",
                "geolocator", "sensapi", "winscard", "hostname")) return Category.INPUT

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
                "selector", "enumeration", "twinapi", "wpc", "htmlhelp", "hlink")) return Category.UI

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
                "query", "data", "model", "wldp", "hvsi", "tbs", "amsi")) return Category.RUNTIME

        if (c in setOf("file", "storage", "volume", "mountmgr", "cabinet", "profile", "path",
                "davclnt", "wofutil", "virtdisk", "sfc", "msisip", "mspatcha", "msopc",
                "wimgapi", "wnet", "mpr", "lanman", "itss", "infosoft")) return Category.FILES

        if (c.startsWith("dp") || c.startsWith("dhcp") || c.startsWith("snmp") ||
            c.startsWith("wsnmp") || c.startsWith("ras") || c.startsWith("wlan") ||
            c in setOf("winsock", "wininet", "winhttp", "http", "urlmon", "dnsapi", "iphlpapi",
                "netapi32", "rpc", "mswsock", "netbios", "netcfgx", "netio", "netprofm", "nsi",
                "url", "jsproxy", "inetcomm", "inetcpl", "inetmib1", "wldap32", "ldap", "webservices",
                "wsdapi", "qwave", "traffic", "qmgr", "hnetcfg", "fwpuclnt", "mprapi", "rtutils",
                "tdi", "tapi", "wpcap", "wtsapi", "winsta", "winstation", "mapi", "cdosys",
                "wuapi", "connect", "sensapi2", "wsock")) return Category.NETWORK

        if (c in setOf("reg", "advapi", "policy")) return Category.REGISTRY

        if (c.startsWith("crypt") || c.startsWith("acl") || c.startsWith("cred") ||
            c in setOf("bcrypt", "ncrypt", "schannel", "secur32", "wintrust", "security", "authz",
                "kerberos", "ntlm", "ksecdd", "sspicli", "msasn1", "dssenh", "pstores", "msdrm",
                "slc", "pidgen", "mssign", "ntdsapi", "activeds", "adsldp", "dsquery", "dsdmo2",
                "dsuiext", "objsel", "msident", "scrobj")) return Category.SECURITY

        if (c.startsWith("ole") || c.startsWith("msxml") || c.startsWith("msdas") ||
            c in setOf("ole", "combase", "olemalloc", "variant", "xmllite", "vbscript", "jscript",
                "mshtml", "actctx", "sxs", "fusion", "actxprxy", "comsvcs", "msctf", "msctfmonitor",
                "msimtf", "msscript", "scrrun", "odbc", "oledb", "msado15", "xolehlp", "dhtmled",
                "ieframe", "inkobj", "inseng", "propsys", "wintypes", "winstring", "dcom",
                "typelib", "msdasql")) return Category.COM

        if (c.startsWith("font") || c.startsWith("atm") ||
            c in setOf("dwrite", "text", "string", "nls", "locale", "imm", "t2embed", "fontsub",
                "nativefont", "mlang", "bidi", "msftedit", "atmlib")) return Category.TEXT

        if (c.startsWith("print") || c.startsWith("spool") || c.startsWith("localsp") ||
            c in setOf("winspool", "compstui", "prntvpt", "ntprint", "winprint", "localui",
                "printui", "spoolss")) return Category.PRINT

        if (c.startsWith("msi") || c.startsWith("appwiz") || c.startsWith("adv") ||
            c in setOf("appx", "advpack", "difxapi", "updspapi", "setupapi2", "cabinet2",
                "msidb", "msisys")) return Category.INSTALL

        return Category.OTHER
    }
}
