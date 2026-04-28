[Setup]
; Basic App Info
AppName=Michi Game
AppVersion=1.0
DefaultDirName={autopf}\MichiGame
DefaultGroupName=Michi Game
; The icon for the uninstaller in Control Panel
UninstallDisplayIcon={app}\MichiGame.exe
Compression=lzma
SolidCompression=yes
; Where the final Setup.exe will be saved
OutputDir=C:\Users\iulia\OneDrive\Desktop\java\Output
OutputBaseFilename=MichiGame_Setup

[Files]
; 1. The Launcher (.exe) you made with Launch4j
Source: "C:\Users\iulia\OneDrive\Desktop\java\MichiGame.exe"; DestDir: "{app}"; Flags: ignoreversion

; 2. The Code (.jar) - Required for the .exe to run
Source: "C:\Users\iulia\OneDrive\Desktop\java\MichiGame.jar"; DestDir: "{app}"; Flags: ignoreversion

; 3. The Java Runtime (JRE folder)
; This pulls your JDK 24 (renamed to jre) and all subfolders (bin, lib, etc.)
Source: "C:\Users\iulia\OneDrive\Desktop\java\jre\*"; DestDir: "{app}\jre"; Flags: ignoreversion recursesubdirs createallsubdirs

; 4. Patch-server endpoint list (used by UpdateClient at startup).
;    onlyifdoesntexist preserves any local edits the player may have made.
Source: "..\update_servers.example.txt"; DestDir: "{app}"; DestName: "update_servers.txt"; Flags: ignoreversion onlyifdoesntexist

; 5. Save-server endpoint list (used by CloudSaveService).
Source: "..\save_servers.example.txt"; DestDir: "{app}"; DestName: "save_servers.txt"; Flags: ignoreversion onlyifdoesntexist

[Icons]
; Creates a shortcut in the Start Menu
Name: "{group}\Michi Game"; Filename: "{app}\MichiGame.exe"
; Creates a shortcut on the Desktop
Name: "{autodesktop}\Michi Game"; Filename: "{app}\MichiGame.exe"; Tasks: desktopicon

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked

[Run]
; Option to launch the game immediately after installation
Filename: "{app}\MichiGame.exe"; Description: "{cm:LaunchProgram,Michi Game}"; Flags: nowait postinstall skipifsilent

[UninstallDelete]
; Clean up generated license and local save files on uninstall
Type: files; Name: "{app}\license.properties"
Type: files; Name: "{app}\local_save.dat"
Type: files; Name: "{app}\local_aes.key"

[Code]
// ── License key generation ──────────────────────────────────────────────
// Replicates data.LicenseGenerator: XXXXXXXX-YYYY where YYYY =
// uppercase hex of first 2 bytes of SHA-256(prefix + "MichiCloudSalt2026").
// Uses PowerShell (.NET crypto) so no extra dependencies are needed.

procedure GenerateLicenseKey();
var
  AppDir, ScriptPath: String;
  Lines: TArrayOfString;
  ResultCode: Integer;
begin
  AppDir := ExpandConstant('{app}');
  ScriptPath := ExpandConstant('{tmp}\gen_license.ps1');

  SetArrayLength(Lines, 9);
  Lines[0] := '$chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"';
  Lines[1] := '$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()';
  Lines[2] := '$bytes = New-Object byte[] 8; $rng.GetBytes($bytes)';
  Lines[3] := '$prefix = -join (0..7 | ForEach-Object { $chars[$bytes[$_] % 36] })';
  Lines[4] := '$hash = [System.Security.Cryptography.SHA256]::Create().ComputeHash(';
  Lines[5] := '    [System.Text.Encoding]::UTF8.GetBytes($prefix + "MichiCloudSalt2026"))';
  Lines[6] := '$suffix = ("{0:x2}{1:x2}" -f $hash[0], $hash[1]).ToUpper()';
  Lines[7] := '$key = "$prefix-$suffix"';
  Lines[8] := 'Set-Content -Path "' + AppDir + '\license.properties" -Value "license_key=$key" -Encoding ASCII -Force';

  SaveStringsToFile(ScriptPath, Lines, False);

  if not Exec('powershell.exe',
    '-NoProfile -ExecutionPolicy Bypass -File "' + ScriptPath + '"',
    '', SW_HIDE, ewWaitUntilTerminated, ResultCode) then
  begin
    MsgBox('Warning: Could not generate license key. The game will run but cloud saves will be unavailable.', mbInformation, MB_OK);
  end;

  DeleteFile(ScriptPath);
end;

procedure CurStepChanged(CurStep: TSetupStep);
begin
  if CurStep = ssPostInstall then
    GenerateLicenseKey();
end;
