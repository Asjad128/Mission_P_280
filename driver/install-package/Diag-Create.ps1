$ErrorActionPreference = 'Stop'
$inf  = 'F:\Docs-Files\Mission_P_280\driver\install-package\IddSampleDriver.inf'
$hwid = 'Root\IddSampleDriver'
"64-bit process: " + [Environment]::Is64BitProcess

Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
using System.Text;
public static class Dev2 {
  [StructLayout(LayoutKind.Sequential)]
  public struct SP_DEVINFO_DATA { public int cbSize; public Guid ClassGuid; public int DevInst; public IntPtr Reserved; }
  [DllImport("setupapi.dll", CharSet=CharSet.Unicode, SetLastError=true)]
  static extern bool SetupDiGetINFClass(string inf, ref Guid g, StringBuilder name, int size, out int req);
  [DllImport("setupapi.dll", SetLastError=true)]
  static extern IntPtr SetupDiCreateDeviceInfoList(ref Guid g, IntPtr hwnd);
  [DllImport("setupapi.dll", CharSet=CharSet.Unicode, SetLastError=true)]
  static extern bool SetupDiCreateDeviceInfo(IntPtr set, string name, ref Guid g, string desc, IntPtr hwnd, int flags, ref SP_DEVINFO_DATA d);
  [DllImport("setupapi.dll", CharSet=CharSet.Unicode, SetLastError=true)]
  static extern bool SetupDiGetDeviceInstanceId(IntPtr set, ref SP_DEVINFO_DATA d, StringBuilder id, int size, out int req);
  [DllImport("setupapi.dll", SetLastError=true)]
  static extern bool SetupDiSetDeviceRegistryProperty(IntPtr set, ref SP_DEVINFO_DATA d, int prop, byte[] buf, int size);
  [DllImport("setupapi.dll", SetLastError=true)]
  static extern bool SetupDiCallClassInstaller(int fn, IntPtr set, ref SP_DEVINFO_DATA d);
  [DllImport("setupapi.dll", SetLastError=true)]
  static extern bool SetupDiDestroyDeviceInfoList(IntPtr set);
  public static string Id = "";
  static string Err(string what) { return what + " FAILED, error 0x" + Marshal.GetLastWin32Error().ToString("X"); }
  public static string Run(string inf, string hwid) {
    var log = new StringBuilder();
    Guid g = Guid.Empty; var name = new StringBuilder(64); int req;
    if (!SetupDiGetINFClass(inf, ref g, name, 64, out req)) return Err("GetINFClass");
    log.AppendLine("INF class: " + name + " " + g);
    IntPtr set = SetupDiCreateDeviceInfoList(ref g, IntPtr.Zero);
    if (set == new IntPtr(-1)) return Err("CreateDeviceInfoList");
    var d = new SP_DEVINFO_DATA(); d.cbSize = Marshal.SizeOf(typeof(SP_DEVINFO_DATA));
    log.AppendLine("SP_DEVINFO_DATA size: " + d.cbSize);
    if (!SetupDiCreateDeviceInfo(set, name.ToString(), ref g, null, IntPtr.Zero, 1, ref d)) return log + Err("CreateDeviceInfo");
    var sb = new StringBuilder(260);
    if (SetupDiGetDeviceInstanceId(set, ref d, sb, 260, out req)) { Id = sb.ToString(); log.AppendLine("Instance ID: " + Id); } else log.AppendLine(Err("GetDeviceInstanceId"));
    byte[] b = Encoding.Unicode.GetBytes(hwid + "\0\0");
    if (!SetupDiSetDeviceRegistryProperty(set, ref d, 1, b, b.Length)) return log + Err("SetHardwareID");
    log.AppendLine("Hardware ID set: OK");
    if (!SetupDiCallClassInstaller(0x19, set, ref d)) return log + Err("DIF_REGISTERDEVICE");
    log.AppendLine("DIF_REGISTERDEVICE: OK");
    SetupDiDestroyDeviceInfoList(set);
    return log.ToString();
  }
}
'@

[Dev2]::Run($inf, $hwid)
$id = [Dev2]::Id
if ($id) {
  "Registry key exists: " + (Test-Path "HKLM:\SYSTEM\CurrentControlSet\Enum\$id")
  pnputil /enum-devices /instanceid $id
}