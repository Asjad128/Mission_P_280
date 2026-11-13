$ErrorActionPreference = 'Stop'
$inf  = 'F:\Docs-Files\Mission_P_280\driver\install-package\IddSampleDriver.inf'
$hwid = 'Root\IddSampleDriver'
if (-not ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) { throw 'Not elevated' }

"--- Removing broken device ROOT\DISPLAY\0001 ---"
pnputil /remove-device 'ROOT\DISPLAY\0001'

Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
using System.Text;
public static class Dev3 {
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
  [DllImport("setupapi.dll", CharSet=CharSet.Unicode, SetLastError=true)]
  static extern bool SetupDiSetDeviceRegistryProperty(IntPtr set, ref SP_DEVINFO_DATA d, int prop, byte[] buf, int size);
  [DllImport("setupapi.dll", SetLastError=true)]
  static extern bool SetupDiCallClassInstaller(int fn, IntPtr set, ref SP_DEVINFO_DATA d);
  [DllImport("setupapi.dll", SetLastError=true)]
  static extern bool SetupDiDestroyDeviceInfoList(IntPtr set);
  public static string Id = "";
  static string Err(string what) { return what + " FAILED, error 0x" + Marshal.GetLastWin32Error().ToString("X"); }
  public static string Run(string inf, string hwid) {
    Guid g = Guid.Empty; var name = new StringBuilder(64); int req;
    if (!SetupDiGetINFClass(inf, ref g, name, 64, out req)) return Err("GetINFClass");
    IntPtr set = SetupDiCreateDeviceInfoList(ref g, IntPtr.Zero);
    if (set == new IntPtr(-1)) return Err("CreateDeviceInfoList");
    var d = new SP_DEVINFO_DATA(); d.cbSize = Marshal.SizeOf(typeof(SP_DEVINFO_DATA));
    if (!SetupDiCreateDeviceInfo(set, name.ToString(), ref g, null, IntPtr.Zero, 1, ref d)) return Err("CreateDeviceInfo");
    var sb = new StringBuilder(260);
    if (SetupDiGetDeviceInstanceId(set, ref d, sb, 260, out req)) Id = sb.ToString();
    byte[] b = Encoding.Unicode.GetBytes(hwid + "\0\0");
    if (!SetupDiSetDeviceRegistryProperty(set, ref d, 1, b, b.Length)) return Err("SetHardwareID");
    if (!SetupDiCallClassInstaller(0x19, set, ref d)) return Err("DIF_REGISTERDEVICE");
    SetupDiDestroyDeviceInfoList(set);
    return "OK";
  }
}
'@

"--- Creating device ---"
"Create: " + [Dev3]::Run($inf, $hwid)
$id = [Dev3]::Id
"Instance ID: $id"
if (-not $id) { throw 'No instance ID returned' }

$stored = @((Get-ItemProperty "HKLM:\SYSTEM\CurrentControlSet\Enum\$id").HardwareID)
"Stored HardwareID entries: " + $stored.Count + " -> " + ($stored -join ' | ')
if ($stored.Count -ne 1 -or $stored[0] -ne $hwid) { throw "Hardware ID not stored correctly; stopping before install. Remove with: pnputil /remove-device '$id'" }

"--- Installing driver ---"
pnputil /add-driver $inf /install
"--- Device state ---"
pnputil /enum-devices /instanceid $id /drivers
(Get-PnpDeviceProperty -InstanceId $id -KeyName 'DEVPKEY_Device_ProblemCode' -ErrorAction SilentlyContinue | Select-Object KeyName,Data | Format-Table -AutoSize | Out-String)