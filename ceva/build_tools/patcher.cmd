@echo off
setlocal

:: --- PRIVATE CONFIG ---
set GITHUB_TOKEN=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
set REPO=Blacknid/Michi-s-adventure
set JAR_NAME=Michi-s-adventure.jar

:: --- PATHS ---
set ROOT=..
set JBSDIFF=jbsdiff-1.0.jar
set OLD_JAR=%ROOT%\history\old_version.jar
set NEW_JAR=%ROOT%\deploy\%JAR_NAME%
set PATCH_OUT=%ROOT%\output\update.patch

echo [1/3] Building current version...
javac -d "%ROOT%\bin" -sourcepath "%ROOT%\src" %ROOT%\src\main\*.java
jar --create --file="%NEW_JAR%" -C "%ROOT%\bin" .

echo [2/3] Downloading previous version from GitHub...
if not exist "%ROOT%\history" mkdir "%ROOT%\history"

:: PowerShell helper to get Asset ID and Download from private repo
powershell -Command "$headers = @{ 'Authorization' = 'token %GITHUB_TOKEN%' }; $release = Invoke-RestMethod -Uri 'https://api.github.com/repos/%REPO%/releases/latest' -Headers $headers; $asset = $release.assets | Where-Object { $_.name -eq '%JAR_NAME%' }; Invoke-WebRequest -Uri $asset.url -Headers (@{ 'Authorization' = 'token %GITHUB_TOKEN%'; 'Accept' = 'application/octet-stream' }) -OutFile '%OLD_JAR%'"

if %ERRORLEVEL% NEQ 0 (echo Download failed! Check Token/Repo. & pause & exit /b)

echo [3/3] Creating Delta Patch...
java -jar "%JBSDIFF%" diff "%OLD_JAR%" "%NEW_JAR%" "%PATCH_OUT%"

echo SUCCESS: Patch saved to %PATCH_OUT%
pause
