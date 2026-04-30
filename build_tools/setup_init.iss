[Setup]
; Basic App Info
AppName=Michi Game
AppVersion=1.0
DefaultDirName={localappdata}\MichiGame
DefaultGroupName=Michi Game
PrivilegesRequired=lowest
; The icon for the uninstaller in Control Panel
UninstallDisplayIcon={app}\MichisAdventure-2.0.exe
Compression=lzma
SolidCompression=yes
; Where the final Setup.exe will be saved
OutputDir=..\deploy
OutputBaseFilename=MichiGame_Setup

[Files]
; 1. The Launcher (.exe) you made with Launch4j
Source: "..\output\MichisAdventure-2.0.exe"; DestDir: "{app}"; Flags: ignoreversion

; 2. The Code (.jar) - Required for the .exe to run
Source: "..\deploy\Michi-s-adventure.jar"; DestDir: "{app}"; Flags: ignoreversion

; 3. The Java Runtime (JRE folder)
; This pulls your JDK 24 (renamed to jre) and all subfolders (bin, lib, etc.)
Source: "..\deploy\jre\*"; DestDir: "{app}\jre"; Flags: ignoreversion recursesubdirs createallsubdirs skipifsourcedoesntexist

; 4. Patch-server endpoint list (used by UpdateClient at startup).
;    onlyifdoesntexist preserves any local edits the player may have made.
Source: "..\ceva\update_servers.example.txt"; DestDir: "{app}"; DestName: "update_servers.txt"; Flags: ignoreversion onlyifdoesntexist

; 5. Save-server endpoint list (used by CloudSaveService).
Source: "..\ceva\save_servers.example.txt"; DestDir: "{app}"; DestName: "save_servers.txt"; Flags: ignoreversion onlyifdoesntexist

[Icons]
; Creates a shortcut in the Start Menu
Name: "{group}\Michi Game"; Filename: "{app}\MichisAdventure-2.0.exe"; WorkingDir: "{app}"
; Creates a shortcut on the Desktop
Name: "{autodesktop}\Michi Game"; Filename: "{app}\MichisAdventure-2.0.exe"; WorkingDir: "{app}"; Tasks: desktopicon

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked

[Run]
; Option to launch the game immediately after installation
Filename: "{app}\MichisAdventure-2.0.exe"; WorkingDir: "{app}"; Description: "{cm:LaunchProgram,Michi Game}"; Flags: nowait postinstall skipifsilent

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

procedure EnsureDefaultMultiplayerServers();
var
  ServersPath: String;
  Lines: TArrayOfString;
begin
  ServersPath := ExpandConstant('{app}\servers.txt');
  if FileExists(ServersPath) then
    exit;

  SetArrayLength(Lines, 1);
  Lines[0] := 'Local Server|127.0.0.1|7777';
  SaveStringsToFile(ServersPath, Lines, False);
end;

procedure CurStepChanged(CurStep: TSetupStep);
begin
  if CurStep = ssPostInstall then
  begin
    GenerateLicenseKey();
    EnsureDefaultMultiplayerServers();
  end;
end;
