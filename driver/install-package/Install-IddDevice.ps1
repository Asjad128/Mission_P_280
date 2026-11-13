$ErrorActionPreference = 'Stop'
$inf  = 'F:\Docs-Files\Mission_P_280\driver\install-package\IddSampleDriver.inf'
$hwid = 'Root\IddSampleDriver'

if (-not ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) { throw 'Not elevated' }

$existing = Get-PnpDevice -ErrorAction SilentlyContinue | Where-Object { $_.HardwareID -contains $hwid }
if ($existing) { "Device already exists: " + $existing.InstanceId; "Skipping creation."; $skipCreate = $true }

Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
using System.Text;
public static class Dev {
  [StructLayout(LayoutKind.Sequential)]
  public struct SP_DEVINFO_DATA { public int cbSize; public Guid ClassGuid; public int DevInst; public IntPtr Reserved; }
  [DllImport("setupapi.dll", CharSet=CharSet.Unicode, SetLastError=true)]
  static extern bool SetupDiGetINFClass(string inf, ref Guid g, StringBuilder name, int size, out int req);
  [DllImport("setupapi.dll", SetLastError=true)]
  static extern IntPtr SetupDiCreateDeviceInfoList(ref Guid g, IntPtr hwnd);
  [DllImport("setupapi.dll", CharSet=CharSet.Unicode, SetLastError=true)]
  static extern bool SetupDiCreateDeviceInfo(IntPtr set, string name, ref Guid g, string desc, IntPtr hwnd, int flags, ref SP_DEVINFO_DATA d);
  [DllImport("setupapi.dll", SetLastError=true)]
  static extern bool SetupDiSetDeviceRegistryProperty(IntPtr set, ref SP_DEVINFO_DATA d, int prop, byte[] buf, int size);
  [DllImport("setupapi.dll", SetLastError=true)]
  static extern bool SetupDiCallClassInstaller(int fn, IntPtr set, ref SP_DEVINFO_DATA d);
  [DllImport("setupapi.dll", SetLastError=true)]
  static extern bool SetupDiDestroyDeviceInfoList(IntPtr set);
  [DllImport("newdev.dll", CharSet=CharSet.Unicode, SetLastError=true)]
  static extern bool UpdateDriverForPlugAndPlayDevices(IntPtr hwnd, string hwid, string inf, int flags, out bool reboot);

  public static string Create(string inf, string hwid) {
    Guid g = Guid.Empty; var name = new StringBuilder(64); int req;
    if (!SetupDiGetINFClass(inf, ref g, name, 64, out req)) return "SetupDiGetINFClass failed: " + Marshal.GetLastWin32Error();
    IntPtr set = SetupDiCreateDeviceInfoList(ref g, IntPtr.Zero);
    if (set == new IntPtr(-1)) return "CreateDeviceInfoList failed: " + Marshal.GetLastWin32Error();
    var d = new SP_DEVINFO_DATA(); d.cbSize = Marshal.SizeOf(typeof(SP_DEVINFO_DATA));
    if (!SetupDiCreateDeviceInfo(set, name.ToString(), ref g, null, IntPtr.Zero, 1, ref d)) return "CreateDeviceInfo failed: " + Marshal.GetLastWin32Error();
    byte[] b = Encoding.Unicode.GetBytes(hwid + "\0\0");
    if (!SetupDiSetDeviceRegistryProperty(set, ref d, 1, b, b.Length)) return "SetHardwareID failed: " + Marshal.GetLastWin32Error();
    if (!SetupDiCallClassInstaller(0x19, set, ref d)) return "RegisterDevice failed: " + Marshal.GetLastWin32Error();
    SetupDiDestroyDeviceInfoList(set);
    return "OK (class " + name + ")";
  }
  public static string Install(string inf, string hwid) {
    bool reboot;
    if (!UpdateDriverForPlugAndPlayDevices(IntPtr.Zero, hwid, inf, 1, out reboot)) return "UpdateDriver failed: 0x" + Marshal.GetLastWin32Error().ToString("X");
    return "OK (reboot required: " + reboot + ")";
  }
}
'@

if (-not $skipCreate) { "Create device : " + [Dev]::Create($inf, $hwid) }
"Install driver: " + [Dev]::Install($inf, $hwid)
""
"--- Device state ---"
pnputil /enum-devices /deviceid $hwid /drivers
"--- Devices with problems (class Display) ---"
pnputil /enum-devices /class Display /problem