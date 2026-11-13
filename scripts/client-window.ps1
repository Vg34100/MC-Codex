# Local real-client checks. Always targets one Minecraft JVM; screenshots contain its window only.
param(
    [Parameter(Mandatory=$true)][int]$MinecraftPid,
    [Parameter(Mandatory=$true)][ValidateSet('inspect','screenshot','click','key','text','resize','wheel','close','command','hover','jump','hold')][string]$Action,
    [int]$X = 0, [int]$Y = 0,
    [ValidateSet('left','right')][string]$Button = 'left',
    [string]$Value = '', [string]$OutputPath = ''
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
if (-not ('CodexClientWindow' -as [type])) {
Add-Type @'
using System;
using System.Runtime.InteropServices;
public class CodexClientWindow {
    [StructLayout(LayoutKind.Sequential)] public struct Rect { public int Left, Top, Right, Bottom; }
    [StructLayout(LayoutKind.Sequential)] public struct Point { public int X, Y; }
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
    [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] public static extern void keybd_event(byte key, byte scan, uint flags, UIntPtr extra);
    [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
    [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int n);
    [DllImport("user32.dll")] public static extern bool GetClientRect(IntPtr h, out Rect r);
    [DllImport("user32.dll")] public static extern bool ClientToScreen(IntPtr h, ref Point p);
    [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
    [DllImport("user32.dll")] public static extern void mouse_event(uint flags, uint dx, uint dy, int data, UIntPtr extra);
    [DllImport("user32.dll")] public static extern bool MoveWindow(IntPtr h, int x, int y, int width, int height, bool repaint);
    [DllImport("user32.dll")] public static extern bool PostMessage(IntPtr h, uint msg, IntPtr wParam, IntPtr lParam);
}
'@
}
[CodexClientWindow]::SetProcessDPIAware() | Out-Null
$client = Get-Process -Id $MinecraftPid
if ($client.MainWindowTitle -notlike '*Minecraft*' -or $client.MainWindowHandle -eq 0) { throw 'The selected process has no Minecraft window' }
$handle = $client.MainWindowHandle
[CodexClientWindow]::ShowWindow($handle, 9) | Out-Null
[CodexClientWindow]::SetForegroundWindow($handle) | Out-Null
if ([CodexClientWindow]::GetForegroundWindow() -ne $handle) {
    [CodexClientWindow]::keybd_event(0x12, 0, 0, [UIntPtr]::Zero)
    [CodexClientWindow]::SetForegroundWindow($handle) | Out-Null
    [CodexClientWindow]::keybd_event(0x12, 0, 2, [UIntPtr]::Zero)
}
Start-Sleep -Milliseconds 250
if ([CodexClientWindow]::GetForegroundWindow() -ne $handle) { throw 'Cannot focus Minecraft; refusing to send input or capture another application' }
$rect = New-Object CodexClientWindow+Rect
$origin = New-Object CodexClientWindow+Point
[CodexClientWindow]::GetClientRect($handle, [ref]$rect) | Out-Null
[CodexClientWindow]::ClientToScreen($handle, [ref]$origin) | Out-Null
if ($Action -in @('click', 'wheel', 'hover') -and ($X -lt 0 -or $Y -lt 0 -or $X -ge $rect.Right -or $Y -ge $rect.Bottom)) { throw 'Input coordinates must be inside the Minecraft client' }
switch ($Action) {
    'inspect' { Write-Output "$($client.MainWindowTitle): $($rect.Right)x$($rect.Bottom), origin $($origin.X),$($origin.Y)" }
    'close' { [CodexClientWindow]::PostMessage($handle, 0x10, [IntPtr]::Zero, [IntPtr]::Zero) | Out-Null }
    'hover' { [CodexClientWindow]::SetCursorPos($origin.X + $X, $origin.Y + $Y) | Out-Null }
    'click' {
        [CodexClientWindow]::SetCursorPos($origin.X + $X, $origin.Y + $Y) | Out-Null
        Start-Sleep -Milliseconds 150
        $down = if ($Button -eq 'right') { 8 } else { 2 }
        $up = if ($Button -eq 'right') { 16 } else { 4 }
        [CodexClientWindow]::mouse_event($down, 0, 0, 0, [UIntPtr]::Zero)
        Start-Sleep -Milliseconds 70
        [CodexClientWindow]::mouse_event($up, 0, 0, 0, [UIntPtr]::Zero)
    }
    'hold' {
        [CodexClientWindow]::SetCursorPos($origin.X + $X, $origin.Y + $Y) | Out-Null
        $down = if ($Button -eq 'right') { 8 } else { 2 }
        $up = if ($Button -eq 'right') { 16 } else { 4 }
        try {
            [CodexClientWindow]::mouse_event($down, 0, 0, 0, [UIntPtr]::Zero)
            Start-Sleep -Milliseconds 400
            if ($OutputPath) {
                $bitmap = New-Object System.Drawing.Bitmap($rect.Right, $rect.Bottom)
                $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
                try { $graphics.CopyFromScreen($origin.X, $origin.Y, 0, 0, $bitmap.Size); $bitmap.Save($OutputPath, [System.Drawing.Imaging.ImageFormat]::Png) }
                finally { $graphics.Dispose(); $bitmap.Dispose() }
            }
            Start-Sleep -Milliseconds 500
        } finally { [CodexClientWindow]::mouse_event($up, 0, 0, 0, [UIntPtr]::Zero) }
    }
    'wheel' {
        [CodexClientWindow]::SetCursorPos($origin.X + $X, $origin.Y + $Y) | Out-Null
        Start-Sleep -Milliseconds 150
        [CodexClientWindow]::mouse_event(0x800, 0, 0, [int]$Value, [UIntPtr]::Zero)
    }
    'jump' {
        try {
            [CodexClientWindow]::keybd_event(0x20, 0, 0, [UIntPtr]::Zero)
            Start-Sleep -Milliseconds 300
        } finally { [CodexClientWindow]::keybd_event(0x20, 0, 2, [UIntPtr]::Zero) }
    }
    'key' { [System.Windows.Forms.SendKeys]::SendWait($Value) }
    'command' {
        [System.Windows.Forms.SendKeys]::SendWait('/')
        Start-Sleep -Milliseconds 200
        foreach ($character in $Value.TrimStart('/').ToCharArray()) {
            $text = [string]$character
            if ('+^%~(){}[]'.Contains($text)) { $text = '{' + $text + '}' }
            [System.Windows.Forms.SendKeys]::SendWait($text)
        }
        [System.Windows.Forms.SendKeys]::SendWait('{ENTER}')
    }
    'text' {
        foreach ($character in $Value.ToCharArray()) {
            $text = [string]$character
            if ('+^%~(){}[]'.Contains($text)) { $text = '{' + $text + '}' }
            [System.Windows.Forms.SendKeys]::SendWait($text)
        }
    }
    'resize' {
        $area = [System.Windows.Forms.Screen]::FromHandle($handle).WorkingArea
        if ($X -gt $area.Width -or $Y -gt $area.Height -or $X -lt 320 -or $Y -lt 240) { throw 'Requested window size must fit the current monitor' }
        $windowLeft = $area.Left + [int](($area.Width - $X) / 2)
        $windowTop = $area.Top + [int](($area.Height - $Y) / 2)
        [CodexClientWindow]::MoveWindow($handle, $windowLeft, $windowTop, $X, $Y, $true) | Out-Null
    }
    'screenshot' {
        if (!$OutputPath) { throw 'OutputPath is required' }
        $directory = Split-Path -Parent $OutputPath
        New-Item -ItemType Directory -Force -Path $directory | Out-Null
        $bitmap = New-Object System.Drawing.Bitmap($rect.Right, $rect.Bottom)
        $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
        try {
            $graphics.CopyFromScreen($origin.X, $origin.Y, 0, 0, $bitmap.Size)
            $bitmap.Save($OutputPath, [System.Drawing.Imaging.ImageFormat]::Png)
        } finally { $graphics.Dispose(); $bitmap.Dispose() }
        Write-Output $OutputPath
    }
}

# Capture the immediate result of a click/key/command without a second PowerShell startup.
if ($OutputPath -and $Action -notin @('screenshot', 'hold')) {
    Start-Sleep -Milliseconds 250
    $bitmap = New-Object System.Drawing.Bitmap($rect.Right, $rect.Bottom)
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    try { $graphics.CopyFromScreen($origin.X, $origin.Y, 0, 0, $bitmap.Size); $bitmap.Save($OutputPath, [System.Drawing.Imaging.ImageFormat]::Png) }
    finally { $graphics.Dispose(); $bitmap.Dispose() }
}
