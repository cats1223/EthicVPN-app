; EthicVPN for Windows installer (Inno Setup 6). CI builds it from the published app in out\:
;   iscc /DAppVersion=1.0.0 /DSourceDir=out windows\installer.iss
; Installs per user, without admin rights; the tunnel mode asks for them itself when it starts.

#ifndef AppVersion
  #define AppVersion "1.0.0"
#endif
#ifndef SourceDir
  #define SourceDir "..\out"
#endif

[Setup]
AppId={{7C1E9F4A-5B2D-4E8A-9C3F-TECHTEAMVPN01}
AppName=EthicVPN
AppVersion={#AppVersion}
AppVerName=EthicVPN {#AppVersion}
AppPublisher=Ethic
AppPublisherURL=https://github.com/cats1223/TechTeamVPN-app
DefaultDirName={localappdata}\Programs\TechTeamVPN
DisableDirPage=yes
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
OutputDir=.
OutputBaseFilename=EthicVPN-Setup-{#AppVersion}
SetupIconFile=TechTeamVPN\icon.ico
UninstallDisplayIcon={app}\TechTeamVPN.exe
Compression=lzma2/ultra64
SolidCompression=yes
WizardStyle=modern
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
; An update closes the running app first (App.xaml.cs holds this mutex).
AppMutex=TechTeamVPN.SingleInstance
CloseApplications=yes

[Languages]
Name: "ru"; MessagesFile: "compiler:Languages\Russian.isl"
Name: "en"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"

[Files]
Source: "{#SourceDir}\*"; DestDir: "{app}"; Excludes: "*.pdb,*.xml,wintun.dll"; Flags: ignoreversion recursesubdirs createallsubdirs
; The driver keeps its version: an unchanged one is left in place, even while the tunnel holds it.
Source: "{#SourceDir}\wintun.dll"; DestDir: "{app}"; Flags: promptifolder
; Microsoft's WebView2 bootstrapper (CI downloads it); run only where the runtime is missing.
Source: "MicrosoftEdgeWebview2Setup.exe"; DestDir: "{tmp}"; Flags: deleteafterinstall; Check: not WebView2Installed

[Icons]
Name: "{autoprograms}\EthicVPN"; Filename: "{app}\TechTeamVPN.exe"
Name: "{autodesktop}\EthicVPN"; Filename: "{app}\TechTeamVPN.exe"; Tasks: desktopicon

[Run]
Filename: "{tmp}\MicrosoftEdgeWebview2Setup.exe"; Parameters: "/silent /install"; StatusMsg: "Установка Microsoft Edge WebView2…"; Check: not WebView2Installed
Filename: "{app}\TechTeamVPN.exe"; Description: "{cm:LaunchProgram,EthicVPN}"; Flags: nowait postinstall skipifsilent

; Shortcuts of the versions named TechTeamVPN: replaced by the EthicVPN ones above.
[InstallDelete]
Type: files; Name: "{autoprograms}\TechTeamVPN.lnk"
Type: files; Name: "{autodesktop}\TechTeamVPN.lnk"

[UninstallDelete]
Type: filesandordirs; Name: "{localappdata}\TechTeamVPN"

[Code]
{ The tunnel mode runs elevated, and this per-user setup cannot close it: ask the user to quit first. }
function PrepareToInstall(var NeedsRestart: Boolean): String;
var
  Code: Integer;
begin
  Result := '';
  if Exec(ExpandConstant('{cmd}'), '/C tasklist /FI "IMAGENAME eq xray.exe" | find /I "xray.exe" >NUL', '', SW_HIDE, ewWaitUntilTerminated, Code) and (Code = 0) then
    Result := 'EthicVPN ещё работает. Выйдите из него (значок в трее у часов → «Выход») и нажмите «Назад», затем «Установить».';
end;

{ The window of the app is WebView2; its runtime registers under EdgeUpdate, machine-wide or per user. }
function WebView2Installed: Boolean;
var
  Version: String;
begin
  Result :=
    (RegQueryStringValue(HKLM, 'SOFTWARE\WOW6432Node\Microsoft\EdgeUpdate\Clients\{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}', 'pv', Version) and (Version <> '') and (Version <> '0.0.0.0')) or
    (RegQueryStringValue(HKLM, 'SOFTWARE\Microsoft\EdgeUpdate\Clients\{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}', 'pv', Version) and (Version <> '') and (Version <> '0.0.0.0')) or
    (RegQueryStringValue(HKCU, 'Software\Microsoft\EdgeUpdate\Clients\{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}', 'pv', Version) and (Version <> '') and (Version <> '0.0.0.0'));
end;
