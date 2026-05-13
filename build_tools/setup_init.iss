[Setup]
; Basic App Info      INNO SETUP
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
// ── License key generation ───────────────────────────────────────────────────
// Generates a unique, RSA-signed, machine-bound license at install time.
//
// license.properties format:
//   license_key=XXXXXXXX-YYYY
//   machine_fp=<16 hex> — SHA-256(Windows MachineGuid)[:8 bytes]
//   signature=<base64>  — RSA-2048 PKCS#1v15 SHA-256 over "key|fp"
//
// SETUP REQUIRED (one-time, developer):
//   1. Run:  python build_tools/generate_license_keys.py
//   2. Open build_tools/license_private.xml and copy its contents.
//   3. Replace REPLACE_WITH_YOUR_PRIVATE_KEY_XML below with that XML (one line).
//   4. Copy build_tools/license_public.b64 into LicenseManager.java.
//
// The private key is embedded here so the installer can sign offline.
// Keep setup_init.iss out of public source control.

procedure GenerateLicenseKey();
var
  AppDir, ScriptPath: String;
  Lines: TArrayOfString;
  ResultCode: Integer;
begin
  AppDir     := ExpandConstant('{app}');
  ScriptPath := ExpandConstant('{tmp}\gen_license.ps1');

  SetArrayLength(Lines, 20);

  // Line 0 — private key (RSA XML, one line — paste from license_private.xml)
  Lines[0]  := '$xmlKey = ''REPLACE_WITH_YOUR_PRIVATE_KEY_XML''';

  // Lines 1-4 — machine fingerprint from Windows MachineGuid
  Lines[1]  := 'try { $guid = (Get-ItemProperty ''HKLM:\SOFTWARE\Microsoft\Cryptography'' -ErrorAction Stop).MachineGuid }';
  Lines[2]  := 'catch { $guid = $env:COMPUTERNAME + $env:USERNAME }';
  Lines[3]  := '$fpHash = [System.Security.Cryptography.SHA256]::Create().ComputeHash([System.Text.Encoding]::UTF8.GetBytes($guid))';
  Lines[4]  := '$fp = ($fpHash[0..7] | ForEach-Object { ''{0:x2}'' -f $_ }) -join '''''';

  // Lines 5-11 — generate license key (XXXXXXXX-YYYY with SHA-256 check digit)
  Lines[5]  := '$chars = ''ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789''';
  Lines[6]  := '$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()';
  Lines[7]  := '$bytes = New-Object byte[] 8; $rng.GetBytes($bytes)';
  Lines[8]  := '$prefix = -join (0..7 | ForEach-Object { $chars[$bytes[$_] % 36] })';
  Lines[9]  := '$hash = [System.Security.Cryptography.SHA256]::Create().ComputeHash([System.Text.Encoding]::UTF8.GetBytes($prefix + ''MichiCloudSalt2026''))';
  Lines[10] := '$suffix = (''{0:x2}{1:x2}'' -f $hash[0], $hash[1]).ToUpper()';
  Lines[11] := '$key = "$prefix-$suffix"';

  // Lines 12-17 — RSA-sign "key|fp" using .NET RSACryptoServiceProvider
  Lines[12] := '$rsa = New-Object System.Security.Cryptography.RSACryptoServiceProvider';
  Lines[13] := '$rsa.FromXmlString($xmlKey)';
  Lines[14] := '$dataBytes = [System.Text.Encoding]::UTF8.GetBytes("$key|$fp")';
  Lines[15] := '$sha = New-Object System.Security.Cryptography.SHA256CryptoServiceProvider';
  Lines[16] := '$sig = $rsa.SignData($dataBytes, $sha)';
  Lines[17] := '$sigB64 = [Convert]::ToBase64String($sig)';

  // Lines 18-19 — write license.properties
  Lines[18] := '$content = @("license_key=$key", "machine_fp=$fp", "signature=$sigB64")';
  Lines[19] := 'Set-Content -Path "' + AppDir + '\license.properties" -Value $content -Encoding ASCII -Force';

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
