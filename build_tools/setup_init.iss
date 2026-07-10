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
<<<<<<< HEAD
;    skipifsourcedoesntexist: this file is optional — UpdateClient falls back to its
;    built-in FALLBACK_HOSTS when it's absent, so a missing source must not fail the build.
Source: "..\deploy\update_servers.txt"; DestDir: "{app}"; DestName: "update_servers.txt"; Flags: ignoreversion onlyifdoesntexist skipifsourcedoesntexist

; 3. Save-server endpoint list (used by CloudSaveService). Same optionality as above.
=======
;    skipifsourcedoesntexist: both files are genuinely optional — UpdateClient/
;    CloudSaveService fall back to built-in defaults when absent, so the
;    installer must not hard-fail when they haven't been hand-placed in deploy/.
Source: "..\deploy\update_servers.txt"; DestDir: "{app}"; DestName: "update_servers.txt"; Flags: ignoreversion onlyifdoesntexist skipifsourcedoesntexist

; 3. Save-server endpoint list (used by CloudSaveService).
>>>>>>> 7ad106be66fed11be5e3dbc1cca5882d73c06d00
Source: "..\deploy\save_servers.txt"; DestDir: "{app}"; DestName: "save_servers.txt"; Flags: ignoreversion onlyifdoesntexist skipifsourcedoesntexist

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
; Clean up generated local files on uninstall (license is now activation.dat, holding only an
; opaque activation_id + server-encrypted blob — never a plaintext license key or signing key).
Type: files; Name: "{app}\app\activation.dat"
Type: files; Name: "{app}\local_save.dat"
Type: files; Name: "{app}\local_aes.key"

[Code]
// Licensing is issued entirely online at first run (platform.LicenseActivation talks to the save
// server's ACTIVATE handshake) — there is nothing to generate or sign at install time.

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
    EnsureDefaultMultiplayerServers();
  end;
end;
