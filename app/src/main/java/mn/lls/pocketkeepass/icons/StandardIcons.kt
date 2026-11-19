package mn.lls.pocketkeepass.icons

import androidx.annotation.DrawableRes
import mn.lls.pocketkeepass.R

/**
 * KeePass's 69 standard icons (`IconID` 0 to 68), in order. The images aren't in the file and
 * KeePass's own artwork isn't ours to copy, so each is the closest Material icon. The comments
 * name KeePass's icon; each drawable names the Material one it's drawn from.
 */
private val standardIcons = intArrayOf(
    R.drawable.ic_kp_00, // 0 Key
    R.drawable.ic_kp_01, // 1 World
    R.drawable.ic_kp_02, // 2 Warning
    R.drawable.ic_kp_03, // 3 Network server
    R.drawable.ic_kp_04, // 4 Marked folder
    R.drawable.ic_kp_05, // 5 User communication
    R.drawable.ic_kp_06, // 6 Parts
    R.drawable.ic_kp_07, // 7 Notepad
    R.drawable.ic_kp_08, // 8 World socket
    R.drawable.ic_kp_09, // 9 Identity
    R.drawable.ic_kp_10, // 10 Paper ready
    R.drawable.ic_kp_11, // 11 Camera
    R.drawable.ic_kp_12, // 12 Infrared communication
    R.drawable.ic_kp_13, // 13 Several keys
    R.drawable.ic_kp_14, // 14 Energy
    R.drawable.ic_kp_15, // 15 Scanner
    R.drawable.ic_kp_16, // 16 World star
    R.drawable.ic_kp_17, // 17 CD
    R.drawable.ic_kp_18, // 18 Monitor
    R.drawable.ic_kp_19, // 19 Email
    R.drawable.ic_kp_20, // 20 Configuration
    R.drawable.ic_kp_21, // 21 Clipboard ready
    R.drawable.ic_kp_22, // 22 New paper
    R.drawable.ic_kp_23, // 23 Screen
    R.drawable.ic_kp_24, // 24 Energy careful
    R.drawable.ic_kp_25, // 25 Mailbox
    R.drawable.ic_kp_26, // 26 Disk
    R.drawable.ic_kp_27, // 27 Drive
    R.drawable.ic_kp_28, // 28 Paper question
    R.drawable.ic_kp_29, // 29 Encrypted terminal
    R.drawable.ic_kp_30, // 30 Console
    R.drawable.ic_kp_31, // 31 Printer
    R.drawable.ic_kp_32, // 32 Program icons
    R.drawable.ic_kp_33, // 33 Run
    R.drawable.ic_kp_34, // 34 Settings
    R.drawable.ic_kp_35, // 35 World computer
    R.drawable.ic_kp_36, // 36 Archive
    R.drawable.ic_kp_37, // 37 Home banking
    R.drawable.ic_kp_38, // 38 Windows drive
    R.drawable.ic_kp_39, // 39 Clock
    R.drawable.ic_kp_40, // 40 Email search
    R.drawable.ic_kp_41, // 41 Flagged paper
    R.drawable.ic_kp_42, // 42 Memory
    R.drawable.ic_kp_43, // 43 Recycle bin
    R.drawable.ic_kp_44, // 44 Note
    R.drawable.ic_kp_45, // 45 Expired
    R.drawable.ic_kp_46, // 46 Info
    R.drawable.ic_kp_47, // 47 Package
    R.drawable.ic_kp_48, // 48 Folder
    R.drawable.ic_kp_49, // 49 Open folder
    R.drawable.ic_kp_50, // 50 Folder package
    R.drawable.ic_kp_51, // 51 Open lock
    R.drawable.ic_kp_52, // 52 Locked paper
    R.drawable.ic_kp_53, // 53 Checked
    R.drawable.ic_kp_54, // 54 Pen
    R.drawable.ic_kp_55, // 55 Thumbnail
    R.drawable.ic_kp_56, // 56 Book
    R.drawable.ic_kp_57, // 57 List
    R.drawable.ic_kp_58, // 58 User key
    R.drawable.ic_kp_59, // 59 Tool
    R.drawable.ic_kp_60, // 60 Home
    R.drawable.ic_kp_61, // 61 Star
    R.drawable.ic_kp_62, // 62 Tux
    R.drawable.ic_kp_63, // 63 Feather
    R.drawable.ic_kp_64, // 64 Apple
    R.drawable.ic_kp_65, // 65 Wiki
    R.drawable.ic_kp_66, // 66 Money
    R.drawable.ic_kp_67, // 67 Certificate
    R.drawable.ic_kp_68, // 68 BlackBerry
)

/** The drawing for a KeePass standard icon number. The engine only hands out 0 to 68. */
@DrawableRes
fun standardIcon(number: UInt): Int = standardIcons.getOrElse(number.toInt()) { R.drawable.ic_kp_00 }
