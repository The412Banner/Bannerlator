package com.winlator.star.linux

import android.content.Context
import android.system.Os
import android.util.Log
import com.winlator.star.core.FileUtils
import java.io.File

/**
 * The session's pad, described to the Steam client as a Steam Deck controller.
 *
 * On a Deck - and on the handhelds InputPlumber serves as one - the client finds the controller
 * through libudev as a `hidraw` device under a `hid` device under a USB interface and reads it
 * itself. libfakeinput serves the device node (`/dev/hidraw16`, built from the pad's input ring);
 * what the client also needs is the sysfs it walks to find and identify that node, which a sandbox
 * shows nothing of. This writes that sysfs as the kernel lays out a Deck's controller interface and
 * returns the binds that put it in place:
 *
 * - `/sys/devices/bannerlator/usb1` - the USB device (Valve, 28de:1205), its interface 2, the HID
 *   device on it (`HID_ID`, `report_descriptor`) and that device's `hidraw/hidraw16`;
 * - `/sys/class/hidraw/hidraw16` - what an enumeration of the `hidraw` subsystem lists;
 * - `/sys/dev/char/240:16` - how the node's device number is looked up (in the directory the
 *   runtime binds there for the GPU, LinuxRuntime.bindGpuNode, which binds it for this alone on a
 *   device with no KGSL node);
 * - `/run/udev/data/c240:16` - the udev database entry that marks the device initialised, without
 *   which libudev's enumeration leaves a device node out;
 * - [listingDir] - stand-ins for the listings of `/sys`, `/sys/class` and `/sys/bus`. An app under
 *   an enforcing SELinux policy (every retail phone) may not list those directories, and libudev
 *   abandons its whole scan when it cannot, so the client never reached `hidraw`; libfakeinput
 *   lists these to the client instead when the real listing is refused (FAKE_DECK_SYSFS_LISTING).
 *
 * Symlinks are absolute guest paths; proot resolves them inside the session.
 * (From Droid-Deck/DroidDeck #86 and its SELinux and sysfs-layout fixes.)
 */
object SteamDeckPad {
    private const val TAG = "SteamDeckPad"
    /** Must match libfakeinput (fakeinput_steam.cpp: DECK_HIDRAW_*). */
    private const val MAJOR = 240
    private const val MINOR = 16
    private const val NODE = "hidraw$MINOR"
    /** The node's entry under `/sys/dev/char`. */
    const val CHAR_DEV = "$MAJOR:$MINOR"
    /** Must match libfakeinput (is_deck_sysfs_path, DECK_SERIAL, HIDIOCGRAWPHYS). */
    private const val GUEST_DEVICES = "/sys/devices/bannerlator"
    private const val USB = "$GUEST_DEVICES/usb1"
    private const val INTERFACE = "$USB/1-1:1.2"
    private const val HID = "$INTERFACE/0003:28DE:1205.0001"
    private const val HIDRAW = "$HID/hidraw/$NODE"
    private const val SERIAL = "BANNERLATOR01"
    private const val PHYS = "usb-bannerlator-1/input2"

    /** InputPlumber's CONTROLLER_DESCRIPTOR, as libfakeinput answers HIDIOCGRDESC. */
    private val REPORT_DESCRIPTOR = intArrayOf(
        0x06, 0xff, 0xff, 0x09, 0x01, 0xa1, 0x01, 0x09, 0x02, 0x09, 0x03, 0x15, 0x00,
        0x26, 0xff, 0x00, 0x75, 0x08, 0x95, 0x40, 0x81, 0x02, 0x09, 0x06, 0x09, 0x07,
        0x15, 0x00, 0x26, 0xff, 0x00, 0x75, 0x08, 0x95, 0x40, 0xb1, 0x02, 0xc0,
    ).map { it.toByte() }.toByteArray()

    /** Where [prepare] puts the stand-in listings; seen in the guest at the same path. */
    @JvmStatic
    fun listingDir(sessionRoot: File) = File(sessionRoot, "sys/deck/listing")

