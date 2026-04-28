@echo off
:: =======================================================================
:: DEPRECATED -- legacy GitHub-based bsdiff patcher.
::
:: Replaced by ceva/patch_server/ + UpdateClient.java (in-app self-update).
:: To publish a new patch, run on the patch-server host:
::     python3 build_patch.py <old.jar> <new.jar> <from_version> <to_version>
:: This script is kept for archival reference only and is no longer wired
:: into the deployment pipeline.
:: =======================================================================
echo [!] patcher.bat is DEPRECATED. Use ceva/patch_server/build_patch.py instead.
echo [!] See ceva/DEPLOYMENT.md.
pause
exit /b 0

setlocal enabledelayedexpansion

:: --- PRIVATE CONFIG ---
set GITHUB_TOKEN=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
set REPO=Blacknid/Michi-s-adventure
set JAR_NAME=Michi-s-adventure.jar

:: --- PATHS (Adjusted for your 'ceva' folder structure) ---
set ROOT=..\..
set JBSDIFF=jbsdiff-1.0.jar
set OLD_JAR=%ROOT%\history\old_version.jar
set NEW_JAR=%ROOT%\deploy\%JAR_NAME%
set PATCH_OUT=%ROOT%\output\update.patch

echo [1/3] Building current version...
if not exist "%ROOT%\bin" mkdir "%ROOT%\bin"
if not exist "%ROOT%\deploy" mkdir "%ROOT%\deploy"
:: Find all java files in all subfolders of /src
dir /s /b "%ROOT%\src\*.java" > sources.txt
javac -d "%ROOT%\bin" -sourcepath "%ROOT%\src" @sources.txt
del sources.txt
jar --create --file="%NEW_JAR%" -C "%ROOT%\bin" .

echo [2/3] Downloading previous version...
if not exist "%ROOT%\history" mkdir "%ROOT%\history"

:: PowerShell modified to find the newest release even if it is a "Pre-release"
powershell -NoProfile -Command "$h=@{'Authorization'='Bearer %GITHUB_TOKEN%';'Accept'='application/vnd.github.v3+json';'User-Agent'='PS'}; try { $r=Invoke-RestMethod -Uri 'https://api.github.com/repos/%REPO%/releases' -Headers $h; $latest=$r[0]; $a=$latest.assets | Where-Object {$_.name -eq '%JAR_NAME%'}; if($null -eq $a){ throw 'File not found' }; Invoke-WebRequest -Uri $a.url -Headers (@{'Authorization'='Bearer %GITHUB_TOKEN%';'Accept'='application/octet-stream';'User-Agent'='PS'}) -OutFile '%OLD_JAR%'; echo ('Downloaded from: ' + $latest.tag_name) } catch { Write-Host ('GitHub Error: ' + $_.Exception.Message) -ForegroundColor Red; exit 1 }"

if %ERRORLEVEL% NEQ 0 (
    echo.
    echo [!] Download failed. Ensure '%JAR_NAME%' is attached to your GitHub release.
    pause
    exit /b
)

echo [3/3] Creating Delta Patch...
if not exist "%ROOT%\output" mkdir "%ROOT%\output"
java -jar "%JBSDIFF%" diff "%OLD_JAR%" "%NEW_JAR%" "%PATCH_OUT%"

echo SUCCESS: Patch created at %PATCH_OUT%
pause
