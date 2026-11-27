# Reads and drives PocketKeePass's window through UI Automation, for checking the app from the Mac
# (Parallels' screen capture of this VM is black, and the app keeps itself out of captures anyway).
# A development tool, never shipped. It sees what any program running as the user can see, so it
# also shows what PocketKeePass exposes to other apps.
#
#   uia.ps1 dump                         every element: type, name, automation id, value
#   uia.ps1 click <id or name>           invoke a button, list item, or hyperlink; closes a window
#   uia.ps1 type <id or name> <text>     set a text or password box's text
#   uia.ps1 key <id or name> <keys>      focus an element and send keys (SendKeys syntax: {ENTER})
#   uia.ps1 wait <id or name> [seconds]  wait until an element exists
#   uia.ps1 send <keys>                  send keys to whatever has the focus (the file picker,
#                                        whose controls UI Automation can't type into)
param(
    [Parameter(Mandatory)] [ValidateSet('dump', 'click', 'type', 'key', 'wait', 'send')] [string] $Action,
    [string] $Target,
    [string] $Text
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName UIAutomationClient, UIAutomationTypes, System.Windows.Forms
$A = [System.Windows.Automation.AutomationElement]

# The app's top-level windows: its own, and dialogs it opens (the file picker).
function Windows {
    $process = Get-Process PocketKeePass -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $process) { throw 'PocketKeePass is not running.' }
    $condition = New-Object System.Windows.Automation.PropertyCondition($A::ProcessIdProperty, $process.Id)
    $windows = $A::RootElement.FindAll([System.Windows.Automation.TreeScope]::Children, $condition)
    if ($windows.Count -eq 0) { throw 'PocketKeePass has no window.' }
    $windows
}

function Find([string] $name) {
    foreach ($window in Windows) {
        if ($window.Current.Name -eq $name -or $window.Current.AutomationId -eq $name) { return $window }
        foreach ($property in @($A::AutomationIdProperty, $A::NameProperty)) {
            $condition = New-Object System.Windows.Automation.PropertyCondition($property, $name)
            $element = $window.FindFirst([System.Windows.Automation.TreeScope]::Descendants, $condition)
            if ($element) { return $element }
        }
    }
    throw "No element '$name'."
}

function Pattern($element, $pattern) {
    $result = $null
    if ($element.TryGetCurrentPattern($pattern, [ref] $result)) { $result } else { $null }
}

function Dump($element, [int] $depth) {
    $c = $element.Current
    $line = ('  ' * $depth) + $c.ControlType.ProgrammaticName.Replace('ControlType.', '')
    if ($c.Name) { $line += " '$($c.Name)'" }
    if ($c.AutomationId) { $line += " #$($c.AutomationId)" }
    if ($c.IsPassword) { $line += ' [password]' }
    if (-not $c.IsEnabled) { $line += ' [disabled]' }
    if ($c.IsOffscreen) { $line += ' [offscreen]' }
    $value = Pattern $element ([System.Windows.Automation.ValuePattern]::Pattern)
    if ($value) {
        try { $line += " = '$($value.Current.Value)'" } catch { $line += ' = (unreadable)' }
    }
    $line
    $walker = [System.Windows.Automation.TreeWalker]::ControlViewWalker
    $child = $walker.GetFirstChild($element)
    while ($child) {
        Dump $child ($depth + 1)
        $child = $walker.GetNextSibling($child)
    }
}

switch ($Action) {
    'dump' { foreach ($window in Windows) { Dump $window 0 } }
    'click' {
        $element = Find $Target
        $invoke = Pattern $element ([System.Windows.Automation.InvokePattern]::Pattern)
        if ($invoke) { $invoke.Invoke(); break }
        $select = Pattern $element ([System.Windows.Automation.SelectionItemPattern]::Pattern)
        if ($select) { $select.Select(); break }
        $toggle = Pattern $element ([System.Windows.Automation.TogglePattern]::Pattern)
        if ($toggle) { $toggle.Toggle(); break }
        $window = Pattern $element ([System.Windows.Automation.WindowPattern]::Pattern)
        if ($window) { $window.Close(); break }
        throw "'$Target' can't be clicked."
    }
    'type' {
        $element = Find $Target
        $value = Pattern $element ([System.Windows.Automation.ValuePattern]::Pattern)
        if (-not $value) {
            # A combo box with a text box inside (the file picker's file name).
            $edit = New-Object System.Windows.Automation.PropertyCondition($A::ControlTypeProperty, [System.Windows.Automation.ControlType]::Edit)
            $element = $element.FindFirst([System.Windows.Automation.TreeScope]::Descendants, $edit)
            $value = Pattern $element ([System.Windows.Automation.ValuePattern]::Pattern)
        }
        if (-not $value) { throw "'$Target' takes no text." }
        $value.SetValue($Text)
    }
    'key' {
        (Find $Target).SetFocus()
        [System.Windows.Forms.SendKeys]::SendWait($Text)
    }
    'send' { [System.Windows.Forms.SendKeys]::SendWait($Target) }
    'wait' {
        $deadline = (Get-Date).AddSeconds($(if ($Text) { [int] $Text } else { 30 }))
        while ((Get-Date) -lt $deadline) {
            try { Find $Target | Out-Null; 'found'; exit 0 } catch { Start-Sleep -Milliseconds 300 }
        }
        throw "No element '$Target' after waiting."
    }
}