    /** The `/sys/dev/char` entry, in the folder the runtime binds there (LinuxRuntime.bindGpuNode). */
    private fun charDevLink(context: Context) = File(context.cacheDir, "drm/sys/$CHAR_DEV")

    /** Writes the tree and returns the `host:guest` binds for it, or none if it could not be made. */
    @JvmStatic
    fun prepare(context: Context, sessionRoot: File): List<String> {
        val base = File(sessionRoot, "sys/deck")
        val devices = File(base, "devices")
        val hidrawClass = File(base, "class-hidraw")
        return try {
            FileUtils.clear(base)
            fun dir(guest: String) = File(devices, guest.removePrefix("$GUEST_DEVICES/")).apply { mkdirs() }
            fun write(guest: String, name: String, text: String) = File(dir(guest), name).writeText(text)
            fun link(file: File, target: String) {
                file.parentFile?.mkdirs()
                file.delete()
                Os.symlink(target, file.path)
            }

            write(USB, "uevent", "DEVTYPE=usb_device\nPRODUCT=28de/1205/100\nTYPE=0/0/0\nBUSNUM=001\nDEVNUM=002\n")
            write(USB, "idVendor", "28de\n")
            write(USB, "idProduct", "1205\n")
            write(USB, "bcdDevice", "0100\n")
            write(USB, "manufacturer", "Valve Software\n")
            write(USB, "product", "Steam Deck Controller\n")
            write(USB, "serial", "$SERIAL\n")
            link(File(dir(USB), "subsystem"), "/sys/bus/usb")

            write(INTERFACE, "uevent", "DEVTYPE=usb_interface\nPRODUCT=28de/1205/100\nINTERFACE=3/0/0\n")
            write(INTERFACE, "bInterfaceNumber", "02\n")
            write(INTERFACE, "bInterfaceClass", "03\n")
            link(File(dir(INTERFACE), "subsystem"), "/sys/bus/usb")

            write(HID, "uevent", "DRIVER=hid-steam\nHID_ID=0003:000028DE:00001205\n" +
                "HID_NAME=Valve Software Steam Deck Controller\nHID_PHYS=$PHYS\n" +
                "HID_UNIQ=$SERIAL\nMODALIAS=hid:b0003g0001v000028DEp00001205\n")
            File(dir(HID), "report_descriptor").writeBytes(REPORT_DESCRIPTOR)
            link(File(dir(HID), "subsystem"), "/sys/bus/hid")

            write(HIDRAW, "uevent", "MAJOR=$MAJOR\nMINOR=$MINOR\nDEVNAME=$NODE\n")
            write(HIDRAW, "dev", "$MAJOR:$MINOR\n")
            link(File(dir(HIDRAW), "subsystem"), "/sys/class/hidraw")
            link(File(dir(HIDRAW), "device"), HID)

            hidrawClass.mkdirs()
            link(File(hidrawClass, NODE), HIDRAW)

            // Only the names matter: libudev reads a listing for the subsystems to descend into and
            // then opens /sys/class/hidraw itself, which is the bind above.
            val listing = listingDir(sessionRoot)
            for (name in listOf("sys/bus", "sys/class", "sys/devices", "class/hidraw", "bus")) File(listing, name).mkdirs()
            link(charDevLink(context), HIDRAW)

            val udevData = File(LinuxRuntime.rootDir(context), "run/udev/data").apply { mkdirs() }
            File(udevData, "c$MAJOR:$MINOR").writeText("I:1\nE:ID_INPUT=1\nE:ID_INPUT_JOYSTICK=1\n")

            Log.i(TAG, "/dev/$NODE described as a Steam Deck controller (28de:1205)")
            listOf(devices.path + ":" + GUEST_DEVICES, hidrawClass.path + ":/sys/class/hidraw")
        } catch (e: Exception) {
            Log.w(TAG, "could not describe the pad as a Deck controller: $e")
            forget(context)
            emptyList()
        }
    }

    /**
     * Takes the `/sys/dev/char` entry down for a session without the Deck, so a device with no
     * KGSL node does not get that folder bound for an entry left by an earlier session.
     */
    @JvmStatic
    fun forget(context: Context) {
        charDevLink(context).delete()
    }
}
