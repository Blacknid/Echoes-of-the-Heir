#include "_privkey_tmp.iss"

[Setup]
; Basic App Info      INNO SETUP
AppName=Michi Game
AppVersion=1.0
DefaultDirName={localappdata}\MichiGame
DefaultGroupName=Michi Game
PrivilegesRequired=lowest
; The icon for the uninstaller in Control Panel
UninstallDisplayIcon={app}\MichisAdventure.exe
Compression=lzma
SolidCompression=yes
; Where the final Setup.exe will be saved
OutputDir=..\deploy
OutputBaseFilename=MichiGame_Setup

[Files]
; 1. The complete jpackage app-image, including launcher, app config, JAR, and runtime.
Source: "..\jpackage_tmp\MichisAdventure\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

; 2. Patch-server endpoint list (used by UpdateClient at startup).
;    onlyifdoesntexist preserves any local edits the player may have made.
Source: "..\deploy\update_servers.txt"; DestDir: "{app}"; DestName: "update_servers.txt"; Flags: ignoreversion onlyifdoesntexist

; 3. Save-server endpoint list (used by CloudSaveService).
Source: "..\deploy\save_servers.txt"; DestDir: "{app}"; DestName: "save_servers.txt"; Flags: ignoreversion onlyifdoesntexist

[Icons]
; Creates a shortcut in the Start Menu
Name: "{group}\Michi Game"; Filename: "{app}\MichisAdventure.exe"; WorkingDir: "{app}"
; Creates a shortcut on the Desktop
Name: "{autodesktop}\Michi Game"; Filename: "{app}\MichisAdventure.exe"; WorkingDir: "{app}"; Tasks: desktopicon

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked

[Run]
; Option to launch the game immediately after installation
Filename: "{app}\MichisAdventure.exe"; WorkingDir: "{app}"; Description: "{cm:LaunchProgram,Michi Game}"; Flags: nowait postinstall skipifsilent

[UninstallDelete]
; Clean up generated license and local save files on uninstall
Type: files; Name: "{app}\app\license.properties"
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
// SETUP: just run build_tools\compile.cmd — it handles everything:
//   1. Generates license_private.xml + license_public.b64 (once).
//   2. Injects the public key into LicenseManager.java automatically.
//   3. Passes the private key to ISCC via /DMICHI_PRIVKEY= at compile time.
//
// This file contains NO embedded secrets and is safe to commit.
// The private key exists only in license_private.xml and in the
// built installer binary — never in source control.

procedure GenerateLicenseKey();
var
  AppDir, ScriptPath: String;
  Lines: TArrayOfString;
  ResultCode: Integer;
begin
  AppDir     := ExpandConstant('{app}');
  ScriptPath := ExpandConstant('{tmp}\gen_license.ps1');

  SetArrayLength(Lines, 18);

  // Line 0 — private key XML injected at compile time via /DMICHI_PRIVKEY= flag.
  Lines[0]  := '$xmlKey = ''{#MICHI_PRIVKEY}''';

  // Lines 1-3 — machine fingerprint: SHA-256(MachineGuid)[:8 bytes] as 16 hex chars
  Lines[1]  := 'try { $guid = (Get-ItemProperty ''HKLM:\SOFTWARE\Microsoft\Cryptography'' -ErrorAction Stop).MachineGuid } catch { $guid = """" }';
  Lines[2]  := 'if ([string]::IsNullOrEmpty($guid)) { $guid = ($env:COMPUTERNAME + $env:USERNAME) }';
  Lines[3]  := '$fp = [System.BitConverter]::ToString([System.Security.Cryptography.SHA256]::Create().ComputeHash([System.Text.Encoding]::UTF8.GetBytes($guid)), 0, 8).Replace(''-'', '''').ToLower()';

  // Lines 4-9 — generate random license key (XXXXXXXX-YYYY, all random base32)
  // The dashes are purely cosmetic — the RSA signature is what makes the key
  // trustworthy. The server registry is what makes the key authorized.
  Lines[4]  := '$chars = ''ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789''';
  Lines[5]  := '$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()';
  Lines[6]  := '$bytes = New-Object byte[] 12; $rng.GetBytes($bytes)';
  Lines[7]  := '$prefix = -join (0..7  | ForEach-Object { $chars[$bytes[$_] % 36] })';
  Lines[8]  := '$suffix = -join (8..11 | ForEach-Object { $chars[$bytes[$_] % 36] })';
  Lines[9]  := '$key = "$prefix-$suffix"';

  // Lines 10-15 — RSA-sign "key|fp" using .NET RSACryptoServiceProvider
  Lines[10] := '$rsa = New-Object System.Security.Cryptography.RSACryptoServiceProvider';
  Lines[11] := '$rsa.FromXmlString($xmlKey)';
  Lines[12] := '$dataBytes = [System.Text.Encoding]::UTF8.GetBytes("$key|$fp")';
  Lines[13] := '$sha = New-Object System.Security.Cryptography.SHA256CryptoServiceProvider';
  Lines[14] := '$sig = $rsa.SignData($dataBytes, $sha)';
  Lines[15] := '$sigB64 = [Convert]::ToBase64String($sig)';

  // Lines 16-17 — write license.properties field-by-field to avoid any embedded
  // newline inside the base64 signature value (which would break Java Properties.load).
  Lines[16] := '$licPath = "' + AppDir + '\app\license.properties"';
  Lines[17] := '[System.IO.File]::WriteAllLines($licPath, @("license_key=$key", "machine_fp=$fp", "signature=$sigB64"), [System.Text.Encoding]::ASCII)';

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
