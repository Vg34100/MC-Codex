# Explicitly authorized desktop input for a single owned Minecraft recording window.
# Every pointer action interpolates from the current position; no instantaneous pointer jumps.
param(
    [Parameter(Mandatory=$true)][int]$MinecraftPid,
    [Parameter(Mandatory=$true)][string]$ActionsPath
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
Add-Type @'
using System;
using System.Runtime.InteropServices;
public class WalkthroughWindow {
    [StructLayout(LayoutKind.Sequential)] public struct Point { public int X, Y; }
    [StructLayout(LayoutKind.Sequential)] public struct Rect { public int Left, Top, Right, Bottom; }
    [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
    [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] public static extern bool GetCursorPos(out Point p);
    [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
    [DllImport("user32.dll")] public static extern bool GetClientRect(IntPtr h, out Rect r);
    [DllImport("user32.dll")] public static extern bool ClientToScreen(IntPtr h, ref Point p);
    [DllImport("user32.dll")] public static extern bool SetWindowText(IntPtr h, string t);
    [DllImport("user32.dll")] public static extern void mouse_event(uint flags, uint dx, uint dy, int data, UIntPtr extra);
}
'@
[WalkthroughWindow]::SetProcessDPIAware() | Out-Null
$client = Get-Process -Id $MinecraftPid
if ($client.MainWindowTitle -notmatch 'Minecraft|Codex Walkthrough' -or !$client.MainWindowHandle) { throw 'Target must be the owned Minecraft window' }
$handle = $client.MainWindowHandle
[WalkthroughWindow]::SetForegroundWindow($handle) | Out-Null
Start-Sleep -Milliseconds 350
function Check-Focus {
    if ([WalkthroughWindow]::GetForegroundWindow() -ne $handle) { throw 'Minecraft lost focus; stopped without sending further input' }
}
function Move-Pointer([int]$x, [int]$y, [int]$duration = 750) {
    Check-Focus
    $r = New-Object WalkthroughWindow+Rect
    $o = New-Object WalkthroughWindow+Point
    $p = New-Object WalkthroughWindow+Point
    [WalkthroughWindow]::GetClientRect($handle,[ref]$r) | Out-Null
    [WalkthroughWindow]::ClientToScreen($handle,[ref]$o) | Out-Null
    [WalkthroughWindow]::GetCursorPos([ref]$p) | Out-Null
    if ($x -lt 0 -or $y -lt 0 -or $x -ge $r.Right -or $y -ge $r.Bottom) { throw 'Pointer target outside Minecraft client' }
    $dx = $o.X + $x - $p.X; $dy = $o.Y + $y - $p.Y
    $length = [Math]::Sqrt($dx*$dx+$dy*$dy)
    $bend = [Math]::Min(28, $length * 0.04)
    $clock = [Diagnostics.Stopwatch]::StartNew()
    do {
        Check-Focus
        $t = [Math]::Min(1.0, $clock.Elapsed.TotalMilliseconds / [Math]::Max(250,$duration))
        $ease = $t*$t*(3-2*$t)
        $arc = [Math]::Sin($t*[Math]::PI)*$bend
        $px = $p.X + $dx*$ease + $(if ($length -gt 0) {-$dy/$length*$arc} else {0})
        $py = $p.Y + $dy*$ease + $(if ($length -gt 0) {$dx/$length*$arc} else {0})
        [WalkthroughWindow]::SetCursorPos([int]$px,[int]$py) | Out-Null
        Start-Sleep -Milliseconds 8
    } while ($t -lt 1)
}
function Capture([string]$path) {
    Check-Focus
    $r = New-Object WalkthroughWindow+Rect; $o = New-Object WalkthroughWindow+Point
    [WalkthroughWindow]::GetClientRect($handle,[ref]$r) | Out-Null
    [WalkthroughWindow]::ClientToScreen($handle,[ref]$o) | Out-Null
    $bmp=New-Object Drawing.Bitmap($r.Right,$r.Bottom);$g=[Drawing.Graphics]::FromImage($bmp)
    try { $g.CopyFromScreen($o.X,$o.Y,0,0,$bmp.Size);$bmp.Save($path,[Drawing.Imaging.ImageFormat]::Png) }
    finally { $g.Dispose();$bmp.Dispose() }
}
Check-Focus
$actions=Get-Content -LiteralPath $ActionsPath -Raw | ConvertFrom-Json
foreach ($a in $actions) {
    Check-Focus
    [pscustomobject]@{ at = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds(); batch = [IO.Path]::GetFileName($ActionsPath); action = $a } |
        ConvertTo-Json -Compress -Depth 8 | Add-Content -LiteralPath (Join-Path (Split-Path $ActionsPath) 'input-timeline.jsonl')
    switch ($a.action) {
        'cue' { } # Timestamped chapter/caption marker; adds no game UI or hidden state.
        'title' { [WalkthroughWindow]::SetWindowText($handle,$a.value) | Out-Null }
        'move' { Move-Pointer $a.x $a.y $(if ($a.duration) {$a.duration} else {750}) }
        'click' {
            Move-Pointer $a.x $a.y $(if ($a.duration) {$a.duration} else {750})
            Start-Sleep -Milliseconds 180
            $down=if ($a.button -eq 'right') {8} else {2};$up=if ($a.button -eq 'right') {16} else {4}
            try { [WalkthroughWindow]::mouse_event($down,0,0,0,[UIntPtr]::Zero);Start-Sleep -Milliseconds 85 }
            finally { [WalkthroughWindow]::mouse_event($up,0,0,0,[UIntPtr]::Zero) }
        }
        'text' {
            foreach ($c in $a.value.ToCharArray()) {
                Check-Focus
                $letter=[string]$c
                if ('+^%~(){}[]'.Contains($letter)) {$letter='{'+$letter+'}'}
                [Windows.Forms.SendKeys]::SendWait($letter)
                Start-Sleep -Milliseconds $(if ($a.interval) {$a.interval} else {75})
            }
        }
        'key' { [Windows.Forms.SendKeys]::SendWait($a.value) }
        'scroll' {
            Move-Pointer $a.x $a.y 750
            [WalkthroughWindow]::mouse_event(0x800,0,0,[int]$a.value,[UIntPtr]::Zero)
        }
        'pause' { Start-Sleep -Milliseconds $a.duration }
        'screenshot' { Capture $a.value }
        default { throw "Unknown walkthrough action: $($a.action)" }
    }
    if ($a.after) {Start-Sleep -Milliseconds $a.after}
}
