# Manual E2E harness: a small WinForms text box that records everything typed
# into it. Used to verify SendInput injection end to end on a real desktop.
param(
    [string]$OutFile = "$env:TEMP\instant-mic-e2e.txt",
    [int]$Seconds = 60
)

Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing

Add-Type @"
using System;
using System.Runtime.InteropServices;
public static class Win {
    [DllImport("user32.dll")]
    public static extern bool SetForegroundWindow(IntPtr hWnd);
    [DllImport("user32.dll")]
    public static extern IntPtr GetForegroundWindow();
}
"@

$form = New-Object System.Windows.Forms.Form
$form.Text = "instant-mic e2e target"
$form.Width = 700
$form.Height = 300
$form.StartPosition = "CenterScreen"

$box = New-Object System.Windows.Forms.TextBox
$box.Multiline = $true
$box.Dock = "Fill"
$box.Font = New-Object System.Drawing.Font("Consolas", 16)
$form.Controls.Add($box)

$script:last = ""
$timer = New-Object System.Windows.Forms.Timer
$timer.Interval = 150
$timer.Add_Tick({
    if ($box.Text -ne $script:last) {
        $script:last = $box.Text
        [System.IO.File]::WriteAllText($OutFile, $box.Text, (New-Object System.Text.UTF8Encoding($false)))
    }
})
$timer.Start()

$deadline = (Get-Date).AddSeconds($Seconds)
$quit = New-Object System.Windows.Forms.Timer
$quit.Interval = 250
$quit.Add_Tick({
    if ((Get-Date) -gt $deadline) { $form.Close() }
})
$quit.Start()

$form.Add_Shown({
    $form.Activate()
    [Win]::SetForegroundWindow($form.Handle) | Out-Null
    $box.Focus() | Out-Null
})

[System.IO.File]::WriteAllText($OutFile, "", (New-Object System.Text.UTF8Encoding($false)))
[System.Windows.Forms.Application]::Run($form)
[System.IO.File]::WriteAllText($OutFile, $box.Text, (New-Object System.Text.UTF8Encoding($false)))
