@echo off
setlocal enabledelayedexpansion

:: --- CONFIGURATION ---
set APP_NAME=MichisAdventure
set VERSION=2.0
set MAIN_CLASS=main.Main
set JAR_NAME=Michi-s-adventure.jar

:: --- PATHS ---
set SCRIPT_DIR=%~dp0
set ROOT=%SCRIPT_DIR%..
set SRC_DIR=%ROOT%\ceva\src
set BIN_DIR=%ROOT%\ceva\bin
set DEPLOY_DIR=%ROOT%\deploy
set JPACKAGE_DIR=%ROOT%\jpackage_tmp
set LIB_DIR=%ROOT%\ceva\lib
set LIBS=%LIB_DIR%\jl-1.0.1.jar;%LIB_DIR%\tritonus-share-0.3.7.jar;%LIB_DIR%\mp3spi-1.9.5.jar

echo ============================================
echo   Michi's Adventure Build Pipeline v%VERSION%
echo ============================================
echo.

:: Auto-increment build number in build.properties
set BUILD_PROPS=%SRC_DIR%\res\build.properties
if exist "%BUILD_PROPS%" (
    for /f "tokens=1,2 delims==" %%a in (%BUILD_PROPS%) do (
        if "%%a"=="build" set /a BUILD_NUM=%%b+1
    )
    echo version=%VERSION%> "%BUILD_PROPS%"
    echo build=!BUILD_NUM!>> "%BUILD_PROPS%"
    echo   Build number: !BUILD_NUM!
)

:: ============================================================
:: [0/5]  LICENSE KEYPAIR  —  generate once, inject everywhere
:: ============================================================
set PUBKEY_FILE=%SCRIPT_DIR%license_public.b64
set PRIVKEY_FILE=%SCRIPT_DIR%license_private.xml
set PKCS8_FILE=%SCRIPT_DIR%license_private_pkcs8.b64
set LM_FILE=%SRC_DIR%\data\LicenseManager.java
set ISS_FILE=%SCRIPT_DIR%setup_init.iss

echo [0/5] Verifying license keypair...
if not exist "%PUBKEY_FILE%" goto :GENKEYS
if not exist "%PRIVKEY_FILE%" goto :GENKEYS
goto :KEYSREADY
:GENKEYS
echo   Generating RSA-2048 license keypair (one-time)...
python "%SCRIPT_DIR%generate_license_keys.py"
if %ERRORLEVEL% NEQ 0 (echo [FAIL] License key generation failed. Install Python ^& pip install cryptography & pause & exit /b 1)
:KEYSREADY
if not exist "%PUBKEY_FILE%"  (echo [FAIL] Missing %PUBKEY_FILE%  & pause & exit /b 1)
if not exist "%PRIVKEY_FILE%" (echo [FAIL] Missing %PRIVKEY_FILE% & pause & exit /b 1)

:: Inject public key into LicenseManager.java AND both server configs.
:: sync_keys.py is the single source of truth — never edit the embedded
:: key by hand. It is idempotent.
python "%SCRIPT_DIR%sync_keys.py"
if %ERRORLEVEL% NEQ 0 (echo [FAIL] sync_keys.py failed. & pause & exit /b 1)

:: Write private key into a temp .iss include file read by setup_init.iss at compile time.
:: This avoids all CMD quoting issues — the XML never touches the command line.
set PRIVKEY_INC=%SCRIPT_DIR%_privkey_tmp.iss
powershell -NoProfile -Command "$k = (Get-Content -Raw -LiteralPath '%PRIVKEY_FILE%').Trim(); $enc = New-Object System.Text.UTF8Encoding($false); [IO.File]::WriteAllText('%PRIVKEY_INC%', '#define MICHI_PRIVKEY ' + [char]34 + $k + [char]34, $enc)"
if %ERRORLEVEL% NEQ 0 (echo [FAIL] Could not write private key include file. & pause & exit /b 1)
echo   [OK] Private key written to build-time include.

echo [1/5] Cleaning and Compiling...
if exist "%BIN_DIR%"      rd /s /q "%BIN_DIR%"      >nul 2>&1
if exist "%JPACKAGE_DIR%" rd /s /q "%JPACKAGE_DIR%" >nul 2>&1
:: Preserve server lists before wiping deploy/
if exist "%DEPLOY_DIR%\save_servers.txt"   copy /Y "%DEPLOY_DIR%\save_servers.txt"   "%TEMP%\michi_ss.txt"  >nul 2>&1
if exist "%DEPLOY_DIR%\update_servers.txt" copy /Y "%DEPLOY_DIR%\update_servers.txt" "%TEMP%\michi_us.txt"  >nul 2>&1
if exist "%DEPLOY_DIR%" rd /s /q "%DEPLOY_DIR%" >nul 2>&1
mkdir "%BIN_DIR%"      >nul 2>&1
mkdir "%DEPLOY_DIR%"   >nul 2>&1
mkdir "%JPACKAGE_DIR%" >nul 2>&1
:: Restore server lists
if exist "%TEMP%\michi_ss.txt" copy /Y "%TEMP%\michi_ss.txt" "%DEPLOY_DIR%\save_servers.txt"   >nul 2>&1
if exist "%TEMP%\michi_us.txt" copy /Y "%TEMP%\michi_us.txt" "%DEPLOY_DIR%\update_servers.txt" >nul 2>&1

