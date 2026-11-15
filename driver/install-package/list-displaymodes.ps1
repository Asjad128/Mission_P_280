# List-DisplayModes.ps1
# Lists, for every active display, the modes Windows accepts from its driver,
# including raw modes that the Settings page may hide.
# Run in normal PowerShell (admin not needed):
#   powershell -NoProfile -ExecutionPolicy Bypass -File .\List-DisplayModes.ps1

Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class DispModes {
  [StructLayout(LayoutKind.Sequential, CharSet=CharSet.Ansi)]
  public struct DEVMODE {
    [MarshalAs(UnmanagedType.ByValTStr, SizeConst=32)] public string dmDeviceName;
    public short dmSpecVersion; public short dmDriverVersion; public short dmSize; public short dmDriverExtra;
    public int dmFields;
    public int dmPositionX; public int dmPositionY; public int dmDisplayOrientation; public int dmDisplayFixedOutput;
    public short dmColor; public short dmDuplex; public short dmYResolution; public short dmTTOption; public short dmCollate;
    [MarshalAs(UnmanagedType.ByValTStr, SizeConst=32)] public string dmFormName;
    public short dmLogPixels; public int dmBitsPerPel; public int dmPelsWidth; public int dmPelsHeight;
    public int dmDisplayFlags; public int dmDisplayFrequency; public int dmICMMethod; public int dmICMIntent;
    public int dmMediaType; public int dmDitherType; public int dmReserved1; public int dmReserved2;
    public int dmPanningWidth; public int dmPanningHeight;
  }
  [StructLayout(LayoutKind.Sequential, CharSet=CharSet.Ansi)]
  public struct DISPLAY_DEVICE {
    public int cb;
    [MarshalAs(UnmanagedType.ByValTStr, SizeConst=32)] public string DeviceName;
    [MarshalAs(UnmanagedType.ByValTStr, SizeConst=128)] public string DeviceString;
    public int StateFlags;
    [MarshalAs(UnmanagedType.ByValTStr, SizeConst=128)] public string DeviceID;
    [MarshalAs(UnmanagedType.ByValTStr, SizeConst=128)] public string DeviceKey;
  }
  [DllImport("user32.dll", CharSet=CharSet.Ansi)] public static extern bool EnumDisplayDevices(string dev, int idx, ref DISPLAY_DEVICE dd, int flags);
  [DllImport("user32.dll", CharSet=CharSet.Ansi)] public static extern bool EnumDisplaySettingsEx(string dev, int mode, ref DEVMODE dm, int flags);

  public static string[] Adapters() {
    var list = new System.Collections.Generic.List<string>();
    for (int i = 0; ; i++) {
      var dd = new DISPLAY_DEVICE(); dd.cb = Marshal.SizeOf(typeof(DISPLAY_DEVICE));
      if (!EnumDisplayDevices(null, i, ref dd, 0)) break;
      if ((dd.StateFlags & 1) != 0) list.Add(dd.DeviceName + "|" + dd.DeviceString + "|" + ((dd.StateFlags & 4) != 0 ? "PRIMARY" : ""));
    }
    return list.ToArray();
  }
  public static string[] Modes(string dev, bool raw) {
    var set = new System.Collections.Generic.SortedSet<string>();
    for (int i = 0; ; i++) {
      var dm = new DEVMODE(); dm.dmSize = (short)Marshal.SizeOf(typeof(DEVMODE));
      if (!EnumDisplaySettingsEx(dev, i, ref dm, raw ? 2 : 0)) break;
      if (dm.dmBitsPerPel != 32) continue;
      set.Add(string.Format("{0,5} x {1,-5} @ {2} Hz", dm.dmPelsWidth, dm.dmPelsHeight, dm.dmDisplayFrequency));
    }
    var arr = new string[set.Count]; set.CopyTo(arr); return arr;
  }
  public static string Current(string dev) {
    var dm = new DEVMODE(); dm.dmSize = (short)Marshal.SizeOf(typeof(DEVMODE));
    if (!EnumDisplaySettingsEx(dev, -1, ref dm, 0)) return "unknown";
    return string.Format("{0} x {1} @ {2} Hz", dm.dmPelsWidth, dm.dmPelsHeight, dm.dmDisplayFrequency);
  }
}
'@

foreach ($a in [DispModes]::Adapters()) {
    $p = $a.Split('|')
    "=== $($p[0])  [$($p[1])] $($p[2])"
    "Current mode: " + [DispModes]::Current($p[0])
    $normal = [DispModes]::Modes($p[0], $false)
    $raw    = [DispModes]::Modes($p[0], $true)
    "Modes (normal list), 32-bit: $($normal.Count)"
    $normal | ForEach-Object { "   $_" }
    $extra = $raw | Where-Object { $normal -notcontains $_ }
    if ($extra) { "Extra raw modes hidden from the normal list: $(@($extra).Count)"; $extra | ForEach-Object { "   $_" } }
    ""
}