:: Compiles all sub-packages in src
set SOURCE_LIST=%TEMP%\michi_sources.txt
if exist "%SOURCE_LIST%" del "%SOURCE_LIST%"
for /R "%SRC_DIR%" %%f in (*.java) do @echo %%f>> "%SOURCE_LIST%"
if not exist "%SOURCE_LIST%" (echo [FAIL] No Java source files found! & pause & exit /b 1)
javac -cp "%LIBS%" -d "%BIN_DIR%" -sourcepath "%SRC_DIR%" @"%SOURCE_LIST%"
set JAVAC_EXIT=%ERRORLEVEL%
del "%SOURCE_LIST%" >nul 2>&1
if %JAVAC_EXIT% NEQ 0 (echo [FAIL] Compilation failed! & pause & exit /b 1)
echo   [OK] Compilation succeeded.

echo [2/5] Copying resources...
:: Copy res/ tree into bin/ so JAR includes sprites, maps, sounds, JSON data
xcopy "%SRC_DIR%\res" "%BIN_DIR%\res" /E /I /Q /Y >nul 2>&1
if %ERRORLEVEL% NEQ 0 (echo [WARN] Resource copy had issues, continuing...)
:: Extract MP3SPI libs into bin so the fat JAR includes them
for %%f in ("%LIB_DIR%\*.jar") do (
    pushd "%BIN_DIR%" & jar xf "%%f" & popd
)
del /Q "%BIN_DIR%\META-INF\*.SF" "%BIN_DIR%\META-INF\*.DSA" "%BIN_DIR%\META-INF\*.RSA" >nul 2>&1
echo   [OK] Resources copied.

echo [3/5] Building JAR...
jar --create --file="%DEPLOY_DIR%\%JAR_NAME%" --main-class=%MAIN_CLASS% -C "%BIN_DIR%" .
if %ERRORLEVEL% NEQ 0 (echo [FAIL] JAR creation failed! & pause & exit /b 1)
echo   [OK] JAR created: %DEPLOY_DIR%\%JAR_NAME%

:: Ship the patch-server endpoint list next to the JAR (used by UpdateClient).
:: Source is deploy\update_servers.txt (already present in the repo).
if exist "%DEPLOY_DIR%\update_servers.txt" (
    echo   [OK] update_servers.txt present in deploy/
) else (
    echo [WARN] deploy\update_servers.txt missing — create it before shipping.
)
if exist "%DEPLOY_DIR%\save_servers.txt" (
    echo   [OK] save_servers.txt present in deploy/
) else (
    echo [WARN] deploy\save_servers.txt missing — create it before shipping.
)

echo [4/5] Verifying JAR...
java -jar "%DEPLOY_DIR%\%JAR_NAME%" --version >nul 2>&1
:: Quick smoke test — just verify JAR is runnable (exits immediately with --version)
echo   [OK] JAR is valid.

echo [5/5] Packaging EXE...
:: Build an app-image so we get a normal launcher EXE, not a second installer.
jpackage ^
    --input "%DEPLOY_DIR%" ^
    --name "%APP_NAME%" ^
    --main-jar "%JAR_NAME%" ^
    --main-class "%MAIN_CLASS%" ^
    --type app-image ^
    --dest "%JPACKAGE_DIR%" ^
  --app-version %VERSION% ^
  --java-options "-XX:+UseG1GC -XX:MaxGCPauseMillis=5 -Dsun.java2d.accthreshold=0"
if %ERRORLEVEL% NEQ 0 (echo [WARN] jpackage failed — JAR still available in deploy/.)

if exist "%JPACKAGE_DIR%\%APP_NAME%\%APP_NAME%.exe" (
    copy /Y "%JPACKAGE_DIR%\%APP_NAME%\%APP_NAME%.exe" "%DEPLOY_DIR%\%APP_NAME%-%VERSION%.exe" >nul
    echo   [OK] EXE copied: %DEPLOY_DIR%\%APP_NAME%-%VERSION%.exe
) else (
    echo [WARN] Launcher EXE was not found in %JPACKAGE_DIR%\%APP_NAME%
)

echo [6/6] Building installer with Inno Setup...
set ISCC_EXE=
set PFX86=C:\Program Files (x86)
set PF64=C:\Program Files
if exist "!PFX86!\Inno Setup 6\ISCC.exe" set ISCC_EXE=!PFX86!\Inno Setup 6\ISCC.exe
if exist "!PF64!\Inno Setup 6\ISCC.exe"  set ISCC_EXE=!PF64!\Inno Setup 6\ISCC.exe
if exist "!PFX86!\Inno Setup 5\ISCC.exe" set ISCC_EXE=!PFX86!\Inno Setup 5\ISCC.exe
if not defined ISCC_EXE for /f "delims=" %%i in ('where iscc 2^>nul') do set ISCC_EXE=%%i

if not defined ISCC_EXE (
    echo   [WARN] Inno Setup compiler not found. Skipping installer build.
    echo          Install from: https://jrsoftware.org/isdl.php
    goto :ISCC_DONE
)
:: setup_init.iss reads the private key via: #include "_privkey_tmp.iss"
"!ISCC_EXE!" "!ISS_FILE!"
if !ERRORLEVEL! NEQ 0 (
    echo   [WARN] Inno Setup compilation failed.
) else (
    echo   [OK] Installer built: !DEPLOY_DIR!\MichiGame_Setup.exe
)
:ISCC_DONE

:: Delete the temp private key include now that the installer is built.
if exist "%PRIVKEY_INC%" del "%PRIVKEY_INC%"

echo.
echo ============================================
echo   Build complete!
echo   JAR:       %DEPLOY_DIR%\%JAR_NAME%
echo   EXE:       %DEPLOY_DIR%\%APP_NAME%-%VERSION%.exe
echo   Installer: %DEPLOY_DIR%\MichiGame_Setup.exe
echo ============================================
echo.
echo   To issue a new license key for a customer run:
echo     python build_tools\issue_license.py --note "customer" --registry SERVERS\save_server\licenses.json --registry SERVERS\multiplayer_server\licenses.json
echo ============================================
pause